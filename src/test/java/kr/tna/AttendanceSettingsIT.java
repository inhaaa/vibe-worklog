package kr.tna;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import kr.tna.clock.AttendanceSettingsService;
import kr.tna.clock.AttendanceSettingsData.*;
import kr.tna.common.auth.TnaPrincipal;
import kr.tna.org.OrgException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest
class AttendanceSettingsIT {
    private static final LocalDate TODAY=LocalDate.now(ZoneId.of("Asia/Seoul"));
    @Autowired AttendanceSettingsService settings;
    private JdbcTemplate db;
    private UUID tenant;
    private TnaPrincipal actor;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry p) {
        var f=DatabaseFixture.start();
        p.add("spring.datasource.url",f::jdbcUrl);p.add("spring.datasource.username",()->DatabaseFixture.APP_USER);
        p.add("spring.datasource.password",()->DatabaseFixture.APP_PASSWORD);
        p.add("tna.auth.url",f::jdbcUrl);p.add("tna.auth.username",()->DatabaseFixture.AUTH_USER);
        p.add("tna.auth.password",()->DatabaseFixture.AUTH_PASSWORD);
    }
    @BeforeEach void administrator() {
        db=new JdbcTemplate(DatabaseFixture.start().adminDataSource());tenant=UUID.randomUUID();
        db.update("INSERT INTO platform.tenant_registry(id,company_code,display_name) VALUES (?,?,'합성 출퇴근 설정')",tenant,"CLOCK-SETTING-"+tenant);
        UUID employee=UUID.randomUUID(),account=UUID.randomUUID();
        db.update("INSERT INTO tna.employee(tenant_id,id,employee_no,display_name,joined_on) VALUES (?,?,'A','합성 담당자',DATE '2020-01-01')",tenant,employee);
        db.update("INSERT INTO tna.tenant_account(tenant_id,id,employee_id,email,password_hash) VALUES (?,?,?,'actor@example.test','synthetic-only')",tenant,account,employee);
        for (String role:List.of("EMPLOYEE","ATTENDANCE_ADMIN")) db.update("INSERT INTO tna.role_grant(tenant_id,account_id,role) VALUES (?,?,?)",tenant,account,role);
        actor=new TnaPrincipal(tenant,account,employee,1,Set.of("EMPLOYEE","ATTENDANCE_ADMIN"),Instant.now());
    }
    @Test void normalizesIPv4IPv6AndMappedNetworksWithoutDns() {
        UUID v4=settings.addNetwork(actor,network("192.0.2.123/24",false));
        assertEquals("192.0.2.0/24",settings.networks(actor).stream().filter(n->n.id().equals(v4)).findFirst().orElseThrow().network());
        UUID v6=settings.addNetwork(actor,network("2001:db8:0:0::abcd/64",false));
        assertEquals("2001:db8::/64",settings.networks(actor).stream().filter(n->n.id().equals(v6)).findFirst().orElseThrow().network());
        UUID mapped=settings.addNetwork(actor,network("::ffff:198.51.100.8/120",false));
        assertEquals("198.51.100.0/24",settings.networks(actor).stream().filter(n->n.id().equals(mapped)).findFirst().orElseThrow().network());
        assertEquals("127.0.0.1",AttendanceSettingsService.normalizeAddress("::ffff:127.0.0.1"));
    }
    @Test void invalidCidrHostnamesPortsScopedAndAmbiguousAddressesAreRejected() {
        for (String input:List.of("localhost/32","127.0.0.1:80/32","127.0.0.1","127.0.0.1/33","::1/129",
                "fe80::1%eth0/64","192.168.001.1/24","127.0.0.1/2/3","::ffff:192.0.2.1/80")) {
            status(422,()->settings.addNetwork(actor,network(input,true)));
        }
        for(String input:List.of("localhost","[::1]","127.0.0.1:80","192.168.001.1","fe80::1%3","2130706433","127.1")) {
            status(422,()->AttendanceSettingsService.normalizeAddress(input));
        }
        assertTrue(settings.networks(actor).isEmpty());
    }
    @Test void broadNetworksRequireExplicitConfirmation() {
        for(String input:List.of("0.0.0.0/0","10.0.0.0/7","::/0","2001::/15")) status(422,()->settings.addNetwork(actor,network(input,false)));
        assertNotNull(settings.addNetwork(actor,network("0.0.0.0/0",true)));
        assertNotNull(settings.addNetwork(actor,network("::/0",true)));
        assertNotNull(settings.addNetwork(actor,network("10.0.0.0/8",false)));
        assertNotNull(settings.addNetwork(actor,network("2001::/16",false)));
    }
    @Test void networkReplayDuplicateClosingAndNewRegistrationPreserveHistory() {
        UUID command=UUID.randomUUID();
        var form=new NetworkForm("192.0.2.17/24"," 합성 허용망 ",false," 등록 근거 ",command);
        UUID id=settings.addNetwork(actor,form);assertEquals(id,settings.addNetwork(actor,form));
        assertEquals(1,settings.networks(actor).size());
        status(409,()->settings.addNetwork(actor,new NetworkForm("198.51.100.1/24","합성 허용망",false,"등록 근거",command)));
        status(409,()->settings.addNetwork(actor,network("192.0.2.99/24",false)));
        UUID close=UUID.randomUUID();assertEquals(id,settings.closeNetwork(actor,id,0L,"허용 종료",close));
        assertEquals(id,settings.closeNetwork(actor,id,0L,"허용 종료",close));
        var closed=settings.networks(actor).getFirst();assertNotNull(closed.validTo());assertEquals(1,closed.version());
        assertNotNull(settings.addNetwork(actor,network("192.0.2.88/24",false)));
        assertEquals(2,settings.networks(actor).size());
        status(409,()->settings.closeNetwork(actor,id,0L,"오래된 변경",UUID.randomUUID()));
    }
    @Test void networkMetadataAndCurrentPermissionsAreValidated() {
        status(422,()->settings.addNetwork(actor,new NetworkForm("127.0.0.1/32"," ",false,"근거",UUID.randomUUID())));
        status(422,()->settings.addNetwork(actor,new NetworkForm("127.0.0.1/32","명칭",false," ",UUID.randomUUID())));
        TnaPrincipal employee=new TnaPrincipal(tenant,actor.accountId(),actor.employeeId(),1,Set.of("EMPLOYEE"),Instant.now());
        status(403,()->settings.networks(employee));status(403,()->settings.addNetwork(employee,network("127.0.0.1/32",false)));
        db.update("UPDATE tna.role_grant SET active=false WHERE tenant_id=? AND role='ATTENDANCE_ADMIN'",tenant);
        status(403,()->settings.addNetwork(actor,network("127.0.0.1/32",false)));
    }
    private NetworkForm network(String network,boolean confirmed) { return new NetworkForm(network,"합성 허용망",confirmed,"등록 근거",UUID.randomUUID()); }
    private void status(int expected,Runnable action) { assertEquals(expected,assertThrows(OrgException.class,action::run).status()); }
}
