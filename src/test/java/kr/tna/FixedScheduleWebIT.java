package kr.tna;

import static org.junit.jupiter.api.Assertions.*;
import static org.hamcrest.Matchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@SpringBootTest @AutoConfigureMockMvc
class FixedScheduleWebIT {
    private static final String PASSWORD="Synthetic schedule web 43!";
    @Autowired MockMvc mvc;
    @DynamicPropertySource static void db(DynamicPropertyRegistry r) {
        var db=DatabaseFixture.start();
        r.add("spring.datasource.url",db::jdbcUrl);r.add("spring.datasource.username",()->DatabaseFixture.APP_USER);r.add("spring.datasource.password",()->DatabaseFixture.APP_PASSWORD);
        r.add("tna.auth.url",db::jdbcUrl);r.add("tna.auth.username",()->DatabaseFixture.AUTH_USER);r.add("tna.auth.password",()->DatabaseFixture.AUTH_PASSWORD);
    }
    @BeforeEach void seed() {
        var db=DatabaseFixture.start();db.seedIdentity(kr.tna.common.auth.PasswordPolicy.passwordEncoder().encode(PASSWORD));
        new JdbcTemplate(db.adminDataSource()).update("INSERT INTO tna.role_grant(tenant_id,account_id,role) VALUES (?,?,'ATTENDANCE_ADMIN')",DatabaseFixture.TENANT_A,DatabaseFixture.ACCOUNT_A);
    }
    @Test void administratorCanOpenTemplates() throws Exception {
        mvc.perform(get("/admin/schedules").session(login(true))).andExpect(status().isOk()).andExpect(view().name("schedule/list"))
            .andExpect(ReactPageAssertions.page()).andExpect(ReactPageAssertions.csrf());
    }
    @Test void employeeCannotOpenOrCreateTemplates() throws Exception {
        var s=login(false);mvc.perform(get("/admin/schedules").session(s)).andExpect(status().isForbidden());
        mvc.perform(form(s,"/admin/schedules")).andExpect(status().isForbidden());
    }
    @Test void templateVersionAndEmployeeAssignmentAreConnected() throws Exception {
        var s=login(true);var created=mvc.perform(form(s,"/admin/schedules")).andExpect(status().isSeeOther()).andReturn();
        String detail=created.getResponse().getRedirectedUrl();assertNotNull(detail);
        var page=mvc.perform(get(detail).session(s)).andExpect(status().isOk()).andExpect(ReactPageAssertions.page()).andReturn();
        var versions=(java.util.List<?>)page.getModelAndView().getModel().get("versions");
        assertEquals(1,versions.size());UUID version=((kr.tna.schedule.ScheduleData.TemplateVersion)versions.getFirst()).id();
        var admin=new JdbcTemplate(DatabaseFixture.start().adminDataSource());
        long expected=admin.queryForObject("SELECT version FROM tna.employee WHERE tenant_id=? AND id=?",Long.class,DatabaseFixture.TENANT_A,DatabaseFixture.EMPLOYEE_A);
        mvc.perform(post("/admin/employees/{id}/schedule",DatabaseFixture.EMPLOYEE_A).session(s).with(csrf())
            .param("scheduleVersionId",version.toString()).param("validFrom",today().plusDays(2).toString()).param("expectedVersion",Long.toString(expected))
            .param("reason","합성 배정").param("commandId",UUID.randomUUID().toString())).andExpect(status().isSeeOther());
        mvc.perform(get("/admin/employees/{id}/schedule",DatabaseFixture.EMPLOYEE_A).session(s)).andExpect(status().isOk())
            .andExpect(content().string(containsString("합성 배정")));
    }
    @Test void invalidDaysPreserveInputAndCsrfIsRequired() throws Exception {
        var s=login(true);
        mvc.perform(form(s,"/admin/schedules").with(r->{r.setParameter("days[0].endTime","08:00");return r;})).andExpect(status().is(422))
            .andExpect(view().name("schedule/list")).andExpect(content().string(containsString("합성 고정근무")));
        mvc.perform(post("/admin/schedules").session(s)).andExpect(status().isForbidden());
    }
    @Test void foreignEmployeeIsHiddenAndMalformedIdIsSafe() throws Exception {
        var s=login(true);mvc.perform(get("/admin/employees/{id}/schedule",DatabaseFixture.EMPLOYEE_B).session(s)).andExpect(status().isNotFound());
        mvc.perform(get("/admin/schedules/not-a-uuid").session(s)).andExpect(status().isBadRequest()).andExpect(view().name("org/problem"))
            .andExpect(content().string(not(containsString("java.lang"))));
    }
    private MockHttpSession login(boolean manager) throws Exception {
        return (MockHttpSession)mvc.perform(post("/login").with(csrf()).param("companyCode",manager?DatabaseFixture.COMPANY_A:DatabaseFixture.COMPANY_B)
            .param("email",DatabaseFixture.EMAIL).param("password",PASSWORD)).andExpect(redirectedUrl("/me/profile")).andReturn().getRequest().getSession(false);
    }
    private MockHttpServletRequestBuilder form(MockHttpSession s,String path) {
        var request=post(path).session(s).with(csrf()).param("code",UUID.randomUUID().toString()).param("name","합성 고정근무")
            .param("validFrom",today().toString()).param("reason","합성 일정 등록").param("commandId",UUID.randomUUID().toString());
        for(int i=0;i<7;i++) {
            request.param("days["+i+"].weekday",Integer.toString(i+1)).param("days["+i+"].workday",Boolean.toString(i<5))
                .param("days["+i+"].startTime",i<5?"09:00":"").param("days["+i+"].endTime",i<5?"18:00":"")
                .param("days["+i+"].breaks",i<5?"12:00-13:00":"");
        }
        return request;
    }
    private LocalDate today() {return LocalDate.now(ZoneId.of("Asia/Seoul"));}
}
