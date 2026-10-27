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
                var current = history.stream().filter(row -> row.supersededAt() == null && !row.validFrom().isAfter(today)
                        && (row.validTo() == null || row.validTo().isAfter(today))).findFirst().orElse(null);
                var form = draft != null ? draft : new HeadForm(id, current == null ? null : current.employeeId(),
                        today, organization == null ? null : organization.version(), "", UUID.randomUUID());
                return new ManagementView(clock.instant(), today, id, organizations,
                        List.copyOf(mapper.candidates(actor.tenantId(), today, query)), history, form, query);
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
                    if (!ending) {
                        if (!organization.active()) throw OrgException.invalid("활성 조직에만 조직장을 배정할 수 있습니다.");
                        var employee = mapper.employee(tenant, form.employeeId());
                        if (employee == null) throw OrgException.notFound();
                        if (!employee.active() || employee.joinedOn().isAfter(today)
                                || employee.leftOn() != null && employee.leftOn().isBefore(today)
                                || employee.joinedOn().isAfter(from) || employee.leftOn() != null && employee.leftOn().isBefore(from)) {
                            throw OrgException.invalid("현재와 적용일 모두 재직 중인 활성 사원을 선택해 주세요.");
                        }
                    }
                    var previous = mapper.covering(tenant, organization.id(), from, today);
                    if (ending && previous == null) throw OrgException.invalid("선택한 날짜에 종료할 조직장 배정이 없습니다.");
                    // Preserve the next planned transition, including a previously scheduled unassigned interval.
                    LocalDate to = previous == null ? mapper.nextStart(tenant, organization.id(), from) : previous.validTo();
                    if (previous != null) changed(mapper.close(tenant, previous.id(), from, previous.version(), actor.accountId()));
                    UUID assignment = null;
                    if (!ending) {
                        assignment = UUID.randomUUID();
                        changed(mapper.insert(tenant, assignment, organization.id(), form.employeeId(), from, to, reason, actor.accountId()));
                    }
                    changed(mapper.advanceOrganization(tenant, organization.id(), organization.version(), actor.accountId()));
                    return new Change(organization.id(), previous == null ? new HeadState(organization.version(), null, null, null, null)
                            : new HeadState(organization.version(), previous.id(), previous.employeeId(), previous.validFrom(), previous.validTo()),
                            new HeadState(organization.version() + 1, assignment, ending ? null : form.employeeId(), from, ending ? from : to));
                });
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
}
