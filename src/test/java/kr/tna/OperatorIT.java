package kr.tna;

import static org.junit.jupiter.api.Assertions.*;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;
import javax.sql.DataSource;
import kr.tna.common.auth.InvitationAcceptance;
import kr.tna.common.auth.InvitationDelivery;
import kr.tna.common.auth.PasswordPolicy;
import kr.tna.console.*;
import kr.tna.console.OperatorData.*;
import kr.tna.org.OrgCommands;
import kr.tna.org.OrgException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;

@SpringBootTest(properties="tna.operator.enabled=true") @ActiveProfiles("local")
class OperatorIT {
    static final String PASSWORD="Synthetic operator account 43!";
    static final LocalDate TODAY=LocalDate.now(ZoneId.of("Asia/Seoul"));
    @Autowired OperatorService operators;
    @Autowired OperatorMapper mapper;
    @Autowired InvitationDelivery delivery;
    @Autowired InvitationAcceptance acceptance;
    @Autowired(required=false) @Qualifier("operatorDataSource") DataSource operatorSource;
    @DynamicPropertySource static void database(DynamicPropertyRegistry p){var f=DatabaseFixture.start();p.add("spring.datasource.url",f::jdbcUrl);p.add("spring.datasource.username",()->DatabaseFixture.APP_USER);p.add("spring.datasource.password",()->DatabaseFixture.APP_PASSWORD);p.add("tna.auth.url",f::jdbcUrl);p.add("tna.auth.username",()->DatabaseFixture.AUTH_USER);p.add("tna.auth.password",()->DatabaseFixture.AUTH_PASSWORD);p.add("tna.operator.url",f::jdbcUrl);p.add("tna.operator.password",()->"synthetic-operator-db-password-only");}
    @Test void operatorMetadataSchemaExists(){assertNotNull(db().queryForObject("SELECT to_regclass('platform.operator_account')::text",String.class));}
    @Test void onboardCreatesOnlyPendingFirstAdminAndGroundedMetadataAuditThenUsesExistingAcceptance(){
        var actor=operator();var form=form();UUID id=operators.onboard(actor,form);assertNotNull(id);
        assertTrue(operators.tenants(actor).stream().anyMatch(t->t.id().equals(id)));
        var account=db().queryForMap("SELECT id,active,credential_set FROM tna.tenant_account WHERE tenant_id=?",id);
        assertEquals(false,account.get("active"));assertEquals(false,account.get("credential_set"));
        assertEquals(java.util.Set.of("EMPLOYEE","ATTENDANCE_ADMIN"),java.util.Set.copyOf(db().queryForList("SELECT role FROM tna.role_grant WHERE tenant_id=? AND active",String.class,id)));
        var envelope=delivery.messages().stream().filter(e->e.email().equals(form.email())).findFirst().orElseThrow();
        var audit=db().queryForMap("SELECT relationship_basis,db_actor,summary::text AS summary FROM platform.operator_audit_event WHERE command_id=?",form.commandId());
        assertEquals(form.relationshipBasis(),audit.get("relationship_basis"));assertEquals("tna_operator",audit.get("db_actor"));
        assertFalse(audit.get("summary").toString().contains(envelope.token()));assertFalse(audit.get("summary").toString().contains(form.email()));
        assertEquals(actor.id(),db().queryForObject("SELECT operator_created_by FROM tna.account_token WHERE tenant_id=?",UUID.class,id));
        acceptance.accept(form.companyCode(),form.email(),envelope.token(),PASSWORD,"198.18.42.1");
        assertTrue(db().queryForObject("SELECT active FROM tna.tenant_account WHERE tenant_id=?",Boolean.class,id));
    }
    @Test void sameCommandReplaysWithoutDuplicatingCompanyOrSendingAnUnstoredToken(){
        var actor=operator();var form=form();int before=delivery.messages().size();UUID id=operators.onboard(actor,form);
        assertEquals(id,operators.onboard(actor,form));assertEquals(before+1,delivery.messages().size());
        assertEquals(1,db().queryForObject("SELECT count(*) FROM platform.operator_audit_event WHERE command_id=?",Integer.class,form.commandId()));
        assertEquals(1,db().queryForObject("SELECT count(*) FROM tna.tenant_account WHERE tenant_id=?",Integer.class,id));
    }
    @Test void sameCommandWithDifferentBodyOrDuplicateCompanyCannotAddAnotherAdmin(){
        var actor=operator();var form=form();UUID id=operators.onboard(actor,form);
        var changed=new TenantForm(form.companyCode(),"다른 회사명",form.employeeNo(),form.employeeName(),form.joinedOn(),form.email(),form.relationshipBasis(),form.reason(),form.commandId());
        assertStatus(409,()->operators.onboard(actor,changed));
        var duplicate=new TenantForm(form.companyCode(),form.companyName(),"DIFFERENT",form.employeeName(),form.joinedOn(),"different@example.test",form.relationshipBasis(),form.reason(),UUID.randomUUID());
        assertStatus(409,()->operators.onboard(actor,duplicate));
        assertEquals(1,db().queryForObject("SELECT count(*) FROM tna.tenant_account WHERE tenant_id=?",Integer.class,id));
    }
    @Test void invalidTokenAtTheBridgeRollsBackEarlierRegistryEmployeeAndAccountWrites(){
        var actor=operator();var form=form();UUID tenant=UUID.randomUUID();
        assertThrows(DataAccessException.class,()->mapper.onboard(actor,form,OrgCommands.fingerprint("failure",form.companyCode()),tenant,UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),"invalid",Instant.now().plusSeconds(3600)));
        assertEquals(0,db().queryForObject("SELECT count(*) FROM platform.tenant_registry WHERE id=?",Integer.class,tenant));
        assertEquals(0,db().queryForObject("SELECT count(*) FROM tna.tenant_account WHERE tenant_id=?",Integer.class,tenant));
        assertEquals(0,db().queryForObject("SELECT count(*) FROM platform.operator_audit_event WHERE command_id=?",Integer.class,form.commandId()));
    }
    @Test void stateChangesRevokeAccountsEvenAcrossStopAndRestartAndRequireCurrentVersion(){
        var actor=operator();UUID id=operators.onboard(actor,form());
        var stop=new StateForm(false,0L,"합성 이용 중지",UUID.randomUUID());operators.state(actor,id,stop);operators.state(actor,id,stop);
        assertEquals(2L,db().queryForObject("SELECT auth_version FROM tna.tenant_account WHERE tenant_id=?",Long.class,id));
        assertStatus(409,()->operators.state(actor,id,new StateForm(true,0L,"오래된 재개",UUID.randomUUID())));
        operators.state(actor,id,new StateForm(true,1L,"합성 재개",UUID.randomUUID()));
        assertEquals(3L,db().queryForObject("SELECT auth_version FROM tna.tenant_account WHERE tenant_id=?",Long.class,id));
        assertEquals(2L,db().queryForObject("SELECT version FROM platform.tenant_registry WHERE id=?",Long.class,id));
    }
    @Test void operatorRoleHasNoHrReadsOrDirectWritesAndCustomerRolesCannotReadOperators(){
        assertNotNull(operatorSource);var op=new JdbcTemplate(operatorSource);
        assertThrows(DataAccessException.class,()->op.queryForList("SELECT id FROM tna.employee"));
        assertThrows(DataAccessException.class,()->op.queryForList("SELECT id FROM tna.tenant_account"));
        assertThrows(DataAccessException.class,()->op.update("UPDATE platform.tenant_registry SET active=false"));
        for(DataSource source:java.util.List.of(DatabaseFixture.start().appDataSource(),DatabaseFixture.start().authDataSource())){
            var jdbc=new JdbcTemplate(source);assertThrows(DataAccessException.class,()->jdbc.queryForList("SELECT id FROM platform.operator_account"));
            assertFalse(jdbc.queryForObject("SELECT has_function_privilege(current_user,'platform.operator_set_state(uuid,bigint,uuid,text,uuid,boolean,bigint,text)','EXECUTE')",Boolean.class));
        }
    }
    @Test void inactiveOrStaleOperatorCannotReadOrMutateMetadata(){
        var actor=operator();var stale=new OperatorPrincipal(actor.id(),actor.loginId(),actor.authVersion()+1,Instant.now());
        assertStatus(403,()->operators.tenants(stale));db().update("UPDATE platform.operator_account SET active=false WHERE id=?",actor.id());
        assertStatus(403,()->operators.onboard(actor,form()));
    }
    @Test void missingRelationshipEvidenceAndFutureFirstEmploymentAreRejected(){
        var actor=operator();var form=form();
        assertStatus(422,()->operators.onboard(actor,new TenantForm(form.companyCode(),form.companyName(),form.employeeNo(),form.employeeName(),form.joinedOn(),form.email()," ",form.reason(),form.commandId())));
        assertStatus(422,()->operators.onboard(actor,new TenantForm(form.companyCode(),form.companyName(),form.employeeNo(),form.employeeName(),TODAY.plusDays(1),form.email(),form.relationshipBasis(),form.reason(),UUID.randomUUID())));
    }
    @Test void bootstrapIsExplicitHashedAndNeverCreatesDefaultCredentials()throws Exception {
        String login="bootstrap-"+UUID.randomUUID();UUID id=OperatorBootstrap.create(DatabaseFixture.start().adminDataSource(),login,PASSWORD.toCharArray());
        var row=db().queryForMap("SELECT login_id,password_hash FROM platform.operator_account WHERE id=?",id);
        assertEquals(login,row.get("login_id"));assertNotEquals(PASSWORD,row.get("password_hash"));assertTrue(PasswordPolicy.passwordEncoder().matches(PASSWORD,row.get("password_hash").toString()));
        assertEquals(1,db().queryForObject("SELECT count(*) FROM platform.operator_audit_event WHERE operator_id=? AND action='OPERATOR_BOOTSTRAP'",Integer.class,id));
        assertThrows(IllegalArgumentException.class,()->OperatorBootstrap.create(DatabaseFixture.start().adminDataSource(),"invalid-"+UUID.randomUUID(),"short".toCharArray()));
    }
    @Test void reissuePreservesInitialIdentityAndRolesAndRevokesOnlyOldInvitations(){
        var actor=operator();var form=form();UUID tenant=operators.onboard(actor,form);
        UUID account=db().queryForObject("SELECT id FROM tna.tenant_account WHERE tenant_id=?",UUID.class,tenant);
        var old=delivery.messages().stream().filter(e->e.email().equals(form.email())).findFirst().orElseThrow();
        var request=new ReissueForm(0L,"최초 초대 재확인",UUID.randomUUID());
        assertEquals(tenant,operators.reissue(actor,tenant,request));
        var messages=delivery.messages().stream().filter(e->e.email().equals(form.email())).toList();assertEquals(2,messages.size());
        var fresh=messages.getLast();assertNotEquals(old.token(),fresh.token());
        assertEquals(account,db().queryForObject("SELECT id FROM tna.tenant_account WHERE tenant_id=?",UUID.class,tenant));
        assertEquals(1,db().queryForObject("SELECT count(*) FROM tna.employee WHERE tenant_id=?",Integer.class,tenant));
        assertEquals(2,db().queryForObject("SELECT count(*) FROM tna.role_grant WHERE tenant_id=? AND active",Integer.class,tenant));
        assertEquals(2L,db().queryForObject("SELECT auth_version FROM tna.tenant_account WHERE tenant_id=?",Long.class,tenant));
        assertEquals(1L,db().queryForObject("SELECT version FROM platform.tenant_registry WHERE id=?",Long.class,tenant));
        assertEquals(1,db().queryForObject("SELECT count(*) FROM tna.account_token WHERE tenant_id=? AND revoked_at IS NOT NULL",Integer.class,tenant));
        assertStatus(422,()->acceptance.accept(form.companyCode(),form.email(),old.token(),PASSWORD,"198.18.42.10"));
        acceptance.accept(form.companyCode(),form.email(),fresh.token(),PASSWORD,"198.18.42.11");
        assertTrue(db().queryForObject("SELECT active FROM tna.tenant_account WHERE tenant_id=?",Boolean.class,tenant));
    }
    @Test void reissueReplayDoesNotResendAndAcceptedInitialAccountsCannotBeReissued(){
        var actor=operator();var form=form();UUID tenant=operators.onboard(actor,form);
        var command=new ReissueForm(0L,"분실 최초 초대 재발급",UUID.randomUUID());
        operators.reissue(actor,tenant,command);int delivered=delivery.messages().size();
        assertEquals(tenant,operators.reissue(actor,tenant,command));assertEquals(delivered,delivery.messages().size());
        var fresh=delivery.messages().stream().filter(e->e.email().equals(form.email())).toList().getLast();
        acceptance.accept(form.companyCode(),form.email(),fresh.token(),PASSWORD,"198.18.42.12");
        assertStatus(422,()->operators.reissue(actor,tenant,new ReissueForm(1L,"수락 계정 우회 시도",UUID.randomUUID())));
        assertEquals(2,db().queryForObject("SELECT count(*) FROM tna.account_token WHERE tenant_id=?",Integer.class,tenant));
    }
    @Test void reissueRequiresActiveCompanyAndTheCurrentMetadataVersion(){
        var actor=operator();UUID tenant=operators.onboard(actor,form());
        operators.state(actor,tenant,new StateForm(false,0L,"초기 이용 중지",UUID.randomUUID()));
        assertStatus(422,()->operators.reissue(actor,tenant,new ReissueForm(1L,"중지 중 발급",UUID.randomUUID())));
        operators.state(actor,tenant,new StateForm(true,1L,"합성 재개",UUID.randomUUID()));
        assertStatus(409,()->operators.reissue(actor,tenant,new ReissueForm(1L,"오래된 버전",UUID.randomUUID())));
        assertEquals(tenant,operators.reissue(actor,tenant,new ReissueForm(2L,"재개 후 최초 초대 재발급",UUID.randomUUID())));
        assertEquals(1,db().queryForObject("SELECT count(*) FROM tna.tenant_account WHERE tenant_id=?",Integer.class,tenant));
    }
    @Test void changedInitialEmailCannotRedirectAReissuedInvitation(){
        var actor=operator();var form=form();UUID tenant=operators.onboard(actor,form);
        db().update("UPDATE tna.tenant_account SET email='changed-initial@example.test' WHERE tenant_id=?",tenant);
        assertStatus(422,()->operators.reissue(actor,tenant,new ReissueForm(0L,"변경된 수신자 우회",UUID.randomUUID())));
        assertTrue(delivery.messages().stream().noneMatch(e->e.email().equals("changed-initial@example.test")));
    }
    private OperatorPrincipal operator(){UUID id=UUID.randomUUID();String login="op-"+id;db().update("INSERT INTO platform.operator_account(id,login_id,password_hash) VALUES (?,?,?)",id,login,PasswordPolicy.passwordEncoder().encode(PASSWORD));return new OperatorPrincipal(id,login,1,Instant.now());}
    private TenantForm form(){UUID id=UUID.randomUUID();return new TenantForm("ONBOARD-"+id,"합성 고객사","FIRST-1","합성 최초 담당자",TODAY,"first-"+id+"@example.test","합성 회사 관계·연락 경로 검토 문서","합성 신규 연결",UUID.randomUUID());}
    private JdbcTemplate db(){return new JdbcTemplate(DatabaseFixture.start().adminDataSource());}
    private static void assertStatus(int status,Runnable action){assertEquals(status,assertThrows(OrgException.class,action::run).status());}
}
