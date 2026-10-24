package kr.tna;

import static org.hamcrest.Matchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import kr.tna.clock.AttendanceSettingsData.NetworkForm;
import kr.tna.clock.AttendanceSettingsService;
import kr.tna.clock.ClockService;
import kr.tna.common.auth.PasswordPolicy;
import kr.tna.common.auth.TnaPrincipal;
import kr.tna.org.AccountData.ApproverForm;
import kr.tna.org.ApproverService;
import kr.tna.org.OrgCommands;
import kr.tna.request.LeaveWorkflowService;
import kr.tna.request.RequestData.CorrectionForm;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties="tna.operator.enabled=true")
@AutoConfigureMockMvc
@ActiveProfiles("local")
class SeoulTimeDisplayWebIT {
    private static final String PASSWORD="Synthetic display web account 43!";
    @Autowired MockMvc mvc;
    @Autowired ApproverService approvers;
    @Autowired ClockService clocks;
    @Autowired LeaveWorkflowService workflow;
    @Autowired AttendanceSettingsService settings;
    private JdbcTemplate db;
    private String company;
    private UUID tenant;
    private TnaPrincipal manager,worker;

    @DynamicPropertySource static void database(DynamicPropertyRegistry p){
        var f=DatabaseFixture.start();
        p.add("spring.datasource.url",f::jdbcUrl);p.add("spring.datasource.username",()->DatabaseFixture.APP_USER);p.add("spring.datasource.password",()->DatabaseFixture.APP_PASSWORD);
        p.add("tna.auth.url",f::jdbcUrl);p.add("tna.auth.username",()->DatabaseFixture.AUTH_USER);p.add("tna.auth.password",()->DatabaseFixture.AUTH_PASSWORD);
        p.add("tna.operator.url",f::jdbcUrl);p.add("tna.operator.password",()->"synthetic-operator-db-password-only");
    }
    @BeforeEach void identities(){
        db=new JdbcTemplate(DatabaseFixture.start().adminDataSource());tenant=UUID.randomUUID();company="DISPLAY-"+tenant.toString().toUpperCase(Locale.ROOT);
        db.update("INSERT INTO platform.tenant_registry(id,company_code,display_name) VALUES (?,?,'합성 표시 회사')",tenant,company);
        String hash=PasswordPolicy.passwordEncoder().encode(PASSWORD);
        manager=member("M-1",Set.of("EMPLOYEE","ATTENDANCE_ADMIN","APPROVER"),hash);
        worker=member("W-1",Set.of("EMPLOYEE"),hash);
    }

    @Test void correctionDetailUsesSeoulDatesMillisecondsAndReadableBreaksWithoutRawJson()throws Exception{
        LocalDate date=LocalDate.of(2025,12,31);
        approvers.set(manager,new ApproverForm(worker.employeeId(),manager.employeeId(),LocalDate.now(ZoneOffset.ofHours(9)),0L,"합성 승인자",UUID.randomUUID()));
        var day=clocks.day(worker,date);
        UUID request=workflow.submitCorrection(worker,new CorrectionForm(null,date,day.inputRevision(),day.sourceFingerprint(),
                "SET","2025-12-31T23:30:00.123","SET","2025-12-31T23:59:59.987",true,true,
                "2025-12-31T23:40:00.234/2025-12-31T23:45:00.345",null,null,false,"합성 표시 정정",UUID.randomUUID()));
        db.update("UPDATE tna.approval_event SET created_at=? WHERE tenant_id=? AND request_id=?",Timestamp.from(Instant.parse("2025-12-31T15:00:00.456Z")),tenant,request);
        db.update("UPDATE tna.correction_detail SET actual_breaks=CAST(? AS jsonb) WHERE tenant_id=? AND request_id=?",
                "[{\"startAt\":\"2025-12-31T14:40:00.234Z\",\"endAt\":\"2025-12-31T14:45:00.345Z\",\"secret\":\"DO_NOT_REFLECT\"}]",tenant,request);
        mvc.perform(get("/requests/{id}",request).session(customer(worker)).locale(Locale.GERMANY))
                .andExpect(status().isOk()).andExpect(content().string(containsString("Asia/Seoul")))
                .andExpect(content().string(containsString("2025-12-31")))
                .andExpect(content().string(containsString("2025-12-31 23:30:00.123")))
                .andExpect(content().string(containsString("2025-12-31 23:59:59.987")))
                .andExpect(content().string(containsString("2025-12-31 23:40:00.234 ~ 2025-12-31 23:45:00.345")))
                .andExpect(content().string(containsString("2026-01-01 00:00:00.456")))
                .andExpect(content().string(not(containsString("DO_NOT_REFLECT"))))
                .andExpect(content().string(not(containsString("2025-12-31T14:30:00.123Z"))));
    }

