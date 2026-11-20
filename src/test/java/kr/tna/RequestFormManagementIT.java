package kr.tna;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Set;
import java.util.UUID;
import kr.tna.approval.RequestFormData.Command;
import kr.tna.approval.RequestFormMapper;
import kr.tna.approval.RequestFormService;
import kr.tna.common.auth.TnaPrincipal;
import kr.tna.common.tenant.TenantTransactions;
import kr.tna.org.OrgException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest
class RequestFormManagementIT {
    @Autowired RequestFormService service;
    @Autowired RequestFormMapper forms;
    @Autowired TenantTransactions transactions;

    @DynamicPropertySource static void database(DynamicPropertyRegistry properties) {
        var db=DatabaseFixture.start();
        properties.add("spring.datasource.url", db::jdbcUrl);
        properties.add("spring.datasource.username", () -> DatabaseFixture.APP_USER);
        properties.add("spring.datasource.password", () -> DatabaseFixture.APP_PASSWORD);
        properties.add("tna.auth.url", db::jdbcUrl);
        properties.add("tna.auth.username", () -> DatabaseFixture.AUTH_USER);
        properties.add("tna.auth.password", () -> DatabaseFixture.AUTH_PASSWORD);
    }

    @Test void seedsAreIsolatedAndReplacementOnFutureDateIsAtomicAndReplaySafe() {
        var db=new JdbcTemplate(DatabaseFixture.start().adminDataSource());
        var tenant=UUID.randomUUID(); var employee=UUID.randomUUID(); var account=UUID.randomUUID();
        db.update("INSERT INTO platform.tenant_registry(id,company_code,display_name) VALUES (?,?,'합성 회사')",tenant,"FORM-"+tenant);
        var today=LocalDate.now(ZoneId.of("Asia/Seoul"));
        db.update("INSERT INTO tna.employee(tenant_id,id,employee_no,display_name,joined_on) VALUES (?,?,'A','담당자',?)",tenant,employee,today);
        db.update("INSERT INTO tna.tenant_account(tenant_id,id,employee_id,email,password_hash) VALUES (?,?,?,?,'synthetic-hash')",tenant,account,employee,account+"@example.test");
        db.update("INSERT INTO tna.role_grant(tenant_id,account_id,role) VALUES (?,?,'ATTENDANCE_ADMIN')",tenant,account);
        var actor=new TnaPrincipal(tenant,account,employee,1,Set.of("ATTENDANCE_ADMIN"),Instant.now());
        var initial=service.page(actor,"",null);
        assertEquals(5,initial.forms().size());
        var leave=initial.forms().stream().filter(row->row.code().equals("L01")).findFirst().orElseThrow();
        var date=today.plusDays(5);
        var replace=new Command(null,null,"007","LEAVE_DAY","새 신청",date,true,null,leave.id(),leave.version(),"새 명칭 적용",UUID.randomUUID());
        var id=service.save(actor,replace);
        assertEquals(id,service.save(actor,replace));
        transactions.execute(tenant,()->{
            assertEquals("L01",forms.effective(tenant,"LEAVE_DAY",date.minusDays(1)).code());
            assertEquals("007",forms.effective(tenant,"LEAVE_DAY",date).code());
            assertEquals("L02",forms.effective(tenant,"CANCEL_LEAVE",date).code());
            return null;
        });
        var conflicting=new Command(null,null,"L10","LEAVE_DAY","겹치는 예약",today.plusDays(2),true,null,leave.id(),leave.version(),"예약 충돌",UUID.randomUUID());
        assertThrows(OrgException.class,()->service.save(actor,conflicting));
        assertEquals(0,service.page(actor,"L10",null).forms().size());

        var other=UUID.randomUUID();
        db.update("INSERT INTO platform.tenant_registry(id,company_code,display_name) VALUES (?,?,'다른 합성 회사')",other,"FORM-"+other);
        transactions.execute(other,()->{
            assertEquals("L01",forms.effective(other,"LEAVE_DAY",date).code());
            assertNull(forms.master(tenant,id));
            return null;
        });
        var newCode=service.page(actor,"007",id).selected();
        var stop=new Command(id,newCode.version(),"007","LEAVE_DAY","새 신청",date.plusDays(1),false,null,null,null,"휴가 접수 중지",UUID.randomUUID());
        service.save(actor,stop);
        transactions.execute(tenant,()->{
            assertNull(forms.effective(tenant,"LEAVE_DAY",date.plusDays(1)));
            assertEquals("L02",forms.effective(tenant,"CANCEL_LEAVE",date.plusDays(1)).code());
            return null;
        });
        db.update("DELETE FROM tna.role_grant WHERE tenant_id=? AND account_id=? AND role='ATTENDANCE_ADMIN'",tenant,account);
        assertEquals(403,assertThrows(OrgException.class,()->service.page(actor,"",id)).status());
        assertEquals(403,assertThrows(OrgException.class,()->service.save(actor,stop)).status());
    }
}
