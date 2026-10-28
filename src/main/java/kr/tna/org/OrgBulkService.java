package kr.tna.org;

import static kr.tna.org.OrgCommands.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import kr.tna.common.auth.TnaPrincipal;
import kr.tna.org.BulkData.*;
import kr.tna.org.OrgData.Employee;
import kr.tna.org.OrgData.Organization;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

@Service
public final class OrgBulkService {
    private final OrgCommands commands;
    private final OrganizationAdminMapper organizations;
    private final EmployeeAdminMapper employees;
    private final PublicDemoIdentityGuard identityGuard;
    private final JsonMapper json = JsonMapper.builder().build();

    public OrgBulkService(OrgCommands commands, OrganizationAdminMapper organizations, EmployeeAdminMapper employees,
                          PublicDemoIdentityGuard identityGuard) {
        this.commands = commands; this.organizations = organizations; this.employees = employees;
        this.identityGuard = identityGuard;
    }

    public Result organizations(TnaPrincipal actor, Form<OrganizationRow> form) {
        String reason = validate(form);
        List<OrganizationRow> rows = form.rows().stream().map(row -> new OrganizationRow(row.clientRowId(), row.id(),
                row.expectedVersion(), row.operation(), text(row, "code", () -> requiredText(row.code(), 40, "조직 코드")),
                text(row, "name", () -> requiredText(row.name(), 120, "조직 이름")), row.parentId(),
                row.operation() != Operation.DEACTIVATE && row.active())).toList();
        String action = "ORGANIZATION_BULK";
        return commands.executeBatch(actor, form.commandId(), action, "ORGANIZATION",
                fingerprint(action, reason, json.writeValueAsString(rows)), reason, () -> {
                    Map<UUID, Organization> before = organizations.options(actor.tenantId()).stream()
                            .collect(Collectors.toMap(Organization::id, value -> value));
                    Map<UUID, Organization> after = new HashMap<>(before);
                    Map<UUID, OrganizationRow> changed = new LinkedHashMap<>();
                    for (var row : rows) {
                        Organization old = row.id() == null ? null : before.get(row.id());
                        if (row.id() != null && old == null) throw OrgException.notFound();
                        if (old != null) {
                            text(row, "expectedVersion", () -> { checkVersion(row.expectedVersion(), old.version()); return old; });
                            if (!old.code().equals(row.code())) throw issue(row, "code", 422, "조직 코드는 변경할 수 없습니다.");
                        }
                        UUID id = old == null ? UUID.randomUUID() : old.id();
                        after.put(id, new Organization(id, row.code(), row.name(), row.parentId(), row.active(),
                                old == null ? 0 : old.version() + 1));
                        changed.put(id, row);
                    }
                    validateOrganizations(actor, before, after, changed);
                    var changes = new ArrayList<BulkData.Change>();
                    for (var entry : changed.entrySet()) {
                        Organization old = before.get(entry.getKey()), current = after.get(entry.getKey());
                        if (old == null) changed(organizations.insert(actor.tenantId(), current, actor.accountId()));
                        else changed(organizations.update(actor.tenantId(), new Organization(current.id(), current.code(), current.name(),
                                current.parentId(), current.active(), old.version()), actor.accountId()));
                        changes.add(new BulkData.Change(entry.getValue().clientRowId(), current.id(), current.version(), old, current));
                    }
                    return changes;
                });
    }

