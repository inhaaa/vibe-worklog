package kr.tna.worktime;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import kr.tna.DatabaseFixture;
import kr.tna.common.tenant.TenantTransactions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/** 새벽 일괄 작업이 회사별로 어제 결근·남은 자동 확인·재집계 필요 날짜를 처리하는지 확인한다. 실행은 이 시험의 회사로만 좁힌다. */
@SpringBootTest(properties = "tna.daily-batch.enabled=true")
class DailyAttendanceBatchIT {
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    @Autowired DailyAttendanceBatch batch;
    @Autowired Clock clock;
    // 스텁하지 않은 시험에서는 실제 동작 그대로다.
    @MockitoSpyBean WorktimeService worktime;
    @MockitoSpyBean TenantTransactions transactions;
    private JdbcTemplate db;
    private LocalDate today, yesterday;
    private UUID tenantA, tenantB, employee1, employee2, employee3;

    @DynamicPropertySource static void database(DynamicPropertyRegistry p) {
        var f = DatabaseFixture.start();
        p.add("spring.datasource.url", f::jdbcUrl);
        p.add("spring.datasource.username", () -> DatabaseFixture.APP_USER);
        p.add("spring.datasource.password", () -> DatabaseFixture.APP_PASSWORD);
        p.add("tna.auth.url", f::jdbcUrl);
        p.add("tna.auth.username", () -> DatabaseFixture.AUTH_USER);
        p.add("tna.auth.password", () -> DatabaseFixture.AUTH_PASSWORD);
    }

    @BeforeEach void twoCompanies() {
        db = new JdbcTemplate(DatabaseFixture.start().adminDataSource());
        today = LocalDate.now(clock.withZone(SEOUL));
        yesterday = today.minusDays(1);
        tenantA = UUID.randomUUID();
        tenantB = UUID.randomUUID();
        var a = company(tenantA);
        var b = company(tenantB);
        employee1 = employee(tenantA, a);
        employee2 = employee(tenantA, a);
        employee3 = employee(tenantB, b);
        UUID network = UUID.randomUUID();
        db.update("INSERT INTO tna.allowed_network(tenant_id,id,network,label,valid_from,reason,created_by) VALUES (?,?,CAST('127.0.0.1/32' AS cidr),'합성 로컬',?,'합성 근거',?)",
                tenantA, network, java.sql.Timestamp.from(today.minusDays(70).atStartOfDay(SEOUL).toInstant()), a[0]);
        record(tenantA, employee2, network, yesterday);
    }

    @Test void batchMarksYesterdayAbsenceAndAutoConfirmsLeftoverDays() {
        LocalDate flagged = today.minusDays(20);
        flag(tenantA, employee1, flagged);

        assertTrue(batch.runOnce(List.of(tenantA, tenantB)));

        assertEquals("FULL", latest(employee1, yesterday).get("absence"));
        assertEquals(tenantA, latest(employee1, yesterday).get("tenant_id"));
        assertNull(db.queryForObject("SELECT requested_by FROM tna.calculation_run WHERE employee_id=? ORDER BY started_at DESC LIMIT 1",
                UUID.class, employee1));
        assertNotNull(latest(employee1, flagged), "최근 62일 안의 재집계 필요 날짜도 계산합니다.");

        var confirmation = db.queryForMap("SELECT method, confirmed_by FROM tna.work_confirmation WHERE employee_id=? AND work_date=?",
                employee2, yesterday);
        assertEquals("AUTO_SCHEDULE", confirmation.get("method"));
        assertNull(confirmation.get("confirmed_by"));
        var worked = latest(employee2, yesterday);
        assertEquals(28_800_000L, worked.get("actual_work_millis"));
        assertEquals(tenantA, worked.get("tenant_id"));

        assertEquals("FULL", latest(employee3, yesterday).get("absence"));
        assertEquals(tenantB, latest(employee3, yesterday).get("tenant_id"));
    }

    @Test void runningTwiceWritesNothingNew() {
        assertTrue(batch.runOnce(List.of(tenantA, tenantB)));
        long results = results(), runs = runs(), confirmations = confirmations();
        assertTrue(results > 0);
        assertEquals(1L, confirmations);
        assertTrue(batch.runOnce(List.of(tenantA, tenantB)));
        assertEquals(results, results());
        assertEquals(runs, runs());
        assertEquals(confirmations, confirmations());
    }

