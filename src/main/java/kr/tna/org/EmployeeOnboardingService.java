package kr.tna.org;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import kr.tna.common.auth.TnaPrincipal;
import kr.tna.org.EmployeeOnboardingData.*;
import kr.tna.schedule.ScheduleData.Template;
import kr.tna.schedule.ScheduleData.TemplateVersion;
import kr.tna.schedule.ScheduleMapper;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

import static kr.tna.org.OrgCommands.*;

@Service
public class EmployeeOnboardingService {
    private final OrgCommands commands;
    private final EmployeeOnboardingMapper onboarding;
    private final EmployeeAdminMapper employees;
    private final OrganizationAdminMapper organizations;
    private final ScheduleMapper schedules;
    private final Clock clock;
    private final boolean publicDemo;

    public EmployeeOnboardingService(OrgCommands commands, EmployeeOnboardingMapper onboarding,
                                    EmployeeAdminMapper employees, OrganizationAdminMapper organizations,
                                    ScheduleMapper schedules, Clock clock, Environment environment) {
        this.commands = commands;
        this.onboarding = onboarding;
        this.employees = employees;
        this.organizations = organizations;
        this.schedules = schedules;
        this.clock = clock;
        this.publicDemo = environment.matchesProfiles("public-demo");
    }

    public View view(TnaPrincipal actor, String q, State state, LocalDate from, LocalDate to, int page,
                     UUID id, Form draft, Action action, boolean retrySameCommand) {
        int offset = offset(page);
        String query = q == null || q.isBlank() ? "" : requiredText(q, 120, "이름 검색어");
        if (invalidYear(from) || invalidYear(to) || from != null && to != null && from.isAfter(to)) {
            throw OrgException.invalid("조회 시작일과 종료일을 확인해 주세요.");
        }
        return commands.read(actor, () -> {
            UUID tenant = actor.tenantId();
            onboarding.lock(tenant);
            var asOf = clock.instant();
            var today = asOf.atZone(ZoneId.of("Asia/Seoul")).toLocalDate();
            var rows = onboarding.list(tenant, query, state, from, to, offset);
            Row detail = id == null ? null : requireRow(tenant, id);
            boolean editable = !publicDemo && (detail == null || detail.state() == State.PLANNED);
            var organizationOptions = editable ? onboarding.organizations(tenant) : List.<OrganizationOption>of();
            var scheduleOptions = editable ? onboarding.scheduleVersions(tenant) : List.<ScheduleOption>of();
            UUID originalVersion = detail == null ? null : detail.scheduleVersionId();
            var scheduleDays = editable || originalVersion != null
                    ? onboarding.scheduleDays(tenant, editable ? null : originalVersion) : List.<ScheduleDayOption>of();
            Form form = draft != null ? draft : newForm(detail, today, organizationOptions, scheduleOptions);
            boolean duplicate = detail != null && detail.state() == State.PLANNED
                    && onboarding.possibleDuplicate(tenant, id, detail.displayName(), detail.joinedOn());
            var employee = publicDemo || detail == null || detail.employeeId() == null ? null
                    : employees.get(tenant, detail.employeeId());
            boolean inviteEligible = employee != null && employee.active() && !employee.joinedOn().isAfter(today)
                    && (employee.leftOn() == null || !employee.leftOn().isBefore(today));
            return new View(asOf, today, query, state, from, to, page, rows.size() > 50,
                    rows.stream().limit(50).toList(), detail, organizationOptions, scheduleOptions, scheduleDays, form, publicDemo, action,
                    duplicate, inviteEligible, retrySameCommand);
        });
    }

