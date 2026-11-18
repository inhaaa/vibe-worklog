package kr.tna;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;
import kr.tna.common.auth.TnaPrincipal;
import kr.tna.common.tenant.TenantTransactions;
import kr.tna.codes.CodeData.GroupRow;
import kr.tna.codes.CodeManagementService;
import kr.tna.org.BulkData;
import kr.tna.org.OrgException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest
@AutoConfigureMockMvc
class CodeManagementWebIT {
    private static final String PAGE="application/vnd.worklog.page+json";
    private static final JsonMapper JSON=JsonMapper.builder().build();
    @Autowired MockMvc mvc;
    @Autowired TenantTransactions transactions;
    @Autowired CodeManagementService service;
    @Autowired @Qualifier("dataSource") DataSource source;
    private JdbcTemplate db;
    private UUID tenant;
    private TnaPrincipal admin;
    private LocalDate today;
    @DynamicPropertySource static void database(DynamicPropertyRegistry properties) {
        var fixture=DatabaseFixture.start();
        properties.add("spring.datasource.url",fixture::jdbcUrl);
        properties.add("spring.datasource.username",()->DatabaseFixture.APP_USER);
        properties.add("spring.datasource.password",()->DatabaseFixture.APP_PASSWORD);
        properties.add("spring.datasource.hikari.maximum-pool-size",()->5);
        properties.add("tna.auth.url",fixture::jdbcUrl);
        properties.add("tna.auth.username",()->DatabaseFixture.AUTH_USER);
        properties.add("tna.auth.password",()->DatabaseFixture.AUTH_PASSWORD);
    }
    @BeforeEach void company() {
        db=new JdbcTemplate(DatabaseFixture.start().adminDataSource());today=LocalDate.now(ZoneId.of("Asia/Seoul"));tenant=UUID.randomUUID();
        db.update("INSERT INTO platform.tenant_registry(id,company_code,display_name) VALUES (?,?,'합성 코드 회사')",tenant,"CODE-"+tenant);
        admin=member("ADMIN",Set.of("EMPLOYEE","ATTENDANCE_ADMIN"));
    }
    @Test void separateScreensProvideSheetsAndSeededSupportedSystemTypes() throws Exception {
        mvc.perform(as(get("/admin/common-codes"),admin).accept(PAGE)).andExpect(status().isOk())
                .andExpect(view().name("org/common-codes")).andExpect(ReactPageAssertions.dataPresent("/commonCodes/groupSheet/commandId"));
        mvc.perform(as(get("/admin/attendance-types"),admin).accept(PAGE)).andExpect(status().isOk())
                .andExpect(view().name("org/attendance-types")).andExpect(ReactPageAssertions.dataPresent("/attendanceTypes/sheet/commandId"));
        assertEquals(2,count("attendance_type"));assertEquals(2,count("attendance_type_version"));
        assertEquals(1,count("common_code_group"));assertEquals(3,count("common_code"));
    }
    @Test void systemTypesCanBeFoundByCompactCodeNameAndHistoricalKey() throws Exception {
        for (String q : List.of("a01", "연차", "ANNUAL_LEAVE")) {
            mvc.perform(as(get("/admin/attendance-types"),admin).accept(PAGE).param("q",q))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.attendanceTypes.types.length()").value(1))
                    .andExpect(ReactPageAssertions.data("/attendanceTypes/types/0/code","A01"))
                    .andExpect(ReactPageAssertions.data("/attendanceTypes/versions/0/typeId",systemType()));
        }
        mvc.perform(as(get("/admin/attendance-types"),admin).accept(PAGE).param("q","ATTENDANCE_CORRECTION"))
                .andExpect(ReactPageAssertions.data("/attendanceTypes/types/0/code","A02"));
    }
    @Test void newCompactCodesAreUniqueAndLegacyCustomCodesRemainEditable() throws Exception {
        var legacy = UUID.randomUUID();
        db.update("INSERT INTO tna.attendance_type(tenant_id,id,code,created_by,updated_by) VALUES(?,?,?,?,?)",
                tenant,legacy,"old_custom_type",admin.accountId(),admin.accountId());
        db.update("""
                INSERT INTO tna.attendance_type_version(tenant_id,id,type_id,revision,effective_from,enabled,name,
                  unit,pay_kind,deduction_rule,recognition_rule,basis,reason,created_by)
                VALUES(?,?,?,1,?,false,'기존 코드','DAY','UNSPECIFIED','NONE','NOT_SUPPORTED','기존','기존',?)
                """,tenant,UUID.randomUUID(),legacy,today,admin.accountId());
        mvc.perform(bulk("attendance-types",UUID.randomUUID(),List.of(type(legacy,"old_custom_type",null,0L)),null))
                .andExpect(status().isSeeOther());
        for (String code : List.of("A_10","ABCDEFGHI","가01")) {
            mvc.perform(bulk("attendance-types",UUID.randomUUID(),List.of(type(null,code,null,null)),null))
                    .andExpect(status().isUnprocessableEntity());
        }
        mvc.perform(bulk("attendance-types",UUID.randomUUID(),List.of(type(null,"A01",null,null)),null))
                .andExpect(status().isConflict());
        mvc.perform(bulk("attendance-types",UUID.randomUUID(),List.of(type(null,"101",null,null)),null))
                .andExpect(status().isSeeOther());
        assertEquals(2,db.queryForObject("SELECT count(*) FROM tna.attendance_type_version WHERE tenant_id=? AND type_id=?",
                Integer.class,tenant,legacy));
    }
    @Test void fiftyGroupsCommitAtomicallyAndExactReplayDoesNotDuplicateAudit() throws Exception {
        var rows=new ArrayList<Map<String,Object>>();for(int i=0;i<50;i++)rows.add(common(null,"G"+i,null,null));
        UUID command=UUID.randomUUID();mvc.perform(bulk("common-code-groups",command,rows,null)).andExpect(status().isSeeOther());
        assertEquals(51,count("common_code_group"));assertEquals(51,count("org_audit_event"));
        mvc.perform(bulk("common-code-groups",command,rows,null)).andExpect(status().isSeeOther());
        assertEquals(51,count("org_audit_event"));rows.getFirst().put("name","다른 입력");
        mvc.perform(bulk("common-code-groups",command,rows,null)).andExpect(status().isConflict());
        mvc.perform(as(get("/admin/common-codes"),admin).accept(PAGE)).andExpect(ReactPageAssertions.data("/commonCodes/hasNext",true));
    }
    @Test void fiftyOneAndInvalidLastRowLeaveNoChanges() throws Exception {
        var rows=new ArrayList<Map<String,Object>>();for(int i=0;i<51;i++)rows.add(common(null,"G"+i,null,null));
        mvc.perform(bulk("common-code-groups",UUID.randomUUID(),rows,null)).andExpect(status().isUnprocessableEntity());rows.removeLast();rows.getLast().put("name","");
        mvc.perform(bulk("common-code-groups",UUID.randomUUID(),rows,null)).andExpect(status().isUnprocessableEntity());
        assertEquals(1,count("common_code_group"));assertEquals(0,count("org_audit_event"));
    }
    @Test void codesAreUniqueWithinGroupAndStoredGroupAndCodeAreImmutable() throws Exception {
        UUID group=createGroup("G1"),other=createGroup("G2");
        mvc.perform(bulk("common-codes",UUID.randomUUID(),List.of(common(null,"SAME",group,null)),group)).andExpect(status().isSeeOther());
        mvc.perform(bulk("common-codes",UUID.randomUUID(),List.of(common(null,"SAME",other,null)),other)).andExpect(status().isSeeOther());
        mvc.perform(bulk("common-codes",UUID.randomUUID(),List.of(common(null,"SAME",group,null)),group)).andExpect(status().isConflict());
        UUID id=db.queryForObject("SELECT id FROM tna.common_code WHERE tenant_id=? AND group_id=?",UUID.class,tenant,group);
        mvc.perform(bulk("common-codes",UUID.randomUUID(),List.of(common(id,"CHANGED",group,0L)),group)).andExpect(status().isUnprocessableEntity());
        mvc.perform(bulk("common-codes",UUID.randomUUID(),List.of(common(id,"SAME",other,0L)),other)).andExpect(status().isUnprocessableEntity());
    }
    @Test void groupAndCodeDeactivationPreserveRowsAndReferences() throws Exception {
        UUID group=createGroup("G");var code=common(null,"C",group,null);
        mvc.perform(bulk("common-codes",UUID.randomUUID(),List.of(code),group)).andExpect(status().isSeeOther());
        UUID id=db.queryForObject("SELECT id FROM tna.common_code WHERE tenant_id=? AND group_id=?",UUID.class,tenant,group);
        var deletion=common(id,"C",group,0L);deletion.put("operation","DEACTIVATE");
        mvc.perform(bulk("common-codes",UUID.randomUUID(),List.of(deletion),group)).andExpect(status().isSeeOther());
        var groupDeletion=common(group,"G",null,0L);groupDeletion.put("operation","DEACTIVATE");
        mvc.perform(bulk("common-code-groups",UUID.randomUUID(),List.of(groupDeletion),null)).andExpect(status().isSeeOther());
        assertFalse(db.queryForObject("SELECT active FROM tna.common_code WHERE tenant_id=? AND id=?",Boolean.class,tenant,id));
        assertFalse(db.queryForObject("SELECT active FROM tna.common_code_group WHERE tenant_id=? AND id=?",Boolean.class,tenant,group));
        assertEquals(4,count("common_code"));
    }
    @Test void systemOwnedGroupsAndCodesCannotBeChangedEvenWithCurrentVersion() throws Exception {
        UUID group=db.queryForObject("SELECT id FROM tna.common_code_group WHERE tenant_id=? AND system_owned",UUID.class,tenant);
        String code=db.queryForObject("SELECT code FROM tna.common_code_group WHERE tenant_id=? AND id=?",String.class,tenant,group);
        mvc.perform(bulk("common-code-groups",UUID.randomUUID(),List.of(common(group,code,null,0L)),null)).andExpect(status().isUnprocessableEntity());
        UUID detail=db.queryForObject("SELECT id FROM tna.common_code WHERE tenant_id=? AND group_id=? ORDER BY code LIMIT 1",UUID.class,tenant,group);
        String detailCode=db.queryForObject("SELECT code FROM tna.common_code WHERE tenant_id=? AND id=?",String.class,tenant,detail);
        mvc.perform(bulk("common-codes",UUID.randomUUID(),List.of(common(detail,detailCode,group,0L)),group)).andExpect(status().isUnprocessableEntity());
        mvc.perform(bulk("common-codes",UUID.randomUUID(),List.of(common(null,"NEW",group,null)),group)).andExpect(status().isUnprocessableEntity());
        assertEquals(0,count("org_audit_event"));
    }
    @Test void conflictKeepsDraftCommandIdAndLatestOriginalOutsideSearch() throws Exception {
        UUID group=createGroup("G"),command=UUID.randomUUID();var row=common(group,"G",null,0L);row.put("name","첫 수정");
        mvc.perform(bulk("common-code-groups",UUID.randomUUID(),List.of(row),null)).andExpect(status().isSeeOther());row.put("name","보존할 수정");
        var result=mvc.perform(bulk("common-code-groups",command,List.of(row),null).param("q","화면에서 사라짐")).andExpect(status().isConflict()).andReturn();
        var sheet=ReactPageAssertions.envelope(result).at("/data/commonCodes/groupSheet");
        assertEquals(command.toString(),sheet.at("/commandId").asString());assertEquals("보존할 수정",sheet.at("/rows/0/name").asString());
        assertEquals(1,sheet.at("/latestRows/0/version").asLong());
    }
    @Test void duplicateUnknownFieldsAndWrongJsonTypesAreRejected() throws Exception {
        var row=common(null,"G",null,null);
        for(String json:List.of(JSON.writeValueAsString(List.of(row)).replace("\"active\":true","\"active\":\"true\""),
                JSON.writeValueAsString(List.of(row)).replace("\"active\":true","\"active\":true,\"active\":false"),
                JSON.writeValueAsString(List.of(row)).replace("\"active\":true","\"active\":true,\"tenantId\":\"bad\""))) {
            mvc.perform(as(post("/admin/common-code-groups/bulk"),admin).with(csrf()).accept(PAGE).param("commandId",UUID.randomUUID().toString()).param("reason","검증").param("rows",json)).andExpect(status().isBadRequest());
        }
        mvc.perform(bulk("common-code-groups",UUID.randomUUID(),List.of(row,row),null)).andExpect(status().isUnprocessableEntity());
        mvc.perform(bulk("common-code-groups",UUID.randomUUID(),List.of(row),null).param("tenantId",tenant.toString())).andExpect(status().isBadRequest());
        assertEquals(1,count("common_code_group"));
    }
    @Test void newTypesAreDraftOnlyAndCannotClaimSystemHandler() throws Exception {
        var row=type(null,"SICK",null,null);mvc.perform(bulk("attendance-types",UUID.randomUUID(),List.of(row),null)).andExpect(status().isSeeOther());
        UUID id=db.queryForObject("SELECT id FROM tna.attendance_type WHERE tenant_id=? AND code='SICK'",UUID.class,tenant);
        assertNull(db.queryForObject("SELECT system_kind FROM tna.attendance_type WHERE tenant_id=? AND id=?",String.class,tenant,id));
        var unsupported=type(null,"OTHER",null,null);unsupported.put("enabled",true);
        mvc.perform(bulk("attendance-types",UUID.randomUUID(),List.of(unsupported),null)).andExpect(status().isUnprocessableEntity());
        unsupported.put("enabled",false);unsupported.put("systemKind","ANNUAL_LEAVE");
        mvc.perform(bulk("attendance-types",UUID.randomUUID(),List.of(unsupported),null)).andExpect(status().isUnprocessableEntity());
        assertEquals(3,count("attendance_type"));
    }
    @Test void typePolicyEditsAndDeactivationAppendImmutableVersions() throws Exception {
        UUID id=systemType();var row=type(id,"ANNUAL_LEAVE","ANNUAL_LEAVE",0L);row.put("enabled",true);row.put("deductionRule","LEAVE_DAY");
        row.put("effectiveFrom",today.plusDays(5).toString());mvc.perform(bulk("attendance-types",UUID.randomUUID(),List.of(row),null)).andExpect(status().isSeeOther());
        row.put("expectedVersion",1);row.put("name","동일일 정정");mvc.perform(bulk("attendance-types",UUID.randomUUID(),List.of(row),null)).andExpect(status().isSeeOther());
        row.put("expectedVersion",2);row.put("operation","DEACTIVATE");row.put("effectiveFrom",today.plusDays(10).toString());
        mvc.perform(bulk("attendance-types",UUID.randomUUID(),List.of(row),null)).andExpect(status().isSeeOther());
        assertEquals(4,db.queryForObject("SELECT count(*) FROM tna.attendance_type_version WHERE tenant_id=? AND type_id=?",Integer.class,tenant,id));
        assertFalse(db.queryForObject("SELECT enabled FROM tna.attendance_type_version WHERE tenant_id=? AND type_id=? AND revision=4",Boolean.class,tenant,id));
        assertTrue(db.queryForObject("SELECT enabled FROM tna.attendance_type_version WHERE tenant_id=? AND type_id=? AND revision=1",Boolean.class,tenant,id));
    }
    @Test void systemCapabilitiesAndPastDatesCannotBeChangedByPolicyEditor() throws Exception {
        var row=type(systemType(),"ANNUAL_LEAVE","ANNUAL_LEAVE",0L);row.put("enabled",true);row.put("deductionRule","LEAVE_DAY");
        row.put("unit","HOUR");mvc.perform(bulk("attendance-types",UUID.randomUUID(),List.of(row),null)).andExpect(status().isUnprocessableEntity());
        row.put("unit","DAY");row.put("effectiveFrom",today.minusDays(1).toString());mvc.perform(bulk("attendance-types",UUID.randomUUID(),List.of(row),null)).andExpect(status().isUnprocessableEntity());
        assertEquals(2,count("attendance_type_version"));
    }
    @Test void failedTypeBatchAndRootAuditRollbackVersionsAndMasters() throws Exception {
        var rows=List.of(type(null,"NEW",null,null),type(null,"BAD",null,null));rows.getLast().put("basis","");
        mvc.perform(bulk("attendance-types",UUID.randomUUID(),rows,null)).andExpect(status().isUnprocessableEntity());assertEquals(2,count("attendance_type"));
        rows.getLast().put("basis","검토 전 초안");String constraint="code_audit_"+UUID.randomUUID().toString().replace("-","");
        db.execute("ALTER TABLE tna.org_audit_event ADD CONSTRAINT "+constraint+" CHECK (tenant_id<>'"+tenant+"'::uuid OR action<>'ATTENDANCE_TYPE_BULK') NOT VALID");
        try {mvc.perform(bulk("attendance-types",UUID.randomUUID(),rows,null)).andExpect(status().isConflict());assertEquals(2,count("attendance_type"));assertEquals(2,count("attendance_type_version"));assertEquals(0,count("org_audit_event"));}
        finally {db.execute("ALTER TABLE tna.org_audit_event DROP CONSTRAINT "+constraint);}
    }
    @Test void rolesCsrfAndForeignGroupCannotCrossAdministrativeBoundary() throws Exception {
        for(var actor:List.of(member("USER",Set.of("EMPLOYEE")),member("REP",Set.of("EMPLOYEE","COMPANY_REPRESENTATIVE")))) {
            for(String path:List.of("common-codes","attendance-types"))mvc.perform(as(get("/admin/"+path),actor)).andExpect(status().isForbidden());
            mvc.perform(as(post("/admin/common-code-groups/bulk"),actor).with(csrf())).andExpect(status().isForbidden());
        }
        mvc.perform(as(post("/admin/common-code-groups/bulk"),admin)).andExpect(status().isForbidden());
        mvc.perform(as(get("/admin/common-codes"),admin).param("groupId",UUID.randomUUID().toString())).andExpect(status().isNotFound());
        UUID command=UUID.randomUUID();var rows=List.of(common(null,"G",null,null));mvc.perform(bulk("common-code-groups",command,rows,null)).andExpect(status().isSeeOther());
        db.update("UPDATE tna.role_grant SET active=false WHERE tenant_id=? AND account_id=? AND role='ATTENDANCE_ADMIN'",tenant,admin.accountId());
        mvc.perform(bulk("common-code-groups",command,rows,null)).andExpect(status().isUnauthorized());
    }
    @Test void allNewTablesForceRlsAndAppCannotRewritePolicyOrDeleteHistory() {
        for(String table:List.of("common_code_group","common_code","attendance_type","attendance_type_version")) {
            assertTrue(db.queryForObject("SELECT relforcerowsecurity FROM pg_class WHERE oid=CAST(? AS regclass)",Boolean.class,"tna."+table));
            assertEquals(0,transactions.execute(UUID.randomUUID(),()->new JdbcTemplate(source).queryForObject("SELECT count(*) FROM tna."+table,Integer.class)));
            assertFalse(db.queryForObject("SELECT has_table_privilege('tna_app',?,'DELETE')",Boolean.class,"tna."+table));
        }
        assertEquals(2,transactions.execute(tenant,()->new JdbcTemplate(source).queryForObject("SELECT count(*) FROM tna.attendance_type_version",Integer.class)));
        assertThrows(org.springframework.dao.DataAccessException.class,()->transactions.execute(tenant,()->new JdbcTemplate(source).update("UPDATE tna.attendance_type_version SET name='허용안됨' WHERE tenant_id=?",tenant)));
        assertThrows(org.springframework.dao.DataAccessException.class,()->transactions.execute(tenant,()->new JdbcTemplate(source).update("DELETE FROM tna.attendance_type_version WHERE tenant_id=?",tenant)));
        assertFalse(db.queryForObject("SELECT has_column_privilege('tna_app','tna.approval_request','attendance_type_version_id','UPDATE')",Boolean.class));
    }
    @Test void fiftyCodesAndTypesCommitWhileFiftyOneRowsAreRejected() throws Exception {
        UUID group=createGroup("G");var codes=new ArrayList<Map<String,Object>>();var types=new ArrayList<Map<String,Object>>();
        for(int i=0;i<51;i++){codes.add(common(null,"C"+i,group,null));types.add(type(null,"DRAFT"+i,null,null));}
        mvc.perform(bulk("common-codes",UUID.randomUUID(),codes,group)).andExpect(status().isUnprocessableEntity());
        mvc.perform(bulk("attendance-types",UUID.randomUUID(),types,null)).andExpect(status().isUnprocessableEntity());
        assertEquals(3,count("common_code"));assertEquals(2,count("attendance_type"));codes.removeLast();types.removeLast();
        mvc.perform(bulk("common-codes",UUID.randomUUID(),codes,group)).andExpect(status().isSeeOther());
        mvc.perform(bulk("attendance-types",UUID.randomUUID(),types,null)).andExpect(status().isSeeOther());
        assertEquals(53,count("common_code"));assertEquals(52,count("attendance_type"));assertEquals(52,count("attendance_type_version"));
    }
    @Test void currentAdministratorIsRequiredEvenForDirectServiceReplay() throws Exception {
        UUID command=UUID.randomUUID();var row=common(null,"G",null,null);
        mvc.perform(bulk("common-code-groups",command,List.of(row),null)).andExpect(status().isSeeOther());
        db.update("UPDATE tna.role_grant SET active=false WHERE tenant_id=? AND account_id=? AND role='ATTENDANCE_ADMIN'",tenant,admin.accountId());
        var form=new BulkData.Form<>(command,"합성 코드 변경",List.of(new GroupRow((String)row.get("clientRowId"),null,null,BulkData.Operation.CREATE,"G","합성 G","표시 분류",0,true)));
        assertEquals(403,assertThrows(OrgException.class,()->service.saveGroups(admin,form)).status());assertEquals(2,count("common_code_group"));
    }
    @Test void concurrentEditsRejectOneStaleVersionWithoutLosingACommittedName() throws Exception {
        UUID group=createGroup("G");
        try(var executor=java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()){
            var ready=new java.util.concurrent.CountDownLatch(2);var start=new java.util.concurrent.CountDownLatch(1);
            java.util.concurrent.Callable<Integer> save=()->{var row=common(group,"G",null,0L);row.put("name","변경 "+UUID.randomUUID());ready.countDown();assertTrue(start.await(10,java.util.concurrent.TimeUnit.SECONDS));return mvc.perform(bulk("common-code-groups",UUID.randomUUID(),List.of(row),null)).andReturn().getResponse().getStatus();};
            var one=executor.submit(save);var two=executor.submit(save);assertTrue(ready.await(10,java.util.concurrent.TimeUnit.SECONDS));start.countDown();
            var outcomes=List.of(one.get(20,java.util.concurrent.TimeUnit.SECONDS),two.get(20,java.util.concurrent.TimeUnit.SECONDS));assertEquals(1,outcomes.stream().filter(value->value==303).count());assertEquals(1,outcomes.stream().filter(value->value==409).count());
        }
        assertEquals(1,db.queryForObject("SELECT version FROM tna.common_code_group WHERE tenant_id=? AND id=?",Integer.class,tenant,group));
        assertEquals(4,count("org_audit_event"));
    }
    @Test void aTypeConflictOutsideSearchKeepsItsHistoryAsWellAsDraftAndLatestPolicy() throws Exception {
        UUID id=systemType();var row=type(id,"ANNUAL_LEAVE","ANNUAL_LEAVE",0L);row.put("enabled",true);row.put("deductionRule","LEAVE_DAY");
        mvc.perform(bulk("attendance-types",UUID.randomUUID(),List.of(row),null)).andExpect(status().isSeeOther());row.put("name","충돌 편집");
        var result=mvc.perform(bulk("attendance-types",UUID.randomUUID(),List.of(row),null).param("q","목록에서 사라진 유형")).andExpect(status().isConflict()).andReturn();
        var model=ReactPageAssertions.envelope(result).at("/data/attendanceTypes");
        assertEquals(1,model.at("/sheet/latestRows/0/version").asLong());assertEquals("충돌 편집",model.at("/sheet/rows/0/name").asString());assertEquals(2,model.at("/versions").size());
    }
    private UUID systemType(){return db.queryForObject("SELECT id FROM tna.attendance_type WHERE tenant_id=? AND system_kind='ANNUAL_LEAVE'",UUID.class,tenant);}
    private UUID createGroup(String code)throws Exception {mvc.perform(bulk("common-code-groups",UUID.randomUUID(),List.of(common(null,code,null,null)),null)).andExpect(status().isSeeOther());return db.queryForObject("SELECT id FROM tna.common_code_group WHERE tenant_id=? AND code=?",UUID.class,tenant,code);}
    private int count(String table){return db.queryForObject("SELECT count(*) FROM tna."+table+" WHERE tenant_id=?",Integer.class,tenant);}
    private MockHttpServletRequestBuilder bulk(String path,UUID command,List<Map<String,Object>> rows,UUID group){var request=as(post("/admin/"+path+"/bulk"),admin).with(csrf()).accept(PAGE).param("commandId",command.toString()).param("reason","합성 코드 변경").param("rows",JSON.writeValueAsString(rows));if(group!=null)request.param("groupId",group.toString());return request;}
    private Map<String,Object> common(UUID id,String code,UUID group,Long version){var r=base(id,version);r.put("code",code);r.put("name","합성 "+code);r.put("description","표시 분류");r.put("sortOrder",0);r.put("active",true);if(group!=null)r.put("groupId",group);return r;}
    private Map<String,Object> type(UUID id,String code,String kind,Long version){var r=base(id,version);r.put("code",code);r.put("systemKind",kind);r.put("name","합성 "+code);r.put("effectiveFrom",today.toString());r.put("enabled",false);r.put("unit","DAY");r.put("payKind","UNSPECIFIED");r.put("deductionRule","NONE");r.put("recognitionRule","NOT_SUPPORTED");r.put("basis","검토 전 초안");return r;}
    private Map<String,Object> base(UUID id,Long version){var r=new LinkedHashMap<String,Object>();r.put("clientRowId",UUID.randomUUID().toString());r.put("id",id);r.put("expectedVersion",version);r.put("operation",id==null?"CREATE":"UPDATE");return r;}
    private MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder request,TnaPrincipal actor){return request.with(authentication(UsernamePasswordAuthenticationToken.authenticated(actor,null,actor.roles().stream().map(role->new SimpleGrantedAuthority("ROLE_"+role)).toList())));}
    private TnaPrincipal member(String number,Set<String> roles){UUID employee=UUID.randomUUID(),account=UUID.randomUUID();db.update("INSERT INTO tna.employee(tenant_id,id,employee_no,display_name,joined_on) VALUES (?,?,?,?,?)",tenant,employee,number,"합성 "+number,today.minusYears(1));db.update("INSERT INTO tna.tenant_account(tenant_id,id,employee_id,email,password_hash) VALUES (?,?,?,?,?)",tenant,account,employee,account+"@example.test","synthetic-code-password-hash");for(String role:roles)db.update("INSERT INTO tna.role_grant(tenant_id,account_id,role) VALUES (?,?,?)",tenant,account,role);return new TnaPrincipal(tenant,account,employee,1,roles,Instant.now());}
}
