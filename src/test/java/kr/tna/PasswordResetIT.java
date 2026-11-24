package kr.tna;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.*;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.*;
import javax.sql.DataSource;
import kr.tna.common.auth.*;
import kr.tna.common.tenant.TenantTransactions;
import kr.tna.org.OrgException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.AbstractDataSource;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest
@ActiveProfiles("local")
class PasswordResetIT {
    private static final String OLD="Synthetic reset old 43!",NEW="Synthetic reset replacement 43!";
    private static final String HASH=PasswordPolicy.passwordEncoder().encode(OLD);
    @Autowired PasswordResetService resets;
    @Autowired InvitationDelivery delivery;
    @Autowired AuthStore auth;
    private JdbcTemplate db;
    private UUID tenant,employee,account;
    private String company,email;
    private TnaPrincipal actor;
    @DynamicPropertySource static void database(DynamicPropertyRegistry p) {
        var f=DatabaseFixture.start();p.add("spring.datasource.url",f::jdbcUrl);
        p.add("spring.datasource.username",()->DatabaseFixture.APP_USER);p.add("spring.datasource.password",()->DatabaseFixture.APP_PASSWORD);
        p.add("tna.auth.url",f::jdbcUrl);p.add("tna.auth.username",()->DatabaseFixture.AUTH_USER);p.add("tna.auth.password",()->DatabaseFixture.AUTH_PASSWORD);
    }
    @BeforeEach void account() {
        db=new JdbcTemplate(DatabaseFixture.start().adminDataSource());tenant=UUID.randomUUID();employee=UUID.randomUUID();account=UUID.randomUUID();
        company=("RESET-"+tenant).toUpperCase(java.util.Locale.ROOT);email=account+"@example.test";
        db.update("INSERT INTO platform.tenant_registry(id,company_code,display_name) VALUES (?,?,'합성 재설정 고객사')",tenant,company);
        db.update("INSERT INTO tna.employee(tenant_id,id,employee_no,display_name,joined_on) VALUES (?,?,'A','합성 사원',DATE '2020-01-01')",tenant,employee);
        db.update("INSERT INTO tna.tenant_account(tenant_id,id,employee_id,email,password_hash) VALUES (?,?,?,?,?)",tenant,account,employee,email,HASH);
        db.update("INSERT INTO tna.role_grant(tenant_id,account_id,role) VALUES (?,?,'EMPLOYEE')",tenant,account);
        actor=new TnaPrincipal(tenant,account,employee,1,Set.of("EMPLOYEE"),Instant.now());
    }
    @Test void unknownAccountHasNoEffectsWhileKnownRequestIssuesBoundPurposeToken() {
        resets.request(company,"unknown@example.test",company);assertTrue(messages().isEmpty());assertEquals(0,tokens());
        resets.request("UNKNOWN-"+company,email,company);assertTrue(messages().isEmpty());
        resets.request(company.toLowerCase(java.util.Locale.ROOT)," "+email.toUpperCase(java.util.Locale.ROOT)+" ",company);
        assertEquals(1,messages().size());assertEquals(43,message().token().length());assertFalse(message().toString().contains(message().token()));
        assertEquals(message().expiresAt(), db.queryForObject(
                "SELECT expires_at FROM tna.account_token WHERE tenant_id=?", java.sql.Timestamp.class, tenant).toInstant());
        assertEquals("RESET",db.queryForObject("SELECT purpose FROM tna.account_token WHERE tenant_id=?",String.class,tenant));
        assertEquals(1L,db.queryForObject("SELECT issued_auth_version FROM tna.account_token WHERE tenant_id=?",Long.class,tenant));
        assertEquals(HASH,hash());assertTrue(auth.isCurrent(actor));assertEquals(1L,version());
        String audit=db.queryForObject("SELECT string_agg(before_state::text||after_state::text,'') FROM tna.org_audit_event WHERE tenant_id=?",String.class,tenant);
        assertNotNull(audit);assertFalse(audit.contains(message().token()));assertFalse(audit.contains(HASH));
    }
    @Test void pendingAccountGetsNoResetLinkAndCannotCompleteAnIssuedToken() {
        issue();String token=message().token();int sent=messages().size();
        db.update("UPDATE tna.tenant_account SET activation_expires_at=now()+interval '7 days' WHERE tenant_id=?",tenant);
        issue();assertEquals(sent,messages().size());
        status(422,()->complete(token));assertEquals(0,used());assertEquals(HASH,hash());assertEquals(1L,version());
    }
    @Test void reissueRevokesOldTokenWithoutChangingPasswordOrSessionsUntilCompletion() {
        issue();String old=message().token();issue();String next=message().token();assertNotEquals(old,next);assertTrue(auth.isCurrent(actor));
        status(422,()->complete(old));complete(next);assertFalse(auth.isCurrent(actor));assertEquals(2L,version());
        assertTrue(PasswordPolicy.passwordEncoder().matches(NEW,hash()));
        status(422,()->complete(next));assertEquals(1,used());
        assertEquals(1,db.queryForObject("SELECT count(*) FROM tna.org_audit_event WHERE tenant_id=? AND action='ACCOUNT_RESET_COMPLETE'",Integer.class,tenant));
    }
    @Test void purposeCompanyEmailAndIssuedVersionCannotBeSubstituted() {
        issue();String token=message().token();
        status(422,()->resets.complete("UNKNOWN-"+company,email,token,NEW,company));
        status(422,()->resets.complete(company,"other@example.test",token,NEW,company));
        db.update("UPDATE tna.account_token SET purpose='INVITE' WHERE tenant_id=?",tenant);status(422,()->complete(token));
        db.update("UPDATE tna.account_token SET purpose='RESET' WHERE tenant_id=?",tenant);
        db.update("UPDATE tna.tenant_account SET auth_version=auth_version+1 WHERE tenant_id=?",tenant);status(422,()->complete(token));
        assertEquals(HASH,hash());assertEquals(0,used());
    }
    @Test void expiresExactlyAtThirtyMinutesWithoutRoundingTheBoundary() {
        Instant start=Instant.now().truncatedTo(ChronoUnit.MILLIS);
        var issued=service(Clock.fixed(start,ZoneOffset.UTC),DatabaseFixture.start().authDataSource(),delivery);issued.request(company,email,company);
        var expired=service(Clock.fixed(start.plusSeconds(1800),ZoneOffset.UTC),DatabaseFixture.start().authDataSource(),delivery);
        status(422,()->expired.complete(company,email,message().token(),NEW,company));assertEquals(0,used());assertEquals(HASH,hash());
        var before=service(Clock.fixed(start.plusMillis(1_799_999),ZoneOffset.UTC),DatabaseFixture.start().authDataSource(),delivery);
        before.complete(company,email,message().token(),NEW,company);assertEquals(1,used());
    }
    @Test void inactiveCompanyAccountAndEmploymentPreventCompletion() {
        issue();String token=message().token();
        for(String table:List.of("platform.tenant_registry","tna.tenant_account","tna.employee")) {
            String where=table.startsWith("platform")?"id":"tenant_id";
            db.update("UPDATE "+table+" SET active=false WHERE "+where+"=?",tenant);status(422,()->complete(token));
            db.update("UPDATE "+table+" SET active=true WHERE "+where+"=?",tenant);
        }
        db.update("UPDATE tna.employee SET left_on=? WHERE tenant_id=?",LocalDate.now(ZoneId.of("Asia/Seoul")).minusDays(1),tenant);
        status(422,()->complete(token));assertEquals(0,used());assertEquals(HASH,hash());
    }
    @Test void concurrentConsumptionChangesPasswordAndVersionOnlyOnce() throws Exception {
        issue();String token=message().token();var start=new CountDownLatch(1);
        try(var workers=Executors.newFixedThreadPool(2)) {
            var first=workers.submit(()->consume(start,token));var second=workers.submit(()->consume(start,token));start.countDown();
            assertEquals(1,first.get(15,TimeUnit.SECONDS)+second.get(15,TimeUnit.SECONDS));
        }
        assertEquals(1,used());assertEquals(2L,version());assertTrue(PasswordPolicy.passwordEncoder().matches(NEW,hash()));
    }
    @Test void failedAuditRollsBackConsumptionCredentialAndVersionWithoutLeakingDelivery() {
        issue();String token=message().token();var broken=service(Clock.systemUTC(),failingAuditSource(),delivery);
        status(503,()->broken.complete(company,email,token,NEW,company));assertEquals(0,used());assertEquals(1L,version());assertEquals(HASH,hash());
        int size=messages().size();status(503,()->broken.request(company,email,company));assertEquals(size,messages().size());
        assertEquals(1,db.queryForObject("SELECT count(*) FROM tna.account_token WHERE tenant_id=? AND used_at IS NULL AND revoked_at IS NULL",Integer.class,tenant));
    }
    @Test void nonLocalDeliveryRejectsEveryRequestWithoutCreatingTokens() {
        var unavailable=service(Clock.systemUTC(),DatabaseFixture.start().authDataSource(),new InvitationDelivery(new StandardEnvironment()));
        status(503,()->unavailable.request(company,email,company));status(503,()->unavailable.request(company,"unknown@example.test",company));assertEquals(0,tokens());
    }
    @Test @ExtendWith(OutputCaptureExtension.class)
    void failureEventsNeverContainEmailTokenPasswordOrHashes(CapturedOutput output) {
        issue();String token=message().token();
        for(int i=0;i<20;i++) status(422,()->resets.complete(company,email,"malformed-private-code",NEW,company));
        status(429,()->resets.complete(company,email,"malformed-private-code",NEW,company));
        assertTrue(output.getAll().contains("event=password_reset_complete outcome=rejected"));
        assertTrue(output.getAll().contains("event=password_reset_complete outcome=rate_limited"));
        for(String secret:List.of(company,email,token,NEW,HASH,"malformed-private-code")) assertFalse(output.getAll().contains(secret));
    }
    @Test void authenticationCanIssueResetButNotInviteTokensAndCannotReadWorkflowData() {
        var jdbc=new JdbcTemplate(DatabaseFixture.start().authDataSource());var tx=new TenantTransactions(DatabaseFixture.start().authDataSource());
        assertThrows(DataAccessException.class,()->tx.execute(tenant,()->jdbc.update("""
                INSERT INTO tna.account_token(tenant_id,id,account_id,purpose,token_hash,email,issued_auth_version,expires_at,created_by)
                VALUES (?,?,?,'INVITE',?,?,1,now()+interval '30 minutes',?)
                """,tenant,UUID.randomUUID(),account,"b".repeat(64),email,account)));
        issue();assertEquals(1,tokens());assertThrows(DataAccessException.class,()->jdbc.queryForList("SELECT * FROM tna.leave_bucket"));
    }
    @Test void passwordValidationPrecedesStorageAndNeverConsumesTokenOnInvalidInput() {
        issue();String token=message().token();
        for(String value:List.of("short","x".repeat(129))) status(422,()->resets.complete(company,email,token,value,company));
        assertEquals(0,used());assertEquals(HASH,hash());
    }
    private void issue(){resets.request(company,email,company);}
    private void complete(String token){resets.complete(company,email,token,NEW,company);}
    private List<InvitationDelivery.Envelope> messages(){return delivery.messages().stream().filter(m->m.companyCode().equals(company)).toList();}
    private InvitationDelivery.Envelope message(){return messages().getLast();}
    private int tokens(){return db.queryForObject("SELECT count(*) FROM tna.account_token WHERE tenant_id=?",Integer.class,tenant);}
    private int used(){return db.queryForObject("SELECT count(*) FROM tna.account_token WHERE tenant_id=? AND used_at IS NOT NULL",Integer.class,tenant);}
    private long version(){return db.queryForObject("SELECT auth_version FROM tna.tenant_account WHERE tenant_id=?",Long.class,tenant);}
    private String hash(){return db.queryForObject("SELECT password_hash FROM tna.tenant_account WHERE tenant_id=?",String.class,tenant);}
    private void status(int code,Runnable action){assertEquals(code,assertThrows(OrgException.class,action::run).status());}
    private int consume(CountDownLatch start,String token)throws Exception{start.await();try{complete(token);return 1;}catch(OrgException e){assertEquals(422,e.status());return 0;}}
    private PasswordResetService service(Clock clock,DataSource source,InvitationDelivery delivery){return new PasswordResetService(source,PasswordPolicy.passwordEncoder(),clock,delivery);}
    private DataSource failingAuditSource(){
        var delegate=DatabaseFixture.start().authDataSource();return new AbstractDataSource(){
            @Override public Connection getConnection()throws SQLException{return wrap(delegate.getConnection());}
            @Override public Connection getConnection(String u,String p)throws SQLException{return wrap(delegate.getConnection(u,p));}
            private Connection wrap(Connection c){return (Connection)Proxy.newProxyInstance(Connection.class.getClassLoader(),new Class<?>[]{Connection.class},(proxy,method,args)->{
                if(method.getName().equals("prepareStatement")&&args[0] instanceof String sql&&sql.contains("INSERT INTO tna.org_audit_event"))throw new SQLException("Synthetic audit failure");
                try{return method.invoke(c,args);}catch(InvocationTargetException e){throw e.getCause();}
            });}
        };
    }
}
