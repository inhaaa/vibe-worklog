package kr.tna.console;

import static kr.tna.org.OrgCommands.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import javax.sql.DataSource;
import kr.tna.common.auth.InvitationDelivery;
import kr.tna.console.OperatorData.*;
import kr.tna.org.OrgException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.*;

@Service @ConditionalOnProperty(name="tna.operator.enabled",havingValue="true")
public final class OperatorService {
    private static final SecureRandom RANDOM=new SecureRandom();
    private final OperatorMapper mapper;private final TransactionTemplate tx;private final Clock clock;private final InvitationDelivery delivery;
    public OperatorService(OperatorMapper mapper,@Qualifier("operatorDataSource") DataSource source,Clock clock,InvitationDelivery delivery){this.mapper=mapper;this.tx=new TransactionTemplate(new DataSourceTransactionManager(source));this.clock=clock;this.delivery=delivery;}
    public List<Tenant> tenants(OperatorPrincipal actor){return read(actor,()->mapper.tenants());}
    public List<Audit> audit(OperatorPrincipal actor){return read(actor,()->mapper.audit());}
    public UUID onboard(OperatorPrincipal actor,TenantForm form){
        if(form==null||form.commandId()==null)throw OrgException.invalid("명령 식별자가 필요합니다.");
        String email=requiredText(form.email(),254,"최초 담당자 이메일").toLowerCase(Locale.ROOT);
        if(!email.matches("[^\\s@]+@[^\\s@]+")||email.codePoints().anyMatch(c->Character.isWhitespace(c)||Character.isSpaceChar(c)))throw OrgException.invalid("올바른 업무 이메일을 입력해 주세요.");
        if(form.joinedOn()==null||form.joinedOn().isAfter(LocalDate.now(clock.withZone(ZoneId.of("Asia/Seoul")))))throw OrgException.invalid("확인한 최초 담당자 입사일을 입력해 주세요.");
        var normalized=new TenantForm(requiredText(form.companyCode()==null?null:form.companyCode().toUpperCase(Locale.ROOT),80,"회사 코드"),requiredText(form.companyName(),120,"회사명"),requiredText(form.employeeNo(),40,"최초 사번"),requiredText(form.employeeName(),120,"최초 담당자명"),form.joinedOn(),email,requiredText(form.relationshipBasis(),500,"회사 관계·연락 경로 검토 근거"),requiredText(form.reason(),500,"등록 사유"),form.commandId());
        String hash=fingerprint("TENANT_ONBOARD",normalized.companyCode(),normalized.companyName(),normalized.employeeNo(),normalized.employeeName(),normalized.joinedOn(),email,normalized.relationshipBasis(),normalized.reason());
        return execute(actor,()->{
            if(!delivery.enabled())throw new OrgException(503,"로컬 모의 전달이 준비되지 않았습니다.");
            byte[] bytes=new byte[32];RANDOM.nextBytes(bytes);String token=Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
            String tokenHash;
            try{tokenHash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.US_ASCII)));}catch(java.security.NoSuchAlgorithmException e){throw new IllegalStateException(e);}
            var result=mapper.onboard(actor,normalized,hash,UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),tokenHash,clock.instant().plus(Duration.ofHours(24)));
            if(result.created())TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization(){@Override public void afterCommit(){delivery.deliver(new InvitationDelivery.Envelope(normalized.companyCode(),email,token));}});
            return result.tenantId();
        });
    }
    public UUID reissue(OperatorPrincipal actor,UUID tenantId,ReissueForm form){
        if(form==null||tenantId==null||form.commandId()==null||form.expectedVersion()==null||form.expectedVersion()<0)throw OrgException.invalid("회사·현재 버전·명령 식별자가 필요합니다.");
        String reason=requiredText(form.reason(),500,"최초 초대 재발급 사유");var checked=new ReissueForm(form.expectedVersion(),reason,form.commandId());
        return execute(actor,()->{
            if(!delivery.enabled())throw new OrgException(503,"로컬 모의 전달이 준비되지 않았습니다.");
            byte[] bytes=new byte[32];RANDOM.nextBytes(bytes);String token=Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);String tokenHash;
            try{tokenHash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.US_ASCII)));}catch(java.security.NoSuchAlgorithmException e){throw new IllegalStateException(e);}
            var result=mapper.reissue(actor,tenantId,checked,fingerprint("TENANT_INVITE_REISSUE",tenantId,form.expectedVersion(),reason),UUID.randomUUID(),tokenHash,clock.instant().plus(Duration.ofHours(24)));
            if(result.sent())TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization(){@Override public void afterCommit(){delivery.deliver(new InvitationDelivery.Envelope(result.companyCode(),result.email(),token));}});
            return tenantId;
        });
    }
    public UUID state(OperatorPrincipal actor,UUID tenantId,StateForm form){
        if(form==null||tenantId==null||form.commandId()==null||form.expectedVersion()==null||form.expectedVersion()<0)throw OrgException.invalid("회사·현재 버전·명령 식별자가 필요합니다.");
        String reason=requiredText(form.reason(),500,"이용 상태 변경 사유");var checked=new StateForm(form.active(),form.expectedVersion(),reason,form.commandId());
        return execute(actor,()->mapper.state(actor,tenantId,checked,fingerprint("TENANT_STATE",tenantId,checked.active(),checked.expectedVersion(),reason)));
    }
    private <T>T read(OperatorPrincipal actor,java.util.function.Supplier<T> action){
        return execute(actor,()->{
            T result=action.get();
            // A metadata read may have waited while operator access was revoked.
            requireCurrent(actor);
            return result;
        });
    }
    private void requireCurrent(OperatorPrincipal actor){
        var age=Duration.between(actor.authenticatedAt(),clock.instant());
        if(age.isNegative()||age.compareTo(Duration.ofHours(12))>=0||!mapper.current(actor))throw OrgException.forbidden();
    }
    private <T>T execute(OperatorPrincipal actor,java.util.function.Supplier<T> action){
        if(actor==null)throw OrgException.forbidden();
        try{return tx.execute(status->{requireCurrent(actor);return action.get();});}
        catch(DataIntegrityViolationException e){throw OrgException.conflict("중복 회사·명령 또는 오래된 버전입니다. 최신 내용을 확인해 주세요.");}
        catch(DataAccessException e){if("P0002".equals(sqlState(e)))throw OrgException.notFound();if("42501".equals(sqlState(e)))throw OrgException.forbidden();if("P0001".equals(sqlState(e)))throw OrgException.invalid("활성 회사의 변경되지 않은 최초 미수락 계정만 재발급할 수 있습니다.");throw new OrgException(503,"운영 요청을 완료하지 못했습니다. 같은 명령으로 다시 확인해 주세요.");}
    }
    private String sqlState(Throwable e){while(e!=null){if(e instanceof java.sql.SQLException s)return s.getSQLState();e=e.getCause();}return "";}
}
