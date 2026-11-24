package kr.tna.common.auth;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Locale;
import java.util.UUID;
import javax.sql.DataSource;
import kr.tna.org.AccountStatus;
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

/** 사번·생년월일로 대기 계정의 첫 비밀번호를 정한다. 실패 이유는 응답에 드러내지 않는다. */
@Service
public final class FirstLoginService {
    private static final Logger LOG = LoggerFactory.getLogger(FirstLoginService.class);
    static final String REJECTED = "입력한 정보가 맞지 않거나 최초 접속 기간이 지났습니다. 담당자에게 문의하세요.";
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final PasswordEncoder encoder;
    private final Clock clock;
    private final LoginRateLimiter limiter;

    public FirstLoginService(@Qualifier("authDataSource") DataSource source, PasswordEncoder encoder, Clock clock) {
        jdbc = new JdbcTemplate(source);
        transaction = new TransactionTemplate(new DataSourceTransactionManager(source));
        this.encoder = encoder;
        this.clock = clock;
        limiter = new LoginRateLimiter(clock);
    }

    public void activate(String companyCode, String employeeNo, LocalDate birthDate, String password, String remoteAddress) {
        String company = companyCode == null ? "" : companyCode.strip().toUpperCase(Locale.ROOT);
        String number = employeeNo == null ? "" : employeeNo.strip();
        if (company.length() > 80 || number.length() > 40) throw rejected();
        if (!limiter.tryAcquire(remoteAddress == null ? "" : remoteAddress, company, number)) {
            LOG.warn("event=first_login outcome=rate_limited");
            throw new OrgException(429, "최초 접속 요청이 많습니다. 잠시 후 다시 시도해 주세요.");
        }
        if (company.isBlank() || number.isBlank() || number.contains("@") || birthDate == null
                || !PasswordPolicy.isValid(password) || password.strip().equals(number)) throw rejected();
        Outcome outcome;
        try {
            String hash = encoder.encode(password);
            outcome = transaction.execute(status -> attempt(company, number, birthDate, hash));
        } catch (DataAccessException | TransactionException exception) {
            LOG.error("event=first_login outcome=storage_unavailable");
            throw new OrgException(503, "일시적으로 처리할 수 없습니다. 잠시 후 다시 시도해 주세요.");
        }
        if (outcome != Outcome.ACTIVATED) throw rejected();
    }

    private Outcome attempt(String company, String number, LocalDate birthDate, String hash) {
        var tenants = jdbc.query("SELECT id FROM platform.tenant_registry WHERE company_code=? AND active",
                (row, index) -> row.getObject("id", UUID.class), company);
        if (tenants.size() != 1) return Outcome.REJECTED;
        UUID tenant = tenants.getFirst();
        jdbc.queryForObject("SELECT set_config('app.tenant_id', ?, true)", String.class, tenant.toString());
        jdbc.queryForObject("SELECT pg_advisory_xact_lock(hashtextextended(CAST(? AS text), 0))", Object.class, tenant);
        var now = clock.instant();
        var today = LocalDate.now(clock.withZone(ZoneId.of("Asia/Seoul")));
        var rows = jdbc.query("""
                SELECT a.id,a.auth_version,a.activation_failures,p.birth_date
                FROM tna.tenant_account a
                JOIN tna.employee e ON e.tenant_id=a.tenant_id AND e.id=a.employee_id
                LEFT JOIN tna.employee_personnel p ON p.tenant_id=a.tenant_id AND p.employee_id=a.employee_id
                WHERE a.tenant_id=? AND e.employee_no=? AND a.activation_expires_at>? AND e.active
                  AND e.joined_on<=? AND (e.left_on IS NULL OR e.left_on>=?)
                """, (row, index) -> new Pending(row.getObject("id", UUID.class), row.getLong("auth_version"),
                row.getInt("activation_failures"), row.getObject("birth_date", LocalDate.class)),
                tenant, number, Timestamp.from(now), today, today);
        if (rows.size() != 1) return Outcome.REJECTED;
        Pending pending = rows.getFirst();
        if (pending.birthDate() == null || pending.failures() >= AccountStatus.MAX_FAILURES) return Outcome.REJECTED;
        if (!pending.birthDate().equals(birthDate)) {
            int failures = pending.failures() + 1;
            jdbc.update("UPDATE tna.tenant_account SET activation_failures=? WHERE tenant_id=? AND id=?", failures, tenant, pending.id());
            audit(tenant, pending.id(), failures == AccountStatus.MAX_FAILURES ? "ACCOUNT_LOCKED" : "ACCOUNT_ACTIVATION_FAILED",
                    "{\"failures\":" + pending.failures() + "}", "{\"failures\":" + failures + "}");
            return Outcome.REJECTED;
        }
        int changed = jdbc.update("""
                UPDATE tna.tenant_account
                SET password_hash=?,active=true,activation_expires_at=NULL,activation_failures=0,auth_version=auth_version+1
                WHERE tenant_id=? AND id=? AND auth_version=? AND activation_expires_at IS NOT NULL
                """, hash, tenant, pending.id(), pending.authVersion());
        if (changed != 1) return Outcome.REJECTED;
        audit(tenant, pending.id(), "ACCOUNT_ACTIVATED", "{\"pending\":true}", "{\"pending\":false,\"active\":true}");
        return Outcome.ACTIVATED;
    }

    private void audit(UUID tenant, UUID account, String action, String before, String after) {
        UUID event = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO tna.org_audit_event
                (tenant_id,id,command_id,actor_account_id,action,target_kind,target_id,input_hash,
                 reason,before_state,after_state,request_trace_id)
                VALUES (?,?,?,?,?,'ACCOUNT',?,?,'최초 접속',?::jsonb,?::jsonb,?)
                """, tenant, event, event, account, action, account,
                OrgCommands.fingerprint(action, tenant, event, account), before, after, UUID.randomUUID());
    }

    private OrgException rejected() {
        LOG.warn("event=first_login outcome=rejected");
        return OrgException.invalid(REJECTED);
    }

    private enum Outcome { ACTIVATED, REJECTED }
    private record Pending(UUID id, long authVersion, int failures, LocalDate birthDate) { }
}
