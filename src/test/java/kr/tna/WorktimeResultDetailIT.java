package kr.tna;

import static org.junit.jupiter.api.Assertions.*;
import static org.hamcrest.Matchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import java.time.*;
import java.util.*;
import kr.tna.common.auth.*;
import kr.tna.org.OrgException;
import kr.tna.worktime.*;
import kr.tna.worktime.WorktimeData.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest @AutoConfigureMockMvc
class WorktimeResultDetailIT {
    static final String PASSWORD="Synthetic detail credential 43!";
    @Autowired WorktimeService reports; @Autowired MockMvc mvc;
    private JdbcTemplate db;private UUID tenant;private String company;
    private TnaPrincipal manager,employee,other;private LocalDate date;
    @DynamicPropertySource static void database(DynamicPropertyRegistry p){var f=DatabaseFixture.start();p.add("spring.datasource.url",f::jdbcUrl);p.add("spring.datasource.username",()->DatabaseFixture.APP_USER);p.add("spring.datasource.password",()->DatabaseFixture.APP_PASSWORD);p.add("tna.auth.url",f::jdbcUrl);p.add("tna.auth.username",()->DatabaseFixture.AUTH_USER);p.add("tna.auth.password",()->DatabaseFixture.AUTH_PASSWORD);}
    @BeforeEach void facts(){
        db=new JdbcTemplate(DatabaseFixture.start().adminDataSource());tenant=UUID.randomUUID();company="DETAIL-"+tenant.toString().toUpperCase(Locale.ROOT);date=LocalDate.now(ZoneId.of("Asia/Seoul")).minusDays(1);
        db.update("INSERT INTO platform.tenant_registry(id,company_code,display_name) VALUES (?,?,'합성 상세 회사')",tenant,company);
        manager=member("manager",Set.of("EMPLOYEE","ATTENDANCE_ADMIN"));employee=member("employee",Set.of("EMPLOYEE"));other=member("other",Set.of("EMPLOYEE"));
    }
    @Test void ownerAndManagerSeeTheStoredEvidenceWithoutCreatingNewResults() throws Exception {
        var policy=policy("PRIVATE_POLICY_BASIS_MUST_NOT_RENDER");UUID id=result();
        int count=results();var detail=reports.detail(employee,id);
        assertNotNull(detail);assertEquals(id,detail.day().id());assertTrue(detail.day().current());assertTrue(detail.latestVersion());
        assertEquals(policy,detail.policy().id());assertEquals(LocalDate.of(2026,1,1),detail.employment().joinedOn());
        assertNotNull(detail.clock());assertEquals(date,detail.clock().workDate());assertEquals(detail.day().runId(),detail.run().id());
        assertTrue(detail.completeEvidence());assertNull(detail.day().result().actualWorkMillis());
        assertEquals(count,results());assertEquals(id,reports.detail(manager,id).day().id());
        mvc.perform(get("/worktime/results/{id}",id).session(login(employee))).andExpect(status().isOk())
            .andExpect(ReactPageAssertions.page())
            .andExpect(ReactPageAssertions.data("/month", YearMonth.from(date).toString()))
            .andExpect(content().string(not(containsString("PRIVATE_POLICY_BASIS_MUST_NOT_RENDER")))).andExpect(content().string(not(containsString("employee@example.test"))))
            .andExpect(header().string("Cache-Control",containsString("no-store")));
        mvc.perform(get("/worktime/results/{id}",id).session(login(manager))).andExpect(status().isOk())
            .andExpect(ReactPageAssertions.data("/detail/day/employeeId", employee.employeeId()));
    }
    @Test void changedPolicyAndEmploymentKeepTheOriginalEvidenceAndMarkItsSourceStale(){
        var first=policy("당시 근거");UUID id=result();policy("변경된 근거");
        db.update("UPDATE tna.employee SET joined_on=DATE '2026-01-02' WHERE tenant_id=? AND id=?",tenant,employee.employeeId());
        var detail=reports.detail(manager,id);assertFalse(detail.day().current());assertTrue(detail.latestVersion());
        assertEquals(first,detail.policy().id());assertEquals(LocalDate.of(2026,1,1),detail.employment().joinedOn());
    }
    @Test void sameInputRecalculationSeparatesTheOldResultFromSourceFreshness(){
        UUID first=result(),latest=result();assertNotEquals(first,latest);
        var old=reports.detail(employee,first);assertTrue(old.day().current());assertFalse(old.latestVersion());assertEquals(latest,old.latestResultId());
        assertEquals(first,old.day().id());assertTrue(reports.detail(employee,latest).latestVersion());
    }
    @Test void anotherEmployeeAndAnotherTenantCannotReadOrProbeTheResult() throws Exception {
        UUID id=result();assertStatus(404,()->reports.detail(other,id));assertStatus(404,()->reports.detail(employee,UUID.randomUUID()));
        UUID foreignTenant=UUID.randomUUID();db.update("INSERT INTO platform.tenant_registry(id,company_code,display_name) VALUES (?,?,'합성 다른 회사')",foreignTenant,"FOREIGN-"+foreignTenant);
        UUID ownTenant=tenant;tenant=foreignTenant;var foreign=member("foreign",Set.of("EMPLOYEE","ATTENDANCE_ADMIN"));tenant=ownTenant;
        assertStatus(404,()->reports.detail(foreign,id));
        mvc.perform(get("/worktime/results/{id}",id).session(login(other))).andExpect(status().isNotFound());
        mvc.perform(get("/worktime/results/{id}",id)).andExpect(redirectedUrl("/login"));
    }
    @Test void revokedManagerCannotUseAnOldPrincipal(){
        UUID id=result();db.update("UPDATE tna.role_grant SET active=false WHERE tenant_id=? AND account_id=? AND role='ATTENDANCE_ADMIN'",tenant,manager.accountId());
        assertStatus(403,()->reports.detail(manager,id));
    }
    @Test void missingHistoricalInputIsExplicitAndNeverFilledFromTheCurrentClock(){
        UUID id=result();db.update("UPDATE tna.day_result SET input_refs='[]'::jsonb WHERE tenant_id=? AND id=?",tenant,id);
        var detail=reports.detail(employee,id);assertFalse(detail.completeEvidence());assertNull(detail.clock());assertNull(detail.policy());assertNull(detail.employment());
        assertNull(detail.day().result().actualWorkMillis());
    }
    @Test void missingStoredEventListsAndConfirmationBreaksAreMarkedAsIncomplete(){
        UUID id=result();String original=db.queryForObject("SELECT input_refs::text FROM tna.day_result WHERE tenant_id=? AND id=?",String.class,tenant,id);
        for(String field:List.of("events","effectiveEvents")){
            db.update("UPDATE tna.day_result SET input_refs=jsonb_set(CAST(? AS jsonb),CAST(? AS text[]),'null'::jsonb) WHERE tenant_id=? AND id=?",original,"{0,clock,"+field+"}",tenant,id);
            var detail=reports.detail(employee,id);assertFalse(detail.completeEvidence(),field+" 누락은 확인된 사건 없음과 다릅니다.");assertNull(detail.clock());
            assertNull(detail.day().result().actualWorkMillis());
        }
        String confirmation="{\"id\":\""+UUID.randomUUID()+"\",\"confirmedInputRevision\":0,\"reviewRevision\":0,\"presenceAsWork\":true,\"breaksMatch\":true,\"sourceFingerprint\":\""+"0".repeat(64)+"\",\"breaks\":null,\"confirmedAt\":\""+Instant.now()+"\"}";
        db.update("UPDATE tna.day_result SET input_refs=jsonb_set(CAST(? AS jsonb),'{0,clock,confirmation}',CAST(? AS jsonb)) WHERE tenant_id=? AND id=?",original,confirmation,tenant,id);
        var incomplete=reports.detail(employee,id);assertFalse(incomplete.completeEvidence(),"휴게 목록 누락을 확인된 휴게0으로 표시하면 안 됩니다.");assertNull(incomplete.clock());
    }
    @Test void missingOrMalformedPolicyReferencesAndForeignAssessmentAreIncomplete(){
        UUID id=result();String original=db.queryForObject("SELECT input_refs::text FROM tna.day_result WHERE tenant_id=? AND id=?",String.class,tenant,id);
        assertTrue(reports.detail(employee,id).completeEvidence(),"명시된 미검토 null은 정상 미확인 근거입니다.");
        for(String field:List.of("policy","assessment")){
            db.update("UPDATE tna.day_result SET input_refs=CAST(? AS jsonb)#-CAST(? AS text[]) WHERE tenant_id=? AND id=?",original,"{0,"+field+"}",tenant,id);
            assertFalse(reports.detail(employee,id).completeEvidence(),field+" 키 누락과 명시 null을 구분해야 합니다.");
            db.update("UPDATE tna.day_result SET input_refs=jsonb_set(CAST(? AS jsonb),CAST(? AS text[]),'{}'::jsonb) WHERE tenant_id=? AND id=?",original,"{0,"+field+"}",tenant,id);
            assertFalse(reports.detail(employee,id).completeEvidence(),field+" 객체에 식별자·개정·시작일이 없으면 근거 확인이 필요합니다.");
        }
        String assessment="{\"id\":\""+UUID.randomUUID()+"\",\"employeeId\":\""+other.employeeId()+"\",\"revision\":1,\"validFrom\":\""+date.minusDays(1)+"\",\"validTo\":null,\"generalRuleReviewed\":true,\"basis\":\"PRIVATE_FOREIGN_BASIS\",\"createdAt\":\""+Instant.now()+"\"}";
        db.update("UPDATE tna.day_result SET input_refs=jsonb_set(CAST(? AS jsonb),'{0,assessment}',CAST(? AS jsonb)) WHERE tenant_id=? AND id=?",original,assessment,tenant,id);
        assertFalse(reports.detail(employee,id).completeEvidence(),"다른 사원의 적용 검토를 이 결과의 근거로 표시하면 안 됩니다.");
    }
    @Test void resultLinksAreRenderedAndMalformedIdsAreRejected()throws Exception{
        UUID id=result();var session=login(employee);
        mvc.perform(get("/me/worktime").session(session).param("month",java.time.YearMonth.from(date).toString()))
            .andExpect(status().isOk()).andExpect(content().string(containsString(id.toString())));
        mvc.perform(get("/worktime/results/not-an-id").session(session)).andExpect(status().isBadRequest());
    }
    private UUID policy(String basis){return reports.reviewPolicy(manager,new PolicyForm(date.minusDays(30),date.plusDays(30),true,basis,"합성 정책 검토",UUID.randomUUID()));}
    private UUID result(){reports.recalculate(manager,employee.employeeId(),date,date,UUID.randomUUID(),"합성 상세 입력");return reports.days(manager,employee.employeeId(),date,date).getFirst().id();}
    private int results(){return db.queryForObject("SELECT count(*) FROM tna.day_result WHERE tenant_id=?",Integer.class,tenant);}
    private TnaPrincipal member(String name,Set<String> roles){UUID emp=UUID.randomUUID(),account=UUID.randomUUID();db.update("INSERT INTO tna.employee(tenant_id,id,employee_no,display_name,joined_on) VALUES (?,?,?,?,DATE '2026-01-01')",tenant,emp,name,name);db.update("INSERT INTO tna.tenant_account(tenant_id,id,employee_id,email,password_hash) VALUES (?,?,?,?,?)",tenant,account,emp,name+"@example.test",PasswordPolicy.passwordEncoder().encode(PASSWORD));for(String role:roles)db.update("INSERT INTO tna.role_grant(tenant_id,account_id,role) VALUES (?,?,?)",tenant,account,role);return new TnaPrincipal(tenant,account,emp,1,roles,Instant.now());}
    private MockHttpSession login(TnaPrincipal actor)throws Exception{String email=db.queryForObject("SELECT email FROM tna.tenant_account WHERE tenant_id=? AND id=?",String.class,actor.tenantId(),actor.accountId());String code=db.queryForObject("SELECT company_code FROM platform.tenant_registry WHERE id=?",String.class,actor.tenantId());return (MockHttpSession)mvc.perform(post("/login").with(csrf()).param("companyCode",code).param("email",email).param("password",PASSWORD)).andExpect(redirectedUrl("/me/home")).andReturn().getRequest().getSession(false);}
    private static void assertStatus(int status,org.junit.jupiter.api.function.Executable action){assertEquals(status,assertThrows(OrgException.class,action).status());}
}
