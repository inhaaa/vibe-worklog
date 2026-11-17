package kr.tna.org;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import kr.tna.common.auth.TnaPrincipal;
import kr.tna.org.OrgCommands.Change;
import kr.tna.org.OrgData.Assignment;
import kr.tna.org.OrgData.AssignmentForm;
import kr.tna.org.OrgData.Employee;
import kr.tna.org.OrgData.EmployeeForm;
import org.springframework.stereotype.Service;

import static kr.tna.org.OrgCommands.*;

@Service
public final class EmployeeService {
    private final EmployeeAdminMapper mapper;
    private final OrgCommands commands;
    private final EmployeeOnboardingMapper onboarding;

    public EmployeeService(EmployeeAdminMapper mapper, OrgCommands commands, EmployeeOnboardingMapper onboarding) {
        this.mapper = mapper;
        this.commands = commands;
        this.onboarding = onboarding;
    }

    public List<Employee> list(TnaPrincipal actor, String query, int page) {
        int offset = offset(page);
        String normalized = query == null || query.isBlank() ? "" : requiredText(query, 120, "검색어");
        String search = "%" + normalized.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
        return commands.read(actor, () -> mapper.list(actor.tenantId(), search, offset));
    }

    public OrgData.EmployeeDirectory directory(TnaPrincipal actor, String query, int page,
            UUID organizationId, String status, UUID selectedId) {
        int offset = offset(page);
        String normalized = query == null || query.isBlank() ? "" : requiredText(query, 120, "검색어");
        String search = "%" + normalized.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
        if (!Set.of("all", "active", "inactive").contains(status)) {
            throw new OrgException(400, "활성 상태 필터를 확인해 주세요.");
        }
        return commands.read(actor, () -> {
            if (organizationId != null && mapper.organizationActive(actor.tenantId(), organizationId) == null) {
                throw OrgException.notFound();
            }
            if (selectedId != null) requireEmployee(actor.tenantId(), selectedId);
            LocalDate today = commands.today();
            var result = mapper.directory(actor.tenantId(), search, offset, organizationId, status, today);
            var visible = result.stream().limit(50).toList();
            Employee selected = selectedId == null ? (visible.isEmpty() ? null : visible.getFirst())
                    : visible.stream().filter(employee -> employee.id().equals(selectedId)).findFirst()
                            .orElseThrow(OrgException::notFound);
            Map<UUID, String> names = new HashMap<>();
            if (!visible.isEmpty()) {
                for (var assignment : mapper.currentOrganizations(actor.tenantId(),
                        visible.stream().map(Employee::id).toList(), today)) {
                    names.put(assignment.employeeId(), assignment.organizationName());
                }
            }
            return new OrgData.EmployeeDirectory(visible, Map.copyOf(names), selected, result.size() > 50);
        });
    }

    public List<Employee> options(TnaPrincipal actor) {
        return commands.read(actor, () -> mapper.options(actor.tenantId()));
    }

    public Employee get(TnaPrincipal actor, UUID id) {
        return commands.read(actor, () -> requireEmployee(actor.tenantId(), id));
    }

    public UUID create(TnaPrincipal actor, EmployeeForm form) {
        var values = normalize(form, true);
        String reason = requiredText(form.reason(), 500, "변경 사유");
        String action = "EMPLOYEE_CREATE";
        return commands.execute(actor, form.commandId(), action, "EMPLOYEE",
                fingerprint(action, values.employeeNo(), values.displayName(), values.joinedOn(),
                        values.leftOn(), values.active(), form.expectedVersion(), reason), reason, () -> {
                    String number = values.employeeNo() == null
                            ? EmployeeNumbers.next(values.joinedOn(), mapper.employeeNumbers(actor.tenantId())) : values.employeeNo();
                    var employee = new Employee(UUID.randomUUID(), number, values.displayName(),
                            values.joinedOn(), values.leftOn(), values.active(), 0);
                    mapper.insert(actor.tenantId(), employee, actor.accountId());
                    return new Change(employee.id(), null, employee);
                });
    }

    public UUID update(TnaPrincipal actor, UUID id, EmployeeForm form) {
        var values = normalize(form, false);
        String reason = requiredText(form.reason(), 500, "변경 사유");
        String action = "EMPLOYEE_UPDATE";
        return commands.execute(actor, form.commandId(), action, "EMPLOYEE",
                fingerprint(action, id, values.employeeNo(), values.displayName(), values.joinedOn(),
                        values.leftOn(), values.active(), form.expectedVersion(), reason), reason, () -> {
                    Employee before = requireEmployee(actor.tenantId(), id);
                    checkVersion(form.expectedVersion(), before.version());
                    if (!before.employeeNo().equals(values.employeeNo())) {
                        throw OrgException.invalid("사번은 등록 후 변경할 수 없습니다.");
                    }
                    if (!before.joinedOn().equals(values.joinedOn()) && onboarding.isConfirmedEmployee(actor.tenantId(), id)) {
                        throw OrgException.invalid("최초 발령이 확정된 사원의 입사일은 변경할 수 없습니다.");
                    }
                    boolean employmentChanged = before.active() != values.active()
                            || !before.joinedOn().equals(values.joinedOn())
                            || !Objects.equals(before.leftOn(), values.leftOn());
                    LocalDate today = commands.today();
                    boolean possibleRemoval = !values.active() || values.leftOn() != null || values.joinedOn().isAfter(today);
                    if (employmentChanged && possibleRemoval
                            && mapper.isEffectiveAdmin(actor.tenantId(), id, today)
                            && !mapper.hasOtherPermanentAdmin(actor.tenantId(), id, today)) {
                        throw OrgException.invalid("다른 재직 중인 활성 담당자를 지정한 뒤 재직 상태를 변경해 주세요.");
                    }
                    var after = new Employee(id, before.employeeNo(), values.displayName(), values.joinedOn(),
                            values.leftOn(), values.active(), before.version() + 1);
                    requireChanged(mapper.update(actor.tenantId(), after, before.version(), actor.accountId()));
                    if (employmentChanged) mapper.revokeSessions(actor.tenantId(), id);
                    return new Change(id, before, after);
                });
    }

