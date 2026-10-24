package kr.tna;
import static org.junit.jupiter.api.Assertions.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import kr.tna.common.auth.TnaPrincipal;
import kr.tna.common.tenant.*;
import kr.tna.org.OrgCommands;
import kr.tna.org.OrgException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.jdbc.core.JdbcTemplate;
class BusinessCommandsIT {
    BusinessCommands commands;JdbcTemplate admin;TnaPrincipal actor;
    @BeforeEach void seed() {
        var db=DatabaseFixture.start();admin=new JdbcTemplate(db.adminDataSource());
        UUID tenant=UUID.randomUUID(),employee=UUID.randomUUID(),account=UUID.randomUUID();
        admin.update("INSERT INTO platform.tenant_registry(id,company_code,display_name) VALUES (?,?,'합성 직원 경계')",tenant,tenant.toString());
        admin.update("INSERT INTO tna.employee(tenant_id,id,employee_no,display_name,joined_on) VALUES (?,?,'E','합성 사원',DATE '2020-01-01')",tenant,employee);
        admin.update("INSERT INTO tna.tenant_account(tenant_id,id,employee_id,email,password_hash) VALUES (?,?,?,'synthetic@example.test','synthetic-hash')",tenant,account,employee);
        admin.update("INSERT INTO tna.role_grant(tenant_id,account_id,role) VALUES (?,?,'EMPLOYEE')",tenant,account);
        actor=new TnaPrincipal(tenant,account,employee,1,Set.of("EMPLOYEE"),Instant.now());
        commands=new BusinessCommands(new TenantTransactions(db.appDataSource()),db.appDataSource(),Clock.systemUTC());
    }
    @Test @ExtendWith(OutputCaptureExtension.class)
    void failuresLogFixedOutcomesWithoutRawInput(CapturedOutput output) {
        assertThrows(OrgException.class,()->commands.execute(actor,UUID.randomUUID(),"SECRET_ACTION_MARKER","EMPLOYEE",OrgCommands.fingerprint("x"),"SECRET_REASON_MARKER","ATTENDANCE_ADMIN",()->new OrgCommands.Change(actor.employeeId(),null,"x")));
        assertThrows(OrgException.class,()->commands.execute(actor,UUID.randomUUID(),"STORAGE_TEST","EMPLOYEE",OrgCommands.fingerprint("x"),"SECRET_REASON_MARKER","EMPLOYEE",()->{throw new org.springframework.jdbc.BadSqlGrammarException("SECRET_SQL_MARKER","SECRET_SQL_MARKER",new java.sql.SQLException("SECRET_DATABASE_MARKER"));}));
        assertTrue(output.getAll().contains("event=business_command outcome=denied"));
        assertTrue(output.getAll().contains("event=business_command outcome=storage_unavailable"));
        for(String secret:List.of("SECRET_ACTION_MARKER","SECRET_REASON_MARKER","SECRET_SQL_MARKER","SECRET_DATABASE_MARKER",actor.accountId().toString()))assertFalse(output.getAll().contains(secret));
    }
    @Test void employeeCanReadWithoutAdministratorRole() {assertEquals(7,commands.read(actor,()->7));}
    @Test void staleAndRevokedEmployeeCannotRead() {
        admin.update("UPDATE tna.tenant_account SET auth_version=2 WHERE tenant_id=? AND id=?",actor.tenantId(),actor.accountId());
        assertEquals(403,assertThrows(OrgException.class,()->commands.read(actor,()->7)).status());
        assertEquals(403,assertThrows(OrgException.class,()->commands.read(null,()->7)).status());
    }
    @Test void commandReplayIsAtomicAndDifferentInputConflicts() {
        var calls=new AtomicInteger();UUID cmd=UUID.randomUUID(),target=UUID.randomUUID();
        for(int i=0;i<2;i++)assertEquals(target,commands.execute(actor,cmd,"SELF_TEST","EMPLOYEE",OrgCommands.fingerprint("one"),"합성 명령","EMPLOYEE",()->{calls.incrementAndGet();return new OrgCommands.Change(target,null,"saved");}));
        assertEquals(1,calls.get());assertEquals(1,admin.queryForObject("SELECT count(*) FROM tna.org_audit_event WHERE tenant_id=? AND command_id=?",Integer.class,actor.tenantId(),cmd));
        assertEquals(409,assertThrows(OrgException.class,()->commands.execute(actor,cmd,"SELF_TEST","EMPLOYEE",OrgCommands.fingerprint("two"),"합성 명령","EMPLOYEE",()->new OrgCommands.Change(target,null,"changed"))).status());
    }
    @Test void employeeCannotInvokeAdministratorMutation() {
        assertEquals(403,assertThrows(OrgException.class,()->commands.execute(actor,UUID.randomUUID(),"ROLE_TEST","EMPLOYEE",OrgCommands.fingerprint("x"),"합성 명령","ATTENDANCE_ADMIN",()->new OrgCommands.Change(actor.employeeId(),null,"x"))).status());
    }
}
