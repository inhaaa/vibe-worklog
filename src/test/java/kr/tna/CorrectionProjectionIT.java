package kr.tna;

import static org.junit.jupiter.api.Assertions.*;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import kr.tna.clock.ClockData;
import kr.tna.clock.ClockService;
import kr.tna.common.auth.TnaPrincipal;
import kr.tna.org.AccountData.ApproverForm;
import kr.tna.org.ApproverService;
import kr.tna.request.LeaveWorkflowService;
import kr.tna.request.RequestData.CorrectionForm;
import kr.tna.request.RequestData.DecisionForm;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest
class CorrectionProjectionIT {
    private static final ZoneId SEOUL=ZoneId.of("Asia/Seoul");
    @Autowired ClockService clocks;
    @Autowired LeaveWorkflowService workflow;
    @Autowired ApproverService approvers;
    private JdbcTemplate db;
    private UUID tenant;
    private TnaPrincipal manager,employee,approver;
    private LocalDate date,today;

    @DynamicPropertySource static void database(DynamicPropertyRegistry p) {
        var f=DatabaseFixture.start();p.add("spring.datasource.url",f::jdbcUrl);
        p.add("spring.datasource.username",()->DatabaseFixture.APP_USER);p.add("spring.datasource.password",()->DatabaseFixture.APP_PASSWORD);
        p.add("tna.auth.url",f::jdbcUrl);p.add("tna.auth.username",()->DatabaseFixture.AUTH_USER);p.add("tna.auth.password",()->DatabaseFixture.AUTH_PASSWORD);
    }
    @BeforeEach void approvedInputFixture() {
        db=new JdbcTemplate(DatabaseFixture.start().adminDataSource());today=LocalDate.now(SEOUL);date=today.minusDays(1);
        tenant=UUID.randomUUID();db.update("INSERT INTO platform.tenant_registry(id,company_code,display_name) VALUES (?,?,'합성 정정 투영')",tenant,"PROJECTION-"+tenant);
        manager=member(Set.of("EMPLOYEE","ATTENDANCE_ADMIN"));employee=member(Set.of("EMPLOYEE"));approver=member(Set.of("EMPLOYEE","APPROVER"));
        approvers.set(manager,new ApproverForm(employee.employeeId(),approver.employeeId(),today,0L,"합성 승인자",UUID.randomUUID()));
        UUID template=UUID.randomUUID(),version=UUID.randomUUID(),calendar=UUID.randomUUID(),network=UUID.randomUUID();
        db.update("INSERT INTO tna.schedule_template(tenant_id,id,code,name,created_by,updated_by) VALUES (?,?,?,'합성 일정',?,?)",tenant,template,template.toString(),manager.accountId(),manager.accountId());
        db.update("INSERT INTO tna.schedule_version(tenant_id,id,template_id,revision,valid_from,reason,created_by) VALUES (?,?,?,1,?,'합성 일정',?)",tenant,version,template,date,manager.accountId());
        db.update("INSERT INTO tna.schedule_day(tenant_id,version_id,weekday,workday,start_time,end_time) VALUES (?,?,?,true,TIME '09:00',TIME '18:00')",tenant,version,date.getDayOfWeek().getValue());
        db.update("INSERT INTO tna.schedule_break(tenant_id,version_id,weekday,sequence,start_time,end_time) VALUES (?,?,?,1,TIME '12:00',TIME '13:00')",tenant,version,date.getDayOfWeek().getValue());
        db.update("INSERT INTO tna.schedule_assignment(tenant_id,id,employee_id,schedule_version_id,valid_from,reason,created_by,updated_by) VALUES (?,?,?,?,?,'합성 일정',?,?)",tenant,UUID.randomUUID(),employee.employeeId(),version,date,manager.accountId(),manager.accountId());
        db.update("INSERT INTO tna.calendar_version(tenant_id,id,revision,valid_from,valid_to,reason,created_by) VALUES (?,?,1,?,?,'합성 달력',?)",tenant,calendar,date,date.plusDays(1),manager.accountId());
        db.update("INSERT INTO tna.calendar_day(tenant_id,version_id,work_date,kind,name,basis) VALUES (?,?,?,'WORKDAY','근로일','합성 검토')",tenant,calendar,date);
        db.update("INSERT INTO tna.allowed_network(tenant_id,id,network,label,valid_from,reason,created_by) VALUES (?,?,CAST('127.0.0.1/32' AS cidr),'합성 허용망',?,'합성',?)",tenant,network,java.sql.Timestamp.from(Instant.now().minusSeconds(3600)),manager.accountId());
        for(var entry:List.of(new Object[]{"IN",at(9)},new Object[]{"OUT",at(17)})) {
            db.update("INSERT INTO tna.attendance_event(tenant_id,id,employee_id,event_kind,occurred_at,received_at,client_command_id,source,validated_ip,network_id,assigned_work_date) VALUES (?,?,?,?,?,?,?,'IP',CAST('127.0.0.1' AS inet),?,?)",tenant,UUID.randomUUID(),employee.employeeId(),entry[0],java.sql.Timestamp.from((Instant)entry[1]),java.sql.Timestamp.from((Instant)entry[1]),UUID.randomUUID(),network,date);
        }
        db.update("INSERT INTO tna.day_input_revision(tenant_id,employee_id,work_date,input_revision,review_revision,recalculation_required) VALUES (?,?,?,2,0,true)",tenant,employee.employeeId(),date);
    }

