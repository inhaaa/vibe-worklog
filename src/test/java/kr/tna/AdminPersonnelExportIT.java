package kr.tna;

import java.io.ByteArrayInputStream;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.zip.ZipInputStream;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import kr.tna.common.auth.TnaPrincipal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.w3c.dom.Element;
import tools.jackson.databind.json.JsonMapper;

import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class AdminPersonnelExportIT {
    private static final String PATH = "/admin/employees/{id}/personnel/tabs/{tab}/export";
    private static final String XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    private static final String NAME = "=2+3";
    private static final String ENTRY_FORMAT = "[worklog:personnel-v1] ";
    private static final List<String> TABS = List.of("basic", "appointments", "family", "education", "qualification", "language", "contact");
    private static final JsonMapper JSON = JsonMapper.builder().build();

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
        admin = member(tenant, "EXPORT-ADMIN", "합성 요청자", Set.of("EMPLOYEE", "ATTENDANCE_ADMIN"));
        employee = member(tenant, "00017", NAME, Set.of("EMPLOYEE"));
    }

    @Test
    void eachWorkbookUsesOnlyTheSelectedEmployeesStoredFieldsAndAuditsItsOwnRows() throws Exception {
        member(tenant, "00018", "다른 사원", Set.of("EMPLOYEE"));
        seedRecords();
        var rowCounts = new HashMap<String, Integer>();

        var basic = download("basic");
        assertEquals(headers("입사일", "마지막 재직일", "입사구분", "인정경력", "그룹입사일", "소속",
                "재직 표시 상태", "직급", "직책", "직무"), basic.getFirst());
        assertEquals(2, basic.size());
        assertEquals(row(today.minusYears(2).toString(), today.plusMonths(1).toString(), "경력", "24",
                "2019-04-13", "합성 조직", "휴직", "합성 직급", "합성 직책", "합성 직무"), basic.get(1));
        rowCounts.put("basic", 1);

        assertEquals(List.of(headers("발령 구분", "조직명", "적용 시작일", "적용 종료일(미포함)"),
                row("소속 배정", "합성 조직", today.minusMonths(3).toString(), today.plusMonths(1).toString())),
                download("appointments"));
        rowCounts.put("appointments", 1);

        var family = download("family");
        assertEquals(headers("관계", "인원", "시작일", "종료일"), family.getFirst());
        assertEquals(4, family.size());
        assertEquals(Set.of(row("자녀", "3", "2020-01-01", "2021-01-01"),
                row("배우자", "1", "2021-01-01", ""), row("", "", "2022-01-01", "")),
                Set.copyOf(family.subList(1, family.size())));
        rowCounts.put("family", 3);

        var education = download("education");
        assertEquals(headers("학교명", "전공·졸업 구분", "시작일", "종료일"), education.getFirst());
        assertEquals(Set.of(row("합성 대학교", "전산·졸업", "2020-01-01", ""), row("이전 학교", "", "2021-01-01", "")),
                Set.copyOf(education.subList(1, education.size())));
        rowCounts.put("education", 2);

        assertEquals(List.of(headers("자격명", "발급기관", "취득일", "만료일"),
                row("합성 자격", "합성 발급기관", "2020-01-01", "2021-01-01")), download("qualification"));
        rowCounts.put("qualification", 1);
        assertEquals(List.of(headers("언어·시험명", "등급 또는 점수", "취득일", "만료일"),
                row("합성 시험", "+850", "2020-01-01", "")), download("language"));
        rowCounts.put("language", 1);

        var contact = download("contact");
        assertEquals(headers("우편번호", "기본주소", "휴대전화", "연락 이메일"), contact.getFirst());
        assertEquals(2, contact.size());
        assertEquals(row("01234", "합성 기본 주소", "****1234", "***@example.test"), contact.get(1));
        rowCounts.put("contact", 1);

        var audit = db.query("""
                SELECT actor_account_id,target_id,command_id,occurred_at,after_state::text
                FROM tna.org_audit_event WHERE tenant_id=? AND action='PERSONNEL_TAB_EXPORT'
                """, (rs, index) -> new Receipt(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                        rs.getObject(3, UUID.class), rs.getTimestamp(4).toInstant(), rs.getString(5)), tenant);
        assertEquals(TABS.size(), audit.size());
        assertEquals(TABS.size(), audit.stream().map(Receipt::command).distinct().count());
        for (var receipt : audit) {
            assertEquals(admin.accountId(), receipt.actor());
            assertEquals(employee.employeeId(), receipt.target());
            assertNotNull(receipt.time());
            var payload = JSON.readTree(receipt.body());
            String tab = payload.path("tab").asString();
            assertTrue(rowCounts.containsKey(tab));
            assertEquals(JSON.valueToTree(Map.of("tab", tab, "rowCount", rowCounts.remove(tab))), payload);
        }
        assertTrue(rowCounts.isEmpty());
    }

    @Test
    void emptyHistoryStillReturnsColumnHeadersAndRecordsZeroRows() throws Exception {
        assertEquals(List.of(headers("언어·시험명", "등급 또는 점수", "취득일", "만료일")), download("language"));
        assertEquals(List.of(headers("우편번호", "기본주소", "휴대전화", "연락 이메일")), download("contact"));
        assertEquals("0", db.queryForObject("""
                SELECT after_state->>'rowCount' FROM tna.org_audit_event
                WHERE tenant_id=? AND action='PERSONNEL_TAB_EXPORT' AND after_state->>'tab'='language'
                """, String.class, tenant));
    }

    @Test
    void basicExportMatchesTheWorkspacesInactiveAndDepartedStatusDisplay() throws Exception {
        db.update("UPDATE tna.employee SET active=false WHERE tenant_id=? AND id=?", tenant, employee.employeeId());
        assertEquals("비활성", download("basic").get(1).get(8));
        db.update("UPDATE tna.employee SET left_on=? WHERE tenant_id=? AND id=?",
                today.minusDays(1), tenant, employee.employeeId());
        assertEquals("퇴사", download("basic").get(1).get(8));
    }

    @Test
    void rejectsMissingCsrfOtherRolesAndForeignTargetsWithoutAudit() throws Exception {
        var foreign = member(companyId(), "00017", "합성 타사 사원", Set.of("EMPLOYEE"));
        mvc.perform(request(admin, employee.employeeId(), "basic")).andExpect(status().isForbidden());
        mvc.perform(request(employee, employee.employeeId(), "basic").with(csrf())).andExpect(status().isForbidden());
        mvc.perform(request(admin, foreign.employeeId(), "basic").with(csrf())).andExpect(status().isNotFound());
        mvc.perform(request(admin, UUID.randomUUID(), "basic").with(csrf())).andExpect(status().isNotFound());
        mvc.perform(request(admin, employee.employeeId(), "unlisted").with(csrf())).andExpect(status().isBadRequest());
        mvc.perform(request(admin, employee.employeeId(), "contact").with(csrf()).param("address", "미저장 값"))
                .andExpect(status().isBadRequest());
        db.update("UPDATE tna.role_grant SET active=false WHERE tenant_id=? AND account_id=? AND role='ATTENDANCE_ADMIN'",
                tenant, admin.accountId());
        mvc.perform(request(admin, employee.employeeId(), "basic").with(csrf()))
                .andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/login"));
        assertNoAudit();
    }

    @Nested
    @ActiveProfiles("public-demo")
    class PublicDemo {
        @Autowired MockMvc publicMvc;

        @Test
        void everyTabDownloadIsForbiddenEvenForAnUnsharedEmployeeInTheSameTenant() throws Exception {
            for (String tab : TABS) {
                publicMvc.perform(request(admin, employee.employeeId(), tab).with(csrf()))
                        .andExpect(status().isForbidden());
            }
            assertNoAudit();
        }
    }

    private List<List<String>> download(String tab) throws Exception {
        byte[] bytes = mvc.perform(request(admin, employee.employeeId(), tab).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("Content-Disposition", startsWith("attachment;")))
                .andExpect(content().contentType(XLSX)).andReturn().getResponse().getContentAsByteArray();
        try (var zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                if (!entry.getName().equals("xl/worksheets/sheet1.xml")) continue;
                var parser = DocumentBuilderFactory.newInstance();
                parser.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
                parser.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
                parser.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
                var sheet = parser.newDocumentBuilder().parse(new ByteArrayInputStream(zip.readAllBytes()));
                var items = sheet.getElementsByTagName("row");
                List<List<String>> rows = new ArrayList<>();
                assertEquals(0, sheet.getElementsByTagName("f").getLength());
                for (int i = 0; i < items.getLength(); i++) {
                    var cells = ((Element) items.item(i)).getElementsByTagName("c");
                    List<String> row = new ArrayList<>();
                    for (int j = 0; j < cells.getLength(); j++) {
                        var cell = (Element) cells.item(j);
                        assertEquals("inlineStr", cell.getAttribute("t"));
                        row.add(cell.getTextContent());
                    }
                    rows.add(row);
                }
                return rows;
            }
        }
        throw new AssertionError("워크시트가 없습니다.");
    }

    private static List<String> headers(String... cells) { return columns("사번", "한글 이름", cells); }
    private static List<String> row(String... cells) { return columns("00017", NAME, cells); }

    private static List<String> columns(String first, String second, String[] cells) {
        var result = new ArrayList<>(List.of(first, second));
        result.addAll(List.of(cells));
        return result;
    }

    private static MockHttpServletRequestBuilder request(TnaPrincipal actor, UUID id, String tab) {
        var authorities = actor.roles().stream().map(role -> new SimpleGrantedAuthority("ROLE_" + role)).toList();
        return post(PATH, id, tab).with(authentication(UsernamePasswordAuthenticationToken.authenticated(actor, null, authorities)));
    }

    private void assertNoAudit() {
        assertEquals(0, db.queryForObject("SELECT count(*) FROM tna.org_audit_event WHERE tenant_id=?", Integer.class, tenant));
    }

    private UUID companyId() {
        UUID id = UUID.randomUUID();
        db.update("INSERT INTO platform.tenant_registry(id,company_code,display_name) VALUES (?,?,'합성 회사')",
                id, "PERSONNEL-EXPORT-" + id);
        return id;
    }

    private TnaPrincipal member(UUID company, String number, String name, Set<String> roles) {
        UUID id = UUID.randomUUID(), account = UUID.randomUUID();
        db.update("INSERT INTO tna.employee(tenant_id,id,employee_no,display_name,joined_on) VALUES (?,?,?,?,?)",
                company, id, number, name, today.minusYears(2));
        db.update("INSERT INTO tna.tenant_account(tenant_id,id,employee_id,email,password_hash) VALUES (?,?,?,?,'synthetic-unused')",
                company, account, id, account + "@example.test");
        roles.forEach(role -> db.update("INSERT INTO tna.role_grant(tenant_id,account_id,role) VALUES (?,?,?)", company, account, role));
        return new TnaPrincipal(company, account, id, 1, roles, Instant.now());
    }

    private void seedRecords() {
        UUID employeeId = employee.employeeId(), actor = admin.accountId(), organization = UUID.randomUUID();
        db.update("UPDATE tna.employee SET left_on=? WHERE tenant_id=? AND id=?", today.plusMonths(1), tenant, employeeId);
        db.update("INSERT INTO tna.organization(tenant_id,id,code,name,created_by,updated_by) VALUES (?,?,'EXPORT-ORG','합성 조직',?,?)",
                tenant, organization, actor, actor);
        db.update("""
                INSERT INTO tna.employee_assignment(tenant_id,id,employee_id,organization_id,valid_from,valid_to,reason,created_by,updated_by)
                VALUES (?,gen_random_uuid(),?,?,?,?,'발령 변경 사유',?,?)
                """, tenant, employeeId, organization, today.minusMonths(3), today.plusMonths(1), actor, actor);
        db.update("""
                INSERT INTO tna.employee_personnel(tenant_id,employee_id,birth_date,grade_name,position_name,job_name,
                    photo_content_type,photo_bytes,version,updated_by)
                VALUES (?,?,DATE '1992-08-04','합성 직급','합성 직책','합성 직무','image/png',decode('fe','hex'),1,?)
                """, tenant, employeeId, actor);
        db.update("""
                INSERT INTO tna.employee_employment_status(tenant_id,id,employee_id,employment_status,effective_from,revision,reason,created_by)
                VALUES (?,gen_random_uuid(),?,'ON_LEAVE',?,1,'재직 변경 사유',?)
                """, tenant, employeeId, today.minusDays(1), actor);
        db.update("""
                INSERT INTO tna.employee_personal_details(tenant_id,employee_id,admission_type,recognized_months,group_joined_on,
                    english_name,marital_status,married_on,veteran_status,disability_status,military_status,military_branch,
                    mobile_phone,postal_code,address,address_detail,contact_email,version,updated_by)
                VALUES (?,?,'EXPERIENCED',24,DATE '2019-04-13','Synthetic Nonexported','YES',DATE '2020-09-10',
                    'YES','YES','COMPLETED','합성 군별','010-9876-1234','01234','합성 기본 주소','합성 상세 주소','private.alias@example.test',1,?)
                """, tenant, employeeId, actor);
        entry(1, "FAMILY", "자녀", ENTRY_FORMAT + "3명", 2020, true);
        entry(2, "FAMILY", "배우자", "1명", 2021, false);
        entry(3, "FAMILY", "자녀", "3명 / 합성 인물 이름", 2022, false);
        entry(4, "EDUCATION", "합성 대학교", ENTRY_FORMAT + "전산·졸업", 2020, false);
        entry(5, "EDUCATION", "이전 학교", "기존 상세 원문", 2021, false);
        entry(6, "QUALIFICATION", "합성 자격", ENTRY_FORMAT + "합성 발급기관", 2020, true);
        entry(7, "LANGUAGE", "합성 시험", ENTRY_FORMAT + "+850", 2020, false);
    }

    private void entry(int revision, String kind, String title, String detail, int year, boolean ended) {
        db.update("""
                INSERT INTO tna.employee_personal_entry(tenant_id,id,employee_id,kind,title,detail,effective_from,effective_to,revision,created_by)
                VALUES (?,gen_random_uuid(),?,?,?,?,?,?,?,?)
                """, tenant, employee.employeeId(), kind, title, detail, LocalDate.of(year, 1, 1),
                ended ? LocalDate.of(year + 1, 1, 1) : null, revision, admin.accountId());
    }

    private record Receipt(UUID actor, UUID target, UUID command, Instant time, String body) { }
}