    @Test void operatorAuditUsesSeoulMillisecondsWithoutCreatingACustomerSession()throws Exception{
        UUID operator=UUID.randomUUID();String login="display-"+operator;
        db.update("INSERT INTO platform.operator_account(id,login_id,password_hash) VALUES (?,?,?)",operator,login,PasswordPolicy.passwordEncoder().encode(PASSWORD));
        Instant at=Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS).minusSeconds(1).plusMillis(123);
        db.update("INSERT INTO platform.operator_audit_event(id,operator_id,command_id,action,input_hash,reason,summary,created_at) VALUES (?,?,?,'DISPLAY_TIME',?,'합성 시각 표시','{}'::jsonb,?)",
                UUID.randomUUID(),operator,UUID.randomUUID(),OrgCommands.fingerprint("display",operator),Timestamp.from(at));
        var session=(MockHttpSession)mvc.perform(post("/operator/login").with(csrf()).param("loginId",login).param("password",PASSWORD))
                .andExpect(redirectedUrl("/operator/tenants")).andReturn().getRequest().getSession(false);
        String expected=DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss.SSS",Locale.ROOT).format(at.atOffset(ZoneOffset.ofHours(9)));
        mvc.perform(get("/operator/tenants").session(session).locale(Locale.GERMANY))
                .andExpect(status().isOk()).andExpect(content().string(containsString("Asia/Seoul")))
                .andExpect(content().string(containsString(expected))).andExpect(content().string(not(containsString(at.toString()))));
        mvc.perform(get("/me/today").session(session)).andExpect(redirectedUrl("/login"));
    }

    @Test void networkHistoryShowsBothSeoulEffectiveBoundsIncludingTheNewYear()throws Exception{
        UUID network=settings.addNetwork(manager,new NetworkForm("127.0.0.1/32","합성 종료 허용망",false,"합성 표시",UUID.randomUUID()));
        db.update("UPDATE tna.allowed_network SET valid_from=?,valid_to=? WHERE tenant_id=? AND id=?",
                Timestamp.from(Instant.parse("2025-12-31T14:30:00.123Z")),Timestamp.from(Instant.parse("2025-12-31T15:00:00.456Z")),tenant,network);
        mvc.perform(get("/admin/networks").session(customer(manager)).locale(Locale.GERMANY))
                .andExpect(status().isOk()).andExpect(content().string(containsString("Asia/Seoul")))
                .andExpect(content().string(containsString("2025-12-31 23:30:00.123")))
                .andExpect(content().string(containsString("2026-01-01 00:00:00.456")))
                .andExpect(content().string(not(containsString("2025-12-31T15:00:00.456Z"))));
    }

    @Test void visibleEmployeeDatesRemainIsoUnderAnotherRequestLocale()throws Exception{
        mvc.perform(get("/admin/employees").session(customer(manager)).locale(Locale.GERMANY))
                .andExpect(status().isOk()).andExpect(content().string(containsString(">2020-01-02</time>")));
    }

    private TnaPrincipal member(String number,Set<String> roles,String hash){
        UUID employee=UUID.randomUUID(),account=UUID.randomUUID();
        db.update("INSERT INTO tna.employee(tenant_id,id,employee_no,display_name,joined_on) VALUES (?,?,?,'합성 표시 사용자',DATE '2020-01-02')",tenant,employee,number);
        db.update("INSERT INTO tna.tenant_account(tenant_id,id,employee_id,email,password_hash) VALUES (?,?,?,?,?)",tenant,account,employee,account+"@example.test",hash);
        for(String role:roles)db.update("INSERT INTO tna.role_grant(tenant_id,account_id,role) VALUES (?,?,?)",tenant,account,role);
        return new TnaPrincipal(tenant,account,employee,1,roles,Instant.now());
    }
    private MockHttpSession customer(TnaPrincipal actor)throws Exception{
        return(MockHttpSession)mvc.perform(post("/login").with(csrf()).param("companyCode",company).param("email",actor.accountId()+"@example.test").param("password",PASSWORD))
                .andExpect(redirectedUrl("/me/today")).andReturn().getRequest().getSession(false);
    }
}
