package kr.tna.worktime;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import javax.sql.DataSource;
import kr.tna.clock.ClockService;
import kr.tna.common.tenant.TenantTransactions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 매일 00:10 서울: 지난 7일과 재집계 필요 날짜를 회사별로 자동 확인·계산한다. 여러 서버에서도 세션 잠금으로 한 번만 실행된다. */
@Component
@ConditionalOnProperty(name = "tna.daily-batch.enabled", havingValue = "true", matchIfMissing = true)
public class DailyAttendanceBatch {
    private static final Logger LOG = LoggerFactory.getLogger(DailyAttendanceBatch.class);
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    static final long LOCK_KEY = 0x544E4144_41494C59L; // "TNADAILY"
    // ponytail: 지난 7일만 다시 본다. 서버가 7일 넘게 멈췄던 구간은 담당자 수동 재집계로 복구한다.
    private static final int LOOKBACK_DAYS = 7;
    private static final int RECALCULATION_DAYS = 62;

    private final DataSource dataSource;
    private final JdbcTemplate registry;
    private final TenantTransactions transactions;
    private final WorktimeMapper mapper;
    private final WorktimeService worktime;
    private final ClockService clocks;
    private final Clock clock;

    // 회사 목록은 RLS상 회사 하나만 보이는 tna_app 대신, 회사 코드 조회 정책이 있는 인증 연결(tna_auth)로 읽는다.
    public DailyAttendanceBatch(@Qualifier("dataSource") DataSource dataSource, @Qualifier("authDataSource") DataSource authDataSource,
            TenantTransactions transactions, WorktimeMapper mapper, WorktimeService worktime, ClockService clocks, Clock clock) {
        this.dataSource = dataSource; this.registry = new JdbcTemplate(authDataSource); this.transactions = transactions;
        this.mapper = mapper; this.worktime = worktime; this.clocks = clocks; this.clock = clock;
    }

    @Scheduled(cron = "0 10 0 * * *", zone = "Asia/Seoul")
    public void nightly() { runOnce(); }

    /** @return 잠금을 얻어 실행했으면 true, 다른 실행이 진행 중이면 false */
    public boolean runOnce() {
        return runOnce(null);
    }

    /** tenants가 null이면 활성 회사 전체. 시험은 자기 회사만 넘긴다. */
    boolean runOnce(Collection<UUID> tenants) {
        try (var connection = dataSource.getConnection()) {
            var lock = new JdbcTemplate(new SingleConnectionDataSource(connection, true));
            if (!Boolean.TRUE.equals(lock.queryForObject("SELECT pg_try_advisory_lock(?)", Boolean.class, LOCK_KEY))) return false;
            try {
                LocalDate today = LocalDate.now(clock.withZone(SEOUL));
                int failed = 0;
                for (UUID tenant : tenants == null ? activeTenants() : tenants) {
                    failed += runTenant(tenant, today);
                }
                LOG.info("event=daily_attendance_batch outcome=completed failures={}", failed);
                return true;
            } finally {
                lock.queryForObject("SELECT pg_advisory_unlock(?)", Boolean.class, LOCK_KEY);
            }
        } catch (java.sql.SQLException e) {
            LOG.warn("event=daily_attendance_batch outcome=storage_unavailable");
            return false;
        }
    }

    private List<UUID> activeTenants() {
        return registry.queryForList("SELECT id FROM platform.tenant_registry WHERE active ORDER BY id", UUID.class);
    }

    /** @return 실패한 사원(또는 회사 전체 실패 시 1) 수 */
    private int runTenant(UUID tenant, LocalDate today) {
        LocalDate from = today.minusDays(LOOKBACK_DAYS), yesterday = today.minusDays(1);
        List<UUID> employees;
        try {
            employees = transactions.execute(tenant, () -> mapper.employedBetween(tenant, from, yesterday));
        } catch (RuntimeException e) {
            LOG.warn("event=daily_attendance_batch outcome=tenant_failed cause={}", e.getClass().getSimpleName());
            return 1;
        }
        int failed = 0;
        for (UUID employee : employees) {
            try {
                // 사원마다 짧은 트랜잭션: 회사 잠금을 오래 잡지 않는다.
                transactions.execute(tenant, () -> {
                    mapper.lockForWrite(tenant); // 자동 확인(공유 잠금)보다 먼저 쓰기 잠금을 잡아 잠금 승격을 피한다
                    var dates = new TreeSet<LocalDate>();
                    for (LocalDate d = from; !d.isAfter(yesterday); d = d.plusDays(1)) dates.add(d);
                    dates.addAll(mapper.recalculationDates(tenant, employee, today.minusDays(RECALCULATION_DAYS), yesterday));
                    var closed = Set.copyOf(mapper.closedMonths(tenant, dates.first(), yesterday));
                    dates.removeIf(d -> closed.contains(d.withDayOfMonth(1)));
                    for (LocalDate d : dates) clocks.autoConfirm(tenant, employee, d, null);
                    return worktime.calculateDatesAsSystem(tenant, null, employee, dates);
                });
            } catch (RuntimeException e) {
                failed++;
                LOG.warn("event=daily_attendance_batch outcome=employee_failed cause={}", e.getClass().getSimpleName());
            }
        }
        return failed;
    }
}
