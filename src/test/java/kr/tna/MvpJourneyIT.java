package kr.tna;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;
import java.util.zip.ZipInputStream;
import kr.tna.clock.AttendanceSettingsData.NetworkForm;
import kr.tna.clock.AttendanceSettingsService;
import kr.tna.clock.ClockData.ConfirmationForm;
import kr.tna.clock.ClockService;
import kr.tna.common.auth.CompanyAuthenticationDetails;
import kr.tna.common.auth.CompanyAuthenticationProvider;
import kr.tna.common.auth.FirstLoginService;
import kr.tna.common.auth.PasswordPolicy;
import kr.tna.common.auth.TnaPrincipal;
import kr.tna.org.AccountData.ApproverForm;
import kr.tna.org.AccountData.RolesForm;
import kr.tna.org.AccountService;
import kr.tna.org.ApproverService;
import kr.tna.org.EmployeeService;
import kr.tna.org.OrgData;
import kr.tna.org.OrgException;
import kr.tna.org.OrganizationService;
import kr.tna.request.LeaveWorkflowService;
import kr.tna.request.RequestData.BucketForm;
import kr.tna.request.RequestData.CorrectionForm;
import kr.tna.request.RequestData.DecisionForm;
import kr.tna.request.RequestData.LeaveForm;
import kr.tna.schedule.ScheduleData;
import kr.tna.schedule.ScheduleService;
import kr.tna.worktime.WorktimeData.AssessmentForm;
import kr.tna.worktime.WorktimeData.PolicyForm;
import kr.tna.worktime.WorktimeService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest
@ActiveProfiles("local")
@Import(MvpJourneyIT.JourneyTime.class)
class MvpJourneyIT {
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final LocalDate MONDAY = LocalDate.of(2026, 10, 26);
    private static final String PASSWORD = "Synthetic journey credential 43!";
    @Autowired OrganizationService organizations;
    @Autowired EmployeeService employees;
    @Autowired AccountService accounts;
    @Autowired FirstLoginService firstLogin;
    @Autowired CompanyAuthenticationProvider authentication;
    @Autowired ApproverService approvers;
    @Autowired ScheduleService schedules;
    @Autowired AttendanceSettingsService settings;
    @Autowired ClockService clocks;
    @Autowired LeaveWorkflowService workflow;
    @Autowired WorktimeService reports;
    @Autowired JourneyClock clock;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry properties) {
        var fixture = DatabaseFixture.start();
        properties.add("spring.datasource.url", fixture::jdbcUrl);
        properties.add("spring.datasource.username", () -> DatabaseFixture.APP_USER);
        properties.add("spring.datasource.password", () -> DatabaseFixture.APP_PASSWORD);
        properties.add("tna.auth.url", fixture::jdbcUrl);
        properties.add("tna.auth.username", () -> DatabaseFixture.AUTH_USER);
        properties.add("tna.auth.password", () -> DatabaseFixture.AUTH_PASSWORD);
    }

    @Test
    void organizationCodesAndStoredCreationTimesFollowSeoulNewYear() {
        clock.now.set(Instant.parse("2026-12-31T14:59:59.999900Z"));
        var manager = bootstrap(UUID.randomUUID());
        var request = new OrgData.OrganizationForm(null, "연말 조직", null, true, null, "합성 연말 등록", UUID.randomUUID());
        UUID december = organizations.create(manager, request);

        clock.at(LocalDate.of(2027, 1, 1), 0, 0);
        UUID january = organizations.create(manager, new OrgData.OrganizationForm(
                null, "연초 조직", null, true, null, "합성 연초 등록", UUID.randomUUID()));
        var another = bootstrap(UUID.randomUUID());
        UUID independent = organizations.create(another, new OrgData.OrganizationForm(
                null, "다른 고객사 조직", null, true, null, "고객사 순번 검증", UUID.randomUUID()));

        assertEquals(december, organizations.create(manager, request));
        assertEquals("20260001", organizations.get(manager, december).code());
        assertEquals("20270001", organizations.get(manager, january).code());
        assertEquals("20270001", organizations.get(another, independent).code());
        assertEquals(List.of("2026-12-31", "2027-01-01"), new JdbcTemplate(DatabaseFixture.start().adminDataSource())
                .queryForList("""
                        SELECT (created_at AT TIME ZONE 'Asia/Seoul')::date::text
                        FROM tna.organization WHERE tenant_id=? ORDER BY created_at
                        """, String.class, manager.tenantId()));
    }

    @Test
    void acceptedEmployeesReachIndependentApprovalAndFrozenReviewEvidence() throws Exception {
        clock.at(MONDAY, 8, 0);
        TnaPrincipal manager = bootstrap(UUID.randomUUID());
        UUID organization = organizations.create(manager, new OrgData.OrganizationForm(
                null, "합성 흐름 조직", null, true, null, "통합 흐름 준비", UUID.randomUUID()));
        UUID employeeId = createEmployee(manager, "J001", "합성 흐름 사원");
        UUID approverId = createEmployee(manager, "J002", "합성 독립 승인자");
        employees.assign(manager, employeeId, new OrgData.AssignmentForm(organization, MONDAY,
                version(manager, employeeId), "합성 소속 배정", UUID.randomUUID()));

        TnaPrincipal employee = activateAndLogin(manager, employeeId);
        TnaPrincipal approver = activateAndLogin(manager, approverId);
        approvers.set(manager, new ApproverForm(employeeId, approverId, MONDAY,
                version(manager, employeeId), "독립 승인자 지정", UUID.randomUUID()));

        List<ScheduleData.DayForm> week = IntStream.rangeClosed(1, 7).mapToObj(day -> day <= 5
                ? new ScheduleData.DayForm(day, true, "09:00", "18:00", "12:00-13:00")
                : new ScheduleData.DayForm(day, false, "", "", "")).toList();
        UUID template = schedules.create(manager, new ScheduleData.TemplateForm("JOURNEY", "합성 고정 근무",
                MONDAY, week, null, "합성 일정 등록", UUID.randomUUID()));
        UUID scheduleVersion = schedules.versions(manager, template).getFirst().id();
        schedules.assign(manager, employeeId, new ScheduleData.AssignmentForm(scheduleVersion, MONDAY,
                version(manager, employeeId), "합성 일정 배정", UUID.randomUUID()));
        settings.addNetwork(manager, new NetworkForm("127.0.0.1/32", "합성 로컬 허용망", false,
                "합성 출퇴근 검증", UUID.randomUUID()));
        UUID calendar = CalendarSeed.period(new JdbcTemplate(DatabaseFixture.start().adminDataSource()), manager, MONDAY, MONDAY.plusDays(6), "합성 일주일 검토", "");
        UUID policy = reports.reviewPolicy(manager, new PolicyForm(MONDAY, MONDAY.plusDays(6), true,
                "합성 월요일 주 기산점 검토", "합성 정책 검토", UUID.randomUUID()));
        UUID assessment = reports.assess(manager, new AssessmentForm(employeeId, MONDAY, MONDAY.plusDays(6),
                true, "합성 일반 규정 적용 검토", "합성 적용 검토", UUID.randomUUID()));

        clock.at(MONDAY, 9, 0);
        assertStatus(403, () -> clocks.record(employee, "IN", UUID.randomUUID(), "192.0.2.1"));
        UUID clockIn = clocks.record(employee, "IN", UUID.randomUUID(), "127.0.0.1");
        clock.at(MONDAY, 18, 0);
        UUID clockOut = clocks.record(employee, "OUT", UUID.randomUUID(), "127.0.0.1");
        var recorded = clocks.day(employee, MONDAY);
        assertEquals(2, recorded.events().size());
        assertEquals(scheduleVersion, recorded.scheduleVersionId());
        assertEquals(calendar, recorded.calendarVersionId());
        clocks.confirm(employee, new ConfirmationForm(MONDAY, recorded.inputRevision(), recorded.reviewRevision(),
                true, true, recorded.sourceFingerprint(), UUID.randomUUID()));

        UUID bucket = workflow.openBucket(manager, new BucketForm(employeeId, MONDAY, MONDAY,
                MONDAY.plusDays(30), 5, "합성 기초 잔여 근거", "합성 기초 등록", UUID.randomUUID()));
        LocalDate leaveDate = MONDAY.plusDays(1);
        UUID leave = workflow.submitLeave(employee, new LeaveForm(bucket, leaveDate, leaveDate, null,
                "합성 종일 연차", UUID.randomUUID()));
        var pending = workflow.buckets(employee, employeeId).getFirst();
        assertEquals(5, pending.balanceDays(), "대기 신청은 잔여를 차감하지 않습니다.");
        assertEquals(1, pending.pendingDays());
        workflow.decide(approver, leave, new DecisionForm("APPROVE", 0L, "합성 연차 승인", UUID.randomUUID()));
        var approved = workflow.buckets(employee, employeeId).getFirst();
        assertEquals(4, approved.balanceDays());
        assertEquals(0, approved.pendingDays());
        var uses = workflow.ledger(employee, bucket).stream().filter(row -> row.entryKind().equals("USE")).toList();
        assertEquals(1, uses.size());
        assertEquals(-1, uses.getFirst().signedQuantityDays());
        assertEquals(leaveDate, uses.getFirst().leaveDate());

        clock.at(leaveDate, 18, 0);
        reports.recalculate(manager, employeeId, MONDAY, leaveDate, UUID.randomUUID(), "정정 전 합성 검토");
        var original = reports.days(employee, employeeId, MONDAY, leaveDate).getFirst();
        assertEquals(Long.valueOf(28_800_000L), original.result().actualWorkMillis());
        UUID snapshot = reports.prepareExport(manager, employeeId, MONDAY, leaveDate, UUID.randomUUID(), "정정 전 합성 출력");
        byte[] frozen = reports.download(manager, snapshot);
        assertTrue(sheet(frozen).contains(original.id().toString()));

        var beforeCorrection = clocks.day(employee, MONDAY);
        UUID correction = workflow.submitCorrection(employee, new CorrectionForm(employeeId, MONDAY,
                beforeCorrection.inputRevision(), beforeCorrection.sourceFingerprint(), "KEEP", "", "SET",
                MONDAY + "T17:30:00+09:00", true, true,
                MONDAY + "T12:00:00+09:00/" + MONDAY + "T13:00:00+09:00",
                null, null, false, "합성 실제 퇴근 정정", UUID.randomUUID()));
        assertFalse(reports.detail(employee, original.id()).day().current(), "정정 대기는 이전 결과를 오래됨으로 표시합니다.");
        assertTrue(workflow.inbox(approver).stream().anyMatch(request -> request.id().equals(correction)));
        assertStatus(403, () -> workflow.decide(employee, correction,
                new DecisionForm("APPROVE", 0L, "본인 승인 시도", UUID.randomUUID())));
        UUID approvalCommand = UUID.randomUUID();
        workflow.decide(approver, correction, new DecisionForm("APPROVE", 0L, "합성 독립 정정 승인", approvalCommand));
        assertEquals("APPROVED", workflow.get(employee, correction).request().status());
        var correctedClock = clocks.day(employee, MONDAY);
        assertEquals(recorded.events(), correctedClock.events(), "승인 정정은 원천 출퇴근을 보존합니다.");
        assertTrue(correctedClock.events().stream().anyMatch(event -> event.id().equals(clockIn)));
        assertTrue(correctedClock.events().stream().anyMatch(event -> event.id().equals(clockOut)));
        assertEquals(at(MONDAY, 17, 30), correctedClock.effectiveEvents().stream()
                .filter(event -> event.kind().equals("OUT")).findFirst().orElseThrow().occurredAt());

        UUID run = reports.recalculate(manager, employeeId, MONDAY, leaveDate, UUID.randomUUID(), "독립 승인 후 합성 재집계");
        var days = reports.days(employee, employeeId, MONDAY, leaveDate);
        var corrected = days.getFirst();
        assertTrue(days.stream().allMatch(day -> day.current()));
        assertEquals(Long.valueOf(27_000_000L), corrected.result().actualWorkMillis());
        assertEquals(1, days.getLast().result().leaveDays());
        assertEquals(Long.valueOf(0L), days.getLast().result().actualWorkMillis());
        var detail = reports.detail(employee, corrected.id());
        assertTrue(detail.completeEvidence());
        assertTrue(detail.latestVersion());
        assertEquals(run, detail.run().id());
        assertEquals(scheduleVersion, detail.clock().scheduleVersionId());
        assertEquals(policy, detail.policy().id());
        assertEquals(assessment, detail.assessment().id());
        assertNotNull(detail.clock().confirmation());
        var oldDetail = reports.detail(employee, original.id());
        assertFalse(oldDetail.latestVersion());
        assertFalse(oldDetail.day().current());
        assertEquals(Long.valueOf(28_800_000L), oldDetail.day().result().actualWorkMillis());
        var month = reports.month(employee, employeeId, YearMonth.from(MONDAY));
        assertEquals(27_000_000L, month.summary().result().confirmedWorkMillis());
        assertEquals(27_000_000L, month.weeks().stream().filter(period -> period.from().equals(MONDAY))
                .findFirst().orElseThrow().result().confirmedWorkMillis());
        assertTrue(month.summary().result().unconfirmedDays() > 0, "부분합을 월 전체 확정으로 표시하지 않습니다.");

        assertArrayEquals(frozen, reports.download(manager, snapshot), "후속 정정·재집계가 기존 XLSX를 바꾸지 않습니다.");
        UUID updatedSnapshot = reports.prepareExport(manager, employeeId, MONDAY, leaveDate, UUID.randomUUID(), "정정 후 합성 출력");
        String updatedSheet = sheet(reports.download(manager, updatedSnapshot));
        assertTrue(updatedSheet.contains(corrected.id().toString()));
        assertTrue(updatedSheet.contains("07:30:00.000"));
        assertTrue(updatedSheet.contains("합성 흐름 조직"));

        var audits = reports.audits(manager, null, null).rows();
        Set<String> actions = Set.copyOf(audits.stream().map(row -> row.action()).toList());
        assertTrue(actions.containsAll(Set.of("ORGANIZATION_CREATE", "EMPLOYEE_CREATE", "ACCOUNT_ACTIVATED",
                "ACCOUNT_ROLES_CHANGE", "APPROVER_ASSIGN", "SCHEDULE_ASSIGN", "ATTENDANCE_RECORD", "WORK_CONFIRM",
                "REQUEST_LEAVE_SUBMIT", "REQUEST_CORRECTION_SUBMIT", "REQUEST_APPROVE", "WORKTIME_RECALCULATE",
                "REVIEW_EXPORT_PREPARE", "REVIEW_EXPORT_DOWNLOAD")));
        UUID approvalAudit = audits.stream().filter(row -> row.action().equals("REQUEST_APPROVE")
                && row.targetId().equals(correction)).findFirst().orElseThrow().id();
        var auditDetail = reports.auditDetail(manager, approvalAudit);
        assertEquals(approver.accountId(), auditDetail.actorAccountId());
        assertEquals(approvalCommand, auditDetail.commandId());
        assertEquals("PENDING", auditDetail.beforeSummary().get("status"));
        assertEquals("APPROVED", auditDetail.afterSummary().get("status"));
        assertNotNull(auditDetail.requestTraceId());

        TnaPrincipal foreign = bootstrap(UUID.randomUUID());
        assertStatus(404, () -> reports.detail(foreign, corrected.id()));
        assertStatus(404, () -> organizations.get(foreign, organization));

        clocks.record(employee, "IN", UUID.randomUUID(), "127.0.0.1");
        clock.at(leaveDate.plusDays(1), 8, 0);
        assertFalse(reports.correctionSuggested(employee, leaveDate),
                "승인된 지난 종일 연차일은 출퇴근 원천이 하나 있어도 정정을 안내하지 않습니다.");
    }

    private UUID createEmployee(TnaPrincipal manager, String number, String name) {
        return employees.create(manager, new OrgData.EmployeeForm(number, name, MONDAY.minusYears(1), null,
                true, null, "합성 사원 등록", UUID.randomUUID()));
    }

    private long version(TnaPrincipal manager, UUID employeeId) {
        return employees.get(manager, employeeId).version();
    }

    private TnaPrincipal activateAndLogin(TnaPrincipal manager, UUID employeeId) {
        var db = new JdbcTemplate(DatabaseFixture.start().adminDataSource());
        String company = company(manager.tenantId());
        String number = db.queryForObject("SELECT employee_no FROM tna.employee WHERE tenant_id=? AND id=?",
                String.class, manager.tenantId(), employeeId);
        db.update("INSERT INTO tna.employee_personnel(tenant_id,employee_id,birth_date,version,updated_by) VALUES (?,?,DATE '1990-01-02',1,?)",
                manager.tenantId(), employeeId, manager.accountId());
        firstLogin.activate(company, number, LocalDate.of(1990, 1, 2), PASSWORD, "127.0.0.1");
        accounts.roles(manager, employeeId, new RolesForm(Set.of("EMPLOYEE", "APPROVER"),
                version(manager, employeeId), "합성 직원·승인자 겸임", UUID.randomUUID()));
        var request = new MockHttpServletRequest();
        request.setRemoteAddr("127.0.0.1");
        request.setParameter("companyCode", company);
        var login = UsernamePasswordAuthenticationToken.unauthenticated(number, PASSWORD);
        login.setDetails(new CompanyAuthenticationDetails(request));
        return (TnaPrincipal) authentication.authenticate(login).getPrincipal();
    }

    private TnaPrincipal bootstrap(UUID tenant) {
        // 최초 고객사·담당자 경계만 fixture로 준비하고 업무 자료는 서비스로 생성합니다.
        var db = new JdbcTemplate(DatabaseFixture.start().adminDataSource());
        UUID employee = UUID.randomUUID(), account = UUID.randomUUID();
        db.update("INSERT INTO platform.tenant_registry(id,company_code,display_name) VALUES (?,?,'합성 통합 고객사')",
                tenant, company(tenant));
        db.update("INSERT INTO tna.employee(tenant_id,id,employee_no,display_name,joined_on) VALUES (?,?,'ADMIN','합성 최초 담당자',?)",
                tenant, employee, MONDAY.minusYears(1));
        db.update("INSERT INTO tna.tenant_account(tenant_id,id,employee_id,email,password_hash) VALUES (?,?,?,'admin@example.test',?)",
                tenant, account, employee, PasswordPolicy.passwordEncoder().encode(PASSWORD));
        for (String role : Set.of("EMPLOYEE", "ATTENDANCE_ADMIN")) {
            db.update("INSERT INTO tna.role_grant(tenant_id,account_id,role) VALUES (?,?,?)", tenant, account, role);
        }
        return new TnaPrincipal(tenant, account, employee, 1, Set.of("EMPLOYEE", "ATTENDANCE_ADMIN"), clock.instant());
    }

    private static String company(UUID tenant) {
        return ("JOURNEY-" + tenant).toUpperCase(Locale.ROOT);
    }

    private static Instant at(LocalDate date, int hour, int minute) {
        return date.atTime(hour, minute).atZone(SEOUL).toInstant();
    }

    private static String sheet(byte[] workbook) throws Exception {
        try (var zip = new ZipInputStream(new ByteArrayInputStream(workbook), StandardCharsets.UTF_8)) {
            for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                if (entry.getName().equals("xl/worksheets/sheet1.xml")) {
                    return new String(zip.readAllBytes(), StandardCharsets.UTF_8);
                }
            }
        }
        throw new AssertionError("검토용 XLSX 워크시트가 없습니다.");
    }

    private static void assertStatus(int expected, Runnable action) {
        assertEquals(expected, assertThrows(OrgException.class, action::run).status());
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class JourneyTime {
        @Bean @Primary JourneyClock journeyClock() { return new JourneyClock(); }
    }

    static final class JourneyClock extends Clock {
        private final AtomicReference<Instant> now = new AtomicReference<>(MvpJourneyIT.at(MONDAY, 8, 0));
        void at(LocalDate date, int hour, int minute) { now.set(MvpJourneyIT.at(date, hour, minute)); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return Clock.fixed(instant(), zone); }
        @Override public Instant instant() { return now.get(); }
    }
}
