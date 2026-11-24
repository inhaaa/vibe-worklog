package kr.tna.common.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;
import java.util.UUID;
import javax.sql.DataSource;
import kr.tna.org.OrgCommands;
import kr.tna.org.OrgException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public final class PasswordResetService {
    private static final Logger LOG=LoggerFactory.getLogger(PasswordResetService.class);
    private static final SecureRandom RANDOM=new SecureRandom();
    private static final ZoneId SEOUL=ZoneId.of("Asia/Seoul");
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final PasswordEncoder encoder;
    private final Clock clock;
    private final InvitationDelivery delivery;
    private final LoginRateLimiter requests,completions;
    public PasswordResetService(@Qualifier("authDataSource") DataSource source,PasswordEncoder encoder,Clock clock,InvitationDelivery delivery) {
        jdbc=new JdbcTemplate(source);transaction=new TransactionTemplate(new DataSourceTransactionManager(source));
        this.encoder=encoder;this.clock=clock;this.delivery=delivery;requests=new LoginRateLimiter(clock);completions=new LoginRateLimiter(clock);
    }
    public void request(String companyCode,String email,String remoteAddress) {
        String company=normalize(companyCode,80,true),normalizedEmail=normalize(email,254,false);
        limit(requests,remoteAddress,company,normalizedEmail,true);
        if(!delivery.enabled()) throw unavailable(true);
        if(company.isEmpty()||normalizedEmail.isEmpty()) {LOG.info("event=password_reset_request outcome=accepted");return;}
        try {
            transaction.executeWithoutResult(status->{
                UUID tenant=tenant(company);if(tenant==null)return;lock(tenant);
                var today=LocalDate.now(clock.withZone(SEOUL));
                var accounts=jdbc.query("""
                        SELECT a.id,a.auth_version FROM tna.tenant_account a
                        JOIN tna.employee e ON e.tenant_id=a.tenant_id AND e.id=a.employee_id
                        JOIN platform.tenant_registry r ON r.id=a.tenant_id
                        WHERE a.tenant_id=? AND a.email=? AND a.active AND a.activation_expires_at IS NULL AND a.credential_set AND e.active AND r.active
                          AND e.joined_on<=? AND(e.left_on IS NULL OR e.left_on>=?)
                          AND EXISTS(SELECT 1 FROM tna.role_grant g WHERE g.tenant_id=a.tenant_id
                            AND g.account_id=a.id AND g.role='EMPLOYEE' AND g.active)
                        """,(row,i)->new Account(row.getObject("id",UUID.class),row.getLong("auth_version")),tenant,normalizedEmail,today,today);
                if(accounts.size()!=1)return;
                var account=accounts.getFirst();var now=clock.instant().truncatedTo(ChronoUnit.MILLIS);
                jdbc.update("""
                        UPDATE tna.account_token SET revoked_at=? WHERE tenant_id=? AND account_id=?
                          AND purpose='RESET' AND used_at IS NULL AND revoked_at IS NULL
                        """,java.sql.Timestamp.from(now),tenant,account.id());
                byte[] random=new byte[32];RANDOM.nextBytes(random);
                String token=Base64.getUrlEncoder().withoutPadding().encodeToString(random);UUID id=UUID.randomUUID();
                var expiresAt = now.plusSeconds(1800);
                jdbc.update("""
                        INSERT INTO tna.account_token(tenant_id,id,account_id,purpose,token_hash,email,
                            issued_auth_version,expires_at,created_by)
                        VALUES (?,?,?,'RESET',?,?,?,?,?)
                        """,tenant,id,account.id(),hash(token),normalizedEmail,account.version(),java.sql.Timestamp.from(expiresAt),account.id());
                audit(tenant,account.id(),UUID.randomUUID(),"ACCOUNT_RESET_REQUEST",account.version(),account.version());
                var envelope=InvitationDelivery.Envelope.reset(company,normalizedEmail,token,expiresAt);
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization(){
                    @Override public void afterCommit(){delivery.deliver(envelope);}
                });
            });
            LOG.info("event=password_reset_request outcome=accepted");
        }catch(DataAccessException|TransactionException exception){throw unavailable(true);}
    }
    public void complete(String companyCode,String email,String token,String password,String remoteAddress) {
        String company=normalize(companyCode,80,true),normalizedEmail=normalize(email,254,false);
        limit(completions,remoteAddress,company,normalizedEmail,false);
        if(company.isEmpty()||normalizedEmail.isEmpty()||token==null||!token.matches("[A-Za-z0-9_-]{43}")||!PasswordPolicy.isValid(password))throw rejected();
        try {
            String passwordHash=encoder.encode(password),tokenHash=hash(token);
            transaction.executeWithoutResult(status->{
                UUID tenant=tenant(company);if(tenant==null)throw rejected();lock(tenant);
                var now=clock.instant();var today=LocalDate.now(clock.withZone(SEOUL));
                var tokens=jdbc.query("""
                        SELECT t.id,a.id AS account_id,a.auth_version FROM tna.account_token t
                        JOIN tna.tenant_account a ON a.tenant_id=t.tenant_id AND a.id=t.account_id
                        JOIN tna.employee e ON e.tenant_id=a.tenant_id AND e.id=a.employee_id
                        JOIN platform.tenant_registry r ON r.id=a.tenant_id
                        WHERE t.tenant_id=? AND t.token_hash=? AND t.purpose='RESET' AND t.email=? AND a.email=t.email
                          AND t.issued_auth_version=a.auth_version AND t.used_at IS NULL AND t.revoked_at IS NULL
                          AND t.expires_at>? AND a.active AND a.activation_expires_at IS NULL AND a.credential_set AND e.active AND r.active
                          AND e.joined_on<=? AND(e.left_on IS NULL OR e.left_on>=?)
                          AND EXISTS(SELECT 1 FROM tna.role_grant g WHERE g.tenant_id=a.tenant_id
                            AND g.account_id=a.id AND g.role='EMPLOYEE' AND g.active)
                        """,(row,i)->new Token(row.getObject("id",UUID.class),row.getObject("account_id",UUID.class),row.getLong("auth_version")),
                        tenant,tokenHash,normalizedEmail,java.sql.Timestamp.from(now),today,today);
                if(tokens.size()!=1)throw rejected();var reset=tokens.getFirst();var at=java.sql.Timestamp.from(now.truncatedTo(ChronoUnit.MILLIS));
                int consumed=jdbc.update("""
                        UPDATE tna.account_token SET used_at=? WHERE tenant_id=? AND id=? AND purpose='RESET'
                          AND used_at IS NULL AND revoked_at IS NULL
                        """,at,tenant,reset.id());
                int changed=jdbc.update("""
                        UPDATE tna.tenant_account SET password_hash=?,auth_version=auth_version+1
                        WHERE tenant_id=? AND id=? AND auth_version=? AND active AND activation_expires_at IS NULL AND credential_set
                        """,passwordHash,tenant,reset.account(),reset.version());
                if(consumed!=1||changed!=1)throw rejected();
                jdbc.update("""
                        UPDATE tna.account_token SET revoked_at=? WHERE tenant_id=? AND account_id=? AND purpose='RESET'
                          AND id<>? AND used_at IS NULL AND revoked_at IS NULL
                        """,at,tenant,reset.account(),reset.id());
                audit(tenant,reset.account(),reset.id(),"ACCOUNT_RESET_COMPLETE",reset.version(),reset.version()+1);
            });
            LOG.info("event=password_reset_complete outcome=success");
        }catch(DataAccessException|TransactionException exception){throw unavailable(false);}
    }
    private UUID tenant(String company) {
        var tenants=jdbc.query("SELECT id FROM platform.tenant_registry WHERE company_code=? AND active",(r,i)->r.getObject("id",UUID.class),company);
        return tenants.size()==1?tenants.getFirst():null;
    }
    private void lock(UUID tenant) {
        jdbc.queryForObject("SELECT set_config('app.tenant_id',?,true)",String.class,tenant.toString());
        jdbc.queryForObject("SELECT pg_advisory_xact_lock(hashtextextended(CAST(? AS text),0))",Object.class,tenant);
    }
    private void audit(UUID tenant,UUID account,UUID command,String action,long before,long after) {
        jdbc.update("""
                INSERT INTO tna.org_audit_event(tenant_id,id,command_id,actor_account_id,action,target_kind,target_id,
                    input_hash,reason,before_state,after_state,request_trace_id)
                VALUES (?,?,?,?,?,'ACCOUNT',?,?,?,CAST(? AS jsonb),CAST(? AS jsonb),?)
                """,tenant,UUID.randomUUID(),command,account,action,account,OrgCommands.fingerprint(action,tenant,account,command),
                action.equals("ACCOUNT_RESET_REQUEST")?"비인증 비밀번호 재설정 요청":"비밀번호 재설정 완료",
                "{\"authVersion\":"+before+"}","{\"authVersion\":"+after+"}",UUID.randomUUID());
    }
    private void limit(LoginRateLimiter limiter,String address,String company,String email,boolean request) {
        if(!limiter.tryAcquire(address==null?"":address,company,email)) {
            LOG.warn(request?"event=password_reset_request outcome=rate_limited":"event=password_reset_complete outcome=rate_limited");
            throw new OrgException(429,"요청이 많습니다. 잠시 후 다시 시도해 주세요.");
        }
    }
    private OrgException rejected() {LOG.warn("event=password_reset_complete outcome=rejected");return OrgException.invalid("재설정 정보를 확인할 수 없습니다. 코드·유효 기간을 확인하거나 다시 요청해 주세요.");}
    private OrgException unavailable(boolean request) {
        LOG.error(request?"event=password_reset_request outcome=storage_unavailable":"event=password_reset_complete outcome=storage_unavailable");
        return new OrgException(503,"일시적으로 비밀번호 재설정을 처리할 수 없습니다. 잠시 후 다시 시도해 주세요.");
    }
    private String normalize(String raw,int maximum,boolean upper) {
        if(raw==null)return "";String value=raw.strip();value=upper?value.toUpperCase(Locale.ROOT):value.toLowerCase(Locale.ROOT);
        return value.length()>maximum||value.codePoints().anyMatch(c->Character.isISOControl(c)||c>=0xD800&&c<=0xDFFF)?"":value;
    }
    private String hash(String token) {
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.US_ASCII)));}
        catch(NoSuchAlgorithmException exception){throw new IllegalStateException(exception);}
    }
    private record Account(UUID id,long version) { }
    private record Token(UUID id,UUID account,long version) { }
}
