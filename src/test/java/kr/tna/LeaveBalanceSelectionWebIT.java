package kr.tna;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import kr.tna.common.auth.PasswordPolicy;
import kr.tna.org.OrgData.Employee;
import kr.tna.request.RequestData.BucketForm;
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
class LeaveBalanceSelectionWebIT {
    private static final String PASSWORD = "Synthetic balance selection 43!";
    private static final String HASH = PasswordPolicy.passwordEncoder().encode(PASSWORD);
    private static final LocalDate TODAY = LocalDate.now(ZoneId.of("Asia/Seoul"));
    @Autowired MockMvc mvc;
    private JdbcTemplate db;
    private UUID tenant, lastEmployee, foreignEmployee;
    private String company;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry p) {
        var fixture = DatabaseFixture.start();
        p.add("spring.datasource.url", fixture::jdbcUrl);
        p.add("spring.datasource.username", () -> DatabaseFixture.APP_USER);
        p.add("spring.datasource.password", () -> DatabaseFixture.APP_PASSWORD);
        p.add("tna.auth.url", fixture::jdbcUrl);
        p.add("tna.auth.username", () -> DatabaseFixture.AUTH_USER);
        p.add("tna.auth.password", () -> DatabaseFixture.AUTH_PASSWORD);
    }

    @BeforeEach
    void companyWithThreeHundredEmployees() {
        db = new JdbcTemplate(DatabaseFixture.start().adminDataSource());
        tenant = UUID.randomUUID();
        company = "BALANCE-" + tenant.toString().toUpperCase(Locale.ROOT);
        db.update("INSERT INTO platform.tenant_registry(id,company_code,display_name) VALUES (?,?,'합성 선택 고객사')", tenant, company);
        db.update("""
                INSERT INTO tna.employee(tenant_id,id,employee_no,display_name,joined_on)
                SELECT ?,gen_random_uuid(),'E'||lpad(n::text,4,'0'),'합성 사원 '||n,DATE '2020-01-01'
                FROM generate_series(1,300) AS n
                """, tenant);
        UUID adminEmployee = employee("E0001");
        lastEmployee = employee("E0300");
        account(adminEmployee, "manager@example.test", true);
        account(lastEmployee, "worker@example.test", false);
        UUID foreignTenant = UUID.randomUUID();
        foreignEmployee = UUID.randomUUID();
        db.update("INSERT INTO platform.tenant_registry(id,company_code,display_name) VALUES (?,?,'타 고객사')", foreignTenant, foreignTenant.toString());
        db.update("INSERT INTO tna.employee(tenant_id,id,employee_no,display_name,joined_on) VALUES (?,?,'FOREIGN-ONLY','타 고객사 사원',DATE '2020-01-01')", foreignTenant, foreignEmployee);
    }

    @Test
    void allThreeHundredEmployeesCanBeSelectedWithoutListPaginationTruncation() throws Exception {
        var result = mvc.perform(get("/admin/leave-balances").session(login("manager@example.test")))
                .andExpect(status().isOk()).andExpect(view().name("request/balances"))
                .andExpect(ReactPageAssertions.data("/employees/299/id", lastEmployee))
                .andReturn();
        var options = options(result.getModelAndView().getModel().get("employees"));
        assertEquals(300, options.size());
        assertEquals(lastEmployee, options.getLast().id());
        assertFalse(options.stream().anyMatch(e -> e.id().equals(foreignEmployee)));
    }

    @Test
    void lastEmployeeCanBeSelectedAndAnOpeningEntryCanBeCreated() throws Exception {
        var session = login("manager@example.test");
        mvc.perform(get("/admin/leave-balances").session(session).param("employeeId", lastEmployee.toString()))
                .andExpect(status().isOk()).andExpect(ReactPageAssertions.data("/form/employeeId", lastEmployee));
        UUID command = UUID.randomUUID();
        mvc.perform(post("/admin/leave-balances/entries").session(session).with(csrf())
                        .param("employeeId", lastEmployee.toString()).param("baseDate", TODAY.toString())
                        .param("usableFrom", TODAY.toString()).param("usableTo", TODAY.plusDays(30).toString())
                        .param("quantityDays", "5").param("sourceReference", "합성 기초 근거")
                        .param("reason", "마지막 사원의 기초 등록").param("commandId", command.toString()))
                .andExpect(status().isSeeOther());
        assertEquals(5, db.queryForObject("""
                SELECT sum(signed_quantity_days)::int FROM tna.leave_ledger
                WHERE tenant_id=? AND employee_id=? AND entry_kind='OPENING'
                """, Integer.class, tenant, lastEmployee));
    }

    @Test
    void invalidOpeningKeepsTheSelectedLateEmployeeAndTheFullSelector() throws Exception {
        var result = mvc.perform(post("/admin/leave-balances/entries").session(login("manager@example.test")).with(csrf())
                        .param("employeeId", lastEmployee.toString()).param("baseDate", TODAY.toString())
                        .param("usableFrom", TODAY.toString()).param("usableTo", TODAY.plusDays(30).toString())
                        .param("quantityDays", "-1").param("sourceReference", "유지할 합성 근거")
                        .param("reason", "음수 검증").param("commandId", UUID.randomUUID().toString()))
                .andExpect(status().isUnprocessableEntity()).andExpect(view().name("request/balances"))
                .andReturn();
        var model = result.getModelAndView().getModel();
        assertEquals(300, options(model.get("employees")).size());
        assertEquals(lastEmployee, ((BucketForm) model.get("form")).employeeId());
        assertEquals("유지할 합성 근거", ((BucketForm) model.get("form")).sourceReference());
        assertEquals(0, db.queryForObject("SELECT count(*) FROM tna.leave_bucket WHERE tenant_id=?", Integer.class, tenant));
    }

    @Test
    void selectorRemainsAdministratorOnlyAndForeignTargetsStayHidden() throws Exception {
        mvc.perform(get("/admin/leave-balances").session(login("worker@example.test")))
                .andExpect(status().isForbidden());
        mvc.perform(get("/admin/leave-balances").session(login("manager@example.test"))
                        .param("employeeId", foreignEmployee.toString()))
                .andExpect(status().isNotFound());
    }

    @SuppressWarnings("unchecked")
    private List<Employee> options(Object value) { return (List<Employee>) value; }

    private UUID employee(String number) {
        return db.queryForObject("SELECT id FROM tna.employee WHERE tenant_id=? AND employee_no=?", UUID.class, tenant, number);
    }

    private void account(UUID employee, String email, boolean administrator) {
        UUID account = UUID.randomUUID();
        db.update("INSERT INTO tna.tenant_account(tenant_id,id,employee_id,email,password_hash) VALUES (?,?,?,?,?)", tenant, account, employee, email, HASH);
        db.update("INSERT INTO tna.role_grant(tenant_id,account_id,role) VALUES (?,?,'EMPLOYEE')", tenant, account);
        if (administrator) db.update("INSERT INTO tna.role_grant(tenant_id,account_id,role) VALUES (?,?,'ATTENDANCE_ADMIN')", tenant, account);
    }

    private MockHttpSession login(String email) throws Exception {
        return (MockHttpSession) mvc.perform(post("/login").with(csrf()).param("companyCode", company)
                        .param("email", email).param("password", PASSWORD))
                .andExpect(redirectedUrl("/me/home")).andReturn().getRequest().getSession(false);
    }
}
