package kr.tna.notification;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import kr.tna.common.tenant.TenantTransactions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 1분마다 회사별 발송 대기 알림 메일을 보낸다. 여러 서버에서도 세션 잠금으로 한 대만 보낸다(설계 §5). */
@Component
@ConditionalOnProperty(name = "tna.notification-mail.enabled", havingValue = "true", matchIfMissing = true)
public class NotificationMailer {
    private static final Logger LOG = LoggerFactory.getLogger(NotificationMailer.class);
    static final long LOCK_KEY = 0x544E414E_4F544946L; // "TNANOTIF"
    private static final int BATCH = 100;

    private final DataSource dataSource;
    private final JdbcTemplate registry;
    private final TenantTransactions transactions;
    private final NotificationMapper mapper;
    private final NotificationMail mail;

    // 회사 목록은 회사 하나만 보이는 tna_app 대신 인증 연결(tna_auth)로 읽는다(DailyAttendanceBatch와 같다).
    public NotificationMailer(@Qualifier("dataSource") DataSource dataSource, @Qualifier("authDataSource") DataSource authDataSource,
            TenantTransactions transactions, NotificationMapper mapper, NotificationMail mail) {
        this.dataSource = dataSource; this.registry = new JdbcTemplate(authDataSource);
        this.transactions = transactions; this.mapper = mapper; this.mail = mail;
    }

    @Scheduled(fixedDelay = 60_000, initialDelay = 60_000)
    public void sweep() { runOnce(null); }

    /** tenants가 null이면 활성 회사 전체. @return 잠금을 얻어 실행했으면 true */
    public boolean runOnce(Collection<UUID> tenants) {
        try (var connection = dataSource.getConnection()) {
            var lock = new JdbcTemplate(new SingleConnectionDataSource(connection, true));
            if (!Boolean.TRUE.equals(lock.queryForObject("SELECT pg_try_advisory_lock(?)", Boolean.class, LOCK_KEY))) return false;
            try {
                for (UUID tenant : tenants == null ? activeTenants() : tenants) {
                    if (Thread.currentThread().isInterrupted()) break;
                    try { runTenant(tenant); }
                    catch (RuntimeException e) { LOG.warn("event=notification_mail outcome=tenant_failed tenant={} cause={}", tenant, e.getClass().getSimpleName()); }
                }
                return true;
            } finally {
                lock.queryForObject("SELECT pg_advisory_unlock(?)", Boolean.class, LOCK_KEY);
            }
        } catch (java.sql.SQLException | DataAccessException e) {
            LOG.warn("event=notification_mail outcome=storage_unavailable cause={}", e.getClass().getSimpleName());
            return false;
        }
    }

    private List<UUID> activeTenants() {
        return registry.queryForList("SELECT id FROM platform.tenant_registry WHERE active ORDER BY id", UUID.class);
    }

    private void runTenant(UUID tenant) {
        var pending = transactions.execute(tenant, () -> mapper.pendingMail(tenant, BATCH));
        for (var p : pending) {
            if (!mail.enabled() || p.email() == null) {
                transactions.execute(tenant, () -> mapper.finishMail(tenant, p.id(), "SKIPPED"));
                continue;
            }
            var facts = new NotificationText.MailFacts(p.kind(), p.companyName(), p.requesterName(),
                    NotificationText.summary(p.requestKind(), p.typeName(), p.fromDate(), p.toDate()),
                    "REJECTED".equals(p.kind()) ? p.eventReason() : null, p.requestId());
            try {
                // 발송은 트랜잭션 밖: 발송 직후 서버가 멈추면 한 번 더 갈 수 있다(최소 1회 전달).
                mail.send(NotificationText.mail(p.email(), facts, mail.origin()));
                transactions.execute(tenant, () -> mapper.finishMail(tenant, p.id(), "SENT"));
            } catch (RuntimeException e) {
                // 메일 예외 외에도 한 건의 실패가 회사 전체를 막지 않게 한다. 공급자 예외에는 받는 주소·본문이 들어 있을 수 있어 종류만 남긴다.
                transactions.execute(tenant, () -> mapper.retryMail(tenant, p.id(), p.mailAttempts() + 1));
                LOG.warn("event=notification_mail kind={} outcome=relay_unconfirmed attempt={} cause={}", p.kind(), p.mailAttempts() + 1, e.getClass().getSimpleName());
            }
        }
    }
}