    public UUID save(TnaPrincipal actor, UUID id, Form form) {
        requireMutable();
        String name = requiredText(form.displayName(), 120, "이름");
        String reason = requiredText(form.reason(), 500, "변경 사유");
        String action = id == null ? "ONBOARDING_CREATE" : "ONBOARDING_EDIT";
        var input = new Form(name, form.joinedOn(), form.leftOn(), form.organizationId(), form.scheduleVersionId(),
                null, form.expectedVersion(), null, null, form.commandId(), reason);
        return commands.execute(actor, input.commandId(), action, "EMPLOYEE_ONBOARDING",
                fingerprint(action, id, name, input.joinedOn(), input.leftOn(), input.organizationId(),
                        input.scheduleVersionId(), input.expectedVersion(), reason), reason, () -> {
                    UUID tenant = actor.tenantId();
                    Row previous = id == null ? null : requirePlanned(tenant, id, input.expectedVersion());
                    validateDates(input.joinedOn(), input.leftOn());
                    if (input.organizationId() != null) activeOrganization(tenant, input.organizationId());
                    if (input.scheduleVersionId() != null) activeSchedule(tenant, input.scheduleVersionId(), input.joinedOn());
                    UUID target = previous == null ? UUID.randomUUID() : id;
                    if (previous == null) changed(onboarding.insert(tenant, target, input, actor.accountId()));
                    else changed(onboarding.edit(tenant, target, input, actor.accountId()));
                    return new Change(target, audit(previous), audit(requireRow(tenant, target)));
                });
    }

    public UUID confirm(TnaPrincipal actor, UUID id, Form form) {
        requireMutable();
        String number = form.employeeNo() == null || form.employeeNo().isBlank() ? null
                : requiredText(form.employeeNo(), 40, "사번");
        String reason = requiredText(form.reason(), 500, "확정 사유");
        String action = "ONBOARDING_CONFIRM";
        return commands.execute(actor, form.commandId(), action, "EMPLOYEE_ONBOARDING",
                fingerprint(action, id, form.expectedVersion(), form.organizationId(), form.expectedOrganizationVersion(),
                        form.scheduleVersionId(), form.expectedTemplateVersion(), number, reason), reason, () -> {
                    UUID tenant = actor.tenantId(), account = actor.accountId();
                    Row planned = requirePlanned(tenant, id, form.expectedVersion());
                    validateDates(planned.joinedOn(), planned.leftOn());
                    var organization = activeOrganization(tenant, form.organizationId());
                    checkVersion(form.expectedOrganizationVersion(), organization.version());
                    var selected = activeSchedule(tenant, form.scheduleVersionId(), planned.joinedOn());
                    checkVersion(form.expectedTemplateVersion(), selected.template().version());
                    if ("99999".equals(number)) throw OrgException.invalid("예약 사번 99999는 사용할 수 없습니다.");
                    String assignedNumber = number == null ? EmployeeNumbers.next(planned.joinedOn(), employees.employeeNumbers(tenant)) : number;
                    UUID employeeId = UUID.randomUUID(), organizationAssignment = UUID.randomUUID(), scheduleAssignment = UUID.randomUUID();
                    changed(employees.insert(tenant, new OrgData.Employee(employeeId, assignedNumber, planned.displayName(),
                            planned.joinedOn(), planned.leftOn(), true, 0), account));
                    changed(employees.insertAssignment(tenant, organizationAssignment, employeeId, organization.id(),
                            planned.joinedOn(), reason, account));
                    if (selected.group()) {
                        changed(onboarding.joinGroup(tenant, scheduleAssignment, employeeId, selected.version().id(),
                                selected.template().id(), planned.joinedOn(), planned.leftOn(), reason, account));
                    } else {
                        changed(schedules.insertAssignment(tenant, scheduleAssignment, employeeId, selected.version().id(),
                                planned.joinedOn(), reason, account));
                    }
                    changed(onboarding.confirm(tenant, id, planned.version(), organization, selected.version(), selected.template(),
                            employeeId, organizationAssignment, scheduleAssignment, account));
                    return new Change(id, audit(planned), audit(requireRow(tenant, id)));
                });
    }

    public UUID withdraw(TnaPrincipal actor, UUID id, Form form) {
        requireMutable();
        String reason = requiredText(form.reason(), 500, "철회 사유");
        String action = "ONBOARDING_WITHDRAW";
        return commands.execute(actor, form.commandId(), action, "EMPLOYEE_ONBOARDING",
                fingerprint(action, id, form.expectedVersion(), reason), reason, () -> {
                    UUID tenant = actor.tenantId();
                    Row planned = requirePlanned(tenant, id, form.expectedVersion());
                    changed(onboarding.withdraw(tenant, id, planned.version(), actor.accountId()));
                    return new Change(id, audit(planned), audit(requireRow(tenant, id)));
                });
    }

