package kr.tna.org;

import static kr.tna.org.OrgCommands.*;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import kr.tna.common.auth.TnaPrincipal;
import kr.tna.org.AccountData.ApproverAssignment;
import kr.tna.org.AccountData.ApproverForm;
import kr.tna.org.OrgCommands.Change;
import kr.tna.org.OrgData.Employee;
import org.springframework.stereotype.Service;

@Service
public final class ApproverService {
    private final ApproverMapper mapper;
    private final EmployeeAdminMapper employees;
    private final OrgCommands commands;

    public ApproverService(ApproverMapper mapper, EmployeeAdminMapper employees, OrgCommands commands) {
        this.mapper = mapper;
        this.employees = employees;
        this.commands = commands;
    }

    public List<ApproverAssignment> history(TnaPrincipal actor, UUID employeeId) {
        return commands.read(actor, () -> {
            requireEmployee(actor.tenantId(), employeeId);
            return mapper.history(actor.tenantId(), employeeId, commands.today());
        });
    }

    public List<Employee> candidates(TnaPrincipal actor) {
        return commands.read(actor, () -> mapper.candidates(actor.tenantId(), commands.today()));
    }

    public UUID set(TnaPrincipal actor, ApproverForm form) {
        if (form.employeeId() == null || form.validFrom() == null) {
            throw OrgException.invalid("대상 사원과 승인자 적용일을 입력해 주세요.");
        }
        String reason = requiredText(form.reason(), 500, "변경 사유");
        String action = "APPROVER_ASSIGN";
        return commands.execute(actor, form.commandId(), action, "EMPLOYEE",
                fingerprint(action, form.employeeId(), form.approverEmployeeId(), form.validFrom(),
                        form.expectedVersion(), reason), reason, () -> {
                    UUID tenant = actor.tenantId();
                    Employee employee = requireEmployee(tenant, form.employeeId());
                    checkVersion(form.expectedVersion(), employee.version());
                    LocalDate today = commands.today();
                    if (!today.equals(form.validFrom())) {
                        throw OrgException.invalid("승인자 적용일은 서울 기준 오늘만 지정할 수 있습니다.");
                    }
                    if (!employee.active() || employee.joinedOn().isAfter(today)
                            || employee.leftOn() != null && employee.leftOn().isBefore(today)) {
                        throw OrgException.invalid("현재 재직 중인 활성 사원의 승인자만 변경할 수 있습니다.");
                    }
                    if (form.approverEmployeeId() != null) {
                        if (employee.id().equals(form.approverEmployeeId())) {
                            throw OrgException.invalid("자기 자신을 승인자로 지정할 수 없습니다.");
                        }
                        requireEmployee(tenant, form.approverEmployeeId());
                        if (!mapper.eligible(tenant, form.approverEmployeeId(), today)) {
                            throw OrgException.invalid("수락 완료한 활성 재직 계정의 승인자 역할이 필요합니다.");
                        }
                    }
                    ApproverAssignment previous = mapper.history(tenant, employee.id(), today).stream()
                            .filter(row -> row.supersededAt() == null && !row.validFrom().isAfter(today)
                                    && (row.validTo() == null || row.validTo().isAfter(today)))
                            .findFirst().orElse(null);
                    if (previous != null) {
                        requireChanged(mapper.close(tenant, previous.id(), today, previous.version(), actor.accountId()));
                    }
                    UUID assignmentId = null;
                    if (form.approverEmployeeId() != null) {
                        assignmentId = UUID.randomUUID();
                        requireChanged(mapper.insert(tenant, assignmentId, employee.id(), form.approverEmployeeId(),
                                today, reason, actor.accountId()));
                    }
                    requireChanged(employees.advanceVersion(tenant, employee.id(), employee.version(), actor.accountId()));
                    return new Change(employee.id(), new AssignmentState(employee.version(),
                            previous == null ? null : previous.id(), previous == null ? null : previous.approverEmployeeId()),
                            new AssignmentState(employee.version() + 1, assignmentId, form.approverEmployeeId()));
                });
    }

    private Employee requireEmployee(UUID tenant, UUID id) {
        Employee employee = employees.get(tenant, id);
        if (employee == null) throw OrgException.notFound();
        return employee;
    }

    private void requireChanged(int rows) {
        if (rows != 1) throw OrgException.conflict("다른 변경이 반영되었습니다. 최신 내용을 확인해 주세요.");
    }

    private record AssignmentState(long employeeVersion, UUID assignmentId, UUID approverEmployeeId) { }
}