    @Test void approvedSetProjectsEffectiveClockAndEvidenceWhilePreservingRawEvents() {
        var before=clocks.day(employee,date);UUID request=submit("KEEP","","SET",time(18),true,null);approve(request);
        var after=clocks.day(employee,date);assertEquals(at(17),event(after.events(),"OUT").occurredAt());
        assertEquals(at(18),event(after.effectiveEvents(),"OUT").occurredAt());
        assertEquals(at(9),event(after.effectiveEvents(),"IN").occurredAt());
        assertNotEquals(before.sourceFingerprint(),after.sourceFingerprint());
        assertNotNull(after.confirmation());assertEquals(after.inputRevision(),after.confirmation().confirmedInputRevision());
        assertEquals(after.reviewRevision(),after.confirmation().reviewRevision());
        assertEquals(after.sourceFingerprint(),after.confirmation().sourceFingerprint());
        assertEquals(List.of(new ClockData.Interval(at(12),at(13))),after.confirmation().breaks());
        assertEquals(2,db.queryForObject("SELECT count(*) FROM tna.attendance_event WHERE tenant_id=? AND employee_id=?",Integer.class,tenant,employee.employeeId()));
    }
    @Test void clearRemovesOnlyEffectiveValueAndDoesNotInventConfirmation() {
        UUID request=submit("KEEP","","CLEAR","",false,null);approve(request);
        var after=clocks.day(employee,date);assertEquals(2,after.events().size());assertEquals(1,after.effectiveEvents().size());
        assertEquals("IN",after.effectiveEvents().getFirst().kind());assertNull(after.confirmation());
    }
    @Test void subsequentKeepUsesPreviouslyEffectiveSnapshotRatherThanOriginalRawOut() {
        UUID first=submit("KEEP","","SET",time(18),true,null);approve(first);
        UUID second=submit("SET",time(10),"KEEP","",true,first);approve(second);
        var after=clocks.day(employee,date);
        assertEquals(at(10),event(after.effectiveEvents(),"IN").occurredAt());
        assertEquals(at(18),event(after.effectiveEvents(),"OUT").occurredAt());
        assertEquals(at(17),event(after.events(),"OUT").occurredAt());
    }
    @Test void syntheticEventIdentitiesAreDistinctAndStableAcrossRepeatedReads() {
        UUID request=submit("SET",time(9),"SET",time(18),true,null);approve(request);
        var first=clocks.day(employee,date);var second=clocks.day(employee,date);
        assertNotEquals(event(first.effectiveEvents(),"IN").id(),event(first.effectiveEvents(),"OUT").id());
        assertEquals(first.effectiveEvents(),second.effectiveEvents());
        assertNotEquals(event(first.events(),"IN").id(),event(first.effectiveEvents(),"IN").id());
    }
    @Test void pendingOrRejectedCorrectionNeverChangesEffectiveClock() {
        var original=clocks.day(employee,date);UUID request=submit("KEEP","","SET",time(18),true,null);
        assertEquals(original.events(),clocks.day(employee,date).effectiveEvents());
        workflow.decide(approver,request,new DecisionForm("REJECT",0L,"합성 반려",UUID.randomUUID()));
        assertEquals(original.events(),clocks.day(employee,date).effectiveEvents());
        assertNull(clocks.day(employee,date).confirmation());
    }
    @Test void laterFactRevisionDoesNotReuseApprovedCorrectionEvidence() {
        UUID request=submit("KEEP","","SET",time(18),true,null);approve(request);
        assertNotNull(clocks.day(employee,date).confirmation());
        db.update("UPDATE tna.day_input_revision SET input_revision=input_revision+1 WHERE tenant_id=? AND employee_id=? AND work_date=?",tenant,employee.employeeId(),date);
        var changed=clocks.day(employee,date);assertNull(changed.confirmation());
        assertEquals(at(18),event(changed.effectiveEvents(),"OUT").occurredAt());
    }
    @Test void matchingRawValuesStillChangeFingerprintWhenAnApprovedEffectIsAdded() {
        var before=clocks.day(employee,date);UUID request=submit("SET",time(9),"SET",time(17),true,null);approve(request);
        assertNotEquals(before.sourceFingerprint(),clocks.day(employee,date).sourceFingerprint());
    }

