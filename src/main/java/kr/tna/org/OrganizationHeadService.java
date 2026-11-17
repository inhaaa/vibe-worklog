package kr.tna.org;

import static kr.tna.org.OrgCommands.*;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import kr.tna.common.auth.TnaPrincipal;
import kr.tna.common.tenant.CompanyReadAccess;
import kr.tna.org.OrganizationHeadData.*;
import org.springframework.stereotype.Service;

@Service
public final class OrganizationHeadService {
    private final OrganizationHeadMapper mapper;
    private final OrgCommands commands;
    private final CompanyReadAccess company;
    private final Clock clock;

    public OrganizationHeadService(OrganizationHeadMapper mapper, OrgCommands commands, CompanyReadAccess company, Clock clock) {
        this.mapper = mapper;
        this.commands = commands;
        this.company = company;
        this.clock = clock;
    }

    public ManagementView management(TnaPrincipal actor, UUID requestedOrganization, String q, HeadForm draft) {
        String query = query(q);
        return commands.read(actor, () -> {
            mapper.lock(actor.tenantId());
            return commands.read(actor, () -> {
                LocalDate today = commands.today();
                var organizations = List.copyOf(mapper.organizations(actor.tenantId()));
                UUID id = draft == null ? requestedOrganization : draft.organizationId();
                if (id == null && !organizations.isEmpty()) id = organizations.getFirst().id();
                var organization = id == null ? null : requireOrganization(actor.tenantId(), id);
                var history = organization == null ? List.<HeadAssignment>of()
                        : List.copyOf(mapper.history(actor.tenantId(), organization.id(), today));
                var endChanges = organization == null ? List.<HeadEndChange>of()
                        : List.copyOf(mapper.endChanges(actor.tenantId(), organization.id()));
                var current = history.stream().filter(row -> row.supersededAt() == null && !row.validFrom().isAfter(today)
                        && (row.validTo() == null || row.validTo().isAfter(today))).findFirst().orElse(null);
                var form = draft != null ? draft : new HeadForm(id, current == null ? null : current.employeeId(),
                        today, organization == null ? null : organization.version(), "", UUID.randomUUID());
                return new ManagementView(clock.instant(), today, id, organizations,
                        List.copyOf(mapper.candidates(actor.tenantId(), today, query)), history, endChanges, form, query);
            });
        });
    }

    public CompanyView tree(TnaPrincipal actor, UUID requestedOrganization, String q, int page) {
        String query = query(q);
        int offset = offset(page);
        return company.read(actor, () -> {
            LocalDate today = commands.today();
            var organizations = List.copyOf(mapper.tree(actor.tenantId(), today));
            var selected = requestedOrganization == null ? (organizations.isEmpty() ? null : organizations.getFirst())
                    : organizations.stream().filter(row -> row.id().equals(requestedOrganization)).findFirst().orElseThrow(OrgException::notFound);
            var found = selected == null ? List.<Member>of()
                    : mapper.members(actor.tenantId(), selected.id(), today, query, offset);
            return new CompanyView(clock.instant(), today, selected == null ? null : selected.id(), query, page,
                    found.size() > 50, organizations, selected, List.copyOf(found.subList(0, Math.min(50, found.size()))));
        });
    }

    public UUID set(TnaPrincipal actor, HeadForm form) { return change(actor, form, false); }

    public UUID end(TnaPrincipal actor, HeadForm form) { return change(actor, form, true); }

    public HeadOverview overview(TnaPrincipal actor, UUID organizationId) {
        return commands.read(actor, () -> {
            mapper.lock(actor.tenantId());
            LocalDate today = commands.today();
            var history = mapper.history(actor.tenantId(), organizationId, today);
            var current = history.stream().filter(row -> row.supersededAt() == null && HeadScheduleChange.covers(row, today))
                    .findFirst().orElse(null);
            var next = history.stream().filter(row -> row.supersededAt() == null && row.validFrom().isAfter(today))
                    .min(java.util.Comparator.comparing(HeadAssignment::validFrom)).orElse(null);
            return new HeadOverview(HeadScheduleChange.period(current), HeadScheduleChange.period(next));
        });
    }

    public HeadPreview preview(TnaPrincipal actor, HeadCommand form) {
        checkCommand(form);
        return commands.read(actor, () -> {
            mapper.lock(actor.tenantId());
            return commands.read(actor, () -> {
                var organization = requireOrganization(actor.tenantId(), form.organizationId());
                checkVersion(form.expectedVersion(), organization.version());
                return plan(actor.tenantId(), organization, form, commands.today()).preview();
            });
        });
    }

