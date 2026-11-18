package kr.tna;

import static org.junit.jupiter.api.Assertions.*;

import java.time.*;
import java.util.*;
import kr.tna.common.auth.TnaPrincipal;
import kr.tna.common.tenant.TenantTransactions;
import kr.tna.org.OrgException;
import kr.tna.schedule.WorkTimeCodeData.*;
import kr.tna.schedule.WorkTimeCodeMapper;
import kr.tna.schedule.WorkTimeCodeService;
import kr.tna.schedule.WorkGroupService;
import kr.tna.schedule.WorkGroupData.GroupCommand;
import kr.tna.schedule.WorkGroupData.WeekInput;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest
class WorkTimeCodeIT {
    @Autowired WorkTimeCodeService service;
    @Autowired WorkTimeCodeMapper mapper;
    @Autowired WorkGroupService groups;
    @Autowired TenantTransactions transactions;

    @DynamicPropertySource static void database(DynamicPropertyRegistry properties) {
        var db=DatabaseFixture.start();
        properties.add("spring.datasource.url",db::jdbcUrl);
        properties.add("spring.datasource.username",()->DatabaseFixture.APP_USER);
        properties.add("spring.datasource.password",()->DatabaseFixture.APP_PASSWORD);
        properties.add("tna.auth.url",db::jdbcUrl);
        properties.add("tna.auth.username",()->DatabaseFixture.AUTH_USER);
        properties.add("tna.auth.password",()->DatabaseFixture.AUTH_PASSWORD);
    }

    private Command command(UUID id, Long expectedVersion, LocalDate date, boolean enabled, UUID replace) {
        return new Command(UUID.randomUUID(),id,expectedVersion,"001","주간 근무",date,enabled,
                LocalTime.of(9,0),LocalTime.of(18,0),null,null,LocalTime.of(18,30),LocalTime.of(21,0),
                List.of(new Break("REGULAR",LocalTime.NOON,LocalTime.of(13,0)),
                        new Break("AFTER",LocalTime.of(20,0),LocalTime.of(20,15))),"기준 개정",replace);
    }

    private UUID publish(TnaPrincipal admin,UUID groupId,String group,Long expected,LocalDate date,UUID dailyVersion) {
        var week=java.util.stream.IntStream.rangeClosed(1,7).mapToObj(i->new WeekInput(i,i<=5?dailyVersion:null)).toList();
        var form=new GroupCommand(groupId,group,group+" 주간조",date,week,"시간코드 적용",expected,null,null,UUID.randomUUID());
        return groups.saveGroup(admin,form.confirm(groups.previewGroup(admin,form).token()));
    }

    @Test void immutableRevisionsPreserveReservationsAndReplaysAndCheckTenantAndRole() {
        var db=new JdbcTemplate(DatabaseFixture.start().adminDataSource());
        UUID tenant=UUID.randomUUID(),employee=UUID.randomUUID(),account=UUID.randomUUID();
        LocalDate today=LocalDate.now(ZoneId.of("Asia/Seoul")), date=today.plusDays(10);
        db.update("INSERT INTO platform.tenant_registry(id,company_code,display_name) VALUES (?,?,'합성 회사')",tenant,"CODE-"+tenant);
        db.update("INSERT INTO tna.employee(tenant_id,id,employee_no,display_name,joined_on) VALUES (?,?,'01','담당자',?)",tenant,employee,today);
        db.update("INSERT INTO tna.tenant_account(tenant_id,id,employee_id,email,password_hash) VALUES (?,?,?,?,'test-hash')",tenant,account,employee,account+"@example.test");
        db.update("INSERT INTO tna.role_grant(tenant_id,account_id,role) VALUES (?,?,'ATTENDANCE_ADMIN')",tenant,account);
        var admin=new TnaPrincipal(tenant,account,employee,1,Set.of("ATTENDANCE_ADMIN"),Instant.now());
        var initial=command(null,null,date,true,null);
        UUID codeId=service.save(admin,initial);
        assertEquals(codeId,service.save(admin,initial));
        var first=service.catalog(admin,date).codes().getFirst();
        assertEquals("001",first.code());
        assertEquals(480,first.versions().getFirst().scheduledMinutes());
        assertEquals(135,first.versions().getFirst().overtimeMinutes());
        var stop=command(codeId,first.version(),date.plusDays(5),false,null);
        service.save(admin,stop);
        assertEquals(409,assertThrows(OrgException.class,()->service.save(admin,command(codeId,1L,date.plusDays(5),true,null))).status());
        var revisions=service.catalog(admin,date).codes().getFirst().versions();
        assertEquals(2,revisions.size());
        assertFalse(revisions.getFirst().enabled());
        assertTrue(revisions.getLast().enabled());
        assertEquals(2,db.queryForObject("SELECT count(*) FROM tna.org_audit_event WHERE tenant_id=? AND target_id=?",Integer.class,tenant,codeId));
        var replacement=command(codeId,1L,date.plusDays(5),true,revisions.getFirst().id());
        service.save(admin,replacement);
        var savedVersions=service.catalog(admin,date).codes().getFirst().versions();
        assertEquals(3,savedVersions.size());
        var oldDaily=savedVersions.getLast().id();
        var g01=publish(admin,null,"G01",null,date,oldDaily);
        publish(admin,null,"G02",null,date,oldDaily);
        publish(admin,g01,"G01",0L,date.plusDays(5),savedVersions.getFirst().id());
        var original=service.catalog(admin,date.plusDays(4)).codes().getFirst().versions().getLast();
        assertEquals(List.of("G01","G02"),original.groups().stream().map(GroupUse::code).toList());
        var changed=service.catalog(admin,date.plusDays(5)).codes().getFirst().versions();
        assertEquals(List.of("G01"),changed.getFirst().groups().stream().map(GroupUse::code).toList());
        assertEquals(List.of("G02"),changed.getLast().groups().stream().map(GroupUse::code).toList());
        assertNull(transactions.execute(UUID.randomUUID(),()->mapper.master(tenant,codeId)));
        db.update("UPDATE tna.role_grant SET active=false WHERE tenant_id=? AND account_id=? AND role='ATTENDANCE_ADMIN'",tenant,account);
        assertEquals(403,assertThrows(OrgException.class,()->service.catalog(admin,date)).status());
        assertEquals(403,assertThrows(OrgException.class,()->service.save(admin,replacement)).status());
    }
}