    private Form newForm(Row detail, LocalDate today, List<OrganizationOption> orgOptions, List<ScheduleOption> choices) {
        if (detail == null) {
            return new Form("", today, null, null, null, null, null, null, null, UUID.randomUUID(), "");
        }
        Long organizationVersion = orgOptions.stream().filter(option -> option.id().equals(detail.organizationId()))
                .findFirst().map(OrganizationOption::version).orElse(null);
        Long templateVersion = choices.stream().filter(option -> option.id().equals(detail.scheduleVersionId()))
                .findFirst().map(ScheduleOption::templateVersion).orElse(null);
        return new Form(detail.displayName(), detail.joinedOn(), detail.leftOn(), detail.organizationId(),
                detail.scheduleVersionId(), null, detail.version(), organizationVersion, templateVersion, UUID.randomUUID(), "");
    }

    private Row requireRow(UUID tenant, UUID id) {
        Row row = onboarding.find(tenant, id);
        if (row == null) throw OrgException.notFound();
        return row;
    }

    private Row requirePlanned(UUID tenant, UUID id, Long expectedVersion) {
        Row row = requireRow(tenant, id);
        if (row.state() != State.PLANNED) throw OrgException.conflict("이미 확정하거나 철회한 예정입니다.");
        checkVersion(expectedVersion, row.version());
        return row;
    }

    private OrgData.Organization activeOrganization(UUID tenant, UUID id) {
        if (id == null) throw OrgException.invalid("최초 소속을 지정해 주세요.");
        var organization = organizations.find(tenant, id);
        if (organization == null) throw OrgException.notFound();
        if (!organization.active()) throw OrgException.invalid("활성 조직을 선택해 주세요.");
        return organization;
    }

    private SelectedSchedule activeSchedule(UUID tenant, UUID id, LocalDate joinedOn) {
        if (id == null) throw OrgException.invalid("최초 근무 버전을 지정해 주세요.");
        var version = schedules.version(tenant, id);
        if (version == null) throw OrgException.notFound();
        var template = schedules.template(tenant, version.templateId());
        if (template == null) throw OrgException.notFound();
        if (!template.active() || joinedOn.isBefore(version.validFrom())) {
            throw OrgException.invalid("입사일에 사용할 수 있는 활성 고정 근무 버전을 선택해 주세요.");
        }
        boolean group = schedules.isWorkGroup(tenant, template.id());
        if (group && !version.id().equals(onboarding.effectiveVersion(tenant, template.id(), joinedOn))) {
            throw OrgException.conflict("입사일의 근무조 개정이 바뀌었습니다. 유효한 개정을 다시 선택해 주세요.");
        }
        return new SelectedSchedule(version, template, group);
    }

    private void validateDates(LocalDate joined, LocalDate last) {
        if (joined == null || invalidYear(joined) || joined.isBefore(commands.today())) {
            throw OrgException.invalid("회사 입사일을 서울 기준 오늘 이후로 지정해 주세요.");
        }
        if (last != null && (invalidYear(last) || last.isBefore(joined))) {
            throw OrgException.invalid("마지막 재직일을 입사일 이후로 지정해 주세요.");
        }
    }

    private void requireMutable() {
        if (publicDemo) throw OrgException.forbidden();
    }

    private static boolean invalidYear(LocalDate date) {
        return date != null && (date.getYear() < 1 || date.getYear() > 9999);
    }

    private static void changed(int rows) {
        if (rows != 1) throw OrgException.conflict("다른 변경이 반영되었습니다. 최신 내용을 다시 확인해 주세요.");
    }

    private static AuditState audit(Row row) {
        return row == null ? null : new AuditState(row.state(), row.version(), row.organizationId(),
                row.scheduleVersionId(), row.employeeId(), row.organizationAssignmentId(), row.scheduleAssignmentId());
    }

    private record SelectedSchedule(TemplateVersion version, Template template, boolean group) { }
    private record AuditState(State state, long version, UUID organizationId, UUID scheduleVersionId, UUID employeeId,
                              UUID organizationAssignmentId, UUID scheduleAssignmentId) { }
}