    @Test void personalReconfirmationUsesTheCorrectedPairWithoutDiscardingDuplicateRawEvents() {
        UUID network=db.queryForObject("SELECT id FROM tna.allowed_network WHERE tenant_id=?",UUID.class,tenant);
        db.update("INSERT INTO tna.attendance_event(tenant_id,id,employee_id,event_kind,occurred_at,received_at,client_command_id,source,validated_ip,network_id,assigned_work_date) VALUES (?,?,?,'IN',?,?,?,'IP',CAST('127.0.0.1' AS inet),?,?)",tenant,UUID.randomUUID(),employee.employeeId(),java.sql.Timestamp.from(at(10)),java.sql.Timestamp.from(at(10)),UUID.randomUUID(),network,date);
        db.update("UPDATE tna.day_input_revision SET input_revision=input_revision+1 WHERE tenant_id=? AND employee_id=? AND work_date=?",tenant,employee.employeeId(),date);
        UUID request=submit("SET",time(9),"SET",time(18),false,null);approve(request);
        var day=clocks.day(employee,date);assertEquals(3,day.events().size());assertEquals(2,day.effectiveEvents().size());
        assertNull(day.confirmation());
        clocks.confirm(employee,new ClockData.ConfirmationForm(date,day.inputRevision(),day.reviewRevision(),true,true,day.sourceFingerprint(),UUID.randomUUID()));
        assertNotNull(clocks.day(employee,date).confirmation());
        assertEquals(3,clocks.day(employee,date).events().size());
    }

