package kr.tna;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import kr.tna.common.auth.TnaPrincipal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class AdminPersonnelWriteIT {
    private static final String PAGE_JSON = "application/vnd.worklog.page+json";
    private static final String BASE_NAME = "합성 인사 사원";
    private static final String SAVED_NAME = "합성 인사 변경";
    private static final String ORIGINAL_ENGLISH = "Synthetic Original";
    private static final String SAVED_ENGLISH = "Synthetic Updated";

    @Autowired MockMvc mvc;
    private final LocalDate today = LocalDate.now(ZoneId.of("Asia/Seoul"));
    private JdbcTemplate db;
    private UUID tenant;
    private TnaPrincipal admin;
    private TnaPrincipal employee;

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
        tenant = companyId();
        admin = member(tenant, "ADMIN", "합성 담당자", true);
        employee = member(tenant, "P001", BASE_NAME, false);
    }

    @Test
    void basicUpdatesThreeSourcesAndHeaderWithOneReplayableCommand() throws Exception {
        seedDetails();
        UUID command = UUID.randomUUID();
        byte[] image = EmployeePhotoTest.image("png");
        String photo = Base64.getEncoder().encodeToString(image);
        for (int retry = 0; retry < 2; retry++) {
            mvc.perform(basic(command, 1, "24", "REPLACE", photo))
                    .andExpect(status().isSeeOther()).andExpect(redirectedUrl(returnTo("basic")));
        }

        mvc.perform(get(personnelPath()).with(as(admin)).param("tab", "basic").accept(PAGE_JSON))
                .andExpect(status().isOk())
                .andExpect(ReactPageAssertions.data("/adminPersonnel/tab", "basic"))
                .andExpect(ReactPageAssertions.data("/employeeHeader/employeeNo", "P001"))
                .andExpect(ReactPageAssertions.data("/employeeHeader/employeeName", SAVED_NAME))
                .andExpect(ReactPageAssertions.data("/employeeHeader/gradeName", "합성 선임"))
                .andExpect(ReactPageAssertions.data("/employeeHeader/employmentStatus", "ON_LEAVE"));
        assertEquals(1L, employeeVersion());
        assertEquals(1L, version("employee_personnel"));
        assertEquals(2L, version("employee_personal_details"));
        assertEquals(1L, rows("employee_employment_status"));
        assertEquals(2L, db.queryForObject("SELECT auth_version FROM tna.tenant_account WHERE tenant_id=? AND id=?",
                Long.class, tenant, employee.accountId()));
        assertArrayEquals(image, db.queryForObject("SELECT photo_bytes FROM tna.employee_personnel WHERE tenant_id=? AND employee_id=?",
                byte[].class, tenant, employee.employeeId()));
        assertEquals(SAVED_ENGLISH, detail("english_name"));
        assertEquals("기존 합성 주소", detail("address"));
        assertEquals("010-0000-1234", detail("mobile_phone"));
        assertEquals(1L, audits(command));
        String audit = db.queryForObject("""
                SELECT concat(before_state::text, after_state::text) FROM tna.org_audit_event WHERE tenant_id=? AND command_id=?
                """, String.class, tenant, command);
        assertNotNull(audit);
        assertFalse(audit.contains(photo));
        assertFalse(audit.contains(SAVED_ENGLISH));
        assertFalse(audit.contains(today.minusYears(28).toString()));
    }

    @Test
    void staleOrInvalidBasicRequestPreservesAllSourcesAndKeepsTheSubmittedTab() throws Exception {
        seedDetails();
        String before = snapshot();
        UUID stale = UUID.randomUUID();
        mvc.perform(basic(stale, 0, "24", "KEEP", ""))
                .andExpect(status().isConflict()).andExpect(view().name("org/personnel"))
                .andExpect(ReactPageAssertions.data("/adminPersonnel/tab", "basic"))
                .andExpect(ReactPageAssertions.data("/tabConflict", true))
                .andExpect(ReactPageAssertions.data("/tabDraft/commandId", stale.toString()))
                .andExpect(ReactPageAssertions.data("/tabDraft/displayName", SAVED_NAME))
                .andExpect(result -> {
                    String body = ReactPageAssertions.envelope(result).toString();
                    assertTrue(body.contains(SAVED_NAME));
                    assertTrue(body.contains(ORIGINAL_ENGLISH));
                });
        assertEquals(before, snapshot());
        assertEquals(0L, audits(stale));

        UUID invalid = UUID.randomUUID();
        mvc.perform(basic(invalid, 1, "1201", "KEEP", ""))
                .andExpect(status().isUnprocessableEntity()).andExpect(view().name("org/personnel"))
                .andExpect(ReactPageAssertions.data("/adminPersonnel/tab", "basic"));
        assertEquals(before, snapshot());
        assertEquals(0L, audits(invalid));
    }

    @Test
    void contactOnlyChangesItsOwnFieldsAndRejectsAStaleSharedDetailsVersion() throws Exception {
        seedDetails();
        String loginEmail = employee.accountId() + "@example.test";
        String before = snapshot();
        UUID stale = UUID.randomUUID();
        mvc.perform(contact(stale, 0))
                .andExpect(status().isConflict()).andExpect(view().name("org/personnel"))
                .andExpect(ReactPageAssertions.data("/adminPersonnel/tab", "contact"));
        assertEquals(before, snapshot());
        assertEquals(0L, audits(stale));

        UUID command = UUID.randomUUID();
        mvc.perform(contact(command, 1))
                .andExpect(status().isSeeOther()).andExpect(redirectedUrl(returnTo("contact")));
        assertEquals("합성 새 주소", detail("address"));
        assertNull(detail("address_detail"));
        assertEquals("010-0000-5678", detail("mobile_phone"));
        assertEquals("contact@example.test", detail("contact_email"));
        assertEquals(ORIGINAL_ENGLISH, detail("english_name"));
        assertEquals("EXPERIENCED", detail("admission_type"));
        assertEquals(2L, version("employee_personal_details"));
        assertEquals(0L, employeeVersion());
        assertEquals(0L, rows("employee_personnel"));
        assertEquals(loginEmail, db.queryForObject("SELECT email FROM tna.tenant_account WHERE tenant_id=? AND id=?",
                String.class, tenant, employee.accountId()));
        assertEquals(1L, audits(command));

        mvc.perform(save("contact", UUID.randomUUID()).param("detailsVersion", "2").param("address", "합성 부분 수정"))
                .andExpect(status().isSeeOther()).andExpect(redirectedUrl(returnTo("contact")));
        assertEquals("합성 부분 수정", detail("address"));
        assertEquals("12345", detail("postal_code"));
        assertEquals("010-0000-5678", detail("mobile_phone"));
        assertEquals("contact@example.test", detail("contact_email"));
    }

    @Test
    void entriesAppendByTabAndPreserveLegacyFamilyData() throws Exception {
        seedDetails();
        UUID legacy = UUID.randomUUID();
        db.update("""
                INSERT INTO tna.employee_personal_entry
                    (tenant_id,id,employee_id,kind,title,detail,effective_from,revision,created_by)
                VALUES (?,?,?,'FAMILY','기존 합성 항목','기존 자유입력 원문',?,1,?)
                """, tenant, legacy, employee.employeeId(), today.minusDays(2), admin.accountId());
        UUID family = UUID.randomUUID();
        mvc.perform(family(family, "0"))
                .andExpect(status().isUnprocessableEntity()).andExpect(view().name("org/personnel"))
                .andExpect(ReactPageAssertions.data("/adminPersonnel/tab", "family"));
        assertEquals(1L, rows("employee_personal_entry"));
        assertEquals(0L, audits(family));
        family = UUID.randomUUID();
        mvc.perform(family(family, "2"))
                .andExpect(status().isSeeOther()).andExpect(redirectedUrl(returnTo("family")));
        assertEquals(2L, rows("employee_personal_entry"));
        assertEquals("기존 자유입력 원문", db.queryForObject("""
                SELECT detail FROM tna.employee_personal_entry WHERE tenant_id=? AND employee_id=? AND id=?
                """, String.class, tenant, employee.employeeId(), legacy));
        assertEquals("2명", db.queryForObject("""
                SELECT detail FROM tna.employee_personal_entry WHERE tenant_id=? AND employee_id=? AND title='자녀'
                """, String.class, tenant, employee.employeeId()));
        assertEquals(List.of("FAMILY", "FAMILY"), entryKinds());

        List<String> tabs = List.of("education", "qualification", "language");
        for (int index = 0; index < tabs.size(); index++) {
            String tab = tabs.get(index);
            mvc.perform(save(tab, UUID.randomUUID()).param("detailsVersion", Integer.toString(2 + index))
                            .param("title", "합성 이력 " + tab).param("detail", "합성 상세")
                            .param("effectiveFrom", today.toString()).param("effectiveTo", today.plusDays(1).toString()))
                    .andExpect(status().isSeeOther()).andExpect(redirectedUrl(returnTo(tab)));
        }
        assertEquals(List.of("FAMILY", "FAMILY", "EDUCATION", "QUALIFICATION", "LANGUAGE"), entryKinds());
        assertEquals(5L, version("employee_personal_details"));
        assertEquals("기존 합성 주소", detail("address"));
    }

    @Test
    void appointmentClosesTheOldRangeAtTheNewStartAndRejectsTheSameStart() throws Exception {
        UUID original = organization("ORIGINAL"), next = organization("NEXT");
        db.update("""
                INSERT INTO tna.employee_assignment
                    (tenant_id,id,employee_id,organization_id,valid_from,reason,created_by,updated_by)
                VALUES (?,?,?,?,?,'합성 기존 소속',?,?)
                """, tenant, UUID.randomUUID(), employee.employeeId(), original, today.minusDays(20),
                admin.accountId(), admin.accountId());
        LocalDate start = today.plusDays(2);
        UUID command = UUID.randomUUID();
        mvc.perform(save("appointments", command).param("employeeVersion", "0")
                        .param("organizationId", next.toString()).param("validFrom", start.toString()))
                .andExpect(status().isSeeOther()).andExpect(redirectedUrl(returnTo("appointments")));
        assertEquals(start, db.queryForObject("""
                SELECT valid_to FROM tna.employee_assignment WHERE tenant_id=? AND employee_id=? AND organization_id=?
                """, LocalDate.class, tenant, employee.employeeId(), original));
        assertEquals(start, db.queryForObject("""
                SELECT valid_from FROM tna.employee_assignment WHERE tenant_id=? AND employee_id=? AND organization_id=?
                  AND valid_to IS NULL
                """, LocalDate.class, tenant, employee.employeeId(), next));
        assertEquals(1L, employeeVersion());
        assertEquals(1L, audits(command));
        mvc.perform(get(personnelPath()).with(as(admin)).accept(PAGE_JSON))
                .andExpect(status().isOk())
                .andExpect(ReactPageAssertions.data("/adminPersonnel/tab", "basic"))
                .andExpect(ReactPageAssertions.data("/adminPersonnel/organizations/0/code", "NEXT"));
        mvc.perform(get(personnelPath()).with(as(admin)).param("tab", "appointments").accept(PAGE_JSON))
                .andExpect(status().isOk())
                .andExpect(ReactPageAssertions.data("/adminPersonnel/tab", "appointments"))
                .andExpect(ReactPageAssertions.data("/employeeHeader/organizationName", "ORIGINAL"));

        UUID invalid = UUID.randomUUID();
        mvc.perform(save("appointments", invalid).param("employeeVersion", "1")
                        .param("organizationId", original.toString()).param("validFrom", start.toString()))
                .andExpect(status().isUnprocessableEntity()).andExpect(view().name("org/personnel"))
                .andExpect(ReactPageAssertions.data("/adminPersonnel/tab", "appointments"));
        assertEquals(2L, rows("employee_assignment"));
        assertEquals(0L, audits(invalid));
    }

    @Test
    void tabWritesRequireCsrfAndCurrentAdministrationAndDoNotResolveForeignEmployees() throws Exception {
        UUID command = UUID.randomUUID();
        var foreign = member(companyId(), "P001", "합성 타사 사원", false);
        mvc.perform(post(personnelPath() + "/tabs/contact").with(as(admin)))
                .andExpect(status().isForbidden());
        mvc.perform(post(personnelPath() + "/tabs/contact").with(as(employee)).with(csrf()))
                .andExpect(status().isForbidden());
        mvc.perform(post("/admin/employees/{id}/personnel/tabs/contact", foreign.employeeId())
                        .with(as(admin)).with(csrf()).contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("detailsVersion", "0").param("address", "타사 변경 테스트")
                        .param("reason", "합성 테넌트 검증").param("commandId", command.toString()))
                .andExpect(status().isNotFound());
        assertEquals(0L, audits(command));
    }

    private MockHttpServletRequestBuilder basic(UUID command, long detailsVersion, String months, String photoAction, String photo) {
        return save("basic", command).param("employeeVersion", "0").param("personnelVersion", "0")
                .param("detailsVersion", Long.toString(detailsVersion))
                .param("displayName", SAVED_NAME).param("joinedOn", today.minusYears(1).toString())
                .param("leftOn", today.plusMonths(4).toString()).param("birthDate", today.minusYears(28).toString())
                .param("gradeName", "합성 선임").param("positionName", "합성 팀원").param("jobName", "합성 직무")
                .param("employmentStatus", "ON_LEAVE").param("effectiveFrom", today.toString())
                .param("photoAction", photoAction).param("photoData", photo)
                .param("admissionType", "NEW").param("recognizedMonths", months).param("groupJoinedOn", "")
                .param("englishName", SAVED_ENGLISH).param("maritalStatus", "NO").param("marriedOn", "")
                .param("veteranStatus", "UNKNOWN").param("disabilityStatus", "NO")
                .param("militaryStatus", "EXEMPT").param("militaryBranch", "");
    }

    private MockHttpServletRequestBuilder contact(UUID command, long version) {
        return save("contact", command).param("detailsVersion", Long.toString(version))
                .param("postalCode", "12345").param("address", "합성 새 주소").param("addressDetail", "")
                .param("mobilePhone", "010-0000-5678").param("contactEmail", "contact@example.test");
    }

    private MockHttpServletRequestBuilder family(UUID command, String count) {
        return save("family", command).param("detailsVersion", "1").param("relationship", "CHILD").param("count", count)
                .param("effectiveFrom", today.toString()).param("effectiveTo", "");
    }

    private MockHttpServletRequestBuilder save(String tab, UUID command) {
        return post(personnelPath() + "/tabs/" + tab).with(as(admin)).with(csrf())
                .contentType(MediaType.APPLICATION_FORM_URLENCODED).accept(PAGE_JSON)
                .param("reason", "합성 탭 저장 검증").param("commandId", command.toString());
    }

    private String personnelPath() { return "/admin/employees/" + employee.employeeId() + "/personnel"; }

    private String returnTo(String tab) { return personnelPath() + "?tab=" + tab + "&saved"; }

    private UUID companyId() {
        UUID id = UUID.randomUUID();
        db.update("INSERT INTO platform.tenant_registry(id,company_code,display_name) VALUES (?,?,'합성 인사 회사')",
                id, "ADMIN-PERSONNEL-" + id);
        return id;
    }

    private TnaPrincipal member(UUID company, String number, String name, boolean manager) {
        UUID id = UUID.randomUUID(), account = UUID.randomUUID();
        db.update("INSERT INTO tna.employee(tenant_id,id,employee_no,display_name,joined_on) VALUES (?,?,?,?,?)",
                company, id, number, name, today.minusYears(1));
        db.update("INSERT INTO tna.tenant_account(tenant_id,id,employee_id,email,password_hash) VALUES (?,?,?,?,'synthetic-unused')",
                company, account, id, account + "@example.test");
        Set<String> roles = manager ? Set.of("EMPLOYEE", "ATTENDANCE_ADMIN") : Set.of("EMPLOYEE");
        roles.forEach(role -> db.update("INSERT INTO tna.role_grant(tenant_id,account_id,role) VALUES (?,?,?)",
                company, account, role));
        return new TnaPrincipal(company, account, id, 1, roles, Instant.now());
    }

    private UUID organization(String code) {
        UUID id = UUID.randomUUID();
        db.update("INSERT INTO tna.organization(tenant_id,id,code,name,created_by,updated_by) VALUES (?,?,?,?,?,?)",
                tenant, id, code, code, admin.accountId(), admin.accountId());
        return id;
    }

    private void seedDetails() {
        db.update("""
                INSERT INTO tna.employee_personal_details
                    (tenant_id,employee_id,admission_type,english_name,postal_code,address,address_detail,mobile_phone,version,updated_by)
                VALUES (?,?,'EXPERIENCED',?,'01234','기존 합성 주소','기존 합성 상세','010-0000-1234',1,?)
                """, tenant, employee.employeeId(), ORIGINAL_ENGLISH, admin.accountId());
    }

    private long employeeVersion() {
        return db.queryForObject("SELECT version FROM tna.employee WHERE tenant_id=? AND id=?",
                Long.class, tenant, employee.employeeId());
    }

    private long version(String table) {
        return db.queryForObject("SELECT version FROM tna." + table + " WHERE tenant_id=? AND employee_id=?",
                Long.class, tenant, employee.employeeId());
    }

    private long rows(String table) {
        return db.queryForObject("SELECT count(*) FROM tna." + table + " WHERE tenant_id=? AND employee_id=?",
                Long.class, tenant, employee.employeeId());
    }

    private String detail(String field) {
        return db.queryForObject("SELECT " + field + " FROM tna.employee_personal_details WHERE tenant_id=? AND employee_id=?",
                String.class, tenant, employee.employeeId());
    }

    private long audits(UUID command) {
        return db.queryForObject("SELECT count(*) FROM tna.org_audit_event WHERE tenant_id=? AND command_id=?",
                Long.class, tenant, command);
    }

    private List<String> entryKinds() {
        return db.queryForList("SELECT kind FROM tna.employee_personal_entry WHERE tenant_id=? AND employee_id=? ORDER BY revision",
                String.class, tenant, employee.employeeId());
    }

    private String snapshot() {
        UUID id = employee.employeeId();
        return db.queryForObject("""
                SELECT jsonb_build_object(
                    'employee',(SELECT to_jsonb(e) FROM tna.employee e WHERE tenant_id=? AND id=?),
                    'personnel',(SELECT to_jsonb(p) FROM tna.employee_personnel p WHERE tenant_id=? AND employee_id=?),
                    'details',(SELECT to_jsonb(d) FROM tna.employee_personal_details d WHERE tenant_id=? AND employee_id=?),
                    'status',(SELECT jsonb_agg(to_jsonb(s) ORDER BY revision) FROM tna.employee_employment_status s
                        WHERE tenant_id=? AND employee_id=?),
                    'session',(SELECT auth_version FROM tna.tenant_account WHERE tenant_id=? AND employee_id=?))::text
                """, String.class, tenant, id, tenant, id, tenant, id, tenant, id, tenant, id);
    }

    private static RequestPostProcessor as(TnaPrincipal actor) {
        return authentication(UsernamePasswordAuthenticationToken.authenticated(actor, null,
                actor.roles().stream().map(role -> new SimpleGrantedAuthority("ROLE_" + role)).toList()));
    }
}