    @Test void closedMonthIsSkipped() {
        // 지지난달 말일: 지난 7일에는 들어가지 않고(매달 1일에도) 재집계 창(62일) 안에 있다.
        LocalDate closedDay = today.withDayOfMonth(1).minusMonths(1).minusDays(1);
        db.update("""
                INSERT INTO tna.attendance_month_closure(tenant_id,id,month_start,version,status,reason,actor_account_id,actor_name)
                SELECT ?,?,?,1,'CLOSED','합성 마감',id,'합성 담당자' FROM tna.tenant_account WHERE tenant_id=? LIMIT 1""",
                tenantA, UUID.randomUUID(), closedDay.withDayOfMonth(1), tenantA);
        flag(tenantA, employee1, closedDay);

        assertTrue(batch.runOnce(List.of(tenantA)));

        assertEquals(0, db.queryForObject("SELECT count(*) FROM tna.day_result WHERE tenant_id=? AND employee_id=? AND work_date=?",
                Integer.class, tenantA, employee1, closedDay));
        assertNotNull(latest(employee1, yesterday), "마감하지 않은 날짜는 계속 계산합니다.");
    }

    // LOCK_KEY는 DB 전체에 하나라서 같은 DB로 이 시험을 동시에 두 번 돌리면 서로 간섭한다.
    @Test void anotherRunHoldingTheLockSkipsThisRun() throws Exception {
        try (var holder = DatabaseFixture.start().adminDataSource().getConnection();
             var lock = holder.prepareStatement("SELECT pg_advisory_lock(?)")) {
            lock.setLong(1, DailyAttendanceBatch.LOCK_KEY);
            lock.execute();
            assertFalse(batch.runOnce(List.of(tenantA)));
            assertEquals(0L, results());
            try (var unlock = holder.prepareStatement("SELECT pg_advisory_unlock(?)")) {
                unlock.setLong(1, DailyAttendanceBatch.LOCK_KEY);
                unlock.execute();
            }
        }
        assertTrue(batch.runOnce(List.of(tenantA)));
    }

    @Test void oneFailingEmployeeDoesNotStopTheOthers() {
        doThrow(new IllegalStateException("합성 실패")).when(worktime).calculateDatesAsSystem(eq(tenantA), isNull(), eq(employee1), any());

        assertTrue(batch.runOnce(List.of(tenantA, tenantB)));

        assertEquals(0, db.queryForObject("SELECT count(*) FROM tna.day_result WHERE employee_id=?", Integer.class, employee1));
        assertEquals(28_800_000L, latest(employee2, yesterday).get("actual_work_millis"));
        assertEquals("FULL", latest(employee3, yesterday).get("absence"));
    }

    @Test void oneFailingTenantDoesNotStopTheNext() {
        doThrow(new IllegalStateException("합성 회사 실패")).when(transactions).execute(eq(tenantA), any());

        assertTrue(batch.runOnce(List.of(tenantA, tenantB)));

        assertEquals(0, db.queryForObject("SELECT count(*) FROM tna.day_result WHERE tenant_id=?", Integer.class, tenantA));
        assertEquals("FULL", latest(employee3, yesterday).get("absence"));
    }

    private java.util.Map<String, Object> latest(UUID employee, LocalDate date) {
        var rows = db.queryForList("""
                SELECT tenant_id, absence, actual_work_millis FROM tna.day_result WHERE employee_id=? AND work_date=?
                ORDER BY result_revision DESC LIMIT 1""", employee, date);
        return rows.isEmpty() ? null : rows.getFirst();
    }
    private long results() {
        return db.queryForObject("SELECT count(*) FROM tna.day_result WHERE tenant_id IN (?,?)", Long.class, tenantA, tenantB);
    }
    private long runs() {
        return db.queryForObject("SELECT count(*) FROM tna.calculation_run WHERE tenant_id IN (?,?)", Long.class, tenantA, tenantB);
    }
    private long confirmations() {
        return db.queryForObject("SELECT count(*) FROM tna.work_confirmation WHERE tenant_id IN (?,?)", Long.class, tenantA, tenantB);
    }
    private void flag(UUID tenant, UUID employee, LocalDate date) {
        db.update("INSERT INTO tna.day_input_revision(tenant_id,employee_id,work_date,input_revision,review_revision,recalculation_required) VALUES (?,?,?,0,0,true)",
                tenant, employee, date);
    }