    @Test void changedScheduleVersionInvalidatesCorrectionEvidenceWithUnchangedFactCounter() {
        UUID request=submit("KEEP","","SET",time(18),true,null);approve(request);
        var before=clocks.day(employee,date);assertNotNull(before.confirmation());
        UUID template=db.queryForObject("SELECT template_id FROM tna.schedule_version WHERE tenant_id=? AND id=?",UUID.class,tenant,before.scheduleVersionId());
        UUID version=UUID.randomUUID();
        db.update("INSERT INTO tna.schedule_version(tenant_id,id,template_id,revision,valid_from,reason,created_by) VALUES (?,?,?,2,?,'새 합성 일정',?)",tenant,version,template,date,manager.accountId());
        db.update("INSERT INTO tna.schedule_day(tenant_id,version_id,weekday,workday,start_time,end_time) VALUES (?,?,?,true,TIME '10:00',TIME '18:00')",tenant,version,date.getDayOfWeek().getValue());
        db.update("INSERT INTO tna.schedule_break(tenant_id,version_id,weekday,sequence,start_time,end_time) VALUES (?,?,?,1,TIME '12:00',TIME '13:00')",tenant,version,date.getDayOfWeek().getValue());
        db.update("UPDATE tna.schedule_assignment SET schedule_version_id=? WHERE tenant_id=? AND employee_id=?",version,tenant,employee.employeeId());
        var after=clocks.day(employee,date);assertEquals(before.inputRevision(),after.inputRevision());
        assertNotEquals(before.scheduleVersionId(),after.scheduleVersionId());assertNull(after.confirmation());
        assertEquals(at(18),event(after.effectiveEvents(),"OUT").occurredAt());
    }
    @Test void changedCalendarVersionInvalidatesCorrectionEvidenceWithUnchangedFactCounter() {
        UUID request=submit("KEEP","","SET",time(18),true,null);approve(request);
        var before=clocks.day(employee,date);assertNotNull(before.confirmation());
        // Simulate a future approved calendar revision without pretending the fact counter changed.
        db.update("UPDATE tna.calendar_version SET valid_from=?,valid_to=? WHERE tenant_id=? AND id=?",date.minusDays(1),date,tenant,before.calendarVersionId());
        UUID version=UUID.randomUUID();
        db.update("INSERT INTO tna.calendar_version(tenant_id,id,revision,valid_from,valid_to,reason,created_by) VALUES (?,?,2,?,?,'새 합성 달력',?)",tenant,version,date,date.plusDays(1),manager.accountId());
        db.update("INSERT INTO tna.calendar_day(tenant_id,version_id,work_date,kind,name,basis) VALUES (?,?,?,'HOLIDAY','검토 휴일','합성 변경 근거')",tenant,version,date);
        var after=clocks.day(employee,date);assertEquals(before.inputRevision(),after.inputRevision());
        assertNotEquals(before.calendarVersionId(),after.calendarVersionId());assertNull(after.confirmation());
        assertEquals(at(18),event(after.effectiveEvents(),"OUT").occurredAt());
    }

    private UUID submit(String in,String inTime,String out,String outTime,boolean confirmed,UUID replaces) {
        var day=clocks.day(employee,date);
        return workflow.submitCorrection(employee,new CorrectionForm(null,date,day.inputRevision(),day.sourceFingerprint(),in,inTime,out,outTime,
                confirmed,confirmed,confirmed?time(12)+"/"+time(13):"",replaces,null,false,"합성 정정",UUID.randomUUID()));
    }
    private void approve(UUID request){workflow.decide(approver,request,new DecisionForm("APPROVE",0L,"독립 승인",UUID.randomUUID()));}
    private ClockData.Event event(List<ClockData.Event> events,String kind){return events.stream().filter(e->e.kind().equals(kind)).findFirst().orElseThrow();}
    private Instant at(int hour){return date.atTime(hour,0).atZone(SEOUL).toInstant();}
    private String time(int hour){return date+"T"+String.format("%02d:00",hour);}
    private TnaPrincipal member(Set<String> roles){
        UUID person=UUID.randomUUID(),account=UUID.randomUUID();
        db.update("INSERT INTO tna.employee(tenant_id,id,employee_no,display_name,joined_on) VALUES (?,?,?,'합성 정정 사원',?)",tenant,person,person.toString(),today.minusYears(1));
        db.update("INSERT INTO tna.tenant_account(tenant_id,id,employee_id,email,password_hash) VALUES (?,?,?,?,?)",tenant,account,person,account+"@example.test","synthetic-projection-hash");
        for(String role:roles)db.update("INSERT INTO tna.role_grant(tenant_id,account_id,role) VALUES (?,?,?)",tenant,account,role);
        return new TnaPrincipal(tenant,account,person,1,roles,Instant.now());
    }
}
