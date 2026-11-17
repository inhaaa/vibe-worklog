package kr.tna;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import javax.sql.DataSource;
import kr.tna.common.auth.TnaPrincipal;
import kr.tna.common.tenant.TenantTransactions;
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
import org.springframework.test.web.servlet.request.RequestPostProcessor;

@SpringBootTest
@AutoConfigureMockMvc
class OrganizationHeadWebIT {
    private static final String JSON = "application/vnd.worklog.page+json";
    private static final String REASON = "합성 조직장 변경의 비공개 사유";
    @Autowired MockMvc mvc;
    @Autowired TenantTransactions transactions;
    @Autowired @Qualifier("dataSource") DataSource applicationSource;
    private JdbcTemplate db;
    private UUID tenant, organization, head, secondHead;
    private LocalDate today;
    private TnaPrincipal admin, employee;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry properties) {
        var fixture = DatabaseFixture.start();
        properties.add("spring.datasource.url", fixture::jdbcUrl);
        properties.add("spring.datasource.username", () -> DatabaseFixture.APP_USER);
        properties.add("spring.datasource.password", () -> DatabaseFixture.APP_PASSWORD);
        properties.add("spring.datasource.hikari.maximum-pool-size", () -> 5);
        properties.add("tna.auth.url", fixture::jdbcUrl);
        properties.add("tna.auth.username", () -> DatabaseFixture.AUTH_USER);
        properties.add("tna.auth.password", () -> DatabaseFixture.AUTH_PASSWORD);
    }

    @BeforeEach
    void company() {
        db = new JdbcTemplate(DatabaseFixture.start().adminDataSource());
        today = LocalDate.now(ZoneId.of("Asia/Seoul"));
        tenant = UUID.randomUUID();
        db.update("INSERT INTO platform.tenant_registry(id,company_code,display_name) VALUES (?,?,'합성 조직장 회사')",
                tenant, "HEAD-" + tenant);
        admin = member("ADMIN", Set.of("EMPLOYEE", "ATTENDANCE_ADMIN"));
        employee = member("MEMBER", Set.of("EMPLOYEE"));
        head = person("HEAD", today.minusYears(1), null, true);
        secondHead = person("HEAD2", today.minusYears(1), null, true);
        organization = organization("ORG");
        assign(employee.employeeId(), organization);
    }

    @Test
    void administrationOffersCurrentCandidatesIncludingEmployeesWithoutAccountsAndFreshFormVersion() throws Exception {
        person("FUTURE", today.plusDays(1), null, true);
        person("LEFT", today.minusYears(1), today.minusDays(1), true);
        var result = mvc.perform(get("/admin/organization-heads").with(as(admin)).accept(JSON)
                        .param("organizationId", organization.toString()))
                .andExpect(status().isOk()).andExpect(view().name("org/organization-heads"))
                .andExpect(ReactPageAssertions.data("/organizationHeads/form/expectedVersion", 0))
                .andExpect(ReactPageAssertions.data("/organizationHeads/form/validFrom", today.toString())).andReturn();
        var view = ReactPageAssertions.envelope(result).at("/data/organizationHeads");
        assertTrue(view.get("history").isArray());
        assertEquals(0, view.get("history").size());
        assertNotNull(UUID.fromString(view.at("/form/commandId").asString()));
        var candidates = view.get("candidates").toString();
        assertTrue(candidates.contains(head.toString()));
        assertFalse(candidates.contains("FUTURE"));
        assertFalse(candidates.contains("LEFT"));
    }

    @Test
    void organizationSheetReadsTheCurrentHeadWithoutExposingTheAssignmentReason() throws Exception {
        set(head, today, 0, UUID.randomUUID()).andExpect(status().isSeeOther());
        var result = mvc.perform(get("/admin/organizations").with(as(admin)).accept(JSON))
                .andExpect(status().isOk())
                .andExpect(ReactPageAssertions.data("/organizationHeadsById/" + organization + "/employeeName", "합성 HEAD"))
                .andExpect(ReactPageAssertions.data("/organizationHeadsById/" + organization + "/eligible", true)).andReturn();
        assertFalse(ReactPageAssertions.envelope(result).at("/data/organizationHeadsById").toString().contains(REASON));
    }

    @Test
    void employeeSheetReadsTheCurrentOrganizationHeadAndScheduleInOneSafeContext() throws Exception {
        set(head, today, 0, UUID.randomUUID()).andExpect(status().isSeeOther());
        mvc.perform(get("/admin/employees").with(as(admin)).accept(JSON).param("q", "MEMBER"))
                .andExpect(status().isOk())
                .andExpect(ReactPageAssertions.data("/employeeContext/" + employee.employeeId() + "/organizationName", "조직 ORG"))
                .andExpect(ReactPageAssertions.data("/employeeContext/" + employee.employeeId() + "/organizationHeadName", "합성 HEAD"))
                .andExpect(ReactPageAssertions.data("/employeeContext/" + employee.employeeId() + "/organizationHeadEligible", true))
                .andExpect(ReactPageAssertions.data("/employeeContext/" + employee.employeeId() + "/organizationHeadApproverEligible", false));
    }

    @Test
    void assignmentDoesNotRequireOrganizationMembershipOrAnAccountAndNeverGrantsApprover() throws Exception {
        set(head, today, 0, UUID.randomUUID()).andExpect(status().isSeeOther());
        assertEquals(1, count("organization_head_assignment"));
        assertEquals(1L, version());
        assertEquals(1, count("org_audit_event"));
        assertEquals(0, db.queryForObject("SELECT count(*) FROM tna.tenant_account WHERE tenant_id=? AND employee_id=?", Integer.class, tenant, head));
        mvc.perform(get("/me/profile").with(as(employee)).accept(JSON))
                .andExpect(status().isOk())
                .andExpect(ReactPageAssertions.data("/profile/organizationHeadName", "합성 HEAD"))
                .andExpect(ReactPageAssertions.data("/profile/organizationHeadEligible", true))
                .andExpect(ReactPageAssertions.data("/profile/organizationHeadApproverEligible", false));
    }

    @Test
    void sameStartReplacementPreservesTheOriginalAndCommandReplayDoesNotRepeatIt() throws Exception {
        UUID firstCommand = UUID.randomUUID();
        set(head, today, 0, firstCommand).andExpect(status().isSeeOther());
        set(secondHead, today, 1, UUID.randomUUID()).andExpect(status().isSeeOther());
        assertEquals(2, count("organization_head_assignment"));
        assertEquals(1, db.queryForObject("SELECT count(*) FROM tna.organization_head_assignment WHERE tenant_id=? AND superseded_at IS NULL", Integer.class, tenant));
        assertNotNull(db.queryForObject("SELECT superseded_at FROM tna.organization_head_assignment WHERE tenant_id=? AND employee_id=?", java.sql.Timestamp.class, tenant, head));
        set(head, today, 0, firstCommand).andExpect(status().isSeeOther());
        assertEquals(2L, version());
        assertEquals(2, count("org_audit_event"));
        set(secondHead, today, 0, firstCommand).andExpect(status().isConflict());
    }

    @Test
    void futureInsertionAndEndingSplitOnlyTheCoveredIntervalAndPreserveTheLaterAssignment() throws Exception {
        set(head, today, 0, UUID.randomUUID()).andExpect(status().isSeeOther());
        set(secondHead, today.plusDays(10), 1, UUID.randomUUID()).andExpect(status().isSeeOther());
        set(employee.employeeId(), today.plusDays(5), 2, UUID.randomUUID()).andExpect(status().isSeeOther());
        assertEquals(today.plusDays(5), toDate(head));
        assertEquals(today.plusDays(10), toDate(employee.employeeId()));
        end(today.plusDays(7), 3, UUID.randomUUID()).andExpect(status().isSeeOther());
        assertEquals(today.plusDays(7), toDate(employee.employeeId()));
        assertNull(toDate(secondHead));
        assertEquals(3, count("organization_head_assignment"));
        assertEquals(4L, version());
    }

    @Test
    void endingAtTheOriginalStartSupersedesItWithoutCreatingAZeroLengthOrDeletingHistory() throws Exception {
        set(head, today, 0, UUID.randomUUID()).andExpect(status().isSeeOther());
        end(today, 1, UUID.randomUUID()).andExpect(status().isSeeOther());
        assertEquals(1, count("organization_head_assignment"));
        assertEquals(0, db.queryForObject("SELECT count(*) FROM tna.organization_head_assignment WHERE tenant_id=? AND superseded_at IS NULL", Integer.class, tenant));
        end(today, 2, UUID.randomUUID()).andExpect(status().isUnprocessableEntity());
        assertEquals(2L, version());
    }

    @Test
    void invalidDatesAndConflictsKeepTheExactUserFormAndDoNotPartiallyChangeTheOrganization() throws Exception {
        UUID command = UUID.randomUUID();
        var result = set(head, today.minusDays(1), 0, command).andExpect(status().isUnprocessableEntity())
                .andExpect(view().name("org/organization-heads")).andReturn();
        var form = ReactPageAssertions.envelope(result).at("/data/organizationHeads/form");
        assertEquals(head.toString(), form.get("employeeId").asString());
        assertEquals(today.minusDays(1).toString(), form.get("validFrom").asString());
        assertEquals(command.toString(), form.get("commandId").asString());
        assertEquals(REASON, form.get("reason").asString());
        assertEquals(0, count("organization_head_assignment"));
        assertEquals(0L, version());
        set(head, today, 0, UUID.randomUUID()).andExpect(status().isSeeOther());
        set(secondHead, today, 0, command).andExpect(status().isConflict());
        assertEquals(1, count("organization_head_assignment"));
        assertEquals(1, count("org_audit_event"));
        mvc.perform(form("/admin/organization-heads", today, 1, UUID.randomUUID()).param("employeeId", head.toString())
                        .with(request -> { request.setParameter("validFrom", "not-a-date"); return request; }))
                .andExpect(status().isBadRequest());
    }

    @Test
    void employeeMustBeCurrentlyActiveAndStillEmployedOnTheSelectedFutureDate() throws Exception {
        UUID leaving = person("LEAVING", today.minusYears(1), today.plusDays(2), true);
        set(leaving, today.plusDays(3), 0, UUID.randomUUID()).andExpect(status().isUnprocessableEntity());
        UUID inactive = person("INACTIVE", today.minusYears(1), null, false);
        set(inactive, today, 0, UUID.randomUUID()).andExpect(status().isUnprocessableEntity());
        UUID future = person("NEW", today.plusDays(1), null, true);
        set(future, today.plusDays(1), 0, UUID.randomUUID()).andExpect(status().isUnprocessableEntity());
        db.update("UPDATE tna.organization SET active=false WHERE tenant_id=? AND id=?", tenant, organization);
        set(head, today, 0, UUID.randomUUID()).andExpect(status().isUnprocessableEntity());
        assertEquals(0, count("organization_head_assignment"));
        assertEquals(0, count("org_audit_event"));
    }

    @Test
    void foreignIdsAreNotFoundAndCsrfAndAdministrationAreAlwaysRequired() throws Exception {
        UUID foreignTenant = UUID.randomUUID(), foreignPerson = UUID.randomUUID(), foreignAccount = UUID.randomUUID(), foreignOrganization = UUID.randomUUID();
        db.update("INSERT INTO platform.tenant_registry(id,company_code,display_name) VALUES (?,?,'합성 다른 회사')", foreignTenant, "HEAD-" + foreignTenant);
        db.update("INSERT INTO tna.employee(tenant_id,id,employee_no,display_name,joined_on) VALUES (?,?,?,'다른 회사 사원',?)",
                foreignTenant, foreignPerson, "FOREIGN", today.minusYears(1));
        db.update("INSERT INTO tna.tenant_account(tenant_id,id,employee_id,email,password_hash) VALUES (?,?,?,?,?)",
                foreignTenant, foreignAccount, foreignPerson, foreignAccount + "@example.test", "synthetic-head-hash");
        db.update("INSERT INTO tna.organization(tenant_id,id,code,name,created_by,updated_by) VALUES (?,?,'FOREIGN','다른 회사 조직',?,?)",
                foreignTenant, foreignOrganization, foreignAccount, foreignAccount);
        set(foreignPerson, today, 0, UUID.randomUUID()).andExpect(status().isNotFound());
        mvc.perform(form("/admin/organization-heads", today, 0, UUID.randomUUID())
                        .with(request -> { request.setParameter("organizationId", foreignOrganization.toString()); return request; })
                        .param("employeeId", head.toString())).andExpect(status().isNotFound());
        mvc.perform(get("/admin/organization-heads").with(as(admin)).accept(JSON)
                        .param("organizationId", foreignOrganization.toString())).andExpect(status().isNotFound());
        mvc.perform(get("/company/organizations").with(as(admin)).accept(JSON)
                        .param("organizationId", foreignOrganization.toString())).andExpect(status().isNotFound());
        mvc.perform(post("/admin/organization-heads").with(as(admin)).accept(JSON)).andExpect(status().isForbidden());
        mvc.perform(get("/admin/organization-heads").with(as(employee)).accept(JSON)).andExpect(status().isForbidden());
        mvc.perform(get("/company/organizations").with(as(employee)).accept(JSON)).andExpect(status().isForbidden());
        assertEquals(0, count("organization_head_assignment"));
    }

    @Test
    void representativeCanReadTheTreeButCannotAssignAHead() throws Exception {
        var representative = member("REP", Set.of("EMPLOYEE", "COMPANY_REPRESENTATIVE"));
        mvc.perform(get("/company/organizations").with(as(representative)).accept(JSON)
                        .param("organizationId", organization.toString())).andExpect(status().isOk());
        mvc.perform(get("/admin/organization-heads").with(as(representative)).accept(JSON)).andExpect(status().isForbidden());
        mvc.perform(post("/admin/organization-heads").with(as(representative)).with(csrf()).accept(JSON)
                        .param("organizationId", organization.toString()).param("employeeId", head.toString())
                        .param("validFrom", today.toString()).param("expectedVersion", "0")
                        .param("reason", REASON).param("commandId", UUID.randomUUID().toString())).andExpect(status().isForbidden());
    }

    @Test
    void companyTreeHasOnlyCurrentMembersInFiftyRowPagesAndNoAdministrativeReasonsOrEmails() throws Exception {
        set(head, today, 0, UUID.randomUUID()).andExpect(status().isSeeOther());
        for (int index = 0; index < 51; index++) assign(person("PAGE-" + String.format("%03d", index), today.minusYears(1), null, true), organization);
        assign(person("PAGE-INACTIVE", today.minusYears(1), null, false), organization);
        var result = mvc.perform(get("/company/organizations").with(as(admin)).accept(JSON)
                        .param("organizationId", organization.toString()).param("q", "PAGE-"))
                .andExpect(status().isOk()).andExpect(view().name("company/organizations"))
                .andExpect(ReactPageAssertions.data("/companyOrganizations/hasNext", true))
                .andExpect(ReactPageAssertions.data("/companyOrganizations/selectedOrganization/headEmployeeName", "합성 HEAD"))
                .andExpect(ReactPageAssertions.data("/companyOrganizations/selectedOrganization/headEligible", true))
                .andExpect(ReactPageAssertions.data("/companyOrganizations/selectedOrganization/headApproverEligible", false)).andReturn();
        assertEquals(50, ReactPageAssertions.envelope(result).at("/data/companyOrganizations/employees").size());
        String body = result.getResponse().getContentAsString();
        assertFalse(body.contains(REASON));
        assertFalse(body.contains("@example.test"));
        assertFalse(body.contains("PAGE-INACTIVE"));
        var next = mvc.perform(get("/company/organizations").with(as(admin)).accept(JSON)
                        .param("organizationId", organization.toString()).param("q", "PAGE-").param("page", "1"))
                .andExpect(status().isOk()).andExpect(ReactPageAssertions.data("/companyOrganizations/hasNext", false)).andReturn();
        assertEquals(1, ReactPageAssertions.envelope(next).at("/data/companyOrganizations/employees").size());
    }

    @Test
    void headEmploymentAndApproverEligibilityAreIndependentAndInactiveNamesRemainVisible() throws Exception {
        var headWithAccount = member("HEAD-APP", Set.of("EMPLOYEE", "APPROVER"));
        set(headWithAccount.employeeId(), today, 0, UUID.randomUUID()).andExpect(status().isSeeOther());
        mvc.perform(get("/me/profile").with(as(employee)).accept(JSON))
                .andExpect(status().isOk()).andExpect(ReactPageAssertions.data("/profile/organizationHeadApproverEligible", true));
        db.update("UPDATE tna.role_grant SET active=false WHERE tenant_id=? AND account_id=? AND role='APPROVER'", tenant, headWithAccount.accountId());
        mvc.perform(get("/me/profile").with(as(employee)).accept(JSON)).andExpect(status().isOk())
                .andExpect(ReactPageAssertions.data("/profile/organizationHeadEligible", true))
                .andExpect(ReactPageAssertions.data("/profile/organizationHeadApproverEligible", false));
        db.update("UPDATE tna.employee SET active=false WHERE tenant_id=? AND id=?", tenant, headWithAccount.employeeId());
        mvc.perform(get("/company/organizations").with(as(admin)).accept(JSON)
                        .param("organizationId", organization.toString())).andExpect(status().isOk())
                .andExpect(ReactPageAssertions.data("/companyOrganizations/selectedOrganization/headEmployeeName", "합성 HEAD-APP"))
                .andExpect(ReactPageAssertions.data("/companyOrganizations/selectedOrganization/headEligible", false));
    }

    @Test
    void concurrentChangesUsingTheSameOrganizationVersionHaveOneWinner() throws Exception {
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = executor.submit(() -> set(head, today, 0, UUID.randomUUID()).andReturn().getResponse().getStatus());
            var second = executor.submit(() -> set(secondHead, today, 0, UUID.randomUUID()).andReturn().getResponse().getStatus());
            assertEquals(List.of(303, 409), java.util.stream.Stream.of(first.get(), second.get()).sorted().toList());
        }
        assertEquals(1, count("organization_head_assignment"));
        assertEquals(1, count("org_audit_event"));
        assertEquals(1L, version());
    }

    @Test
    void schemaEnforcesTenantIsolationAndOnlyClosingColumnsCanBeUpdatedByTheApplication() throws Exception {
        assertNotNull(db.queryForObject("SELECT to_regclass('tna.organization_head_assignment')::text", String.class));
        assertEquals(1, db.queryForObject("SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname='tna' AND c.relname='organization_head_assignment' AND c.relrowsecurity AND c.relforcerowsecurity", Integer.class));
        assertFalse(db.queryForObject("SELECT has_table_privilege('tna_app','tna.organization_head_assignment','DELETE')", Boolean.class));
        assertFalse(db.queryForObject("SELECT has_column_privilege('tna_app','tna.organization_head_assignment','employee_id','UPDATE')", Boolean.class));
        assertTrue(db.queryForObject("SELECT has_column_privilege('tna_app','tna.organization_head_assignment','valid_to','UPDATE')", Boolean.class));
        set(head, today, 0, UUID.randomUUID()).andExpect(status().isSeeOther());
        var application = new JdbcTemplate(applicationSource);
        assertEquals(1, transactions.execute(tenant, () -> application.queryForObject("SELECT count(*) FROM tna.organization_head_assignment", Integer.class)));
        assertEquals(0, transactions.execute(UUID.randomUUID(), () -> application.queryForObject("SELECT count(*) FROM tna.organization_head_assignment", Integer.class)));
        assertEquals(1, transactions.execute(tenant, () -> application.queryForObject("SELECT count(*) FROM tna.organization_head_assignment", Integer.class)));
    }

    private org.springframework.test.web.servlet.ResultActions set(UUID person, LocalDate from, long version, UUID command) throws Exception {
        return mvc.perform(form("/admin/organization-heads", from, version, command).param("employeeId", person.toString()));
    }

    @Test
    void chartCommandPreviewsAndSavesOneDayWithoutMovingTheMemberQuery() throws Exception {
        UUID command = UUID.randomUUID();
        mvc.perform(chartCommand("ASSIGN", 0, command).param("employeeId", head.toString())
                        .param("validFrom", today.toString()).param("lastDay", today.toString()).param("intent", "PREVIEW"))
                .andExpect(status().isOk()).andExpect(view().name("company/organizations"))
                .andExpect(ReactPageAssertions.data("/headPreview/after/lastDay", today.toString()))
                .andExpect(ReactPageAssertions.data("/companyOrganizations/q", "MEMBER"))
                .andExpect(ReactPageAssertions.data("/companyOrganizations/page", 1));
        assertEquals(0, count("organization_head_assignment"));
        var result = mvc.perform(chartCommand("ASSIGN", 0, command).param("employeeId", head.toString())
                        .param("validFrom", today.toString()).param("lastDay", today.toString()))
                .andExpect(status().isSeeOther()).andReturn();
        assertEquals(today.plusDays(1), toDate(head));
        mvc.perform(get(result.getResponse().getRedirectedUrl()).with(as(admin)).accept(JSON))
                .andExpect(status().isOk()).andExpect(view().name("company/organizations"))
                .andExpect(ReactPageAssertions.data("/companyOrganizations/page", 1))
                .andExpect(ReactPageAssertions.data("/organizationHeads/history/0/employeeId", head.toString()));
        mvc.perform(chartCommand("ASSIGN", 0, command).param("employeeId", head.toString())
                        .param("validFrom", today.toString()).param("lastDay", today.toString()))
                .andExpect(status().isSeeOther());
        assertEquals(1, count("org_audit_event"));
    }

    @Test
    void replacementPreservesTheLaterReservationAndReportsTheNewVacancy() throws Exception {
        set(head, today, 0, UUID.randomUUID()).andExpect(status().isSeeOther());
        set(secondHead, today.plusDays(10), 1, UUID.randomUUID()).andExpect(status().isSeeOther());
        UUID replacement = employee.employeeId();
        LocalDate from = today.plusDays(3), last = today.plusDays(5);
        mvc.perform(chartCommand("REPLACE", 2, UUID.randomUUID()).param("assignmentId", assignmentId(head).toString())
                        .param("employeeId", replacement.toString()).param("validFrom", from.toString()).param("lastDay", last.toString())
                        .param("intent", "PREVIEW"))
                .andExpect(status().isOk())
                .andExpect(ReactPageAssertions.data("/headPreview/previousLastDay", from.minusDays(1).toString()))
                .andExpect(ReactPageAssertions.data("/headPreview/vacancy/from", last.plusDays(1).toString()))
                .andExpect(ReactPageAssertions.data("/headPreview/vacancy/through", today.plusDays(9).toString()));
        mvc.perform(chartCommand("REPLACE", 2, UUID.randomUUID()).param("assignmentId", assignmentId(head).toString())
                        .param("employeeId", replacement.toString()).param("validFrom", from.toString()).param("lastDay", last.toString()))
                .andExpect(status().isSeeOther());
        assertEquals(from, toDate(head));
        assertEquals(last.plusDays(1), toDate(replacement));
        assertNull(toDate(secondHead));
        assertEquals(3, count("org_audit_event"));
    }

    @Test
    void upcomingReservationsCanBeMovedAndCanceledWhilePastAndOverlappingChangesAreRejected() throws Exception {
        set(head, today.plusDays(10), 0, UUID.randomUUID()).andExpect(status().isSeeOther());
        UUID original = assignmentId(head);
        mvc.perform(chartCommand("RESCHEDULE", 1, UUID.randomUUID()).param("assignmentId", original.toString())
                        .param("employeeId", secondHead.toString()).param("validFrom", today.plusDays(8).toString())
                        .param("lastDay", today.plusDays(9).toString()))
                .andExpect(status().isSeeOther());
        assertNotNull(db.queryForObject("SELECT superseded_at FROM tna.organization_head_assignment WHERE tenant_id=? AND id=?",
                java.sql.Timestamp.class, tenant, original));
        mvc.perform(chartCommand("ASSIGN", 2, UUID.randomUUID()).param("employeeId", head.toString())
                        .param("validFrom", today.plusDays(7).toString()).param("lastDay", today.plusDays(8).toString()))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(ReactPageAssertions.data("/headCommand/lastDay", today.plusDays(8).toString()))
                .andExpect(ReactPageAssertions.data("/companyOrganizations/page", 1));
        mvc.perform(chartCommand("CANCEL", 2, UUID.randomUUID()).param("assignmentId", assignmentId(secondHead).toString()))
                .andExpect(status().isSeeOther());
        assertEquals(0, db.queryForObject("SELECT count(*) FROM tna.organization_head_assignment WHERE tenant_id=? AND superseded_at IS NULL", Integer.class, tenant));
        assertEquals(2, count("organization_head_assignment"));
    }

    @Test
    void chartEndDateIncludesTheLastDayAndTodayCanBecomeVacant() throws Exception {
        set(head, today, 0, UUID.randomUUID()).andExpect(status().isSeeOther());
        UUID assignment = assignmentId(head);
        mvc.perform(chartCommand("CHANGE_END", 1, UUID.randomUUID()).param("assignmentId", assignment.toString())
                        .param("lastDay", today.plusDays(2).toString()))
                .andExpect(status().isSeeOther());
        assertEquals(today.plusDays(3), toDate(head));
        mvc.perform(chartCommand("CHANGE_END", 2, UUID.randomUUID()).param("assignmentId", assignment.toString()).param("endToday", "true"))
                .andExpect(status().isSeeOther());
        assertEquals(0, db.queryForObject("SELECT count(*) FROM tna.organization_head_assignment WHERE tenant_id=? AND superseded_at IS NULL", Integer.class, tenant));
    }

    @Test
    void inactiveOrganizationAndEmployeeCanShortenOrEndButCannotExtendTheExistingPeriod() throws Exception {
        set(head, today, 0, UUID.randomUUID()).andExpect(status().isSeeOther());
        UUID assignment = assignmentId(head);
        mvc.perform(chartCommand("CHANGE_END", 1, UUID.randomUUID()).param("assignmentId", assignment.toString())
                        .param("lastDay", today.plusDays(2).toString()))
                .andExpect(status().isSeeOther());
        db.update("UPDATE tna.organization SET active=false WHERE tenant_id=? AND id=?", tenant, organization);
        mvc.perform(chartCommand("CHANGE_END", 2, UUID.randomUUID()).param("assignmentId", assignment.toString())
                        .param("lastDay", today.plusDays(3).toString()))
                .andExpect(status().isUnprocessableEntity());
        mvc.perform(chartCommand("CHANGE_END", 2, UUID.randomUUID()).param("assignmentId", assignment.toString()))
                .andExpect(status().isUnprocessableEntity());
        db.update("UPDATE tna.employee SET active=false WHERE tenant_id=? AND id=?", tenant, head);
        mvc.perform(chartCommand("CHANGE_END", 2, UUID.randomUUID()).param("assignmentId", assignment.toString())
                        .param("lastDay", today.plusDays(1).toString()))
                .andExpect(status().isSeeOther());
        assertEquals(today.plusDays(2), toDate(head));
        mvc.perform(chartCommand("CHANGE_END", 3, UUID.randomUUID()).param("assignmentId", assignment.toString())
                        .param("endToday", "true"))
                .andExpect(status().isSeeOther());
        assertEquals(0, db.queryForObject("SELECT count(*) FROM tna.organization_head_assignment WHERE tenant_id=? AND superseded_at IS NULL", Integer.class, tenant));
    }

    @Test
    void chartShowsTheEmploymentLimitAndPreservesAStaleDraftWithoutLeakingManagement() throws Exception {
        UUID leaving = person("LEAVING", today.minusYears(1), today.plusDays(2), true);
        mvc.perform(chartCommand("ASSIGN", 0, UUID.randomUUID()).param("employeeId", leaving.toString())
                        .param("validFrom", today.toString()).param("intent", "PREVIEW"))
                .andExpect(status().isOk())
                .andExpect(ReactPageAssertions.data("/headPreview/after/lastDay", today.plusDays(2).toString()));
        mvc.perform(chartCommand("ASSIGN", 0, UUID.randomUUID()).param("employeeId", leaving.toString())
                        .param("validFrom", today.toString()).param("lastDay", today.plusDays(3).toString()))
                .andExpect(status().isUnprocessableEntity());
        set(head, today, 0, UUID.randomUUID()).andExpect(status().isSeeOther());
        mvc.perform(chartCommand("ASSIGN", 0, UUID.randomUUID()).param("employeeId", secondHead.toString())
                        .param("validFrom", today.toString()).param("lastDay", today.toString()))
                .andExpect(status().isConflict())
                .andExpect(ReactPageAssertions.data("/headCommand/employeeId", secondHead.toString()))
                .andExpect(ReactPageAssertions.data("/companyOrganizations/q", "MEMBER"))
                .andExpect(ReactPageAssertions.data("/organizationHeads/form/expectedVersion", 1));
        var representative = member("REP", Set.of("EMPLOYEE", "COMPANY_REPRESENTATIVE"));
        var view = mvc.perform(get("/company/organizations").with(as(representative)).accept(JSON))
                .andExpect(status().isOk()).andReturn();
        assertFalse(ReactPageAssertions.envelope(view).at("/data").has("organizationHeads"));
        mvc.perform(get("/admin/organization-heads").with(as(representative)).accept(JSON)
                        .param("organizationId", organization.toString()).param("chart", "true"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/admin/organization-heads/commands").with(as(representative)).with(csrf()).accept(JSON))
                .andExpect(status().isForbidden());
        mvc.perform(post("/admin/organization-heads/commands").with(as(admin)).accept(JSON)).andExpect(status().isForbidden());
    }

    private MockHttpServletRequestBuilder chartCommand(String operation, long version, UUID command) {
        return post("/admin/organization-heads/commands").with(as(admin)).with(csrf()).accept(JSON)
                .param("organizationId", organization.toString()).param("operation", operation).param("q", "MEMBER").param("page", "1")
                .param("expectedVersion", Long.toString(version)).param("reason", REASON).param("commandId", command.toString());
    }

    private UUID assignmentId(UUID person) {
        return db.queryForObject("SELECT id FROM tna.organization_head_assignment WHERE tenant_id=? AND employee_id=? AND superseded_at IS NULL",
                UUID.class, tenant, person);
    }

    private org.springframework.test.web.servlet.ResultActions end(LocalDate from, long version, UUID command) throws Exception {
        return mvc.perform(form("/admin/organization-heads/end", from, version, command));
    }

    private MockHttpServletRequestBuilder form(String path, LocalDate from, long version, UUID command) {
        return post(path).with(as(admin)).with(csrf()).accept(JSON).param("organizationId", organization.toString())
                .param("validFrom", from.toString()).param("expectedVersion", Long.toString(version))
                .param("reason", REASON).param("commandId", command.toString());
    }

    private long version() { return db.queryForObject("SELECT version FROM tna.organization WHERE tenant_id=? AND id=?", Long.class, tenant, organization); }

    private int count(String table) { return db.queryForObject("SELECT count(*) FROM tna." + table + " WHERE tenant_id=?", Integer.class, tenant); }

    private LocalDate toDate(UUID person) {
        return db.queryForObject("SELECT valid_to FROM tna.organization_head_assignment WHERE tenant_id=? AND employee_id=? AND superseded_at IS NULL", LocalDate.class, tenant, person);
    }

    private UUID person(String number, LocalDate joined, LocalDate left, boolean active) {
        UUID id = UUID.randomUUID();
        db.update("INSERT INTO tna.employee(tenant_id,id,employee_no,display_name,joined_on,left_on,active) VALUES (?,?,?,?,?,?,?)",
                tenant, id, number, "합성 " + number, joined, left, active);
        return id;
    }

    private TnaPrincipal member(String number, Set<String> roles) {
        UUID person = person(number, today.minusYears(1), null, true), account = UUID.randomUUID();
        db.update("INSERT INTO tna.tenant_account(tenant_id,id,employee_id,email,password_hash) VALUES (?,?,?,?,?)",
                tenant, account, person, account + "@example.test", "synthetic-head-hash");
        for (String role : roles) db.update("INSERT INTO tna.role_grant(tenant_id,account_id,role) VALUES (?,?,?)", tenant, account, role);
        return new TnaPrincipal(tenant, account, person, 1, roles, Instant.now());
    }

    private UUID organization(String code) {
        UUID id = UUID.randomUUID();
        db.update("INSERT INTO tna.organization(tenant_id,id,code,name,created_by,updated_by) VALUES (?,?,?,?,?,?)",
                tenant, id, code, "조직 " + code, admin.accountId(), admin.accountId());
        return id;
    }

    private void assign(UUID person, UUID target) {
        db.update("INSERT INTO tna.employee_assignment(tenant_id,id,employee_id,organization_id,valid_from,reason,created_by,updated_by) VALUES (?,?,?,?,?,?,?,?)",
                tenant, UUID.randomUUID(), person, target, today, REASON, admin.accountId(), admin.accountId());
    }

    private static RequestPostProcessor as(TnaPrincipal actor) {
        return authentication(UsernamePasswordAuthenticationToken.authenticated(actor, null,
                actor.roles().stream().map(role -> new SimpleGrantedAuthority("ROLE_" + role)).toList()));
    }
}