    public Result employees(TnaPrincipal actor, Form<EmployeeRow> form) {
        String reason = validate(form);
        List<EmployeeRow> rows = form.rows().stream().map(row -> {
            identityGuard.requireMutable(actor, row.id());
            String number = text(row, "employeeNo", () -> requiredText(row.employeeNo(), 40, "사번"));
            String name = text(row, "displayName", () -> requiredText(row.displayName(), 120, "이름"));
            if (row.joinedOn() == null) throw issue(row, "joinedOn", 422, "입사일을 입력해 주세요.");
            if (row.leftOn() != null && row.leftOn().isBefore(row.joinedOn())) {
                throw issue(row, "leftOn", 422, "마지막 재직일은 입사일 이후로 지정해 주세요.");
            }
            return new EmployeeRow(row.clientRowId(), row.id(), row.expectedVersion(), row.operation(), number, name,
                    row.joinedOn(), row.leftOn(), row.operation() != Operation.DEACTIVATE && row.active());
        }).toList();
        String action = "EMPLOYEE_BULK";
        return commands.executeBatch(actor, form.commandId(), action, "EMPLOYEE",
                fingerprint(action, reason, json.writeValueAsString(rows)), reason, () -> {
                    Map<UUID, Employee> before = employees.options(actor.tenantId()).stream()
                            .collect(Collectors.toMap(Employee::id, value -> value));
                    Map<UUID, Employee> after = new HashMap<>(before);
                    Map<UUID, EmployeeRow> changed = new LinkedHashMap<>();
                    for (var row : rows) {
                        Employee old = row.id() == null ? null : before.get(row.id());
                        if (row.id() != null && old == null) throw OrgException.notFound();
                        if (old != null) {
                            text(row, "expectedVersion", () -> { checkVersion(row.expectedVersion(), old.version()); return old; });
                            if (!old.employeeNo().equals(row.employeeNo())) throw issue(row, "employeeNo", 422, "사번은 등록 후 변경할 수 없습니다.");
                        }
                        UUID id = old == null ? UUID.randomUUID() : old.id();
                        after.put(id, new Employee(id, row.employeeNo(), row.displayName(), row.joinedOn(), row.leftOn(), row.active(),
                                old == null ? 0 : old.version() + 1));
                        changed.put(id, row);
                    }
                    for (var entry : changed.entrySet()) {
                        Employee current = after.get(entry.getKey());
                        if (after.values().stream().anyMatch(value -> !value.id().equals(current.id())
                                && value.employeeNo().equals(current.employeeNo()))) {
                            throw issue(entry.getValue(), "employeeNo", 409, "이미 사용 중인 사번입니다.");
                        }
                    }
                    var administrators = employees.administratorEmployees(actor.tenantId());
                    boolean removal = changed.keySet().stream().anyMatch(id -> {
                        Employee old = before.get(id), current = after.get(id);
                        return old != null && administrators.contains(id) && eligible(old) && employmentChanged(old, current)
                                && (!current.active() || current.leftOn() != null || current.joinedOn().isAfter(commands.today()));
                    });
                    if (removal && administrators.stream().map(after::get).noneMatch(value -> value != null
                            && eligible(value) && value.leftOn() == null)) {
                        throw issue(first(rows), "active", 422, "다른 재직 중인 활성 담당자를 지정한 뒤 재직 상태를 변경해 주세요.");
                    }
                    var changes = new ArrayList<BulkData.Change>();
                    for (var entry : changed.entrySet()) {
                        Employee old = before.get(entry.getKey()), current = after.get(entry.getKey());
                        if (old == null) changed(employees.insert(actor.tenantId(), current, actor.accountId()));
                        else {
                            changed(employees.update(actor.tenantId(), current, old.version(), actor.accountId()));
                            if (employmentChanged(old, current)) employees.revokeSessions(actor.tenantId(), current.id());
                        }
                        changes.add(new BulkData.Change(entry.getValue().clientRowId(), current.id(), current.version(), old, current));
                    }
                    return changes;
                });
    }

    public List<Organization> organizationOriginals(TnaPrincipal actor, List<Organization> page, List<OrganizationRow> dirty) {
        return commands.read(actor, () -> {
            var fresh = organizations.options(actor.tenantId()).stream().collect(Collectors.toMap(Organization::id, value -> value));
            var ids = new LinkedHashSet<UUID>();
            page.forEach(row -> ids.add(row.id()));
            addDraftIds(ids, dirty);
            requireDraftIds(fresh.keySet(), dirty);
            return ids.stream().filter(fresh::containsKey).map(fresh::get).toList();
        });
    }

    public List<Employee> employeeOriginals(TnaPrincipal actor, List<Employee> page, List<EmployeeRow> dirty) {
        return commands.read(actor, () -> {
            var ids = new LinkedHashSet<UUID>();
            page.forEach(row -> ids.add(row.id()));
            addDraftIds(ids, dirty);
            var requested = List.copyOf(ids);
            var fresh = new HashMap<UUID, Employee>();
            for (int offset = 0; offset < requested.size(); offset += 50) {
                for (var row : employees.getMany(actor.tenantId(), requested.subList(offset, Math.min(offset + 50, requested.size())))) {
                    fresh.put(row.id(), row);
                }
            }
            requireDraftIds(fresh.keySet(), dirty);
            return requested.stream().filter(fresh::containsKey).map(fresh::get).toList();
        });
    }

    private static void addDraftIds(Set<UUID> ids, List<? extends Row> dirty) {
        if (dirty.size() <= 50) {
            dirty.stream().filter(row -> row.operation() != Operation.CREATE && row.id() != null)
                    .forEach(row -> ids.add(row.id()));
        }
    }

    private static void requireDraftIds(Set<UUID> available, List<? extends Row> dirty) {
        if (dirty.size() <= 50 && dirty.stream().anyMatch(row -> row.operation() != Operation.CREATE
                && row.id() != null && !available.contains(row.id()))) throw OrgException.notFound();
    }

