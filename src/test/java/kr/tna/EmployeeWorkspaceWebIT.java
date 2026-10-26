package kr.tna;

import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import kr.tna.common.auth.PasswordPolicy;
import kr.tna.org.OrgData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class EmployeeWorkspaceWebIT {
    private static final String PASSWORD = "Synthetic employee workspace 43!";
    private static final String HASH = PasswordPolicy.passwordEncoder().encode(PASSWORD);
    private static final AtomicInteger ADDRESS = new AtomicInteger(1);
    private static final LocalDate TODAY = LocalDate.now(ZoneId.of("Asia/Seoul"));
    @Autowired MockMvc mvc;
    private JdbcTemplate db;
    private UUID tenant, managerAccount, firstDepartment, secondDepartment, foreignDepartment, foreignEmployee;
    private String company;

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
    void actualTenantEmployeesAndEffectiveDepartments() {
        db = new JdbcTemplate(DatabaseFixture.start().adminDataSource());
        tenant = UUID.randomUUID();
        company = "WORKSPACE-" + tenant;
        db.update("INSERT INTO platform.tenant_registry(id,company_code,display_name) VALUES (?,?,'실제 조회 합성 고객사')",
                tenant, company.toUpperCase(java.util.Locale.ROOT));
        db.update("""
                INSERT INTO tna.employee(tenant_id,id,employee_no,display_name,joined_on,active)
                SELECT ?,gen_random_uuid(),'E'||lpad(n::text,4,'0'),'실제 조회 직원 '||n,DATE '2020-01-01',n<>53
                FROM generate_series(1,53) AS n
                """, tenant);
        managerAccount = account(employee("E0001"), "manager@example.test", true);
        account(employee("E0002"), "worker@example.test", false);
        firstDepartment = organization(tenant, managerAccount, "FIRST", "실제 개발 조직");
        secondDepartment = organization(tenant, managerAccount, "SECOND", "실제 운영 조직");
        db.update("""
                INSERT INTO tna.employee_assignment
                (tenant_id,id,employee_id,organization_id,valid_from,reason,created_by,updated_by)
                SELECT ?,gen_random_uuid(),id,CASE WHEN employee_no<='E0026' THEN ?::uuid ELSE ?::uuid END,
                       ?,'합성 현재 소속',?,?
                FROM tna.employee WHERE tenant_id=?
                """, tenant, firstDepartment, secondDepartment, TODAY, managerAccount, managerAccount, tenant);
        db.update("UPDATE tna.employee_assignment SET valid_to=? WHERE tenant_id=? AND employee_id=?",
                TODAY.plusDays(1), tenant, employee("E0003"));
        db.update("""
                INSERT INTO tna.employee_assignment
                (tenant_id,id,employee_id,organization_id,valid_from,reason,created_by,updated_by)
                VALUES (?,gen_random_uuid(),?,?,?,'합성 미래 소속',?,?)
                """, tenant, employee("E0003"), secondDepartment, TODAY.plusDays(1), managerAccount, managerAccount);
        UUID foreignTenant = UUID.randomUUID(), foreignAccount = UUID.randomUUID();
        foreignEmployee = UUID.randomUUID();
        db.update("INSERT INTO platform.tenant_registry(id,company_code,display_name) VALUES (?,?,'외부 합성 고객사')",
                foreignTenant, foreignTenant.toString());
        db.update("INSERT INTO tna.employee(tenant_id,id,employee_no,display_name,joined_on) VALUES (?,?,'PRIVATE-FOREIGN','외부 비공개 직원',DATE '2020-01-01')",
                foreignTenant, foreignEmployee);
        db.update("INSERT INTO tna.tenant_account(tenant_id,id,employee_id,email,password_hash) VALUES (?,?,?,'foreign@example.test',?)",
                foreignTenant, foreignAccount, foreignEmployee, HASH);
        foreignDepartment = organization(foreignTenant, foreignAccount, "PRIVATE", "외부 비공개 조직");
    }

    @Test
    void workspaceUsesRealFirstPageCountsDepartmentsAndExistingRegistrationForm() throws Exception {
        mvc.perform(get("/admin/employees").session(login("manager@example.test")))
                .andExpect(status().isOk()).andExpect(view().name("org/employees"))
                .andExpect(model().attribute("employees", hasSize(50)))
                .andExpect(model().attribute("hasNext", true))
                .andExpect(model().attribute("pageEmployeeCount", 50))
                .andExpect(model().attribute("pageActiveCount", 50))
                .andExpect(model().attribute("pageInactiveCount", 0))
                .andExpect(content().string(containsString("workspace-body")))
                .andExpect(content().string(containsString("t-employee-layout")))
                .andExpect(content().string(containsString("현재 페이지")))
                .andExpect(content().string(containsString("실제 개발 조직")))
                .andExpect(content().string(containsString("name=\"organizationId\"")))
                .andExpect(content().string(containsString("name=\"status\"")))
                .andExpect(content().string(containsString("name=\"commandId\"")))
                .andExpect(content().string(containsString("name=\"_csrf\"")))
                .andExpect(content().string(containsString("로그인 계정은 자동으로 생성되지 않습니다")))
                .andExpect(content().string(not(anyOf(containsString("김하늘"), containsString("haneul@tna.example.test"),
                        containsString("외부 비공개 직원"), containsString("외부 비공개 조직")))));
    }

    @Test
    void pagingAndSearchKeepTheirScopeAndCanSelectTheLastRealEmployee() throws Exception {
        mvc.perform(get("/admin/employees").session(login("manager@example.test")).param("page", "1"))
                .andExpect(status().isOk()).andExpect(model().attribute("employees", hasSize(3)))
                .andExpect(model().attribute("hasNext", false))
                .andExpect(model().attribute("pageEmployeeCount", 3))
                .andExpect(model().attribute("pageActiveCount", 2))
                .andExpect(model().attribute("pageInactiveCount", 1));
        var selected = mvc.perform(get("/admin/employees").session(login("manager@example.test"))
                        .param("q", "E0053").param("employeeId", employee("E0053").toString()))
                .andExpect(status().isOk()).andExpect(model().attribute("employees", hasSize(1)))
                .andExpect(model().attributeExists("selectedEmployee"))
                .andExpect(content().string(containsString("실제 조회 직원 53")))
                .andExpect(content().string(containsString("비활성")))
                .andExpect(content().string(containsString("/admin/employees/" + employee("E0053"))))
                .andReturn();
        assertEquals(employee("E0053"), ((OrgData.Employee) selected.getModelAndView()
                .getModel().get("selectedEmployee")).id());
    }

    @Test
    void departmentAndActiveFiltersApplyBeforePagingAndIgnoreFutureAssignments() throws Exception {
        var session = login("manager@example.test");
        mvc.perform(get("/admin/employees").session(session).param("organizationId", firstDepartment.toString())
                        .param("employeeId", employee("E0003").toString()))
                .andExpect(status().isOk()).andExpect(model().attribute("employees", hasSize(26)))
                .andExpect(model().attribute("hasNext", false))
                .andExpect(model().attribute("employeeOrganizations", hasEntry(employee("E0003"), "실제 개발 조직")))
                .andExpect(content().string(containsString("실제 조회 직원 3")));
        mvc.perform(get("/admin/employees").session(session).param("organizationId", secondDepartment.toString())
                        .param("status", "inactive"))
                .andExpect(status().isOk()).andExpect(model().attribute("employees", hasSize(1)))
                .andExpect(model().attribute("pageInactiveCount", 1))
                .andExpect(content().string(containsString("실제 조회 직원 53")));
        mvc.perform(get("/admin/employees").session(session).param("q", "no-such-employee"))
                .andExpect(status().isOk()).andExpect(model().attribute("employees", hasSize(0)))
                .andExpect(model().attribute("pageEmployeeCount", 0))
                .andExpect(content().string(containsString("검색 결과가 없습니다")));
    }

    @Test
    void foreignSelectionAndDepartmentStayHiddenDespiteForgedTenantValues() throws Exception {
        var session = login("manager@example.test");
        for (String key : List.of("employeeId", "organizationId")) {
            String value = key.equals("employeeId") ? foreignEmployee.toString() : foreignDepartment.toString();
            mvc.perform(get("/admin/employees").session(session).param(key, value)
                            .param("tenant_id", UUID.randomUUID().toString()))
                    .andExpect(status().isNotFound())
                    .andExpect(content().string(not(anyOf(containsString("외부 비공개 직원"), containsString("외부 비공개 조직")))));
        }
        mvc.perform(get("/admin/employees").session(session).param("status", "unsupported"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void ordinaryEmployeesCannotUseDirectorySelectionOrFilters() throws Exception {
        mvc.perform(get("/admin/employees").session(login("worker@example.test"))
                        .param("employeeId", employee("E0053").toString())
                        .param("organizationId", firstDepartment.toString()).param("status", "inactive"))
                .andExpect(status().isForbidden());
    }

    @Test
    void registrationValidationAndExistingDetailEditingKeepCsrfVersionAndInput() throws Exception {
        var session = login("manager@example.test");
        UUID command = UUID.randomUUID();
        String maliciousName = "<img src=x onerror=alert(1)>";
        mvc.perform(post("/admin/employees").session(session)).andExpect(status().isForbidden());
        var result = mvc.perform(post("/admin/employees").session(session).with(csrf())
                        .param("employeeNo", "NEW-EMPLOYEE").param("displayName", maliciousName)
                        .param("joinedOn", "2026-01-01").param("active", "true")
                        .param("reason", " ").param("commandId", command.toString()))
                .andExpect(status().isUnprocessableEntity()).andExpect(view().name("org/employees"))
                .andExpect(content().string(containsString("workspace-body")))
                .andExpect(content().string(containsString("role=\"alert\"")))
                .andExpect(content().string(not(containsString(maliciousName))))
                .andReturn();
        var form = (OrgData.EmployeeForm) result.getModelAndView().getModel().get("form");
        assertEquals(command, form.commandId());
        assertEquals(maliciousName, form.displayName());
        mvc.perform(get("/admin/employees/{id}", employee("E0053")).session(session))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("name=\"expectedVersion\"")))
                .andExpect(content().string(containsString("name=\"commandId\"")))
                .andExpect(content().string(containsString("name=\"_csrf\"")));
    }

    private UUID employee(String number) {
        return db.queryForObject("SELECT id FROM tna.employee WHERE tenant_id=? AND employee_no=?", UUID.class, tenant, number);
    }

    private UUID account(UUID employee, String email, boolean administrator) {
        UUID account = UUID.randomUUID();
        db.update("INSERT INTO tna.tenant_account(tenant_id,id,employee_id,email,password_hash) VALUES (?,?,?,?,?)",
                tenant, account, employee, email, HASH);
        db.update("INSERT INTO tna.role_grant(tenant_id,account_id,role) VALUES (?,?,'EMPLOYEE')", tenant, account);
        if (administrator) db.update("INSERT INTO tna.role_grant(tenant_id,account_id,role) VALUES (?,?,'ATTENDANCE_ADMIN')", tenant, account);
        return account;
    }

    private UUID organization(UUID targetTenant, UUID actorAccount, String code, String name) {
        UUID id = UUID.randomUUID();
        db.update("INSERT INTO tna.organization(tenant_id,id,code,name,created_by,updated_by) VALUES (?,?,?,?,?,?)",
                targetTenant, id, code, name, actorAccount, actorAccount);
        return id;
    }

    private MockHttpSession login(String email) throws Exception {
        return (MockHttpSession) mvc.perform(post("/login").with(csrf())
                        .with(request -> { request.setRemoteAddr("198.18.22." + ADDRESS.getAndIncrement()); return request; })
                        .param("companyCode", company).param("email", email).param("password", PASSWORD))
                .andExpect(redirectedUrl("/me/today")).andReturn().getRequest().getSession(false);
    }
}
