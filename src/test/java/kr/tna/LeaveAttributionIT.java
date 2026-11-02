package kr.tna;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;
import kr.tna.common.auth.TnaPrincipal;
import kr.tna.common.tenant.TenantTransactions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest @AutoConfigureMockMvc
class LeaveAttributionIT {
    private static final String PAGE="application/vnd.worklog.page+json";
    private static final JsonMapper JSON=JsonMapper.builder().build();
    @Autowired MockMvc mvc;
    @Autowired TenantTransactions transactions;
    @Autowired @Qualifier("dataSource") DataSource source;
    JdbcTemplate db;
    UUID tenant,bucket;
    TnaPrincipal admin,worker;
    LocalDate today;

    @DynamicPropertySource static void database(DynamicPropertyRegistry p) {
        var fixture=DatabaseFixture.start();
        p.add("spring.datasource.url",fixture::jdbcUrl);p.add("spring.datasource.username",()->DatabaseFixture.APP_USER);p.add("spring.datasource.password",()->DatabaseFixture.APP_PASSWORD);
        p.add("spring.datasource.hikari.maximum-pool-size",()->5);
        p.add("tna.auth.url",fixture::jdbcUrl);p.add("tna.auth.username",()->DatabaseFixture.AUTH_USER);p.add("tna.auth.password",()->DatabaseFixture.AUTH_PASSWORD);
    }
    @BeforeEach void seed() {
        db=new JdbcTemplate(DatabaseFixture.start().adminDataSource());tenant=UUID.randomUUID();today=LocalDate.now(ZoneId.of("Asia/Seoul"));
        db.update("INSERT INTO platform.tenant_registry(id,company_code,display_name) VALUES (?,?,'합성 귀속 회사')",tenant,"ATTR-"+tenant);
        admin=member(Set.of("EMPLOYEE","ATTENDANCE_ADMIN"));worker=member(Set.of("EMPLOYEE"));bucket=bucket(worker.employeeId());
    }
    @Test void unclassifiedByDefaultDoesNotInferBaseYearOrWriteDuringReads() throws Exception {
        String before=ledger();var page=read(admin,bucket);
        assertEquals("request/attributions",page.path("page").asString());
        var model=page.at("/data/leaveAttributions");
        assertEquals(0,model.path("history").size());assertEquals(0,model.at("/form/expectedRevision").asLong());
        assertEquals("UNCLASSIFIED",model.at("/form/classificationStatus").asString());assertTrue(model.at("/form/entitlementYear").isNull());
        assertEquals(12,model.at("/bucket/balanceDays").asInt());assertEquals(0,count());assertEquals(before,ledger());
    }
    @Test void confirmAndUnclassifyAppendRevisionsWithoutChangingBucketOrLedger() throws Exception {
        String before=ledger();long version=db.queryForObject("SELECT version FROM tna.leave_bucket WHERE tenant_id=? AND id=?",Long.class,tenant,bucket);
        save(admin,bucket,UUID.randomUUID(),0,"CONFIRMED","2025",true).andExpect(status().isSeeOther());
        save(admin,bucket,UUID.randomUUID(),1,"UNCLASSIFIED","",false).andExpect(status().isSeeOther());
        var history=read(admin,bucket).at("/data/leaveAttributions/history");assertEquals(2,history.size());
        assertEquals(2,history.get(0).path("revision").asLong());assertTrue(history.get(0).path("entitlementYear").isNull());
        assertEquals("UNCLASSIFIED",history.get(0).path("classificationStatus").asString());
        assertEquals(2025,history.get(1).path("entitlementYear").asInt());
        assertEquals(systemType(),UUID.fromString(history.get(1).path("attendanceTypeId").asString()));
        assertEquals(before,ledger());assertEquals(version,db.queryForObject("SELECT version FROM tna.leave_bucket WHERE tenant_id=? AND id=?",Long.class,tenant,bucket));
    }
    @Test void confirmedNeedsExplicitWholeBucketSingleYearAndValidYear() throws Exception {
        save(admin,bucket,UUID.randomUUID(),0,"CONFIRMED","2025",false).andExpect(status().isUnprocessableEntity());
        for(String year:List.of("","1899","9999")) save(admin,bucket,UUID.randomUUID(),0,"CONFIRMED",year,true).andExpect(status().isUnprocessableEntity());
        assertEquals(0,count());
    }
    @Test void unclassifiedRejectsAnEntitlementYearAndUnknownStatus() throws Exception {
        save(admin,bucket,UUID.randomUUID(),0,"UNCLASSIFIED","2025",false).andExpect(status().isUnprocessableEntity());
        save(admin,bucket,UUID.randomUUID(),0,"GUESSED","2025",true).andExpect(status().isUnprocessableEntity());assertEquals(0,count());
    }
    @Test void basisAndReasonAreRequiredAndErrorsPreserveSubmittedInput() throws Exception {
        UUID command=UUID.randomUUID();var response=mvc.perform(as(post(path(bucket)),admin).with(csrf()).accept(PAGE)
                .param("commandId",command.toString()).param("expectedRevision","0").param("classificationStatus","CONFIRMED")
                .param("entitlementYear","2025").param("singleYearConfirmed","true").param("basis","").param("reason","합성 귀속 근거"))
                .andExpect(status().isUnprocessableEntity()).andReturn().getResponse();
        var form=JSON.readTree(response.getContentAsString()).at("/data/leaveAttributions/form");
        assertEquals(command.toString(),form.path("commandId").asString());assertEquals(2025,form.path("entitlementYear").asInt());
        mvc.perform(as(post(path(bucket)),admin).with(csrf()).accept(PAGE).param("commandId",UUID.randomUUID().toString())
                .param("expectedRevision","0").param("classificationStatus","UNCLASSIFIED").param("basis","혼합 귀속 확인").param("reason",""))
                .andExpect(status().isUnprocessableEntity());assertEquals(0,count());
    }
    @Test void staleRevisionKeepsDraftAndLatestHistoryWithoutOverwriting() throws Exception {
        save(admin,bucket,UUID.randomUUID(),0,"CONFIRMED","2025",true).andExpect(status().isSeeOther());
        UUID command=UUID.randomUUID();var response=save(admin,bucket,command,0,"CONFIRMED","2026",true)
                .andExpect(status().isConflict()).andReturn().getResponse();
        var model=JSON.readTree(response.getContentAsString()).at("/data/leaveAttributions");
        assertEquals(command.toString(),model.at("/form/commandId").asString());assertEquals(0,model.at("/form/expectedRevision").asLong());
        assertEquals(2026,model.at("/form/entitlementYear").asInt());assertEquals(2025,model.at("/history/0/entitlementYear").asInt());assertEquals(1,count());
    }
    @Test void exactReplayDoesNotDuplicateAttributionOrAuditButChangedInputConflicts() throws Exception {
        UUID command=UUID.randomUUID();save(admin,bucket,command,0,"CONFIRMED","2025",true).andExpect(status().isSeeOther());
        save(admin,bucket,command,0,"CONFIRMED","2025",true).andExpect(status().isSeeOther());
        save(admin,bucket,command,0,"CONFIRMED","2026",true).andExpect(status().isConflict());assertEquals(1,count());
        assertEquals(1,db.queryForObject("SELECT count(*) FROM tna.org_audit_event WHERE tenant_id=? AND command_id=?",Integer.class,tenant,command));
    }
    @Test void currentAdministratorAndCsrfAreRequiredAndOwnClassificationIsBlocked() throws Exception {
        var representative=member(Set.of("EMPLOYEE","COMPANY_REPRESENTATIVE"));
        for(var actor:List.of(worker,representative)) {
            mvc.perform(as(get(path(bucket)),actor)).andExpect(status().isForbidden());
            save(actor,bucket,UUID.randomUUID(),0,"CONFIRMED","2025",true).andExpect(status().isForbidden());
        }
        mvc.perform(as(post(path(bucket)),admin)).andExpect(status().isForbidden());
        UUID own=bucket(admin.employeeId());read(admin,own);save(admin,own,UUID.randomUUID(),0,"CONFIRMED","2025",true).andExpect(status().isForbidden());
        UUID command=UUID.randomUUID();save(admin,bucket,command,0,"CONFIRMED","2025",true).andExpect(status().isSeeOther());
        db.update("UPDATE tna.role_grant SET active=false WHERE tenant_id=? AND account_id=? AND role='ATTENDANCE_ADMIN'",tenant,admin.accountId());
        save(admin,bucket,command,0,"CONFIRMED","2025",true).andExpect(status().isUnauthorized());assertEquals(1,count());
    }
    @Test void foreignBucketAndUntrustedExtraFieldsAreRejected() throws Exception {
        UUID foreignTenant=UUID.randomUUID(),foreignEmployee=UUID.randomUUID(),foreignAccount=UUID.randomUUID(),foreign=UUID.randomUUID();
        db.update("INSERT INTO platform.tenant_registry(id,company_code,display_name) VALUES (?,?,'다른 합성 귀속 회사')",foreignTenant,"ATTR-"+foreignTenant);
        db.update("INSERT INTO tna.employee(tenant_id,id,employee_no,display_name,joined_on) VALUES (?,?,'FOREIGN','다른 합성 직원',DATE '2020-01-01')",foreignTenant,foreignEmployee);
        db.update("INSERT INTO tna.tenant_account(tenant_id,id,employee_id,email,password_hash) VALUES (?,?,?,'foreign@example.test','synthetic-hash')",foreignTenant,foreignAccount,foreignEmployee);
        db.update("INSERT INTO tna.leave_bucket(tenant_id,id,employee_id,base_date,usable_from,source_reference,created_by) VALUES (?,?,?,DATE '2024-01-01',DATE '2024-01-01','다른 합성 기초',?)",foreignTenant,foreign,foreignEmployee,foreignAccount);
        mvc.perform(as(get(path(foreign)),admin)).andExpect(status().isNotFound());
        save(admin,foreign,UUID.randomUUID(),0,"CONFIRMED","2025",true).andExpect(status().isNotFound());
        for(String field:List.of("tenantId","employeeId","attendanceTypeId"))
            mvc.perform(as(post(path(bucket)),admin).with(csrf()).param(field,UUID.randomUUID().toString())).andExpect(status().isBadRequest());
        mvc.perform(as(post(path(bucket)),admin).with(csrf()).param("expectedRevision","0","1")).andExpect(status().isBadRequest());
        mvc.perform(as(post(path(bucket)),admin).with(csrf()).param("entitlementYear","text")).andExpect(status().isBadRequest());assertEquals(0,count());
    }
    @Test void concurrentInitialClassificationCommitsOnlyOneRevision() throws Exception {
        try(var executor=java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var ready=new java.util.concurrent.CountDownLatch(2);var start=new java.util.concurrent.CountDownLatch(1);
            java.util.concurrent.Callable<Integer> change=()->{ready.countDown();assertTrue(start.await(10,java.util.concurrent.TimeUnit.SECONDS));return save(admin,bucket,UUID.randomUUID(),0,"CONFIRMED","2025",true).andReturn().getResponse().getStatus();};
            var a=executor.submit(change);var b=executor.submit(change);assertTrue(ready.await(10,java.util.concurrent.TimeUnit.SECONDS));start.countDown();
            assertEquals(List.of(303,409),java.util.stream.Stream.of(a.get(),b.get()).sorted().toList());assertEquals(1,count());
        }
    }
    @Test void auditFailureRollsBackNewRevision() throws Exception {
        String suffix=UUID.randomUUID().toString().replace("-","");String function="tna.attribution_audit_"+suffix;
        db.execute("CREATE FUNCTION "+function+"() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.tenant_id='"+tenant+"'::uuid AND NEW.action='LEAVE_BUCKET_ATTRIBUTION_ADD' THEN RAISE EXCEPTION 'synthetic attribution audit'; END IF; RETURN NEW; END $$");
        db.execute("CREATE TRIGGER attribution_audit_"+suffix+" BEFORE INSERT ON tna.org_audit_event FOR EACH ROW EXECUTE FUNCTION "+function+"()");
        try {save(admin,bucket,UUID.randomUUID(),0,"CONFIRMED","2025",true).andExpect(status().isServiceUnavailable());assertEquals(0,count());}
        finally {db.execute("DROP TRIGGER attribution_audit_"+suffix+" ON tna.org_audit_event");db.execute("DROP FUNCTION "+function+"()");}
    }
    @Test void historyForcesRlsAndAppCannotRewriteOrDeleteIt() throws Exception {
        save(admin,bucket,UUID.randomUUID(),0,"CONFIRMED","2025",true).andExpect(status().isSeeOther());
        JdbcTemplate app=new JdbcTemplate(source);assertTrue(db.queryForObject("SELECT relforcerowsecurity FROM pg_class WHERE oid='tna.leave_bucket_attribution'::regclass",Boolean.class));
        assertEquals(0,app.queryForObject("SELECT count(*) FROM tna.leave_bucket_attribution",Integer.class));
        assertEquals(1,transactions.execute(tenant,()->app.queryForObject("SELECT count(*) FROM tna.leave_bucket_attribution",Integer.class)));
        assertEquals(0,transactions.execute(UUID.randomUUID(),()->app.queryForObject("SELECT count(*) FROM tna.leave_bucket_attribution",Integer.class)));
        assertEquals(0,app.queryForObject("SELECT count(*) FROM tna.leave_bucket_attribution",Integer.class));
        for(String sql:List.of("UPDATE tna.leave_bucket_attribution SET entitlement_year=2026","DELETE FROM tna.leave_bucket_attribution")) {
            var error=assertThrows(DataAccessException.class,()->transactions.execute(tenant,()->app.update(sql)));
            assertEquals("42501",((SQLException)error.getMostSpecificCause()).getSQLState());
        }
    }
    @Test void confirmedYearIsRequiredByTheDatabaseAsWellAsTheForm() {
        var error=assertThrows(DataAccessException.class,()->transactions.execute(tenant,()->new JdbcTemplate(source).update("""
                INSERT INTO tna.leave_bucket_attribution(tenant_id,id,bucket_id,revision,attendance_type_id,classification_status,entitlement_year,basis,reason,created_by)
                VALUES (?,?,?,1,?,'CONFIRMED',NULL,'합성 귀속 근거','합성 직접 SQL',?)
                """,tenant,UUID.randomUUID(),bucket,systemType(),admin.accountId())));
        assertEquals("23514",((SQLException)error.getMostSpecificCause()).getSQLState());assertEquals(0,count());
    }
    private JsonNode read(TnaPrincipal actor,UUID id) throws Exception {return JSON.readTree(mvc.perform(as(get(path(id)),actor).accept(PAGE)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());}
    private org.springframework.test.web.servlet.ResultActions save(TnaPrincipal actor,UUID id,UUID command,long revision,String classification,String year,boolean confirmed)throws Exception {
        return mvc.perform(as(post(path(id)),actor).with(csrf()).accept(PAGE).param("commandId",command.toString()).param("expectedRevision",Long.toString(revision))
                .param("classificationStatus",classification).param("entitlementYear",year).param("singleYearConfirmed",Boolean.toString(confirmed))
                .param("basis","묶음 전체 단일 연도 근거").param("reason","합성 귀속 확인"));
    }
    private String path(UUID id){return "/admin/leave-balances/buckets/"+id+"/attributions";}
    private MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder r,TnaPrincipal a){return r.with(authentication(new UsernamePasswordAuthenticationToken(a,null,a.roles().stream().map(x->new SimpleGrantedAuthority("ROLE_"+x)).toList())));}
    private TnaPrincipal member(Set<String> roles){
        UUID employee=UUID.randomUUID(),account=UUID.randomUUID();db.update("INSERT INTO tna.employee(tenant_id,id,employee_no,display_name,joined_on) VALUES (?,?,?,'합성 귀속 직원',DATE '2020-01-01')",tenant,employee,"E-"+employee);
        db.update("INSERT INTO tna.tenant_account(tenant_id,id,employee_id,email,password_hash,active,auth_version) VALUES (?,?,?,?,?,true,1)",tenant,account,employee,account+"@example.test","synthetic-hash");
        for(String role:roles)db.update("INSERT INTO tna.role_grant(tenant_id,account_id,role) VALUES (?,?,?)",tenant,account,role);
        return new TnaPrincipal(tenant,account,employee,1,roles,Instant.now());
    }
    private UUID bucket(UUID employee){UUID id=UUID.randomUUID();db.update("INSERT INTO tna.leave_bucket(tenant_id,id,employee_id,base_date,usable_from,usable_to,source_reference,created_by) VALUES (?,?,?,DATE '2024-12-01',DATE '2024-12-01',DATE '2030-12-31','합성 기초',?)",tenant,id,employee,admin.accountId());
        db.update("INSERT INTO tna.leave_ledger(tenant_id,id,bucket_id,employee_id,entry_kind,signed_quantity_days,effective_on,source_reference,reason,created_by) VALUES (?,?,?,?,'OPENING',12,DATE '2024-12-01','합성 기초','합성 기초',?)",tenant,UUID.randomUUID(),id,employee,admin.accountId());return id;}
    private int count(){return db.queryForObject("SELECT count(*) FROM tna.leave_bucket_attribution WHERE tenant_id=?",Integer.class,tenant);}
    private String ledger(){return db.queryForList("SELECT * FROM tna.leave_ledger WHERE tenant_id=? AND bucket_id=? ORDER BY id",tenant,bucket).toString();}
    private UUID systemType(){return db.queryForObject("SELECT id FROM tna.attendance_type WHERE tenant_id=? AND system_kind='ANNUAL_LEAVE'",UUID.class,tenant);}
}
