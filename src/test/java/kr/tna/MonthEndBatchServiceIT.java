package kr.tna;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import kr.tna.common.auth.TnaPrincipal;
import kr.tna.org.OrgException;
import kr.tna.worktime.CheckoutFollowUpService.DayKey;
import kr.tna.worktime.MonthEndBatchService;
import kr.tna.worktime.MonthEndBatchService.Command;
import kr.tna.worktime.MonthEndBatchService.Selection;
import kr.tna.worktime.WorktimeMapper;
import kr.tna.worktime.WorktimeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class MonthEndBatchServiceIT {
    private static final LocalDate DATE = LocalDate.of(2024, 3, 1);
    private static final String PATH = "/admin/calculations/month-end";
    private static final JsonMapper JSON = JsonMapper.builder().build();
    @Autowired MockMvc mvc;
    @Autowired MonthEndBatchService batches;
    @Autowired WorktimeService worktime;
    @MockitoSpyBean WorktimeMapper mapper;
    private JdbcTemplate db;
    private TnaPrincipal manager;
    private UUID tenant, first, second;
    private Selection selection;

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

    @BeforeEach
    void syntheticHistoricalEmployeeDays() {
        db = new JdbcTemplate(DatabaseFixture.start().adminDataSource());
        tenant = UUID.randomUUID();
        db.update("INSERT INTO platform.tenant_registry(id,company_code,display_name) VALUES (?,?,'합성 월말 회사')",
                tenant, "MONTH-" + tenant);
        manager = account("ADMIN", Set.of("EMPLOYEE", "ATTENDANCE_ADMIN"));
        first = employee("BATCH-A", DATE.plusDays(1));
        second = employee("BATCH-B", DATE.plusDays(1));
        selection = new Selection("2024-03", null, "BATCH", "ALL", 0, List.of(
                new DayKey(second, DATE), new DayKey(first, DATE), new DayKey(first, DATE.plusDays(1))));
    }

    @Test
    void concurrentRetriesShareTheOriginalImmutableReceiptAcrossLaterRunsButStillRequireCurrentAuthority() throws Exception {
        var preview = batches.preview(manager, selection);
        assertEquals(2, preview.weekCount());
        assertEquals(0, count("calculation_run"));
        assertEquals(0, count("org_audit_event"));
        var command = command(preview.previewToken(), UUID.randomUUID(), "합성 월말 확인");
        var pair = together(() -> batches.execute(manager, command), () -> batches.execute(manager, command));
        var firstReceipt = pair.getFirst();
        assertEquals(firstReceipt, pair.getLast());
        assertEquals(3, firstReceipt.rows().size());
        assertEquals(2, count("calculation_run"));
        assertEquals(14, count("day_result"));
        assertEquals(3, count("org_audit_event"));
        for (var row : firstReceipt.rows()) {
            assertEquals(List.of("UNCONFIRMED"), row.reasons());
            assertNull(row.actualWorkMillis());
            assertEquals(row.resultId(), db.queryForObject("""
                    SELECT id FROM tna.day_result WHERE tenant_id=? AND employee_id=? AND work_date=?
                    ORDER BY result_revision DESC LIMIT 1
                    """, UUID.class, tenant, row.employeeId(), row.workDate()));
        }
        worktime.recalculate(manager, first, DATE, DATE, UUID.randomUUID(), "후속 재집계");
        assertEquals(firstReceipt, batches.execute(manager, command));
        assertEquals(3, count("calculation_run"));
        var changed = command(preview.previewToken(), command.commandId(), "다른 사유");
        assertEquals(409, assertThrows(OrgException.class, () -> batches.execute(manager, changed)).status());
        db.update("UPDATE tna.role_grant SET active=false WHERE tenant_id=? AND account_id=? AND role='ATTENDANCE_ADMIN'",
                tenant, manager.accountId());
        assertEquals(403, assertThrows(OrgException.class, () -> batches.execute(manager, command)).status());
        assertEquals(403, assertThrows(OrgException.class, () -> batches.preview(manager, selection)).status());
    }

    @Test
    void concurrentDistinctCommandsCannotReuseTheSamePreviewAfterItsResultChanges() throws Exception {
        String token = batches.preview(manager, selection).previewToken();
        var firstCommand = command(token, UUID.randomUUID(), "동시 요청");
        var secondCommand = command(token, UUID.randomUUID(), "동시 요청");
        var statuses = together(() -> executeStatus(firstCommand), () -> executeStatus(secondCommand));
        assertEquals(List.of(200, 409), statuses.stream().sorted().toList());
        assertEquals(2, count("calculation_run"));
        assertEquals(14, count("day_result"));
        assertEquals(3, count("org_audit_event"));
    }

    @Test
    void replayRequiresTheEmployeeGrantEvenWhileTheAttendanceAdministratorGrantIsStillActive() {
        var preview = batches.preview(manager, selection);
        var command = command(preview.previewToken(), UUID.randomUUID(), "기본 권한 확인");
        assertEquals(3, batches.execute(manager, command).rows().size());
        db.update("UPDATE tna.role_grant SET active=false WHERE tenant_id=? AND account_id=? AND role='EMPLOYEE'",
                tenant, manager.accountId());
        assertEquals(403, assertThrows(OrgException.class, () -> batches.preview(manager, selection)).status());
        assertEquals(403, assertThrows(OrgException.class, () -> batches.execute(manager, command)).status());
        assertEquals(2, count("calculation_run"));
    }

    @Test
    void failureOnTheSecondEmployeeRollsBackTheFirstEmployeesRunResultsAndAudits() {
        String token = batches.preview(manager, selection).previewToken();
        doReturn(0).when(mapper).insertRun(eq(tenant), any(), eq(second), any(), any(), any(), any(), any());
        var command = command(token, UUID.randomUUID(), "실패 시 전부 취소");
        assertEquals(409, assertThrows(OrgException.class, () -> batches.execute(manager, command)).status());
        verify(mapper, times(7)).nextResult(eq(tenant), eq(first), any());
        assertEquals(0, count("calculation_run"));
        assertEquals(0, count("day_result"));
        assertEquals(0, count("org_audit_event"));
    }

    @Test
    void acceptsAllFiftyRowsOnOnePageAndRecalculatesEightEmployeeWeeks() {
        extendEmploymentThroughMarch();
        var keys = new ArrayList<DayKey>();
        for (int day = 7; day <= 31; day++) {
            keys.add(new DayKey(first, DATE.withDayOfMonth(day)));
            keys.add(new DayKey(second, DATE.withDayOfMonth(day)));
        }
        selection = new Selection("2024-03", null, "BATCH", "ALL", 0, keys);
        var preview = batches.preview(manager, selection);
        assertEquals(50, preview.rows().size());
        assertEquals(8, preview.weekCount());
        var result = batches.execute(manager, command(preview.previewToken(), UUID.randomUUID(), "현재 페이지 전체"));
        assertEquals(50, result.rows().size());
        assertEquals(8, count("calculation_run"));
        assertEquals(56, count("day_result"));
    }

    @Test
    void skipsIntermediateWeeksForTwoNonconsecutiveSelectionsOfOneEmployee() {
        extendEmploymentThroughMarch();
        selection = new Selection("2024-03", null, "BATCH-A", "ALL", 0,
                List.of(new DayKey(first, DATE), new DayKey(first, DATE.plusWeeks(3))));
        var preview = batches.preview(manager, selection);
        assertEquals(2, preview.weekCount());
        var result = batches.execute(manager, command(preview.previewToken(), UUID.randomUUID(), "두 주만 재집계"));
        assertEquals(2, result.rows().size());
        assertEquals(2, count("calculation_run"));
        assertEquals(14, count("day_result"));
        assertEquals(0, db.queryForObject("""
                SELECT count(*) FROM tna.day_result WHERE tenant_id=? AND work_date BETWEEN DATE '2024-03-04' AND DATE '2024-03-17'
                """, Integer.class, tenant));
    }

    @Test
    void bothPostsRequireCsrfAndCurrentAdministratorAndExcludeOtherTenantsAndPages() throws Exception {
        var employee = account("EMP", Set.of("EMPLOYEE"));
        var representative = account("REP", Set.of("EMPLOYEE", "COMPANY_REPRESENTATIVE"));
        var token = batches.preview(manager, selection).previewToken();
        for (String path : List.of(PATH, PATH + "/preview")) {
            Object body = path.equals(PATH) ? command(token, UUID.randomUUID(), "담당자 재집계") : selection;
            mvc.perform(request(path, manager, body)).andExpect(status().isForbidden());
            mvc.perform(request(path, employee, body).with(csrf())).andExpect(status().isForbidden());
            mvc.perform(request(path, representative, body).with(csrf())).andExpect(status().isForbidden());
        }
        UUID foreignTenant = UUID.randomUUID(), foreignEmployee = UUID.randomUUID();
        db.update("INSERT INTO platform.tenant_registry(id,company_code,display_name) VALUES (?,?,'다른 합성 월말 회사')",
                foreignTenant, "FOREIGN-" + foreignTenant);
        db.update("INSERT INTO tna.employee(tenant_id,id,employee_no,display_name,joined_on) VALUES (?,?,'BATCH-X','다른 합성 사원',?)",
                foreignTenant, foreignEmployee, DATE);
        var invalid = List.of(new Selection("2024-03", null, "BATCH", "ALL", 1, selection.rows()),
                new Selection("2024-03", null, "BATCH", "ALL", 0, List.of(new DayKey(first, DATE), new DayKey(foreignEmployee, DATE))),
                new Selection("2024-03", null, "BATCH-B", "ALL", 0, selection.rows()),
                new Selection("2024-02", null, "BATCH", "ALL", 0, selection.rows()));
        for (Selection candidate : invalid) {
            mvc.perform(request(PATH + "/preview", manager, candidate).with(csrf())).andExpect(status().isConflict());
            var command = new Command(candidate.month(), candidate.organizationId(), candidate.q(), candidate.reason(), candidate.page(),
                    candidate.rows(), token, "대상 재검증", UUID.randomUUID());
            mvc.perform(request(PATH, manager, command).with(csrf())).andExpect(status().isConflict());
        }
        assertEquals(0, count("day_result"));
        assertEquals(0, count("org_audit_event"));
    }

    private MockHttpServletRequestBuilder request(String path, TnaPrincipal actor, Object body) {
        var auth = UsernamePasswordAuthenticationToken.authenticated(actor, null,
                actor.roles().stream().map(role -> new SimpleGrantedAuthority("ROLE_" + role)).toList());
        return post(path).with(authentication(auth)).contentType("application/json").accept("application/json")
                .content(JSON.writeValueAsString(body));
    }

    private void extendEmploymentThroughMarch() {
        db.update("UPDATE tna.employee SET left_on=DATE '2024-03-31' WHERE tenant_id=? AND id IN (?,?)", tenant, first, second);
    }

    private TnaPrincipal account(String number, Set<String> roles) {
        UUID account = UUID.randomUUID(), employee = employee(number, null);
        db.update("INSERT INTO tna.tenant_account(tenant_id,id,employee_id,email,password_hash) VALUES (?,?,?,?,'synthetic-unused')",
                tenant, account, employee, account + "@example.test");
        for (String role : roles) db.update("INSERT INTO tna.role_grant(tenant_id,account_id,role) VALUES (?,?,?)", tenant, account, role);
        return new TnaPrincipal(tenant, account, employee, 1, roles, Instant.now());
    }

    private UUID employee(String number, LocalDate leftOn) {
        UUID id = UUID.randomUUID();
        db.update("INSERT INTO tna.employee(tenant_id,id,employee_no,display_name,joined_on,left_on) VALUES (?,?,?,?,?,?)",
                tenant, id, number, "합성 " + number, DATE, leftOn);
        return id;
    }

    private Command command(String token, UUID id, String reason) {
        return new Command(selection.month(), null, selection.q(), selection.reason(), selection.page(), selection.rows(), token, reason, id);
    }

    private int executeStatus(Command command) {
        try {
            batches.execute(manager, command);
            return 200;
        } catch (OrgException error) {
            return error.status();
        }
    }

    private int count(String table) {
        return db.queryForObject("SELECT count(*) FROM tna." + table + " WHERE tenant_id=?", Integer.class, tenant);
    }

    private static <T> List<T> together(Callable<T> first, Callable<T> second) throws Exception {
        var ready = new CyclicBarrier(2);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var a = executor.submit(() -> { ready.await(10, TimeUnit.SECONDS); return first.call(); });
            var b = executor.submit(() -> { ready.await(10, TimeUnit.SECONDS); return second.call(); });
            return List.of(a.get(45, TimeUnit.SECONDS), b.get(45, TimeUnit.SECONDS));
        }
    }
}
