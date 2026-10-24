package kr.tna;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import kr.tna.clock.ClockData.*;
import kr.tna.clock.ClockService;
import kr.tna.clock.ClockMapper;
import kr.tna.common.auth.TnaPrincipal;
import kr.tna.common.tenant.TenantTransactions;
import kr.tna.org.OrgException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest
class AttendanceServiceIT {
    private static final ZoneId SEOUL=ZoneId.of("Asia/Seoul");
    private static final LocalDate TODAY=LocalDate.now(SEOUL);
    private static final LocalDate DAY=TODAY.minusDays(1);
    @Autowired ClockService clocks;
    @Autowired ClockMapper mapper;
    @Autowired TenantTransactions transactions;
    private JdbcTemplate db;
    private UUID tenant;
    private UUID network;
    private TnaPrincipal actor;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry properties) {
        var fixture=DatabaseFixture.start();
        properties.add("spring.datasource.url",fixture::jdbcUrl);
        properties.add("spring.datasource.username",()->DatabaseFixture.APP_USER);
        properties.add("spring.datasource.password",()->DatabaseFixture.APP_PASSWORD);
        properties.add("tna.auth.url",fixture::jdbcUrl);
        properties.add("tna.auth.username",()->DatabaseFixture.AUTH_USER);
        properties.add("tna.auth.password",()->DatabaseFixture.AUTH_PASSWORD);
    }
    @BeforeEach
    void identities() {
        db=new JdbcTemplate(DatabaseFixture.start().adminDataSource());tenant=UUID.randomUUID();
        db.update("INSERT INTO platform.tenant_registry(id,company_code,display_name) VALUES (?,?,'합성 출퇴근')",tenant,"CLOCK-"+tenant);
        UUID employee=UUID.randomUUID(),account=UUID.randomUUID();
        db.update("INSERT INTO tna.employee(tenant_id,id,employee_no,display_name,joined_on) VALUES (?,?,?,'합성 출퇴근 사원',?)",tenant,employee,employee.toString(),TODAY.minusYears(1));
        db.update("INSERT INTO tna.tenant_account(tenant_id,id,employee_id,email,password_hash) VALUES (?,?,?,?,?)",tenant,account,employee,account+"@example.test","synthetic-clock-hash");
        db.update("INSERT INTO tna.role_grant(tenant_id,account_id,role) VALUES (?,?,'EMPLOYEE')",tenant,account);
        actor=new TnaPrincipal(tenant,account,employee,1,Set.of("EMPLOYEE"),Instant.now());
        network=UUID.randomUUID();
        db.update("INSERT INTO tna.allowed_network(tenant_id,id,network,label,valid_from,reason,created_by) VALUES (?,?,CAST(? AS cidr),'합성 허용망',?,'합성 설정',?)",tenant,network,"127.0.0.0/8",java.sql.Timestamp.from(Instant.now().minusSeconds(3600)),account);
    }

    @Test
    void recordsServerMillisecondsAndReplaysOneEventWithoutCopyingIpToAudit() {
        UUID command=UUID.randomUUID();Instant before=Instant.now();
        UUID id=clocks.record(actor,"IN",command,"127.0.0.1");Instant after=Instant.now();assertNotNull(id);
        assertEquals(id,clocks.record(actor,"IN",command,"127.0.0.1"));
        var day=clocks.day(actor,TODAY);assertEquals(1,day.events().size());
        var event=day.events().getFirst();assertEquals(id,event.id());assertEquals("IN",event.kind());
        assertFalse(event.occurredAt().isBefore(before.minusMillis(1)));assertFalse(event.occurredAt().isAfter(after));
        assertEquals(0,event.occurredAt().getNano()%1_000_000);assertEquals(TODAY,event.workDate());
        assertEquals(1,day.inputRevision());assertEquals(0,day.reviewRevision());
        assertEquals(1,count("tna.org_audit_event","command_id",command));
        String audit=db.queryForObject("SELECT before_state::text||after_state::text FROM tna.org_audit_event WHERE tenant_id=? AND command_id=?",String.class,tenant,command);
        assertFalse(audit.contains("127.0.0.1"));assertFalse(event.toString().contains("127.0.0.1"));
        assertEquals(network,db.queryForObject("SELECT network_id FROM tna.attendance_event WHERE tenant_id=? AND id=?",UUID.class,tenant,id));
    }

    @Test
    void sameCommandCannotChangeEventKindAndInvalidKindsHaveNoEffect() {
        UUID command=UUID.randomUUID();clocks.record(actor,"IN",command,"127.0.0.1");
        assertStatus(409,()->clocks.record(actor,"OUT",command,"127.0.0.1"));
        assertStatus(422,()->clocks.record(actor,"BREAK",UUID.randomUUID(),"127.0.0.1"));
        assertEquals(1,clocks.day(actor,TODAY).events().size());
    }

    @Test
    void validatesNumericAddressAndCurrentAllowedNetwork() {
        for(String address:List.of("localhost","127.0.0.1:8080","127.000.0.1","[::1]","::1%eth0","256.0.0.1")) {
            assertStatus(422,()->clocks.record(actor,"IN",UUID.randomUUID(),address));
        }
        assertStatus(403,()->clocks.record(actor,"IN",UUID.randomUUID(),"198.51.100.7"));
        db.update("UPDATE tna.allowed_network SET valid_to=? WHERE tenant_id=? AND id=?",java.sql.Timestamp.from(Instant.now().minusSeconds(1)),tenant,network);
        assertStatus(403,()->clocks.record(actor,"IN",UUID.randomUUID(),"127.0.0.1"));
        assertEquals(0,clocks.day(actor,TODAY).events().size());
    }

    @Test
    void mappedIpv6NormalizesToAllowedIpv4() {
        UUID id=clocks.record(actor,"IN",UUID.randomUUID(),"::ffff:127.0.0.1");assertNotNull(id);
        assertEquals("127.0.0.1",db.queryForObject("SELECT host(validated_ip) FROM tna.attendance_event WHERE tenant_id=? AND id=?",String.class,tenant,id));
    }

    @Test
    void distinctDuplicateEventsArePreservedAndCannotBeConfirmed() {
        configure(DAY);seedEvent(DAY,"IN",9,0);seedEvent(DAY,"IN",9,1);seedEvent(DAY,"OUT",18,0);
        var day=clocks.day(actor,DAY);assertEquals(3,day.events().size());
        assertStatus(422,()->clocks.confirm(actor,confirmation(day,true,true)));
        assertEquals(0,count("tna.work_confirmation","employee_id",actor.employeeId()));
    }

    @Test
    void confirmationBindsEvidenceAndOnlyAdvancesReviewRevision() {
        configure(DAY);pair(DAY,9,18);var before=clocks.day(actor,DAY);
        var form=confirmation(before,true,true);UUID id=clocks.confirm(actor,form);assertNotNull(id);
        assertEquals(id,clocks.confirm(actor,form));
        var after=clocks.day(actor,DAY);assertEquals(2,after.inputRevision());assertEquals(1,after.reviewRevision());
        var evidence=after.confirmation();assertNotNull(evidence);assertEquals(id,evidence.id());
        assertEquals(2,evidence.confirmedInputRevision());assertEquals(before.sourceFingerprint(),evidence.sourceFingerprint());
        assertEquals(1,evidence.breaks().size());
        assertEquals(at(DAY,12,0),evidence.breaks().getFirst().startAt());assertEquals(at(DAY,13,0),evidence.breaks().getFirst().endAt());
        assertEquals(1,count("tna.work_confirmation","employee_id",actor.employeeId()));
        assertEquals(1,count("tna.org_audit_event","command_id",form.commandId()));
    }

    @Test
    void presenceAndBreakMismatchRequireCorrectionInsteadOfFalseEvidence() {
        configure(DAY);pair(DAY,9,18);var day=clocks.day(actor,DAY);
        assertStatus(422,()->clocks.confirm(actor,confirmation(day,false,true)));
        assertStatus(422,()->clocks.confirm(actor,confirmation(day,true,false)));
        assertEquals(0,clocks.day(actor,DAY).reviewRevision());
    }

    @Test
    void missingOutOrScheduleOrCalendarNeverCreatesConfirmation() {
        assertEquals("UNCONFIRMED",clocks.day(actor,DAY).dayKind());
        assertNull(clocks.day(actor,DAY).schedule());
        seedEvent(DAY,"IN",9,0);
        assertStatus(422,()->clocks.confirm(actor,confirmation(clocks.day(actor,DAY),true,true)));
        configure(DAY);
        assertStatus(422,()->clocks.confirm(actor,confirmation(clocks.day(actor,DAY),true,true)));
        seedEvent(DAY,"OUT",18,0);
        db.update("DELETE FROM tna.calendar_day WHERE tenant_id=? AND work_date=?",tenant,DAY);
        assertStatus(422,()->clocks.confirm(actor,confirmation(clocks.day(actor,DAY),true,true)));
    }

    @Test
    void staleFactsAndReviewRevisionAreRejectedWithoutNewEvidence() {
        configure(DAY);pair(DAY,9,18);var old=clocks.day(actor,DAY);
        clocks.confirm(actor,confirmation(old,true,true));
        assertStatus(409,()->clocks.confirm(actor,confirmation(old,true,true)));
        var current=clocks.day(actor,DAY);
        seedEvent(DAY,"IN",10,0);
        assertStatus(409,()->clocks.confirm(actor,confirmation(current,true,true)));
        assertNull(clocks.day(actor,DAY).confirmation());
        assertEquals(1,count("tna.work_confirmation","employee_id",actor.employeeId()));
    }

    @Test
    void changedScheduleFingerprintInvalidatesOldConfirmationEvenWithoutFactCounterChange() {
        UUID assignment=configure(DAY);pair(DAY,9,18);var old=clocks.day(actor,DAY);
        clocks.confirm(actor,confirmation(old,true,true));var confirmed=clocks.day(actor,DAY);
        UUID changed=version(DAY,10,19);
        db.update("UPDATE tna.schedule_assignment SET schedule_version_id=? WHERE tenant_id=? AND id=?",changed,tenant,assignment);
        var after=clocks.day(actor,DAY);assertEquals(confirmed.inputRevision(),after.inputRevision());
        assertNotEquals(old.sourceFingerprint(),after.sourceFingerprint());assertNull(after.confirmation());
        assertStatus(409,()->clocks.confirm(actor,new ConfirmationForm(DAY,after.inputRevision(),after.reviewRevision(),true,true,old.sourceFingerprint(),UUID.randomUUID())));
    }

    @Test
    void plannedBreakOutsideActualPresenceIsNotClipped() {
        configure(DAY);pair(DAY,13,18);var day=clocks.day(actor,DAY);
        assertStatus(422,()->clocks.confirm(actor,confirmation(day,true,true)));
        assertEquals(0,count("tna.work_confirmation","employee_id",actor.employeeId()));
    }

    @Test
    void emptyAndFutureDaysHaveNoInventedEventsOrConfirmation() {
        for(LocalDate date:List.of(DAY,TODAY.plusDays(1))) {
            var day=clocks.day(actor,date);assertEquals(actor.employeeId(),day.employeeId());
            assertTrue(day.events().isEmpty());assertNull(day.confirmation());assertEquals(0,day.inputRevision());
            assertNotNull(day.sourceFingerprint());
            assertStatus(422,()->clocks.confirm(actor,confirmation(day,true,true)));
        }
    }

    @Test
    void roleAndEmploymentAreRevalidatedBeforeEveryReadAndWrite() {
        db.update("UPDATE tna.role_grant SET active=false WHERE tenant_id=? AND account_id=? AND role='EMPLOYEE'",tenant,actor.accountId());
        assertStatus(403,()->clocks.day(actor,TODAY));
        assertStatus(403,()->clocks.record(actor,"IN",UUID.randomUUID(),"127.0.0.1"));
    }

    @Test
    void crossMidnightEventsRemainOnTheirObservedDatesWithoutAutomaticCheckout() {
        seedEvent(DAY,"IN",22,0);seedEvent(TODAY,"OUT",6,0);
        assertEquals(1,clocks.day(actor,DAY).events().size());assertEquals(1,clocks.day(actor,TODAY).events().size());
        assertStatus(422,()->clocks.confirm(actor,confirmation(clocks.day(actor,DAY),true,true)));
        assertStatus(422,()->clocks.confirm(actor,confirmation(clocks.day(actor,TODAY),true,true)));
    }

    @Test
    void internalSnapshotRequiresAnExistingMatchingTenantTransaction() {
        assertThrows(IllegalStateException.class,()->clocks.snapshot(tenant,actor.employeeId(),DAY));
        transactions.execute(tenant,()->{
            assertThrows(IllegalStateException.class,()->clocks.snapshot(UUID.randomUUID(),actor.employeeId(),DAY));
            assertEquals(actor.employeeId(),clocks.snapshot(tenant,actor.employeeId(),DAY).employeeId());
            return null;
        });
    }

    @Test
    void recordMapperWritesEventAndRevisionWithRuntimePrivileges() {
        transactions.execute(tenant,()->{
            assertEquals(network.toString(),mapper.network(tenant,"127.0.0.1",Instant.now()));
            var event=new Event(UUID.randomUUID(),"IN",Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS),TODAY);
            assertEquals(1,mapper.event(tenant,actor.employeeId(),event,UUID.randomUUID(),"127.0.0.1",network));
            assertEquals(1,mapper.advanceInput(tenant,actor.employeeId(),TODAY));
            return null;
        });
        assertEquals(1,clocks.day(actor,TODAY).events().size());
        assertEquals(1,clocks.day(actor,TODAY).inputRevision());
    }

    private UUID configure(LocalDate day) {
        UUID version=version(day,9,18),assignment=UUID.randomUUID(),calendar=UUID.randomUUID();
        db.update("INSERT INTO tna.schedule_assignment(tenant_id,id,employee_id,schedule_version_id,valid_from,reason,created_by,updated_by) VALUES (?,?,?,?,?,'합성 일정',?,?)",tenant,assignment,actor.employeeId(),version,day,actor.accountId(),actor.accountId());
        db.update("INSERT INTO tna.calendar_version(tenant_id,id,revision,valid_from,valid_to,reason,created_by) VALUES (?,?,1,?,?,'합성 달력',?)",tenant,calendar,day,day.plusDays(1),actor.accountId());
        db.update("INSERT INTO tna.calendar_day(tenant_id,version_id,work_date,kind,name,basis) VALUES (?,?,?,'WORKDAY','합성 근로일','합성 날짜 확인')",tenant,calendar,day);
        return assignment;
    }
    private UUID version(LocalDate date,int start,int end) {
        UUID template=UUID.randomUUID(),version=UUID.randomUUID();
        db.update("INSERT INTO tna.schedule_template(tenant_id,id,code,name,created_by,updated_by) VALUES (?,?,?,'합성 일정',?,?)",tenant,template,template.toString(),actor.accountId(),actor.accountId());
        db.update("INSERT INTO tna.schedule_version(tenant_id,id,template_id,revision,valid_from,reason,created_by) VALUES (?,?,?,1,?,'합성 일정',?)",tenant,version,template,date,actor.accountId());
        db.update("INSERT INTO tna.schedule_day(tenant_id,version_id,weekday,workday,start_time,end_time) VALUES (?,?,?,true,CAST(? AS time),CAST(? AS time))",tenant,version,date.getDayOfWeek().getValue(),start+":00",end+":00");
        db.update("INSERT INTO tna.schedule_break(tenant_id,version_id,weekday,sequence,start_time,end_time) VALUES (?,?,?,1,TIME '12:00',TIME '13:00')",tenant,version,date.getDayOfWeek().getValue());
        return version;
    }
    private void pair(LocalDate day,int in,int out){seedEvent(day,"IN",in,0);seedEvent(day,"OUT",out,0);}
    private void seedEvent(LocalDate day,String kind,int hour,int minute) {
        UUID id=UUID.randomUUID();
        db.update("INSERT INTO tna.attendance_event(tenant_id,id,employee_id,event_kind,occurred_at,received_at,client_command_id,source,validated_ip,network_id,assigned_work_date) VALUES (?,?,?,?,?, ?,?,'IP',CAST('127.0.0.1' AS inet),?,?)",tenant,id,actor.employeeId(),kind,java.sql.Timestamp.from(at(day,hour,minute)),java.sql.Timestamp.from(at(day,hour,minute)),UUID.randomUUID(),network,day);
        db.update("INSERT INTO tna.day_input_revision(tenant_id,employee_id,work_date,input_revision,review_revision,recalculation_required) VALUES (?,?,?,1,0,true) ON CONFLICT(tenant_id,employee_id,work_date) DO UPDATE SET input_revision=day_input_revision.input_revision+1,recalculation_required=true",tenant,actor.employeeId(),day);
    }
    private static Instant at(LocalDate date,int hour,int minute){return date.atTime(hour,minute).atZone(SEOUL).toInstant();}
    private static ConfirmationForm confirmation(DaySnapshot day,boolean work,boolean breaks){return new ConfirmationForm(day.workDate(),day.inputRevision(),day.reviewRevision(),work,breaks,day.sourceFingerprint(),UUID.randomUUID());}
    private int count(String table,String field,UUID value){return db.queryForObject("SELECT count(*) FROM "+table+" WHERE tenant_id=? AND "+field+"=?",Integer.class,tenant,value);}
    private static void assertStatus(int status,Runnable action){assertEquals(status,assertThrows(OrgException.class,action::run).status());}
}
