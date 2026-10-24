package kr.tna.common.tenant;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import javax.sql.DataSource;
import kr.tna.common.auth.TnaPrincipal;
import kr.tna.org.OrgCommands;
import kr.tna.org.OrgCommands.Change;
import kr.tna.org.OrgException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/** Current employee authorization; administrative commands retain their stricter existing boundary. */
@Component
public final class BusinessCommands {
    private static final Logger LOG=LoggerFactory.getLogger(BusinessCommands.class);
    private final TenantTransactions transactions;
    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final JsonMapper json=JsonMapper.builder().build();
    public BusinessCommands(TenantTransactions transactions,@Qualifier("dataSource") DataSource source,Clock clock) {
        this.transactions=transactions;this.jdbc=new JdbcTemplate(source);this.clock=clock;
    }
    public <T> T read(TnaPrincipal actor,Supplier<T> action) {
        if(actor==null)throw OrgException.forbidden();
        try {return transactions.execute(actor.tenantId(),()->{requireCurrent(actor,"EMPLOYEE");return action.get();});}
        catch(OrgException e){failure(e);throw e;}
        catch(DataAccessException e){LOG.error("event=business_command outcome=storage_unavailable");throw new OrgException(503,"업무 자료를 확인할 수 없습니다. 잠시 후 다시 조회해 주세요.");}
    }
    public UUID execute(TnaPrincipal actor,UUID commandId,String action,String kind,String inputHash,String reason,String requiredRole,Supplier<Change> mutation) {
        if(actor==null)throw OrgException.forbidden();
        if(commandId==null)throw OrgException.invalid("명령 식별자가 필요합니다.");
        String checkedReason=OrgCommands.requiredText(reason,500,"사유");
        if(!Set.of("EMPLOYEE","APPROVER","ATTENDANCE_ADMIN").contains(requiredRole))throw OrgException.forbidden();
        try {return transactions.execute(actor.tenantId(),()->{
            jdbc.queryForObject("SELECT pg_advisory_xact_lock(hashtextextended(CAST(? AS text),0))",Object.class,actor.tenantId());
            requireCurrent(actor,requiredRole);
            var previous=jdbc.query("SELECT actor_account_id,action,target_kind,input_hash,target_id FROM tna.org_audit_event WHERE tenant_id=? AND command_id=?",
                (r,i)->new Replay(r.getObject(1,UUID.class),r.getString(2),r.getString(3),r.getString(4),r.getObject(5,UUID.class)),actor.tenantId(),commandId);
            if(!previous.isEmpty()) {
                var old=previous.getFirst();
                if(!old.actor().equals(actor.accountId())||!old.action().equals(action)||!old.kind().equals(kind)||!old.hash().equals(inputHash))throw OrgException.conflict("같은 명령으로 다른 내용을 처리할 수 없습니다.");
                return old.target();
            }
            var change=Objects.requireNonNull(mutation.get());
            jdbc.update("""
                INSERT INTO tna.org_audit_event(tenant_id,id,command_id,actor_account_id,action,target_kind,target_id,input_hash,reason,before_state,after_state,request_trace_id)
                VALUES (?,?,?,?,?,?,?,?,?,CAST(? AS jsonb),CAST(? AS jsonb),?)
                """,actor.tenantId(),UUID.randomUUID(),commandId,actor.accountId(),action,kind,change.targetId(),inputHash,checkedReason,
                json.writeValueAsString(change.before()),json.writeValueAsString(change.after()),UUID.randomUUID());
            return change.targetId();
        });}
        catch(OrgException e){failure(e);throw e;}
        catch(DataIntegrityViolationException e){LOG.warn("event=business_command outcome=conflict");throw OrgException.conflict("다른 변경과 충돌했습니다. 최신 자료를 확인해 주세요.");}
        catch(DataAccessException|JacksonException e){LOG.error("event=business_command outcome=storage_unavailable");throw new OrgException(503,"저장을 완료하지 못했습니다. 같은 명령으로 다시 확인해 주세요.");}
    }
    private static void failure(OrgException error) {
        if(error.status()==403)LOG.warn("event=business_command outcome=denied");
        else LOG.warn("event=business_command outcome=rejected");
    }
    private void requireCurrent(TnaPrincipal actor,String role) {
        if(!actor.roles().contains("EMPLOYEE")||!actor.roles().contains(role))throw OrgException.forbidden();
        LocalDate today=LocalDate.now(clock.withZone(ZoneId.of("Asia/Seoul")));
        int allowed=jdbc.queryForObject("""
            SELECT count(*) FROM tna.tenant_account a
            JOIN tna.employee e ON e.tenant_id=a.tenant_id AND e.id=a.employee_id
            JOIN platform.tenant_registry r ON r.id=a.tenant_id
            WHERE a.tenant_id=? AND a.id=? AND a.employee_id=? AND a.auth_version=?
              AND a.active AND a.credential_set AND e.active AND r.active AND e.joined_on<=? AND(e.left_on IS NULL OR e.left_on>=?)
              AND EXISTS(SELECT 1 FROM tna.role_grant g WHERE g.tenant_id=a.tenant_id AND g.account_id=a.id AND g.role='EMPLOYEE' AND g.active)
              AND EXISTS(SELECT 1 FROM tna.role_grant g WHERE g.tenant_id=a.tenant_id AND g.account_id=a.id AND g.role=? AND g.active)
            """,Integer.class,actor.tenantId(),actor.accountId(),actor.employeeId(),actor.authVersion(),today,today,role);
        if(allowed!=1)throw OrgException.forbidden();
    }
    private record Replay(UUID actor,String action,String kind,String hash,UUID target) { }
}
