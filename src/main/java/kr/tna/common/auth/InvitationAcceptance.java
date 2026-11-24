package kr.tna.common.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
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
import org.springframework.transaction.support.TransactionTemplate;

@Service
public final class InvitationAcceptance {
    private static final Logger LOG = LoggerFactory.getLogger(InvitationAcceptance.class);
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final PasswordEncoder encoder;
    private final Clock clock;
    private final LoginRateLimiter limiter;

    public InvitationAcceptance(@Qualifier("authDataSource") DataSource source, PasswordEncoder encoder, Clock clock) {
        jdbc = new JdbcTemplate(source);
        transaction = new TransactionTemplate(new DataSourceTransactionManager(source));
        this.encoder = encoder;
        this.clock = clock;
        limiter = new LoginRateLimiter(clock);
    }

    public void accept(String companyCode, String email, String token, String password, String remoteAddress) {
        String company = companyCode == null ? "" : companyCode.strip().toUpperCase(Locale.ROOT);
        String normalizedEmail = email == null ? "" : email.strip().toLowerCase(Locale.ROOT);
        if (company.length() > 80 || normalizedEmail.length() > 254) throw rejected();
        if (!limiter.tryAcquire(remoteAddress == null ? "" : remoteAddress, company, normalizedEmail)) {
            LOG.warn("event=invitation_accept outcome=rate_limited");
            throw new OrgException(429, "초대 확인 요청이 많습니다. 잠시 후 다시 시도해 주세요.");
        }
        if (company.isBlank() || normalizedEmail.isBlank() || token == null
                || !token.matches("[A-Za-z0-9_-]{43}") || !PasswordPolicy.isValid(password)) throw rejected();
        try {
            String passwordHash = encoder.encode(password);
            String tokenHash = tokenHash(token);
            transaction.executeWithoutResult(status -> {
                var tenants = jdbc.query("SELECT id FROM platform.tenant_registry WHERE company_code=? AND active",
                        (row, index) -> row.getObject("id", UUID.class), company);
                if (tenants.size() != 1) throw rejected();
                UUID tenant = tenants.getFirst();
                jdbc.queryForObject("SELECT set_config('app.tenant_id', ?, true)", String.class, tenant.toString());
                jdbc.queryForObject("SELECT pg_advisory_xact_lock(hashtextextended(CAST(? AS text), 0))", Object.class, tenant);
                var now = clock.instant();
                var today = LocalDate.now(clock.withZone(ZoneId.of("Asia/Seoul")));
                var tokens = jdbc.query("""
                        SELECT t.id,a.id AS account_id,a.auth_version
                        FROM tna.account_token t
                        JOIN tna.tenant_account a ON a.tenant_id=t.tenant_id AND a.id=t.account_id
                        JOIN tna.employee e ON e.tenant_id=a.tenant_id AND e.id=a.employee_id
                        JOIN platform.tenant_registry r ON r.id=a.tenant_id
                        WHERE t.tenant_id=? AND t.token_hash=? AND t.purpose='INVITE'
                          AND t.email=? AND a.email=t.email AND t.issued_auth_version=a.auth_version
                          AND t.used_at IS NULL AND t.revoked_at IS NULL AND t.expires_at>?
                          AND NOT a.active AND NOT a.credential_set AND a.activation_expires_at IS NULL AND r.active AND e.active
                          AND e.joined_on<=? AND (e.left_on IS NULL OR e.left_on>=?)
                        """, (row, index) -> new Token(row.getObject("id", UUID.class), row.getObject("account_id", UUID.class),
                        row.getLong("auth_version")), tenant, tokenHash, normalizedEmail, java.sql.Timestamp.from(now), today, today);
                if (tokens.size() != 1) throw rejected();
                Token invitation = tokens.getFirst();
                int used = jdbc.update("""
                        UPDATE tna.account_token SET used_at=?
                        WHERE tenant_id=? AND id=? AND used_at IS NULL AND revoked_at IS NULL
                        """, java.sql.Timestamp.from(now), tenant, invitation.id());
                int activated = jdbc.update("""
                        UPDATE tna.tenant_account SET password_hash=?,active=true,auth_version=auth_version+1
                        WHERE tenant_id=? AND id=? AND auth_version=? AND NOT active AND NOT credential_set AND activation_expires_at IS NULL
                        """, passwordHash, tenant, invitation.accountId(), invitation.authVersion());
                if (used != 1 || activated != 1) throw rejected();
                jdbc.update("""
                        INSERT INTO tna.org_audit_event
                        (tenant_id,id,command_id,actor_account_id,action,target_kind,target_id,input_hash,
                         reason,before_state,after_state,request_trace_id)
                        VALUES (?,?,?,?,'ACCOUNT_INVITE_ACCEPT','ACCOUNT',?,?,'초대 수락',
                                '{"active":false,"credentialSet":false}'::jsonb,
                                '{"active":true,"credentialSet":true}'::jsonb,?)
                        """, tenant, UUID.randomUUID(), invitation.id(), invitation.accountId(), invitation.accountId(),
                        OrgCommands.fingerprint("ACCOUNT_INVITE_ACCEPT", tenant, invitation.id(), invitation.accountId()), UUID.randomUUID());
            });
        } catch (DataAccessException | TransactionException exception) {
            LOG.error("event=invitation_accept outcome=storage_unavailable");
            throw new OrgException(503, "일시적으로 초대를 확인할 수 없습니다. 잠시 후 다시 시도해 주세요.");
        }
    }

    private OrgException rejected() {
        LOG.warn("event=invitation_accept outcome=rejected");
        return OrgException.invalid("초대를 확인할 수 없습니다. 입력과 유효 기간을 확인하거나 담당자에게 문의해 주세요.");
    }

    private String tokenHash(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.US_ASCII)));
        } catch (NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); }
    }

    private record Token(UUID id, UUID accountId, long authVersion) { }
}
