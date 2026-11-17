package kr.tna.org;

import static kr.tna.org.OrgCommands.fingerprint;
import static kr.tna.org.OrgCommands.requiredText;

import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import kr.tna.common.auth.TnaPrincipal;
import kr.tna.org.AccountAdminMapper.AccountRow;
import kr.tna.org.OrgData.Employee;
import kr.tna.org.RoleMembershipData.Form;
import kr.tna.org.RoleMembershipData.Latest;
import kr.tna.org.RoleMembershipData.Operation;
import kr.tna.org.RoleMembershipData.Outcome;
import kr.tna.org.RoleMembershipData.Rejected;
import kr.tna.org.RoleMembershipData.Row;
import kr.tna.org.RoleMembershipData.RowError;
import kr.tna.org.RoleMembershipData.RowResult;
import org.springframework.stereotype.Service;

@Service
public final class RoleMembershipService {
    private static final String ACTION = "ACCOUNT_ROLE_MEMBERSHIP";
    private final OrgCommands commands;
    private final EmployeeAdminMapper employees;
    private final AccountAdminMapper accounts;

    public RoleMembershipService(OrgCommands commands, EmployeeAdminMapper employees, AccountAdminMapper accounts) {
        this.commands = commands;
        this.employees = employees;
        this.accounts = accounts;
    }

    Outcome change(TnaPrincipal actor, String role, Form form) {
        if (actor == null) throw OrgException.forbidden();
        if (!AccountService.SUPPORTED_ROLES.contains(role) || "EMPLOYEE".equals(role)) {
            throw OrgException.invalid("부여하거나 해제할 수 있는 권한을 선택해 주세요.");
        }
        String reason = requiredText(form.reason(), 500, "변경 사유");
        List<Row> rows = checkedRows(form.rows());
        var applied = commands.executeBatch(actor, form.commandId(), ACTION, "EMPLOYEE",
                fingerprint(ACTION, role, reason, rows), reason, () -> apply(actor, role, rows));
        var results = applied.rows().stream()
                .map(row -> new RowResult(row.clientRowId(), row.id(), row.version())).toList();
        return new Outcome(results.stream().anyMatch(row -> row.employeeId().equals(actor.employeeId())), results);
    }

    private List<BulkData.Change> apply(TnaPrincipal actor, String role, List<Row> rows) {
        UUID tenantId = actor.tenantId();
        LocalDate today = commands.today();
        List<Target> targets = rows.stream().map(row -> target(tenantId, row, today)).toList();
        var conflicts = targets.stream().filter(target -> target.row().expectedVersion() != target.employee().version()
                || target.roles().contains(role) == target.granting())
                .map(target -> new RowError(target.row().clientRowId(),
                        "사원 버전 또는 권한 보유 상태가 바뀌었습니다.")).toList();
        if (!conflicts.isEmpty()) {
            var latest = targets.stream().map(target -> new Latest(target.row().clientRowId(), target.employee().id(),
                    target.employee().version(), target.roles().contains(role), target.available())).toList();
            throw new Rejected(409, "최신 권한 상태를 확인해 주세요.", conflicts, latest);
        }
        var blocked = targets.stream().filter(target -> !target.available())
                .map(target -> new RowError(target.row().clientRowId(),
                        "초대를 수락한 활성 계정의 재직 사원만 권한을 변경할 수 있습니다.")).toList();
        if (!blocked.isEmpty()) {
            throw new Rejected(422, "권한을 변경할 수 없는 사원이 있습니다.", blocked, List.of());
        }
        var changes = targets.stream().map(target -> update(actor, role, target)).toList();
        if ("ATTENDANCE_ADMIN".equals(role) && targets.stream().anyMatch(target -> !target.granting())
                && !employees.hasPermanentAdmin(tenantId, today)) {
            String message = "퇴직 예정일이 없는 활성 담당자를 한 명 이상 남겨 주세요.";
            var revocations = targets.stream().filter(target -> !target.granting())
                    .map(target -> new RowError(target.row().clientRowId(), message)).toList();
            throw new Rejected(422, message, revocations, List.of());
        }
        return changes;
    }

    private Target target(UUID tenantId, Row row, LocalDate today) {
        Employee employee = employees.get(tenantId, row.employeeId());
        if (employee == null) throw OrgException.notFound();
        AccountRow account = accounts.account(tenantId, employee.id());
        boolean available = employee.active() && !employee.joinedOn().isAfter(today)
                && (employee.leftOn() == null || !employee.leftOn().isBefore(today))
                && account != null && account.active() && account.credentialSet();
        Set<String> roles = account == null ? Set.of() : new TreeSet<>(accounts.roles(tenantId, account.id()));
        return new Target(row, employee, account, roles, available);
    }

    private BulkData.Change update(TnaPrincipal actor, String role, Target target) {
        UUID tenantId = actor.tenantId();
        UUID employeeId = target.employee().id();
        UUID accountId = target.account().id();
        long version = target.employee().version();
        changed(accounts.setRole(tenantId, accountId, role, target.granting()));
        changed(accounts.revokeSessions(tenantId, accountId));
        changed(employees.advanceVersion(tenantId, employeeId, version, actor.accountId()));
        var rolesAfter = new TreeSet<>(target.roles());
        if (target.granting()) rolesAfter.add(role);
        else rolesAfter.remove(role);
        return new BulkData.Change(target.row().clientRowId().toString(), employeeId, version + 1,
                new AuditState(role, version, target.roles()), new AuditState(role, version + 1, rolesAfter));
    }

    private static List<Row> checkedRows(List<Row> rows) {
        if (rows == null || rows.isEmpty() || rows.size() > 50) {
            throw OrgException.invalid("변경할 사원을 1~50명 선택해 주세요.");
        }
        var employeeIds = new HashSet<UUID>();
        var clientIds = new HashSet<UUID>();
        for (Row row : rows) {
            if (row == null || row.employeeId() == null || row.clientRowId() == null
                    || row.operation() == null || row.expectedVersion() == null || row.expectedVersion() < 0) {
                throw OrgException.invalid("변경할 사원과 버전, 동작을 확인해 주세요.");
            }
            if (!employeeIds.add(row.employeeId()) || !clientIds.add(row.clientRowId())) {
                throw new Rejected(422, "같은 사원을 중복으로 변경할 수 없습니다.",
                        List.of(new RowError(row.clientRowId(), "중복된 사원입니다.")), List.of());
            }
        }
        return List.copyOf(rows);
    }

    private static void changed(int count) {
        if (count != 1) throw OrgException.conflict("다른 변경이 반영되었습니다. 최신 내용을 확인해 주세요.");
    }

    private record AuditState(String role, long version, Set<String> roles) { }

    private record Target(Row row, Employee employee, AccountRow account, Set<String> roles, boolean available) {
        boolean granting() { return row.operation() == Operation.GRANT; }
    }
}