    public List<Assignment> assignments(TnaPrincipal actor, UUID employeeId) {
        return commands.read(actor, () -> {
            requireEmployee(actor.tenantId(), employeeId);
            return mapper.assignments(actor.tenantId(), employeeId);
        });
    }

    public UUID assign(TnaPrincipal actor, UUID employeeId, AssignmentForm form) {
        if (form.organizationId() == null || form.validFrom() == null) {
            throw OrgException.invalid("조직과 소속 적용일을 입력해 주세요.");
        }
        String reason = requiredText(form.reason(), 500, "변경 사유");
        String action = "EMPLOYEE_ASSIGN";
        return commands.execute(actor, form.commandId(), action, "EMPLOYEE",
                fingerprint(action, employeeId, form.organizationId(), form.validFrom(), form.expectedVersion(), reason),
                reason, () -> {
                    Employee employee = requireEmployee(actor.tenantId(), employeeId);
                    checkVersion(form.expectedVersion(), employee.version());
                    LocalDate start = form.validFrom();
                    if (start.isBefore(commands.today()) || start.isBefore(employee.joinedOn())
                            || employee.leftOn() != null && start.isAfter(employee.leftOn())) {
                        throw OrgException.invalid("소속 적용일은 오늘 이후의 재직 기간 안이어야 합니다.");
                    }
                    Boolean active = mapper.organizationActive(actor.tenantId(), form.organizationId());
                    if (active == null) throw OrgException.notFound();
                    if (!active) throw OrgException.invalid("활성 조직에만 소속을 배정할 수 있습니다.");
                    List<Assignment> history = mapper.assignments(actor.tenantId(), employeeId);
                    Assignment previous = history.isEmpty() ? null : history.getFirst();
                    if (previous != null && (!start.isAfter(previous.validFrom())
                            || previous.validTo() != null && start.isBefore(previous.validTo()))) {
                        throw OrgException.invalid("마지막 소속 시작일보다 늦은 적용일을 입력해 주세요.");
                    }
                    if (previous != null && previous.validTo() == null) {
                        requireChanged(mapper.closeAssignment(actor.tenantId(), previous.id(), start,
                                previous.version(), actor.accountId()));
                    }
                    UUID assignmentId = UUID.randomUUID();
                    mapper.insertAssignment(actor.tenantId(), assignmentId, employeeId, form.organizationId(),
                            start, reason, actor.accountId());
                    requireChanged(mapper.advanceVersion(actor.tenantId(), employeeId, employee.version(), actor.accountId()));
                    Assignment after = mapper.assignments(actor.tenantId(), employeeId).getFirst();
                    Assignment closed = previous != null && previous.validTo() == null
                            ? new Assignment(previous.id(), employeeId, previous.organizationId(), previous.organizationName(),
                                    previous.validFrom(), start, previous.reason(), previous.version() + 1)
                            : previous;
                    return new Change(employeeId, new AssignmentState(employee.version(), previous, null),
                            new AssignmentState(employee.version() + 1, closed, after));
                });
    }

    private Employee requireEmployee(UUID tenantId, UUID id) {
        Employee employee = mapper.get(tenantId, id);
        if (employee == null) throw OrgException.notFound();
        return employee;
    }

    private EmployeeValues normalize(EmployeeForm form, boolean creating) {
        String number = creating && (form.employeeNo() == null || form.employeeNo().isBlank())
                ? null : requiredText(form.employeeNo(), 40, "사번");
        String name = requiredText(form.displayName(), 120, "이름");
        if (form.joinedOn() == null || form.leftOn() != null && form.leftOn().isBefore(form.joinedOn())) {
            throw OrgException.invalid("입사일을 입력하고 마지막 재직일은 입사일 이후로 지정해 주세요.");
        }
        return new EmployeeValues(number, name, form.joinedOn(), form.leftOn(), form.active());
    }

    private void requireChanged(int rows) {
        if (rows != 1) throw OrgException.conflict("다른 변경이 반영되었습니다. 최신 내용을 확인해 주세요.");
    }

    private record EmployeeValues(String employeeNo, String displayName, LocalDate joinedOn,
                                  LocalDate leftOn, boolean active) { }
    private record AssignmentState(long employeeVersion, Assignment previous, Assignment current) { }
}
