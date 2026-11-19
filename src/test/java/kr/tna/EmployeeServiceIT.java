package kr.tna;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import kr.tna.common.auth.TnaPrincipal;
import kr.tna.clock.AttendanceSettingsData.CalendarForm;
import kr.tna.clock.AttendanceSettingsService;
import kr.tna.org.BulkData;
import kr.tna.org.EmployeeService;
import kr.tna.org.OrgBulkService;
import kr.tna.org.OrgData.AssignmentForm;
import kr.tna.org.OrgData.EmployeeForm;
import kr.tna.org.OrgException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class EmployeeServiceIT {
    private static final LocalDate TODAY = LocalDate.now(ZoneId.of("Asia/Seoul"));
    private static final LocalDate JOINED = TODAY.minusYears(1);
    @Autowired EmployeeService employees;
    @Autowired AttendanceSettingsService attendanceSettings;
    @Autowired OrgBulkService bulk;
    private JdbcTemplate database;
    private TnaPrincipal actor;
    private UUID tenant;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry properties) {
        var fixture = DatabaseFixture.start();
        properties.add("spring.datasource.url", fixture::jdbcUrl);
        properties.add("spring.datasource.username", () -> DatabaseFixture.APP_USER);
        properties.add("spring.datasource.password", () -> DatabaseFixture.APP_PASSWORD);
        properties.add("spring.datasource.hikari.maximum-pool-size", () -> 5);
        properties.add("tna.auth.url", fixture::jdbcUrl);
        properties.add("tna.auth.username", () -> DatabaseFixture.AUTH_USER);
        properties.add("tna.auth.password", () -> DatabaseFixture.AUTH_PASSWORD);
    }

    @BeforeEach
    void tenantWithOneAdmin() {
        database = new JdbcTemplate(DatabaseFixture.start().adminDataSource());
        tenant = UUID.randomUUID();
        database.update("""
                INSERT INTO platform.tenant_registry(id, company_code, display_name)
                VALUES (?, ?, '합성 직원 테스트')
                """, tenant, "EMP-" + tenant);
        actor = admin("ADMIN-1");
    }

    @Test
    void employmentEditsKeepUnresolvedCheckoutDaysReachableAndRollBackTheWholeSheet() {
        UUID id = create("FOLLOW-UP");
        LocalDate day = TODAY.minusDays(7);
        database.update("""
                INSERT INTO tna.checkout_follow_up(tenant_id,id,employee_id,work_date,requested_by,reason)
                VALUES (?,?,?,?,?,'합성 퇴근 보완')
                """, tenant, UUID.randomUUID(), id, day, actor.accountId());
        var edit = form("FOLLOW-UP", "퇴사일 수정", JOINED, day.minusDays(1), true, 0L);
        var error = assertThrows(OrgException.class, () -> employees.update(actor, id, edit));
        assertEquals(409, error.status());
        assertTrue(error.getMessage().contains(day.toString()));
        assertTrue(error.getMessage().contains("보완"));
        assertNull(employees.get(actor, id).leftOn());
        assertEquals(0, count("tna.org_audit_event", "command_id", edit.commandId()));

        var batch = new BulkData.Form<>(UUID.randomUUID(), "소급 일괄 수정", List.of(
                new BulkData.EmployeeRow(UUID.randomUUID().toString(), null, null, BulkData.Operation.CREATE, "FOLLOW-NEW", "신규", TODAY, null, true),
                new BulkData.EmployeeRow(UUID.randomUUID().toString(), id, 0L, BulkData.Operation.UPDATE, "FOLLOW-UP", "입사일 수정", day.plusDays(1), null, true)));
        var bulkError = assertThrows(OrgException.class, () -> bulk.employees(actor, batch));
        assertEquals(409, bulkError.status());
        assertTrue(bulkError.getMessage().contains(day.toString()));
        assertEquals(0, count("tna.org_audit_event", "command_id", batch.commandId()));
        assertEquals(0, database.queryForObject("SELECT count(*) FROM tna.employee WHERE tenant_id=? AND employee_no='FOLLOW-NEW'", Integer.class, tenant));
        assertEquals(0, employees.get(actor, id).version());

        employees.update(actor, id, form("FOLLOW-UP", "이름만 변경", JOINED, null, true, 0L));
        employees.update(actor, id, form("FOLLOW-UP", "보완일 포함", day, day, true, 1L));
        assertEquals(day, employees.get(actor, id).leftOn());
    }

    @Test
    void anAlreadyOutOfEmploymentFollowUpAllowsUnrelatedEditsAndRepairOfEmploymentDates() {
        UUID id = create("OLD-FOLLOW");
        LocalDate orphan = JOINED.minusDays(1);
        database.update("""
                INSERT INTO tna.checkout_follow_up(tenant_id,id,employee_id,work_date,requested_by,reason)
                VALUES (?,?,?,?,?,'이관된 보완 요청')
                """, tenant, UUID.randomUUID(), id, orphan, actor.accountId());
        employees.update(actor, id, form("OLD-FOLLOW", "퇴직 예정", JOINED, TODAY.plusDays(10), true, 0L));
        employees.update(actor, id, form("OLD-FOLLOW", "입사일 복구", orphan, TODAY.plusDays(10), true, 1L));
        assertEquals(orphan, employees.get(actor, id).joinedOn());
        assertStatus(409, () -> employees.update(actor, id,
                form("OLD-FOLLOW", "다시 보완일이 빠짐", JOINED, TODAY.plusDays(10), true, 2L)));
    }

    @Test
    void closedMonthProtectsEmploymentDaysAndCalendarButAllowsFutureEmploymentChanges() {
        var month = TODAY.withDayOfMonth(1).minusMonths(1);
        var last = month.plusMonths(1).minusDays(1);
        UUID original = create("CLOSE-OLD");
        database.update("""
                INSERT INTO tna.attendance_month_closure
                    (tenant_id,id,month_start,version,status,reason,actor_account_id,actor_name)
                VALUES (?,?,?,1,'CLOSED','합성 월마감',?,'합성 담당자')
                """, tenant, UUID.randomUUID(), month, actor.accountId());

        assertStatus(409, () -> employees.create(actor, form("CLOSE-RETRO", "소급 입사", last, null, true, null)));
        assertStatus(409, () -> employees.update(actor, original, form("CLOSE-OLD", "소급 퇴직", JOINED, last.minusDays(1), true, 0L)));
        assertStatus(409, () -> attendanceSettings.calendar(actor, new CalendarForm(last, last.plusDays(1), true, "기준", "", "달력 수정", UUID.randomUUID())));
        var mixed = List.of(
                new BulkData.EmployeeRow(UUID.randomUUID().toString(), null, null, BulkData.Operation.CREATE, "CLOSE-NEW", "신규", TODAY, null, true),
                new BulkData.EmployeeRow(UUID.randomUUID().toString(), original, 0L, BulkData.Operation.UPDATE, "CLOSE-OLD", "소급", JOINED, month, true));
        assertStatus(409, () -> bulk.employees(actor, new BulkData.Form<>(UUID.randomUUID(), "일괄 변경", mixed)));
        assertEquals(0, database.queryForObject("SELECT count(*) FROM tna.employee WHERE tenant_id=? AND employee_no IN ('CLOSE-NEW','CLOSE-RETRO')", Integer.class, tenant));
        assertEquals(0, employees.get(actor, original).version());

        employees.update(actor, original, form("CLOSE-OLD", "이름 변경", JOINED, last, true, 0L));
        assertEquals(last, employees.get(actor, original).leftOn());
        employees.update(actor, original, form("CLOSE-OLD", "이름 변경", JOINED, TODAY.plusDays(2), true, 1L));
        assertNotNull(employees.create(actor, form("CLOSE-NEXT", "다음 달 입사", last.plusDays(1), null, true, null)));
        assertNotNull(attendanceSettings.calendar(actor, new CalendarForm(last.plusDays(1), last.plusDays(1), true, "기준", "", "새 달", UUID.randomUUID())));
        database.update("""
                INSERT INTO tna.attendance_month_closure
                    (tenant_id,id,month_start,version,status,reason,actor_account_id,actor_name)
                VALUES (?,?,?,2,'OPEN','합성 재개',?,'합성 담당자')
                """, tenant, UUID.randomUUID(), month, actor.accountId());
        assertNotNull(employees.create(actor, form("CLOSE-RETRO", "재개 후 소급 입사", last, null, true, null)));
    }

    @Test
    void registrationNormalizesTextAndCreatesNoAccount() {
        UUID id = employees.create(actor, form(" E100 ", " 합성 직원 😀 ", JOINED, null, true, null));
        var employee = employees.get(actor, id);
        assertEquals("E100", employee.employeeNo());
        assertEquals("합성 직원 😀", employee.displayName());
        assertEquals(0, employee.version());
        assertEquals(0, count("tna.tenant_account", "employee_id", id));
        assertEquals(1, count("tna.org_audit_event", "target_id", id));
    }

    @Test
    void employeeNumberCannotBeDuplicatedReusedOrChanged() {
        UUID id = create("E100");
        assertStatus(409, () -> create("E100"));
        employees.update(actor, id, form("E100", "비활성 직원", JOINED, null, false, 0L));
        assertStatus(409, () -> create("E100"));
        assertStatus(422, () -> employees.update(actor, id,
                form("E200", "새 이름", JOINED, null, false, 1L)));
        assertEquals("E100", employees.get(actor, id).employeeNo());
    }

    @Test
    void requiredFieldsUnicodeLengthsAndEmploymentDatesAreValidated() {
        assertStatus(422, () -> employees.create(actor, form("E".repeat(41), "직원", JOINED, null, true, null)));
        assertStatus(422, () -> employees.create(actor, form("E1", "😀".repeat(121), JOINED, null, true, null)));
        assertStatus(422, () -> employees.create(actor, form("E1", "직원", null, null, true, null)));
        assertStatus(422, () -> employees.create(actor, form("E1", "직원", JOINED, JOINED.minusDays(1), true, null)));
        UUID unicode = employees.create(actor, form("😀".repeat(40), "😀".repeat(120), TODAY, TODAY, true, null));
        assertEquals(TODAY, employees.get(actor, unicode).leftOn());
        UUID future = employees.create(actor, form("FUTURE", "입사 예정", TODAY.plusDays(10), null, true, null));
        assertTrue(employees.get(actor, future).active());
    }

    @Test
    void missingOrWhitespaceNumberUsesTheJoiningYearAndNeverReusesInactiveNumbers() {
        LocalDate joined = LocalDate.of(2026, 1, 1);
        UUID first = employees.create(actor, form(null, "자동 사원", joined, null, true, null));
        assertEquals("26001", employees.get(actor, first).employeeNo());
        employees.update(actor, first, form("26001", "비활성 사원", joined, null, false, 0L));
        UUID second = employees.create(actor, form(" \t ", "자동 사원", joined, null, true, null));
        assertEquals("26002", employees.get(actor, second).employeeNo());
        employees.create(actor, form("26010", "수동 사원", joined, null, false, null));
        employees.create(actor, form("260999", "이전 형식", joined, null, true, null));
        employees.create(actor, form("26ABC", "이전 형식", joined, null, true, null));
        UUID third = employees.create(actor, form("", "자동 사원", joined, null, true, null));
        assertEquals("26011", employees.get(actor, third).employeeNo());
        UUID anotherYear = employees.create(actor, form(null, "전년도", LocalDate.of(2025, 1, 1), null, true, null));
        assertEquals("25001", employees.get(actor, anotherYear).employeeNo());
        assertStatus(422, () -> employees.update(actor, second, form("", "번호 누락", joined, null, true, 0L)));
    }

    @Test
    void automaticNumberReplayDoesNotIssueAnotherNumberAndForeignNumbersAreIsolated() {
        UUID other = UUID.randomUUID();
        database.update("INSERT INTO platform.tenant_registry(id,company_code,display_name) VALUES (?,?,'합성 외부')", other, "NUMBER-" + other);
        TnaPrincipal foreign = account(other, "FOREIGN-ADMIN", true);
        LocalDate joined = LocalDate.of(2026, 1, 1);
        employees.create(foreign, form("26999", "외부 사원", joined, null, true, null));
        var request = form(null, "자동 사원", joined, null, true, null);
        UUID first = employees.create(actor, request);
        assertEquals(first, employees.create(actor, request));
        assertEquals("26001", employees.get(actor, first).employeeNo());
        assertEquals("26002", employees.get(actor, employees.create(actor, form(null, "다음 사원", joined, null, true, null))).employeeNo());
        assertEquals(1, count("tna.org_audit_event", "command_id", request.commandId()));
    }

    @Test
    void exhaustedYearAndReservedAdministratorNumberAreNeverGenerated() {
        LocalDate joined = LocalDate.of(2026, 1, 1);
        employees.create(actor, form("26999", "마지막 사번", joined, null, false, null));
        assertStatus(422, () -> employees.create(actor, form(null, "초과 사원", joined, null, true, null)));
        employees.create(actor, form("99999", "예약 담당자", joined, null, true, null));
        UUID year99 = employees.create(actor, form(null, "99년 사원", LocalDate.of(2099, 1, 1), null, true, null));
        assertEquals("99001", employees.get(actor, year99).employeeNo());
        employees.create(actor, form("99998", "마지막 일반 사번", LocalDate.of(2099, 1, 1), null, false, null));
        assertStatus(422, () -> employees.create(actor, form(null, "예약번호 충돌", LocalDate.of(2099, 1, 1), null, true, null)));
    }

    @Test
    void concurrentAutomaticCreatesAreUniqueAndSequential() throws Exception {
        var start = new CountDownLatch(1);
        try (var workers = Executors.newFixedThreadPool(4)) {
            var jobs = new java.util.ArrayList<java.util.concurrent.Future<UUID>>();
            for (int index = 0; index < 4; index++) jobs.add(workers.submit(() -> {
                start.await();
                return employees.create(actor, form(null, "동시 사원", LocalDate.of(2026, 1, 1), null, true, null));
            }));
            start.countDown();
            var numbers = new java.util.HashSet<String>();
            for (var job : jobs) numbers.add(employees.get(actor, job.get(20, TimeUnit.SECONDS)).employeeNo());
            assertEquals(Set.of("26001", "26002", "26003", "26004"), numbers);
        }
    }

    @Test
    void staleUpdatesAndCrossTenantTargetsAreRejected() {
        UUID id = create("E100");
        employees.update(actor, id, form("E100", "수정 이름", JOINED, null, true, 0L));
        assertStatus(409, () -> employees.update(actor, id, form("E100", "덮어쓰기", JOINED, null, true, 0L)));
        assertStatus(422, () -> employees.update(actor, id, form("E100", "버전 누락", JOINED, null, true, null)));
        assertStatus(404, () -> employees.get(actor, DatabaseFixture.EMPLOYEE_B));
        assertStatus(404, () -> employees.update(actor, DatabaseFixture.EMPLOYEE_B,
                form("E001", "범위 밖", JOINED, null, true, 0L)));
        assertStatus(404, () -> employees.assignments(actor, UUID.randomUUID()));
    }

    @Test
    void successfulCommandsReplayOnceAndChangedInputConflicts() {
        UUID command = UUID.randomUUID();
        var form = new EmployeeForm("E100", "합성 직원", JOINED, null, true, null, "등록", command);
        UUID id = employees.create(actor, form);
        assertEquals(id, employees.create(actor, form));
        assertEquals(1, count("tna.org_audit_event", "command_id", command));
        assertStatus(409, () -> employees.create(actor,
                new EmployeeForm("E101", "합성 직원", JOINED, null, true, null, "등록", command)));
        assertEquals(0, employees.get(actor, id).version());
    }

    @Test
    void listSearchIsTenantBoundAndUsesStablePages() {
        for (int index = 0; index < 53; index++) create("SEARCH-" + String.format("%03d", index));
        var first = employees.list(actor, "SEARCH-", 0);
        var second = employees.list(actor, "SEARCH-", 1);
        assertEquals(51, first.size());
        assertEquals(3, second.size());
        assertEquals(first.get(50).id(), second.getFirst().id());
        assertTrue(employees.list(actor, "unmatched", 0).isEmpty());
        assertEquals(0, employees.list(actor, "E001", 0).size());
        assertStatus(422, () -> employees.list(actor, "", -1));
    }

    @Test
    void assignmentsCloseOnlyThePreviousOpenIntervalAndReplayWithoutRepeating() {
        UUID id = create("E100");
        UUID firstOrg = organization(true);
        UUID secondOrg = organization(true);
        employees.assign(actor, id, assignment(firstOrg, TODAY, 0L));
        UUID command = UUID.randomUUID();
        var move = new AssignmentForm(secondOrg, TODAY.plusDays(1), 1L, "조직 이동", command);
        assertEquals(id, employees.assign(actor, id, move));
        assertEquals(id, employees.assign(actor, id, move));
        var history = employees.assignments(actor, id);
        assertEquals(2, history.size());
        var first = history.stream().filter(row -> row.organizationId().equals(firstOrg)).findFirst().orElseThrow();
        var second = history.stream().filter(row -> row.organizationId().equals(secondOrg)).findFirst().orElseThrow();
        assertEquals(TODAY, first.validFrom());
        assertEquals(TODAY.plusDays(1), first.validTo());
        assertEquals(TODAY.plusDays(1), second.validFrom());
        assertNull(second.validTo());
        assertEquals(2, employees.get(actor, id).version());
        assertEquals(1, count("tna.org_audit_event", "command_id", command));
    }

    @Test
    void sameDayRetroactiveAndStaleMovesLeaveHistoryIntact() {
        UUID id = create("E100");
        UUID organization = organization(true);
        employees.assign(actor, id, assignment(organization, TODAY.plusDays(2), 0L));
        assertStatus(422, () -> employees.assign(actor, id, assignment(organization, TODAY.plusDays(2), 1L)));
        assertStatus(422, () -> employees.assign(actor, id, assignment(organization, TODAY, 1L)));
        assertStatus(422, () -> employees.assign(actor, id, assignment(organization, TODAY.minusDays(1), 1L)));
        assertStatus(409, () -> employees.assign(actor, id, assignment(organization, TODAY.plusDays(3), 0L)));
        assertEquals(1, employees.assignments(actor, id).size());
        assertNull(employees.assignments(actor, id).getFirst().validTo());
    }

    @Test
    void movesRequireActiveSameTenantOrganizationAndEmploymentWindow() {
        UUID id = employees.create(actor, form("E100", "입사 예정", TODAY.plusDays(2), TODAY.plusDays(3), false, null));
        UUID organization = organization(true);
        assertStatus(422, () -> employees.assign(actor, id, assignment(organization, TODAY, 0L)));
        assertStatus(422, () -> employees.assign(actor, id, assignment(organization, TODAY.plusDays(4), 0L)));
        assertStatus(422, () -> employees.assign(actor, id, assignment(organization(false), TODAY.plusDays(2), 0L)));
        assertStatus(404, () -> employees.assign(actor, id, assignment(UUID.randomUUID(), TODAY.plusDays(2), 0L)));
        UUID foreignTenant = UUID.randomUUID();
        database.update("INSERT INTO platform.tenant_registry(id,company_code,display_name) VALUES (?,?,'합성 외부')",
                foreignTenant, "FOREIGN-" + foreignTenant);
        TnaPrincipal foreignAdmin = account(foreignTenant, "FOREIGN-ADMIN", true);
        UUID foreignOrg = UUID.randomUUID();
        database.update("""
                INSERT INTO tna.organization(tenant_id,id,code,name,created_by,updated_by)
                VALUES (?,?,'FOREIGN','합성 외부 조직',?,?)
                """, foreignTenant, foreignOrg, foreignAdmin.accountId(), foreignAdmin.accountId());
        assertStatus(404, () -> employees.assign(actor, id, assignment(foreignOrg, TODAY.plusDays(2), 0L)));
        employees.assign(actor, id, assignment(organization, TODAY.plusDays(3), 0L));
        assertEquals(TODAY.plusDays(3), employees.assignments(actor, id).getFirst().validFrom());
    }

    @Test
    void retirementRetainsOrganizationHistoryAndDateChangesRevokeSessions() {
        TnaPrincipal target = account("E100", false);
        UUID organization = organization(true);
        employees.assign(actor, target.employeeId(), assignment(organization, TODAY, 0L));
        assertEquals(1, authVersion(target));
        employees.update(actor, target.employeeId(), form("E100", "이름만 변경", JOINED, null, true, 1L));
        assertEquals(1, authVersion(target));
        employees.update(actor, target.employeeId(), form("E100", "비활성화", JOINED, TODAY, false, 2L));
        assertEquals(2, authVersion(target));
        employees.update(actor, target.employeeId(), form("E100", "활성 복구", JOINED, null, true, 3L));
        assertEquals(3, authVersion(target));
        employees.update(actor, target.employeeId(), form("E100", "입사 정정", JOINED.plusDays(1), null, true, 4L));
        assertEquals(4, authVersion(target));
        assertEquals(1, employees.assignments(actor, target.employeeId()).size());
        assertNull(employees.assignments(actor, target.employeeId()).getFirst().validTo());
    }

    @Test
    void lastAdminCannotBeDisabledRetiredOrMovedToFutureEmployment() {
        UUID id = actor.employeeId();
        assertStatus(422, () -> employees.update(actor, id, form("ADMIN-1", "담당자", JOINED, null, false, 0L)));
        assertStatus(422, () -> employees.update(actor, id, form("ADMIN-1", "담당자", JOINED, TODAY.plusDays(10), true, 0L)));
        assertStatus(422, () -> employees.update(actor, id, form("ADMIN-1", "담당자", TODAY.plusDays(1), null, true, 0L)));
        assertEquals(0, employees.get(actor, id).version());
        assertEquals(1, authVersion(actor));
    }

    @Test
    void onlyAnotherCurrentlyActiveUnscheduledAdminAllowsRemoval() {
        TnaPrincipal replacement = admin("ADMIN-2");
        database.update("UPDATE tna.employee SET left_on=? WHERE tenant_id=? AND id=?",
                TODAY.plusDays(10), tenant, replacement.employeeId());
        assertStatus(422, () -> employees.update(actor, actor.employeeId(),
                form("ADMIN-1", "담당자", JOINED, TODAY, true, 0L)));
        database.update("UPDATE tna.employee SET left_on=NULL WHERE tenant_id=? AND id=?", tenant, replacement.employeeId());
        database.update("UPDATE tna.tenant_account SET active=false WHERE tenant_id=? AND id=?", tenant, replacement.accountId());
        assertStatus(422, () -> employees.update(actor, actor.employeeId(),
                form("ADMIN-1", "담당자", JOINED, null, false, 0L)));
        database.update("UPDATE tna.tenant_account SET active=true WHERE tenant_id=? AND id=?", tenant, replacement.accountId());
        employees.update(actor, actor.employeeId(), form("ADMIN-1", "담당자", JOINED, null, false, 0L));
        assertEquals(2, authVersion(actor));
        assertFalse(employees.get(replacement, actor.employeeId()).active());
        assertStatus(403, () -> employees.list(actor, "", 0));
    }

    @Test
    void concurrentAdminRemovalsLeaveOneEffectiveAdmin() throws Exception {
        TnaPrincipal second = admin("ADMIN-2");
        var start = new CountDownLatch(1);
        try (var workers = Executors.newFixedThreadPool(2)) {
            var first = workers.submit(() -> removalAfter(start, actor, "ADMIN-1"));
            var other = workers.submit(() -> removalAfter(start, second, "ADMIN-2"));
            start.countDown();
            var results = List.of(first.get(15, TimeUnit.SECONDS), other.get(15, TimeUnit.SECONDS));
            assertEquals(1, results.stream().filter(result -> result == 0).count());
            assertEquals(1, results.stream().filter(result -> result == 422).count());
        }
        assertEquals(1, database.queryForObject("""
                SELECT count(*) FROM tna.employee e
                JOIN tna.tenant_account a ON a.tenant_id=e.tenant_id AND a.employee_id=e.id
                JOIN tna.role_grant r ON r.tenant_id=a.tenant_id AND r.account_id=a.id
                WHERE e.tenant_id=? AND e.active AND a.active AND r.role='ATTENDANCE_ADMIN'
                """, Integer.class, tenant));
    }

    @Test
    void currentRoleAndAuthVersionAreRecheckedForEveryReadAndWrite() {
        TnaPrincipal nonAdmin = account("E100", false);
        assertStatus(403, () -> employees.list(nonAdmin, "", 0));
        assertStatus(403, () -> employees.create(nonAdmin, form("E101", "직원", JOINED, null, true, null)));
        database.update("UPDATE tna.tenant_account SET auth_version=auth_version+1 WHERE tenant_id=? AND id=?",
                tenant, actor.accountId());
        assertStatus(403, () -> employees.list(actor, "", 0));
        assertStatus(403, () -> create("E102"));
    }

    private int removalAfter(CountDownLatch start, TnaPrincipal principal, String employeeNo) throws Exception {
        start.await();
        try {
            employees.update(principal, principal.employeeId(), form(employeeNo, "담당자", JOINED, null, false, 0L));
            return 0;
        } catch (OrgException error) {
            return error.status();
        }
    }

    private UUID create(String employeeNo) {
        return employees.create(actor, form(employeeNo, "합성 직원", JOINED, null, true, null));
    }

    private EmployeeForm form(String employeeNo, String name, LocalDate joined, LocalDate left,
            boolean active, Long version) {
        return new EmployeeForm(employeeNo, name, joined, left, active, version, "합성 테스트 변경", UUID.randomUUID());
    }

    private AssignmentForm assignment(UUID organization, LocalDate from, Long version) {
        return new AssignmentForm(organization, from, version, "합성 소속 이동", UUID.randomUUID());
    }

    private UUID organization(boolean active) {
        UUID id = UUID.randomUUID();
        database.update("""
                INSERT INTO tna.organization(tenant_id,id,code,name,active,created_by,updated_by)
                VALUES (?,?,?,'합성 조직',?,?,?)
                """, tenant, id, "ORG-" + id, active, actor.accountId(), actor.accountId());
        return id;
    }

    private TnaPrincipal admin(String number) {
        return account(number, true);
    }

    private TnaPrincipal account(String number, boolean admin) {
        return account(tenant, number, admin);
    }

    private TnaPrincipal account(UUID accountTenant, String number, boolean admin) {
        UUID employee = UUID.randomUUID();
        UUID account = UUID.randomUUID();
        database.update("""
                INSERT INTO tna.employee(tenant_id,id,employee_no,display_name,joined_on)
                VALUES (?,?,?,'합성 직원',?)
                """, accountTenant, employee, number, JOINED);
        database.update("""
                INSERT INTO tna.tenant_account(tenant_id,id,employee_id,email,password_hash)
                VALUES (?,?,?,?,'synthetic-unused-password-hash')
                """, accountTenant, account, employee, account + "@example.test");
        database.update("INSERT INTO tna.role_grant(tenant_id,account_id,role) VALUES (?,?,'EMPLOYEE')", accountTenant, account);
        if (admin) database.update("INSERT INTO tna.role_grant(tenant_id,account_id,role) VALUES (?,?,'ATTENDANCE_ADMIN')", accountTenant, account);
        return new TnaPrincipal(accountTenant, account, employee, 1,
                admin ? Set.of("EMPLOYEE", "ATTENDANCE_ADMIN") : Set.of("EMPLOYEE"), Instant.now());
    }

    private int count(String table, String column, UUID id) {
        return database.queryForObject("SELECT count(*) FROM " + table + " WHERE tenant_id=? AND " + column + "=?",
                Integer.class, tenant, id);
    }

    private long authVersion(TnaPrincipal principal) {
        return database.queryForObject("SELECT auth_version FROM tna.tenant_account WHERE tenant_id=? AND id=?",
                Long.class, tenant, principal.accountId());
    }

    private void assertStatus(int expected, Runnable action) {
        assertEquals(expected, assertThrows(OrgException.class, action::run).status());
    }
}
