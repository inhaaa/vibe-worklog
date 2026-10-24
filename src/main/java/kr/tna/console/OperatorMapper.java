package kr.tna.console;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import kr.tna.console.OperatorData.*;
import org.springframework.jdbc.core.JdbcTemplate;

/** Uses only the explicitly supplied operator connection, never the application SqlSessionFactory. */
public final class OperatorMapper {
    private final JdbcTemplate jdbc;
    public OperatorMapper(DataSource source){jdbc=new JdbcTemplate(source);}
    public Credential credential(String login){var rows=jdbc.query("SELECT id,login_id,password_hash,active,auth_version FROM platform.operator_account WHERE login_id=?",(r,i)->new Credential(r.getObject(1,UUID.class),r.getString(2),r.getString(3),r.getBoolean(4),r.getLong(5)),login);return rows.isEmpty()?null:rows.getFirst();}
    public boolean current(OperatorPrincipal actor){return actor!=null&&Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM platform.operator_account WHERE id=? AND login_id=? AND active AND auth_version=?)",Boolean.class,actor.id(),actor.loginId(),actor.authVersion()));}
    public List<Tenant> tenants(){return jdbc.query("SELECT id,company_code,display_name,active,version FROM platform.tenant_registry ORDER BY company_code,id",(r,i)->new Tenant(r.getObject(1,UUID.class),r.getString(2),r.getString(3),r.getBoolean(4),r.getLong(5)));}
    public List<Audit> audit(){return jdbc.query("SELECT id,tenant_id,action,reason,relationship_basis,db_actor,created_at FROM platform.operator_audit_event ORDER BY created_at DESC,id LIMIT 100",(r,i)->new Audit(r.getObject(1,UUID.class),r.getObject(2,UUID.class),r.getString(3),r.getString(4),r.getString(5),r.getString(6),r.getTimestamp(7).toInstant()));}
    public Onboarded onboard(OperatorPrincipal actor,TenantForm form,String hash,UUID tenant,UUID employee,UUID account,UUID token,String tokenHash,Instant expires){
        return jdbc.queryForObject("SELECT * FROM platform.operator_onboard(?,?,?, ?,?,?, ?,?,?, ?,?,?, ?,?,?, ?,?,?)",(r,i)->new Onboarded(r.getObject(1,UUID.class),r.getObject(2,UUID.class),r.getBoolean(3)),actor.id(),actor.authVersion(),form.commandId(),hash,tenant,form.companyCode(),form.companyName(),employee,form.employeeNo(),form.employeeName(),form.joinedOn(),account,form.email(),token,tokenHash,java.sql.Timestamp.from(expires),form.relationshipBasis(),form.reason());
    }
    public Reissued reissue(OperatorPrincipal actor,UUID tenant,ReissueForm form,String hash,UUID token,String tokenHash,Instant expires){
        return jdbc.queryForObject("SELECT * FROM platform.operator_reissue_initial(?,?,?,?,?,?,?,?,?,?)",(r,i)->new Reissued(r.getObject(1,UUID.class),r.getString(2),r.getString(3),r.getBoolean(4)),actor.id(),actor.authVersion(),form.commandId(),hash,tenant,form.expectedVersion(),token,tokenHash,java.sql.Timestamp.from(expires),form.reason());
    }
    public UUID state(OperatorPrincipal actor,UUID tenant,StateForm form,String hash){return jdbc.queryForObject("SELECT platform.operator_set_state(?,?,?,?,?,?,?,?)",UUID.class,actor.id(),actor.authVersion(),form.commandId(),hash,tenant,form.active(),form.expectedVersion(),form.reason());}
    public record Credential(UUID id,String loginId,String passwordHash,boolean active,long authVersion){@Override public String toString(){return "Credential[redacted]";}}
}