    /** 회사와 담당자 계정, 고정 일정(08~17시, 12~13시 휴게), 70일 전부터 내일까지의 근로일 달력. {담당자 계정, 일정 버전}을 돌려준다. */
    private UUID[] company(UUID tenant) {
        db.update("INSERT INTO platform.tenant_registry(id,company_code,display_name) VALUES (?,?,'합성 일괄 고객사')", tenant, "BATCH-" + tenant);
        UUID managerEmployee = UUID.randomUUID(), manager = UUID.randomUUID();
        db.update("INSERT INTO tna.employee(tenant_id,id,employee_no,display_name,joined_on) VALUES (?,?,?,'합성 담당자',?)",
                tenant, managerEmployee, "E-" + managerEmployee, today.minusYears(1));
        db.update("INSERT INTO tna.tenant_account(tenant_id,id,employee_id,email,password_hash) VALUES (?,?,?,?, 'synthetic-unused-password-hash')",
                tenant, manager, managerEmployee, manager + "@example.test");
        LocalDate from = today.minusDays(70);
        UUID template = UUID.randomUUID(), schedule = UUID.randomUUID();
        db.update("INSERT INTO tna.schedule_template(tenant_id,id,code,name,created_by,updated_by) VALUES (?,?,?,'합성 고정 일정',?,?)",
                tenant, template, template.toString(), manager, manager);
        db.update("INSERT INTO tna.schedule_version(tenant_id,id,template_id,revision,valid_from,reason,created_by) VALUES (?,?,?,1,?,'합성 버전',?)",
                tenant, schedule, template, from, manager);
        for (int day = 1; day <= 7; day++) {
            db.update("INSERT INTO tna.schedule_day(tenant_id,version_id,weekday,workday,start_time,end_time) VALUES (?,?,?,true,TIME '08:00',TIME '17:00')", tenant, schedule, day);
            db.update("INSERT INTO tna.schedule_break(tenant_id,version_id,weekday,sequence,start_time,end_time) VALUES (?,?,?,1,TIME '12:00',TIME '13:00')", tenant, schedule, day);
        }
        UUID calendar = UUID.randomUUID();
        db.update("INSERT INTO tna.calendar_version(tenant_id,id,revision,valid_from,valid_to,reason,created_by) VALUES (?,?,1,?,?,'합성 달력',?)",
                tenant, calendar, from, today.plusDays(2), manager);
        for (LocalDate d = from; !d.isAfter(today.plusDays(1)); d = d.plusDays(1)) {
            db.update("INSERT INTO tna.calendar_day(tenant_id,version_id,work_date,kind,name,basis) VALUES (?,?,?,'WORKDAY','검토한 기본 근로일','합성 근로일 검토')",
                    tenant, calendar, d);
        }
        return new UUID[] {manager, schedule};
    }

    private UUID employee(UUID tenant, UUID[] company) {
        UUID id = UUID.randomUUID();
        db.update("INSERT INTO tna.employee(tenant_id,id,employee_no,display_name,joined_on) VALUES (?,?,?,'합성 직원',?)",
                tenant, id, "E-" + id, today.minusYears(1));
        db.update("INSERT INTO tna.schedule_assignment(tenant_id,id,employee_id,schedule_version_id,valid_from,reason,created_by,updated_by) VALUES (?,?,?,?,?,'합성 배정',?,?)",
                tenant, UUID.randomUUID(), id, company[1], today.minusDays(70), company[0], company[0]);
        return id;
    }

    private void record(UUID tenant, UUID employee, UUID network, LocalDate date) {
        for (String kind : List.of("IN", "OUT")) {
            Instant at = date.atTime(kind.equals("IN") ? 8 : 17, 0).atZone(SEOUL).toInstant();
            db.update("""
                    INSERT INTO tna.attendance_event(tenant_id,id,employee_id,event_kind,occurred_at,received_at,client_command_id,
                        source,validated_ip,network_id,assigned_work_date) VALUES (?,?,?,?,?,?,?,'IP',CAST('127.0.0.1' AS inet),?,?)
                    """, tenant, UUID.randomUUID(), employee, kind, java.sql.Timestamp.from(at), java.sql.Timestamp.from(at),
                    UUID.randomUUID(), network, date);
        }
        db.update("INSERT INTO tna.day_input_revision(tenant_id,employee_id,work_date,input_revision,review_revision) VALUES (?,?,?,2,0)",
                tenant, employee, date);
    }
}
