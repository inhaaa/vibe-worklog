package kr.tna;

import static org.junit.jupiter.api.Assertions.*;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import kr.tna.clock.ClockMapper;
import kr.tna.common.auth.TnaPrincipal;
import kr.tna.common.tenant.TenantTransactions;
import kr.tna.worktime.WorktimeMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest
class CalendarEffectiveDayIT {
    @Autowired ClockMapper clock;
    @Autowired WorktimeMapper worktime;
    @Autowired TenantTransactions transactions;
    JdbcTemplate db;
    UUID tenant;

    @DynamicPropertySource static void database(DynamicPropertyRegistry p) {
        var f = DatabaseFixture.start();
        p.add("spring.datasource.url", f::jdbcUrl);
        p.add("spring.datasource.username", () -> DatabaseFixture.APP_USER);
        p.add("spring.datasource.password", () -> DatabaseFixture.APP_PASSWORD);
        p.add("tna.auth.url", f::jdbcUrl);
        p.add("tna.auth.username", () -> DatabaseFixture.AUTH_USER);
        p.add("tna.auth.password", () -> DatabaseFixture.AUTH_PASSWORD);
    }

    @Test void aDayRevisionOverridesThePeriodForSingleAndMonthReaders() {
        db = new JdbcTemplate(DatabaseFixture.start().adminDataSource());
        tenant = UUID.randomUUID();
        db.update("INSERT INTO platform.tenant_registry(id,company_code,display_name) VALUES (?,?,'합성 유효 달력')", tenant, "EFFECTIVE-" + tenant);
        TnaPrincipal manager = member("ADMIN", Set.of("EMPLOYEE", "ATTENDANCE_ADMIN"));
        LocalDate holiday = LocalDate.of(2026, 5, 5);
        UUID period = CalendarSeed.period(db, manager, LocalDate.of(2026, 5, 1), LocalDate.of(2026, 5, 31), "합성 달력", "");
        UUID day = UUID.randomUUID();
        db.update("""
                INSERT INTO tna.calendar_version(tenant_id,id,revision,valid_from,valid_to,scope,reason,created_by)
                VALUES (?,?,2,?,?,'DAY','합성 날짜 반영',?)
                """, tenant, day, holiday, holiday.plusDays(1), manager.accountId());
        db.update("INSERT INTO tna.calendar_day(tenant_id,version_id,work_date,kind,name,basis) VALUES (?,?,?,'HOLIDAY','어린이날','합성')",
                tenant, day, holiday);

        assertEquals(List.of(new ClockMapper.CalendarRow(day, "HOLIDAY")), transactions.execute(tenant, () -> clock.calendar(tenant, holiday)));
        assertEquals(List.of(new ClockMapper.CalendarRow(period, "WORKDAY")),
                transactions.execute(tenant, () -> clock.calendar(tenant, holiday.plusDays(1))));
        var month = transactions.execute(tenant, () -> worktime.monthDays(tenant, manager.employeeId(), holiday.minusDays(1), holiday.plusDays(1)));
        assertEquals(List.of(period, day, period), month.stream().map(WorktimeMapper.MonthDayRow::calendarVersionId).toList());
        assertEquals(List.of("WORKDAY", "HOLIDAY", "WORKDAY"), month.stream().map(WorktimeMapper.MonthDayRow::dayKind).toList());
    }

    private TnaPrincipal member(String number, Set<String> roles) {
        UUID id = UUID.randomUUID(), account = UUID.randomUUID();
        db.update("INSERT INTO tna.employee(tenant_id,id,employee_no,display_name,joined_on) VALUES (?,?,?,'합성 시트 사원',?)",
                tenant, id, number, LocalDate.now(java.time.ZoneId.of("Asia/Seoul")).minusYears(1));
        db.update("INSERT INTO tna.tenant_account(tenant_id,id,employee_id,email,password_hash) VALUES (?,?,?,?,?)",
                tenant, account, id, account + "@example.test", "synthetic-sheet-password-hash");
        for (String role : roles) db.update("INSERT INTO tna.role_grant(tenant_id,account_id,role) VALUES (?,?,?)", tenant, account, role);
        return new TnaPrincipal(tenant, account, id, 1, roles, java.time.Instant.now());
    }
}