    public UUID command(TnaPrincipal actor, HeadCommand form) {
        String reason = checkCommand(form);
        String action = "ORGANIZATION_HEAD_" + form.operation() + "_PERIOD";
        return commands.execute(actor, form.commandId(), action, "ORGANIZATION",
                fingerprint(action, form.organizationId(), form.assignmentId(), form.employeeId(), form.validFrom(),
                        form.lastDay(), form.endsToday(), form.expectedVersion(), reason), reason, () -> {
                            UUID tenant = actor.tenantId();
                            var organization = requireOrganization(tenant, form.organizationId());
                            checkVersion(form.expectedVersion(), organization.version());
                            var plan = plan(tenant, organization, form, commands.today());
                            var target = plan.target();
                            var after = plan.preview().after();
                            if (form.lastDay() == null && after != null && after.lastDay() != null) {
                                throw OrgException.invalid("종료일 " + after.lastDay() + "을 미리보기에서 확인하고 저장해 주세요.");
                            }
                            if (plan.closeAt() != null) close(actor, target, plan.closeAt());
                            UUID assignment = null;
                            if (after != null) {
                                var to = HeadScheduleChange.boundary(after.lastDay());
                                if (form.operation() == HeadOperation.CHANGE_END) {
                                    assignment = target.id();
                                    changed(mapper.changeEnd(tenant, assignment, to, target.version(), actor.accountId()));
                                } else {
                                    assignment = insert(actor, organization.id(), after.employeeId(), after.validFrom(), to, reason);
                                }
                            }
                            advance(actor, organization);
                            var applied = after == null ? null
                                    : new HeadState(organization.version() + 1, assignment, after.employeeId(),
                                            after.validFrom(), HeadScheduleChange.boundary(after.lastDay()));
                            return new Change(organization.id(), state(organization.version(), target),
                                    new PeriodChange(organization.version() + 1, target == null ? null : target.id(),
                                            plan.closeAt(), applied));
                        });
    }

    private HeadScheduleChange.Plan plan(UUID tenant, OrganizationOption organization, HeadCommand form, LocalDate today) {
        var rows = mapper.history(tenant, organization.id(), today);
        var target = HeadScheduleChange.target(form, rows);
        OrgData.Employee employee = switch (form.operation()) {
            case ASSIGN, REPLACE, RESCHEDULE -> candidate(tenant, form.employeeId(), today, form.validFrom());
            case CHANGE_END -> form.endsToday() ? null : mapper.employee(tenant, target.employeeId());
            case CANCEL -> null;
        };
        return HeadScheduleChange.plan(form, organization, today, rows, target, employee);
    }

    private static String checkCommand(HeadCommand form) {
        if (form == null || form.operation() == null || form.organizationId() == null || form.commandId() == null) {
            throw OrgException.invalid("조직·변경 종류·명령 식별자를 확인해 주세요.");
        }
        return requiredText(form.reason(), 500, "변경 사유");
    }

    private OrgData.Employee candidate(UUID tenant, UUID employeeId, LocalDate today, LocalDate from) {
        if (employeeId == null || from == null) throw OrgException.invalid("조직장과 시작일을 입력해 주세요.");
        var person = mapper.employee(tenant, employeeId);
        if (person == null) throw OrgException.notFound();
        if (!person.active() || person.joinedOn().isAfter(today) || person.joinedOn().isAfter(from)
                || person.leftOn() != null && (person.leftOn().isBefore(today) || person.leftOn().isBefore(from))) {
            throw OrgException.invalid("현재와 적용일 모두 재직 중인 활성 사원을 선택해 주세요.");
        }
        return person;
    }

    public Map<UUID, SheetHead> organizationSheetHeads(TnaPrincipal actor) {
        return commands.read(actor, () -> {
            mapper.lock(actor.tenantId());
            return commands.read(actor, () -> {
                var heads = new LinkedHashMap<UUID, SheetHead>();
                for (var node : mapper.tree(actor.tenantId(), commands.today())) {
                    heads.put(node.id(), new SheetHead(node.headEmployeeName(), node.headEligible(), node.headApproverEligible()));
                }
                return Map.copyOf(heads);
            });
        });
    }