    private void validateOrganizations(TnaPrincipal actor, Map<UUID, Organization> before, Map<UUID, Organization> after,
                                       Map<UUID, OrganizationRow> changed) {
        for (var entry : changed.entrySet()) {
            Organization current = after.get(entry.getKey());
            if (after.values().stream().anyMatch(value -> !value.id().equals(current.id()) && value.code().equals(current.code()))) {
                throw issue(entry.getValue(), "code", 409, "이미 사용 중인 조직 코드입니다.");
            }
        }
        for (var entry : changed.entrySet()) {
            Organization old = before.get(entry.getKey()), current = after.get(entry.getKey());
            if (current.parentId() != null) {
                Organization parent = after.get(current.parentId());
                if (parent == null) throw OrgException.notFound();
                boolean preserved = old != null && !current.active() && Objects.equals(old.parentId(), current.parentId());
                if (!parent.active() && !preserved) throw issue(entry.getValue(), "parentId", 422, "활성 조직만 상위 조직으로 지정할 수 있습니다.");
            }
            if (old != null && old.active() && !current.active()
                    && organizations.hasCurrentOrFutureAssignments(actor.tenantId(), current.id(), commands.today())) {
                throw issue(entry.getValue(), "active", 422, "오늘 또는 미래 소속 배정이 있어 비활성화할 수 없습니다.");
            }
        }
        for (Organization value : after.values()) {
            if (value.active() && value.parentId() != null && !after.get(value.parentId()).active()) {
                var row = changed.getOrDefault(value.parentId(), changed.get(value.id()));
                throw issue(row, "active", 422, "활성 하위 조직이 있어 비활성화할 수 없습니다.");
            }
            Set<UUID> visited = new HashSet<>();
            UUID cursor = value.id();
            while (cursor != null) {
                if (!visited.add(cursor)) throw issue(changed.values().iterator().next(), "parentId", 422,
                        "조직은 자신이나 하위 조직을 상위 조직으로 지정할 수 없습니다.");
                cursor = after.get(cursor).parentId();
            }
        }
    }

    private <R extends Row> String validate(Form<R> form) {
        if (form == null || form.commandId() == null) throw new BulkException(422, "명령 식별자가 필요합니다.", "", "commandId");
        String reason;
        try { reason = requiredText(form.reason(), 500, "변경 사유"); }
        catch (OrgException error) { throw new BulkException(error.status(), error.getMessage(), "", "reason"); }
        if (form.rows() == null || form.rows().isEmpty() || form.rows().size() > 50) {
            throw new BulkException(422, "한 번에 변경한 1~50행을 저장해 주세요.", "", "rows");
        }
        Set<String> clientIds = new HashSet<>();
        Set<UUID> ids = new HashSet<>();
        for (R row : form.rows()) {
            if (row == null || row.clientRowId() == null || row.operation() == null) throw new BulkException(422, "행 식별자와 작업이 필요합니다.", "", "rows");
            try {
                if (!UUID.fromString(row.clientRowId()).toString().equals(row.clientRowId())) throw new IllegalArgumentException();
            } catch (IllegalArgumentException invalid) { throw issue(row, "clientRowId", 422, "올바른 행 식별자가 필요합니다."); }
            if (!clientIds.add(row.clientRowId()) || row.id() != null && !ids.add(row.id())) throw issue(row, "id", 422, "같은 행이나 대상을 중복 저장할 수 없습니다.");
            if (row.operation() == Operation.CREATE && (row.id() != null || row.expectedVersion() != null)) throw issue(row, "id", 422, "신규 행에 기존 식별자나 버전을 지정할 수 없습니다.");
            if (row.operation() != Operation.CREATE && (row.id() == null || row.expectedVersion() == null || row.expectedVersion() < 0)) throw issue(row, "expectedVersion", 422, "기존 행의 식별자와 현재 버전이 필요합니다.");
        }
        return reason;
    }

    private boolean eligible(Employee value) {
        return value.active() && !value.joinedOn().isAfter(commands.today())
                && (value.leftOn() == null || !value.leftOn().isBefore(commands.today()));
    }
    private static boolean employmentChanged(Employee before, Employee after) {
        return before.active() != after.active() || !before.joinedOn().equals(after.joinedOn()) || !Objects.equals(before.leftOn(), after.leftOn());
    }
    private static <R extends Row> R first(List<R> rows) { return rows.getFirst(); }
    private static <T> T text(Row row, String field, Supplier<T> action) {
        try { return action.get(); }
        catch (OrgException error) { throw issue(row, field, error.status(), error.getMessage()); }
    }
    private static BulkException issue(Row row, String field, int status, String message) {
        return new BulkException(status, message, row == null ? "" : row.clientRowId(), field);
    }
    private static void changed(int count) {
        if (count != 1) throw OrgException.conflict("다른 변경이 반영되었습니다. 최신 내용을 확인해 주세요.");
    }
}
