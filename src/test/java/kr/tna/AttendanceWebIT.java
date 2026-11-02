package kr.tna;
import static org.junit.jupiter.api.Assertions.*;
import static org.hamcrest.Matchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import java.util.UUID;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.MockMvc;
@SpringBootTest @AutoConfigureMockMvc
class AttendanceWebIT {
    static final String PASSWORD="Synthetic clock web 43!";
    @Autowired MockMvc mvc;
    @DynamicPropertySource static void db(DynamicPropertyRegistry r) {var d=DatabaseFixture.start();r.add("spring.datasource.url",d::jdbcUrl);r.add("spring.datasource.username",()->DatabaseFixture.APP_USER);r.add("spring.datasource.password",()->DatabaseFixture.APP_PASSWORD);r.add("tna.auth.url",d::jdbcUrl);r.add("tna.auth.username",()->DatabaseFixture.AUTH_USER);r.add("tna.auth.password",()->DatabaseFixture.AUTH_PASSWORD);}
    @BeforeEach void seed() {var d=DatabaseFixture.start();d.seedIdentity(kr.tna.common.auth.PasswordPolicy.passwordEncoder().encode(PASSWORD));new JdbcTemplate(d.adminDataSource()).update("INSERT INTO tna.role_grant(tenant_id,account_id,role) VALUES (?,?,'ATTENDANCE_ADMIN')",DatabaseFixture.TENANT_A,DatabaseFixture.ACCOUNT_A);}
    @Test void administratorCanOpenNetworkAndCalendarSettings() throws Exception {var s=login(true);for(String p:java.util.List.of("/admin/networks","/admin/calendar"))mvc.perform(get(p).session(s)).andExpect(status().isOk()).andExpect(ReactPageAssertions.csrf());}
    @Test void employeeCannotManageNetworksAndAllWritesRequireCsrf() throws Exception {
        var s=login(false);mvc.perform(get("/admin/networks").session(s)).andExpect(status().isForbidden());
        mvc.perform(post("/me/attendance/events").session(s).param("eventKind","IN")).andExpect(status().isForbidden());
        mvc.perform(post("/admin/networks/changes").session(s).with(csrf())).andExpect(status().isForbidden());
    }
    @Test void todayBookmarkRedirectsToAttendanceAndCurrentDayProvidesCommands() throws Exception {
        var s=login(false);
        mvc.perform(get("/me/today").session(s)).andExpect(redirectedUrl("/me/attendance"));
        mvc.perform(get("/me/attendance").session(s)).andExpect(status().isOk())
                .andExpect(ReactPageAssertions.page()).andExpect(ReactPageAssertions.data("/isToday",true))
                .andExpect(ReactPageAssertions.dataPresent("/inCommand")).andExpect(ReactPageAssertions.dataPresent("/outCommand"));
        mvc.perform(get("/me/attendance").session(s).param("date","2026-01-01"))
                .andExpect(status().isOk()).andExpect(ReactPageAssertions.data("/isToday",false))
                .andExpect(model().attributeDoesNotExist("inCommand","outCommand"));
    }
    @Test void forwardedHeaderCannotBypassNetworkPolicy() throws Exception {
        var s=login(false);mvc.perform(post("/me/attendance/events").session(s).with(csrf()).header("X-Forwarded-For","127.0.0.1")
            .with(r->{r.setRemoteAddr("198.51.100.7");return r;}).param("eventKind","IN").param("commandId",UUID.randomUUID().toString()))
            .andExpect(status().isForbidden());
    }
    @Test void validatedLocalNetworkRecordsOnlyTheAuthenticatedEmployee() throws Exception {
        var manager=login(true);
        mvc.perform(post("/admin/networks/changes").session(manager).with(csrf()).param("network","127.0.0.1/32").param("label","합성 웹 테스트").param("_wideConfirmed","on")
            .param("reason","합성 등록").param("commandId",UUID.randomUUID().toString())).andExpect(status().isSeeOther());
        mvc.perform(post("/me/attendance/events").session(manager).with(csrf()).with(r->{r.setRemoteAddr("127.0.0.1");return r;})
            .param("eventKind","IN").param("commandId",UUID.randomUUID().toString()).param("employeeId",DatabaseFixture.EMPLOYEE_B.toString()).param("tenantId",DatabaseFixture.TENANT_B.toString()))
            .andExpect(status().isSeeOther()).andExpect(redirectedUrl("/me/attendance"));
        var jdbc=new JdbcTemplate(DatabaseFixture.start().adminDataSource());
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM tna.attendance_event WHERE tenant_id=? AND employee_id=?",Integer.class,DatabaseFixture.TENANT_A,DatabaseFixture.EMPLOYEE_A));
    }
    private MockHttpSession login(boolean admin) throws Exception {return (MockHttpSession)mvc.perform(post("/login").with(csrf()).param("companyCode",admin?DatabaseFixture.COMPANY_A:DatabaseFixture.COMPANY_B).param("email",DatabaseFixture.EMAIL).param("password",PASSWORD)).andExpect(redirectedUrl("/me/profile")).andReturn().getRequest().getSession(false);}
}