    public Map<UUID, EmployeeContext> employeeSheetContext(TnaPrincipal actor, List<UUID> employeeIds) {
        if (employeeIds == null || employeeIds.size() > 50 || employeeIds.stream().anyMatch(java.util.Objects::isNull)) {
            throw OrgException.invalid("현재 페이지의 사원 50명 이내로 조회해 주세요.");
        }
        var ids = List.copyOf(employeeIds);
        return commands.read(actor, () -> {
            mapper.lock(actor.tenantId());
            return commands.read(actor, () -> {
                var contexts = new LinkedHashMap<UUID, EmployeeContext>();
                if (!ids.isEmpty()) {
                    for (var row : mapper.employeeContexts(actor.tenantId(), ids, commands.today())) {
                        contexts.put(row.employeeId(), new EmployeeContext(row.organizationName(), row.organizationHeadName(),
                                row.organizationHeadEligible(), row.organizationHeadApproverEligible(), row.scheduleName()));
                    }
                }
                return Map.copyOf(contexts);
            });
        });
    }

    private UUID change(TnaPrincipal actor, HeadForm form, boolean ending) {
        if (form == null || form.organizationId() == null || form.validFrom() == null || !ending && form.employeeId() == null) {
            throw OrgException.invalid("조직과 적용일, 배정할 조직장을 확인해 주세요.");
        }
        String reason = requiredText(form.reason(), 500, "변경 사유");
        String action = ending ? "ORGANIZATION_HEAD_END" : "ORGANIZATION_HEAD_ASSIGN";
        return commands.execute(actor, form.commandId(), action, "ORGANIZATION",
                fingerprint(action, form.organizationId(), ending ? null : form.employeeId(), form.validFrom(), form.expectedVersion(), reason),
                reason, () -> {
                    UUID tenant = actor.tenantId();
                    var organization = requireOrganization(tenant, form.organizationId());
                    checkVersion(form.expectedVersion(), organization.version());
                    LocalDate today = commands.today(), from = form.validFrom();
                    if (from.isBefore(today) || from.getYear() > 9999) throw OrgException.invalid("조직장 적용일은 서울 기준 오늘 이후의 올바른 날짜로 지정해 주세요.");
                    LocalDate employmentEnd = null;
                    if (!ending) {
                        if (!organization.active()) throw OrgException.invalid("활성 조직에만 조직장을 배정할 수 있습니다.");
                        employmentEnd = HeadScheduleChange.boundary(candidate(tenant, form.employeeId(), today, from).leftOn());
                    }
                    var previous = mapper.covering(tenant, organization.id(), from, today);
                    if (ending && previous == null) throw OrgException.invalid("선택한 날짜에 종료할 조직장 배정이 없습니다.");
                    // Preserve the next planned transition, including a previously scheduled unassigned interval.
                    LocalDate to = HeadScheduleChange.earlier(employmentEnd,
                            previous == null ? mapper.nextStart(tenant, organization.id(), from) : previous.validTo());
                    if (previous != null) close(actor, previous, from);
                    UUID assignment = ending ? null : insert(actor, organization.id(), form.employeeId(), from, to, reason);
                    advance(actor, organization);
                    return new Change(organization.id(), state(organization.version(), previous),
                            new HeadState(organization.version() + 1, assignment, ending ? null : form.employeeId(), from, ending ? from : to));
                });
    }

    private void close(TnaPrincipal actor, HeadAssignment row, LocalDate date) {
        changed(mapper.close(actor.tenantId(), row.id(), date, row.version(), actor.accountId()));
    }

    private UUID insert(TnaPrincipal actor, UUID organization, UUID employee, LocalDate from, LocalDate to, String reason) {
        UUID id = UUID.randomUUID();
        changed(mapper.insert(actor.tenantId(), id, organization, employee, from, to, reason, actor.accountId()));
        return id;
    }

    private void advance(TnaPrincipal actor, OrganizationOption organization) {
        changed(mapper.advanceOrganization(actor.tenantId(), organization.id(), organization.version(), actor.accountId()));
    }

    private OrganizationOption requireOrganization(UUID tenant, UUID id) {
        var organization = mapper.organization(tenant, id);
        if (organization == null) throw OrgException.notFound();
        return organization;
    }

    private static String query(String q) {
        return q == null || q.isBlank() ? "" : requiredText(q, 100, "검색어");
    }

    private static void changed(int rows) {
        if (rows != 1) throw OrgException.conflict("다른 변경이 반영되었습니다. 최신 내용을 확인해 주세요.");
    }

    private record HeadState(long organizationVersion, UUID assignmentId, UUID headEmployeeId, LocalDate validFrom, LocalDate validTo) { }

    private static HeadState state(long version, HeadAssignment row) {
        return row == null ? new HeadState(version, null, null, null, null)
                : new HeadState(version, row.id(), row.employeeId(), row.validFrom(), row.validTo());
    }

    private record PeriodChange(long organizationVersion, UUID previousAssignmentId, LocalDate previousEndBoundary,
            HeadState assignment) { }
}
