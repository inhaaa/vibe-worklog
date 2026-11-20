package kr.tna;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Base64;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import kr.tna.common.auth.TnaPrincipal;
import kr.tna.common.tenant.TenantTransactions;
import kr.tna.org.EmployeePersonnelData.Form;
import kr.tna.org.EmployeePersonnelMapper;
import kr.tna.org.EmployeePersonnelService;
import kr.tna.org.OrgException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.dao.DataAccessException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class EmployeePersonnelIT {
    private static final String PAGE_JSON = "application/vnd.worklog.page+json";
    @Autowired EmployeePersonnelService personnel;
    @Autowired EmployeePersonnelMapper mapper;
    @Autowired kr.tna.org.EmployeePersonalDetailsService details;
    @Autowired TenantTransactions transactions;
    @Autowired MockMvc mvc;
    private JdbcTemplate db;
    private UUID tenant;
    private TnaPrincipal admin, employee;
    private final LocalDate today = LocalDate.now(ZoneId.of("Asia/Seoul"));

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
    void company() {
        db = new JdbcTemplate(DatabaseFixture.start().adminDataSource());
        tenant = UUID.randomUUID();
        db.update("INSERT INTO platform.tenant_registry(id,company_code,display_name) VALUES (?,?,'합성 인사 회사')", tenant, "PERSONNEL-" + tenant);
        admin = member("ADMIN", true);
        employee = member("E001", false);
    }

    @Test
    void searchesWithinCompanyAndOpensTheRequestedPersonnelTab() throws Exception {
        var sameName = member("E002", false);
        db.update("UPDATE tna.employee SET display_name='합성 동명이인' WHERE tenant_id=? AND id IN (?,?)",
                tenant, employee.employeeId(), sameName.employeeId());
        db.update("UPDATE tna.employee SET active=false,left_on=? WHERE tenant_id=? AND id=?",
                today.minusDays(1), tenant, sameName.employeeId());
        mvc.perform(get("/admin/employees/personnel/search").with(as(admin)).param("q", "동명이인")
                        .param("tenantId", DatabaseFixture.TENANT_B.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows.length()").value(2))
                .andExpect(jsonPath("$.rows[0].employeeNo").value("E001"))
                .andExpect(jsonPath("$.rows[1].status").value("LEFT"))
                .andExpect(jsonPath("$.rows[0].birthDate").doesNotExist())
                .andExpect(jsonPath("$.hasNext").value(false));
        mvc.perform(get("/admin/employees/personnel/search").with(as(employee)).param("q", "E001"))
                .andExpect(status().isForbidden());
        mvc.perform(get("/admin/employees/{id}/personnel", employee.employeeId()).with(as(admin))
                        .param("tab", "family").accept(PAGE_JSON))
                .andExpect(status().isOk())
                .andExpect(ReactPageAssertions.data("/adminPersonnel/tab", "family"))
                .andExpect(ReactPageAssertions.data("/employeeHeader/employeeId", employee.employeeId().toString()));
        mvc.perform(get("/admin/employees/{id}/personnel", employee.employeeId()).with(as(admin))
                        .param("tab", "other").accept(PAGE_JSON))
                .andExpect(status().isBadRequest());
    }

    @Test
    void nullableRegistrationPreservesEmployeeAndAccountAndMasksPrivateAudit() {
        var initial = personnel.get(admin, employee.employeeId());
        assertEquals(0, initial.version());
        assertNull(initial.form().birthDate());
        assertEquals("UNKNOWN", personnel.selfHeader(employee).employmentStatus());
        var form = form(today.minusYears(30), " 선임 ", "ON_LEAVE", today, 0L, "KEEP", null);
        personnel.update(admin, employee.employeeId(), form);
        var header = personnel.selfHeader(employee);
        assertEquals(today.minusYears(30), header.birthDate());
        assertEquals("선임", header.gradeName());
        assertEquals("ON_LEAVE", header.employmentStatus());
        assertEquals(1, personnel.get(admin, employee.employeeId()).version());
        assertEquals(0, db.queryForObject("SELECT version FROM tna.employee WHERE tenant_id=? AND id=?", Long.class, tenant, employee.employeeId()));
        assertEquals(1, db.queryForObject("SELECT auth_version FROM tna.tenant_account WHERE tenant_id=? AND id=?", Long.class, tenant, employee.accountId()));
        String audit = db.queryForObject("SELECT before_state::text || after_state::text FROM tna.org_audit_event WHERE tenant_id=? AND command_id=?", String.class, tenant, form.commandId());
        assertFalse(audit.contains(form.birthDate().toString()));
        assertFalse(audit.contains("\"birthDate\""));
        assertFalse(audit.contains("photoData"));
    }

    @Test
    void futureStatusSameDateCorrectionsAndEmploymentBoundariesAreExplicit() {
        personnel.update(admin, employee.employeeId(), form(null, null, "WORKING", today, 0L, "KEEP", null));
        personnel.update(admin, employee.employeeId(), form(null, null, "ON_LEAVE", today.plusDays(1), 1L, "KEEP", null));
        assertEquals("WORKING", personnel.selfHeader(employee).employmentStatus());
        personnel.update(admin, employee.employeeId(), form(null, null, "SUSPENDED", today, 2L, "KEEP", null));
        assertEquals("SUSPENDED", personnel.selfHeader(employee).employmentStatus());
        assertEquals(3, personnel.get(admin, employee.employeeId()).statusHistory().size());
        db.update("UPDATE tna.employee SET joined_on=? WHERE tenant_id=? AND id=?", today.plusDays(2), tenant, employee.employeeId());
        assertEquals("PRE_JOINING", personnel.header(admin, employee.employeeId()).employmentStatus());
        db.update("UPDATE tna.employee SET joined_on=?,left_on=? WHERE tenant_id=? AND id=?", today.minusYears(1), today.minusDays(1), tenant, employee.employeeId());
        assertEquals("LEFT", personnel.header(admin, employee.employeeId()).employmentStatus());
        db.update("UPDATE tna.employee SET left_on=? WHERE tenant_id=? AND id=?", today, tenant, employee.employeeId());
        assertEquals("SUSPENDED", personnel.header(admin, employee.employeeId()).employmentStatus());
        db.update("UPDATE tna.employee SET left_on=NULL,active=false WHERE tenant_id=? AND id=?", tenant, employee.employeeId());
        assertEquals("SUSPENDED", personnel.header(admin, employee.employeeId()).employmentStatus());
    }

    @Test
    void validatesDatesNamesVersionsReplayAndCurrentAuthorization() {
        assertStatus(422, () -> personnel.update(admin, employee.employeeId(), form(today.plusDays(1), null, "WORKING", today, 0L, "KEEP", null)));
        assertStatus(422, () -> personnel.update(admin, employee.employeeId(), form(null, "😀".repeat(121), "WORKING", today, 0L, "KEEP", null)));
        assertStatus(422, () -> personnel.update(admin, employee.employeeId(), form(null, null, "WORKING", today.minusDays(1), 0L, "KEEP", null)));
        assertStatus(422, () -> personnel.update(admin, employee.employeeId(), form(null, null, "LEFT", today, 0L, "KEEP", null)));
        var valid = form(null, null, "WORKING", today, 0L, "KEEP", null);
        personnel.update(admin, employee.employeeId(), valid);
        personnel.update(admin, employee.employeeId(), valid);
        assertEquals(1, personnel.get(admin, employee.employeeId()).version());
        assertStatus(409, () -> personnel.update(admin, employee.employeeId(), new Form(null, "다른 직급", null, null,
                "WORKING", today, 0L, valid.reason(), valid.commandId(), "KEEP", null)));
        assertStatus(422, () -> personnel.update(admin, employee.employeeId(), form(null, null, "WORKING", today, null, "KEEP", null)));
        assertStatus(409, () -> personnel.update(admin, employee.employeeId(), form(null, null, "ON_LEAVE", today, 0L, "KEEP", null)));
        assertStatus(403, () -> personnel.get(employee, employee.employeeId()));
        assertStatus(403, () -> personnel.update(employee, employee.employeeId(), form(null, null, "WORKING", today, 1L, "KEEP", null)));
        assertStatus(404, () -> personnel.get(admin, DatabaseFixture.EMPLOYEE_B));
        assertStatus(404, () -> personnel.photo(admin, DatabaseFixture.EMPLOYEE_B));
        db.update("UPDATE tna.tenant_account SET auth_version=auth_version+1 WHERE tenant_id=? AND id=?", tenant, admin.accountId());
        assertStatus(403, () -> personnel.get(admin, employee.employeeId()));
        assertStatus(403, () -> personnel.header(admin, employee.employeeId()));
    }

    @Test
    void photoReplaceKeepClearAndTenantRlsHaveNarrowPermissions() throws Exception {
        String photo = Base64.getEncoder().encodeToString(EmployeePhotoTest.image("png"));
        personnel.update(admin, employee.employeeId(), form(null, null, "UNKNOWN", today, 0L, "REPLACE", photo));
        assertNotNull(personnel.selfHeader(employee).photoUrl());
        assertEquals("image/png", personnel.selfPhoto(employee).contentType());
        mvc.perform(get("/me/profile/photo").with(as(employee))
                        .param("employeeId", admin.employeeId().toString()).param("tenantId", UUID.randomUUID().toString()))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(content().contentType("image/png"))
                .andExpect(content().bytes(personnel.selfPhoto(employee).bytes()));
        mvc.perform(get("/admin/employees/" + employee.employeeId() + "/photo").with(as(employee))).andExpect(status().isForbidden());
        transactions.execute(tenant, () -> {
            assertNull(mapper.stored(DatabaseFixture.TENANT_B, DatabaseFixture.EMPLOYEE_B));
            return null;
        });
        var source = DatabaseFixture.start().appDataSource();
        var isolated = new TenantTransactions(source);
        var jdbc = new JdbcTemplate(source);
        isolated.execute(tenant, () -> {
            assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM tna.employee_personnel", Integer.class));
            assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM tna.employee_employment_status", Integer.class));
            return null;
        });
        isolated.execute(UUID.randomUUID(), () -> {
            assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM tna.employee_personnel", Integer.class));
            assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM tna.employee_employment_status", Integer.class));
            return null;
        });
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM tna.employee_personnel", Integer.class));
        assertEquals(2, db.queryForObject("SELECT count(*) FROM pg_class WHERE relnamespace='tna'::regnamespace AND relname IN ('employee_personnel','employee_employment_status') AND relrowsecurity AND relforcerowsecurity", Integer.class));
        assertEquals(false, db.queryForObject("SELECT has_table_privilege('tna_app','tna.employee_employment_status','UPDATE')", Boolean.class));
        assertEquals(false, db.queryForObject("SELECT has_table_privilege('tna_app','tna.employee_personnel','DELETE')", Boolean.class));
        assertEquals(false, db.queryForObject("SELECT has_table_privilege('tna_auth','tna.employee_personnel','SELECT')", Boolean.class));
        personnel.update(admin, employee.employeeId(), form(null, null, "UNKNOWN", today, 1L, "KEEP", null));
        assertNotNull(personnel.selfPhoto(employee));
        personnel.update(admin, employee.employeeId(), form(null, null, "UNKNOWN", today, 2L, "CLEAR", null));
        assertNull(personnel.selfHeader(employee).photoUrl());
        assertStatus(404, () -> personnel.selfPhoto(employee));
    }

    @Test
    void replacingPhotoChangesSelfAndAdminUrlsSoTheBrowserRequestsNewPixels() throws Exception {
        String png = Base64.getEncoder().encodeToString(EmployeePhotoTest.image("png"));
        personnel.update(admin, employee.employeeId(), form(null, null, "UNKNOWN", today, 0L, "REPLACE", png));
        String first = personnel.selfHeader(employee).photoUrl();
        assertEquals("/me/profile/photo?v=1", first);
        assertEquals("/admin/employees/" + employee.employeeId() + "/photo?v=1", personnel.header(admin, employee.employeeId()).photoUrl());
        String jpeg = Base64.getEncoder().encodeToString(EmployeePhotoTest.image("jpeg"));
        personnel.update(admin, employee.employeeId(), form(null, null, "UNKNOWN", today, 1L, "REPLACE", jpeg));
        String second = personnel.selfHeader(employee).photoUrl();
        assertEquals("/me/profile/photo?v=2", second);
        assertNotEquals(first, second);
        mvc.perform(get(second).with(as(employee))).andExpect(status().isOk()).andExpect(content().contentType("image/jpeg"));
    }

    @Test
    void concurrentChangesRejectOneStaleVersionWithoutLosingHistory() throws Exception {
        var start = new CountDownLatch(1);
        try (var workers = Executors.newFixedThreadPool(2)) {
            var first = workers.submit(() -> saveAfter(start, "WORKING"));
            var second = workers.submit(() -> saveAfter(start, "ON_LEAVE"));
            start.countDown();
            var outcomes = java.util.List.of(first.get(15, TimeUnit.SECONDS), second.get(15, TimeUnit.SECONDS));
            assertEquals(1, outcomes.stream().filter(status -> status == 0).count());
            assertEquals(1, outcomes.stream().filter(status -> status == 409).count());
        }
        assertEquals(1, personnel.get(admin, employee.employeeId()).version());
        assertEquals(1, personnel.get(admin, employee.employeeId()).statusHistory().size());
    }

    @Test
    void directSqlCannotWriteAcrossTenantsOrRewriteTheStatusTimeline() {
        personnel.update(admin, employee.employeeId(), form(null, null, "WORKING", today, 0L, "KEEP", null));
        var source = DatabaseFixture.start().appDataSource();
        var isolated = new TenantTransactions(source);
        var jdbc = new JdbcTemplate(source);
        isolated.execute(UUID.randomUUID(), () -> {
            assertEquals(0, jdbc.update("UPDATE tna.employee_personnel SET grade_name='침범' WHERE tenant_id=? AND employee_id=?", tenant, employee.employeeId()));
            return null;
        });
        deniedSql(() -> isolated.execute(UUID.randomUUID(), () -> jdbc.update(
                "INSERT INTO tna.employee_personnel(tenant_id,employee_id,version,updated_by) VALUES (?,?,1,?)", tenant, employee.employeeId(), admin.accountId())));
        deniedSql(() -> isolated.execute(UUID.randomUUID(), () -> jdbc.update(
                "INSERT INTO tna.employee_employment_status(tenant_id,id,employee_id,employment_status,effective_from,revision,reason,created_by) VALUES (?,?,?,'WORKING',?,2,'차단 검증',?)", tenant, UUID.randomUUID(), employee.employeeId(), today, admin.accountId())));
        deniedSql(() -> isolated.execute(tenant, () -> jdbc.update("UPDATE tna.employee_employment_status SET employment_status='SUSPENDED' WHERE tenant_id=?", tenant)));
        deniedSql(() -> isolated.execute(tenant, () -> jdbc.update("DELETE FROM tna.employee_personnel WHERE tenant_id=?", tenant)));
        assertEquals("WORKING", personnel.selfHeader(employee).employmentStatus());
        assertNull(personnel.selfHeader(employee).gradeName());
    }

    @Test
    void successfulPostAndConflictExposeCurrentFormAndKeepSubmittedPrivateInput() throws Exception {
        String path = "/admin/employees/" + employee.employeeId() + "/personnel";
        UUID command = UUID.randomUUID();
        mvc.perform(post(path).with(as(admin)).with(csrf()).param("gradeName", "현재 직급")
                        .param("employmentStatus", "WORKING").param("effectiveFrom", today.toString())
                        .param("expectedVersion", "0").param("photoAction", "KEEP")
                        .param("reason", "합성 인사 저장").param("commandId", command.toString()))
                .andExpect(status().isSeeOther()).andExpect(redirectedUrl(path + "?saved"));
        String photo = Base64.getEncoder().encodeToString(EmployeePhotoTest.image("png"));
        mvc.perform(post(path).with(as(admin)).with(csrf()).accept(PAGE_JSON)
                        .param("birthDate", today.minusYears(20).toString()).param("gradeName", "미저장 직급")
                        .param("employmentStatus", "ON_LEAVE").param("effectiveFrom", today.toString())
                        .param("expectedVersion", "0").param("photoAction", "REPLACE").param("photoData", photo)
                        .param("reason", "충돌 입력 보존").param("commandId", UUID.randomUUID().toString()))
                .andExpect(status().isConflict())
                .andExpect(ReactPageAssertions.data("/personnel/version", 1))
                .andExpect(ReactPageAssertions.data("/personnel/form/expectedVersion", 0))
                .andExpect(ReactPageAssertions.data("/personnel/form/photoData", photo))
                .andExpect(ReactPageAssertions.data("/personnel/currentForm/gradeName", "현재 직급"))
                .andExpect(ReactPageAssertions.data("/personnel/currentForm/expectedVersion", 1))
                .andExpect(ReactPageAssertions.data("/personnel/currentForm/photoData", ""));
    }

    @Test
    void privateHeaderStaysOffCompanyPagesAndRevokedSelfReadsFail() throws Exception {
        mvc.perform(get("/company/organizations").with(as(admin)).accept(PAGE_JSON))
                .andExpect(status().isOk()).andExpect(result ->
                        assertTrue(ReactPageAssertions.envelope(result).at("/data/employeeHeader").isMissingNode()));
        mvc.perform(get("/admin/employees/" + DatabaseFixture.EMPLOYEE_B + "/personnel").with(as(admin)).accept(PAGE_JSON))
                .andExpect(status().isNotFound());
        db.update("UPDATE tna.role_grant SET active=false WHERE tenant_id=? AND account_id=? AND role='EMPLOYEE'", tenant, employee.accountId());
        assertStatus(403, () -> personnel.selfHeader(employee));
        assertStatus(403, () -> personnel.selfPhoto(employee));
    }

    @Test
    void webContractPreservesInvalidInputAndRequiresCsrf() throws Exception {
        String path = "/admin/employees/" + employee.employeeId() + "/personnel";
        mvc.perform(get(path).with(as(admin)).accept(PAGE_JSON)).andExpect(status().isOk())
                .andExpect(view().name("org/personnel"))
                .andExpect(ReactPageAssertions.data("/personnel/employeeId", employee.employeeId().toString()))
                .andExpect(ReactPageAssertions.data("/employeeHeader/employeeNo", "E001"));
        mvc.perform(get("/me/profile").with(as(employee)).accept(PAGE_JSON)).andExpect(status().isOk())
                .andExpect(ReactPageAssertions.data("/employeeHeader/employeeId", employee.employeeId().toString()));
        mvc.perform(post(path).with(as(admin)).param("expectedVersion", "0")).andExpect(status().isForbidden());
        UUID command = UUID.randomUUID();
        mvc.perform(post(path).with(as(admin)).with(csrf()).accept(PAGE_JSON)
                        .param("birthDate", today.plusDays(1).toString()).param("gradeName", "보존 직급")
                        .param("employmentStatus", "WORKING").param("effectiveFrom", today.toString())
                        .param("expectedVersion", "0").param("photoAction", "KEEP")
                        .param("reason", "검증 실패 보존").param("commandId", command.toString()))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(ReactPageAssertions.data("/personnel/form/gradeName", "보존 직급"))
                .andExpect(ReactPageAssertions.data("/personnel/form/commandId", command.toString()));
    }

    @Test
    void privateDetailsAndEntriesAreVersionedIdempotentAndOnlyVisibleToSelfAndCurrentAdmin() throws Exception {
        String path = "/admin/employees/" + employee.employeeId();
        UUID command = UUID.randomUUID();
        mvc.perform(post(path + "/personal-details").with(as(admin)).with(csrf())
                        .param("admissionType", "NEW").param("recognizedMonths", "12")
                        .param("englishName", "Synthetic Person").param("maritalStatus", "NO")
                        .param("veteranStatus", "UNKNOWN").param("disabilityStatus", "NO")
                        .param("militaryStatus", "EXEMPT").param("mobilePhone", "010-0000-1234")
                        .param("address", "합성 주소").param("expectedVersion", "0")
                        .param("reason", "합성 인사 상세").param("commandId", command.toString()))
                .andExpect(status().isSeeOther());
        mvc.perform(get("/me/profile").with(as(employee)).accept(PAGE_JSON)
                        .param("employeeId", admin.employeeId().toString()))
                .andExpect(status().isOk())
                .andExpect(ReactPageAssertions.data("/personalDetails/form/englishName", "Synthetic Person"))
                .andExpect(ReactPageAssertions.data("/personalDetails/form/recognizedMonths", 12))
                .andExpect(ReactPageAssertions.data("/personalDetails/version", 1))
                .andExpect(result -> assertTrue(ReactPageAssertions.envelope(result).at("/data/employeeHeader/englishName").isMissingNode()));
        UUID entryCommand = UUID.randomUUID();
        for (int retry = 0; retry < 2; retry++) {
            mvc.perform(post(path + "/personal-entries").with(as(admin)).with(csrf())
                            .param("kind", "EDUCATION").param("title", "합성대학교")
                            .param("detail", "컴퓨터공학·졸업").param("effectiveFrom", today.toString())
                            .param("expectedVersion", "1").param("reason", "학력 등록")
                            .param("commandId", entryCommand.toString()))
                    .andExpect(status().isSeeOther());
        }
        mvc.perform(get("/me/profile").with(as(employee)).accept(PAGE_JSON))
                .andExpect(ReactPageAssertions.data("/personalDetails/entries/0/title", "합성대학교"))
                .andExpect(ReactPageAssertions.data("/personalDetails/version", 2));
        String audit = db.queryForObject("SELECT string_agg(after_state::text,' ') FROM tna.org_audit_event WHERE tenant_id=?", String.class, tenant);
        assertFalse(audit.contains("Synthetic Person"));
        assertFalse(audit.contains("010-0000-1234"));
        assertFalse(audit.contains("합성대학교"));
        mvc.perform(post(path + "/personal-details").with(as(employee)).with(csrf()).param("expectedVersion", "2"))
                .andExpect(status().isForbidden());
        db.update("UPDATE tna.role_grant SET active=false WHERE tenant_id=? AND account_id=? AND role='EMPLOYEE'", tenant, employee.accountId());
        mvc.perform(get("/me/profile").with(as(employee)).accept(PAGE_JSON)).andExpect(status().isUnauthorized());
    }

    @Test
    void privateDetailsValidateReplayConflictsCrossTenantAndRevokedAdmin() {
        var valid = detailForm(0L, "Synthetic", 0);
        details.update(admin, employee.employeeId(), valid);
        details.update(admin, employee.employeeId(), valid);
        assertEquals(1, details.self(employee).version());
        assertEquals(0, details.self(employee).form().recognizedMonths());
        assertStatus(409, () -> details.update(admin, employee.employeeId(), detailForm(0L, "Stale", null)));
        assertStatus(422, () -> details.update(admin, employee.employeeId(), detailForm(1L, "Negative", -1)));
        assertStatus(422, () -> details.update(admin, employee.employeeId(), detailForm(1L, "Too long", 1201)));
        assertStatus(422, () -> details.update(admin, employee.employeeId(), detailForm(1L, "😀".repeat(121), null)));
        assertStatus(404, () -> details.get(admin, DatabaseFixture.EMPLOYEE_B));
        assertStatus(404, () -> details.update(admin, DatabaseFixture.EMPLOYEE_B, detailForm(0L, "Cross tenant", null)));
        assertStatus(403, () -> details.get(employee, admin.employeeId()));
        assertStatus(403, () -> details.update(employee, employee.employeeId(), detailForm(1L, "Self write", null)));
        db.update("UPDATE tna.role_grant SET active=false WHERE tenant_id=? AND account_id=? AND role='ATTENDANCE_ADMIN'", tenant, admin.accountId());
        assertStatus(403, () -> details.get(admin, employee.employeeId()));
        assertStatus(403, () -> details.update(admin, employee.employeeId(), valid));
    }

    @Test
    void privateEntriesHaveCompositeKeysForcedRlsNarrowPrivilegesAndNoContextLeak() {
        details.append(admin, employee.employeeId(), new kr.tna.org.EmployeePersonalDetailsData.EntryForm(
                "FAMILY", "배우자", "1명", today, null, 0L, "합성 이력", UUID.randomUUID()));
        var source = DatabaseFixture.start().appDataSource();
        var isolated = new TenantTransactions(source);
        var jdbc = new JdbcTemplate(source);
        isolated.execute(tenant, () -> {
            assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM tna.employee_personal_details", Integer.class));
            assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM tna.employee_personal_entry", Integer.class));
            return null;
        });
        isolated.execute(UUID.randomUUID(), () -> {
            assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM tna.employee_personal_details", Integer.class));
            assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM tna.employee_personal_entry", Integer.class));
            assertEquals(0, jdbc.update("UPDATE tna.employee_personal_details SET english_name='차단' WHERE tenant_id=?", tenant));
            return null;
        });
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM tna.employee_personal_details", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM tna.employee_personal_entry", Integer.class));
        deniedSql(() -> isolated.execute(tenant, () -> jdbc.update("UPDATE tna.employee_personal_entry SET title='차단' WHERE tenant_id=?", tenant)));
        deniedSql(() -> isolated.execute(tenant, () -> jdbc.update("DELETE FROM tna.employee_personal_details WHERE tenant_id=?", tenant)));
        deniedSql(() -> isolated.execute(UUID.randomUUID(), () -> jdbc.update(
                "INSERT INTO tna.employee_personal_details(tenant_id,employee_id,version,updated_by) VALUES (?,?,1,?)", tenant, employee.employeeId(), admin.accountId())));
        assertThrows(DataAccessException.class, () -> isolated.execute(tenant, () -> jdbc.update(
                "INSERT INTO tna.employee_personal_details(tenant_id,employee_id,version,updated_by) VALUES (?,?,1,?)", tenant, DatabaseFixture.EMPLOYEE_B, admin.accountId())));
        assertEquals(2, db.queryForObject("SELECT count(*) FROM pg_class WHERE relnamespace='tna'::regnamespace AND relname IN ('employee_personal_details','employee_personal_entry') AND relrowsecurity AND relforcerowsecurity", Integer.class));
        assertEquals(false, db.queryForObject("SELECT has_table_privilege('tna_auth','tna.employee_personal_details','SELECT')", Boolean.class));
        assertEquals(false, db.queryForObject("SELECT has_table_privilege('tna_operator','tna.employee_personal_details','SELECT')", Boolean.class));
    }

    @Test
    void privateDetailsPreserveSubmittedInputAndRejectInvalidEntryDatesAndCsrf() throws Exception {
        details.update(admin, employee.employeeId(), detailForm(0L, "Current name", 12));
        String path = "/admin/employees/" + employee.employeeId();
        mvc.perform(post(path + "/personal-details").with(as(admin)).with(csrf()).accept(PAGE_JSON)
                        .param("englishName", "Submitted name").param("expectedVersion", "0")
                        .param("reason", "입력 보존").param("commandId", UUID.randomUUID().toString()))
                .andExpect(status().isConflict())
                .andExpect(ReactPageAssertions.data("/personalDetails/form/englishName", "Submitted name"))
                .andExpect(ReactPageAssertions.data("/personalDetails/currentForm/englishName", "Current name"))
                .andExpect(ReactPageAssertions.data("/personalDetails/version", 1));
        mvc.perform(post(path + "/personal-entries").with(as(admin)).with(csrf()).accept(PAGE_JSON)
                        .param("kind", "LANGUAGE").param("title", "영어").param("effectiveFrom", today.toString())
                        .param("effectiveTo", today.minusDays(1).toString()).param("expectedVersion", "1")
                        .param("reason", "잘못된 일자").param("commandId", UUID.randomUUID().toString()))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(ReactPageAssertions.data("/personalDetails/entryForm/title", "영어"));
        mvc.perform(post(path + "/personal-details").with(as(admin))).andExpect(status().isForbidden());
        mvc.perform(post(path + "/personal-entries").with(as(admin))).andExpect(status().isForbidden());
        assertEquals(1, details.get(admin, employee.employeeId()).version());
    }

    @Test
    void privateDetailsConcurrentUpdatesHaveOneWinnerAndActualAppointmentsOmitReasons() throws Exception {
        UUID organization = UUID.randomUUID();
        db.update("INSERT INTO tna.organization(tenant_id,id,code,name,created_by,updated_by) VALUES (?,?,'SYNTHETIC','합성팀',?,?)", tenant, organization, admin.accountId(), admin.accountId());
        db.update("INSERT INTO tna.employee_assignment(tenant_id,id,employee_id,organization_id,valid_from,reason,created_by,updated_by) VALUES (?,?,?,?,?,'발령 사유 비공개',?,?)", tenant, UUID.randomUUID(), employee.employeeId(), organization, today, admin.accountId(), admin.accountId());
        assertEquals("합성팀", details.self(employee).appointments().getFirst().organizationName());
        var start = new CountDownLatch(1);
        try (var workers = Executors.newFixedThreadPool(2)) {
            var futures = java.util.List.of("First", "Second").stream().map(name -> workers.submit(() -> {
                start.await();
                try { details.update(admin, employee.employeeId(), detailForm(0L, name, null)); return 0; }
                catch (OrgException failure) { return failure.status(); }
            })).toList();
            start.countDown();
            var outcomes = java.util.List.of(futures.get(0).get(15, TimeUnit.SECONDS), futures.get(1).get(15, TimeUnit.SECONDS));
            assertEquals(1, outcomes.stream().filter(status -> status == 0).count());
            assertEquals(1, outcomes.stream().filter(status -> status == 409).count());
        }
        assertEquals(1, details.self(employee).version());
    }

    private kr.tna.org.EmployeePersonalDetailsData.Form detailForm(Long version, String name, Integer months) {
        return new kr.tna.org.EmployeePersonalDetailsData.Form("NEW", months, null, name, "UNKNOWN", null,
                "UNKNOWN", "UNKNOWN", "UNKNOWN", null, null, null, null, null, null, version, "합성 기본정보", UUID.randomUUID());
    }

    private Form form(LocalDate birthDate, String grade, String status, LocalDate effective, Long version, String action, String data) {
        return new Form(birthDate, grade, null, null, status, effective, version, "합성 인사 등록", UUID.randomUUID(), action, data);
    }

    private int saveAfter(CountDownLatch start, String status) throws Exception {
        start.await();
        try {
            personnel.update(admin, employee.employeeId(), form(null, null, status, today, 0L, "KEEP", null));
            return 0;
        } catch (OrgException error) { return error.status(); }
    }

    private void deniedSql(Runnable action) {
        var denied = assertThrows(DataAccessException.class, action::run);
        assertInstanceOf(java.sql.SQLException.class, denied.getMostSpecificCause());
        assertEquals("42501", ((java.sql.SQLException) denied.getMostSpecificCause()).getSQLState());
    }

    private TnaPrincipal member(String number, boolean isAdmin) {
        UUID id = UUID.randomUUID(), account = UUID.randomUUID();
        db.update("INSERT INTO tna.employee(tenant_id,id,employee_no,display_name,joined_on) VALUES (?,?,?,? ,?)", tenant, id, number, "합성 " + number, today.minusYears(1));
        db.update("INSERT INTO tna.tenant_account(tenant_id,id,employee_id,email,password_hash) VALUES (?,?,?,?,'synthetic-unused')", tenant, account, id, account + "@example.test");
        db.update("INSERT INTO tna.role_grant(tenant_id,account_id,role) VALUES (?,?,'EMPLOYEE')", tenant, account);
        if (isAdmin) db.update("INSERT INTO tna.role_grant(tenant_id,account_id,role) VALUES (?,?,'ATTENDANCE_ADMIN')", tenant, account);
        return new TnaPrincipal(tenant, account, id, 1, isAdmin ? Set.of("EMPLOYEE", "ATTENDANCE_ADMIN") : Set.of("EMPLOYEE"), Instant.now());
    }

    private static RequestPostProcessor as(TnaPrincipal actor) {
        return authentication(UsernamePasswordAuthenticationToken.authenticated(actor, null,
                actor.roles().stream().map(role -> new SimpleGrantedAuthority("ROLE_" + role)).toList()));
    }
    private void assertStatus(int expected, Runnable action) {
        assertEquals(expected, assertThrows(OrgException.class, action::run).status());
    }
}
