package kr.tna.org;

import java.time.Clock;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import kr.tna.common.auth.TnaPrincipal;
import kr.tna.org.AccountData.Account;
import kr.tna.org.AccountData.ResetForm;
import kr.tna.org.AccountData.RolesForm;
import org.springframework.stereotype.Service;

import static kr.tna.org.OrgCommands.*;

@Service
public final class AccountService {
    static final Set<String> SUPPORTED_ROLES = Set.of("EMPLOYEE", "APPROVER", "ATTENDANCE_ADMIN", "COMPANY_REPRESENTATIVE");
    private final AccountAdminMapper mapper;
    private final EmployeeAdminMapper employees;
    private final OrgCommands commands;
    private final Clock clock;
    private final PublicDemoIdentityGuard identityGuard;
    private final AccountProvisioning provisioning;
    private final EmployeePersonnelMapper personnel;

    public AccountService(AccountAdminMapper mapper, EmployeeAdminMapper employees, OrgCommands commands,
                          Clock clock, PublicDemoIdentityGuard identityGuard, AccountProvisioning provisioning,
                          EmployeePersonnelMapper personnel) {
        this.mapper = mapper;
        this.employees = employees;
        this.commands = commands;
        this.clock = clock;
        this.identityGuard = identityGuard;
        this.provisioning = provisioning;
        this.personnel = personnel;
    }

    public Account get(TnaPrincipal actor, UUID employeeId) {
        return commands.read(actor, () -> {
            requireEmployee(actor.tenantId(), employeeId, false);
            return account(actor.tenantId(), employeeId);
        });
    }

    public UUID reset(TnaPrincipal actor, UUID employeeId, ResetForm form) {
        identityGuard.requireMutable(actor, employeeId);
        if (employeeId.equals(actor.employeeId())) {
            throw OrgException.invalid("본인 계정은 초기화할 수 없습니다. 다른 담당자에게 요청해 주세요.");
        }
        String reason = requiredText(form.reason(), 500, "초기화 사유");
        String action = "ACCOUNT_ACTIVATION_RESET";
        return commands.execute(actor, form.commandId(), action, "EMPLOYEE",
                fingerprint(action, employeeId, form.expectedVersion(), reason), reason, () -> {
                    var employee = requireEmployee(actor.tenantId(), employeeId, true);
                    checkVersion(form.expectedVersion(), employee.version());
                    Account before = account(actor.tenantId(), employeeId);
                    if (before == null) throw OrgException.invalid("계정이 없습니다. 입사 확정 시 계정이 만들어집니다.");
                    provisioning.reopen(actor.tenantId(), before.id());
                    changed(employees.advanceVersion(actor.tenantId(), employeeId, employee.version(), actor.accountId()));
                    return new Change(employeeId, Map.of("pending", before.activationExpiresAt() != null),
                            Map.of("pending", true));
                });
    }

    /** 계정 관리 화면에 보이는 상태 이름. 생년월일 값은 읽지 않고 입력 여부만 쓴다. */
    public AccountStatus status(TnaPrincipal actor, UUID employeeId) {
        return commands.read(actor, () -> {
            requireEmployee(actor.tenantId(), employeeId, false);
            var account = account(actor.tenantId(), employeeId);
            return AccountStatus.of(account != null, account != null && account.active(),
                    account == null ? null : account.activationExpiresAt(),
                    account == null ? 0 : account.activationFailures(),
                    personnel.hasBirthDate(actor.tenantId(), employeeId), clock.instant());
        });
    }

    public UUID roles(TnaPrincipal actor, UUID employeeId, RolesForm form) {
        identityGuard.requireMutable(actor, employeeId);
        if (form.roles() == null || !form.roles().contains("EMPLOYEE")
                || form.roles().stream().anyMatch(role -> !SUPPORTED_ROLES.contains(role))) {
            throw OrgException.invalid("직원 역할을 유지하고 지원하는 역할만 선택해 주세요.");
        }
        Set<String> selected = Set.copyOf(form.roles());
        String reason = requiredText(form.reason(), 500, "역할 변경 사유");
        String action = "ACCOUNT_ROLES_CHANGE";
        return commands.execute(actor, form.commandId(), action, "EMPLOYEE",
                fingerprint(action, employeeId, new TreeSet<>(selected), form.expectedVersion(), reason), reason, () -> {
                    var employee = requireEmployee(actor.tenantId(), employeeId, true);
                    checkVersion(form.expectedVersion(), employee.version());
                    Account before = account(actor.tenantId(), employeeId);
                    if (before == null || !(before.active() && before.credentialSet()) && before.activationExpiresAt() == null) {
                        throw OrgException.invalid("계정이 있는 재직 사원의 역할만 변경할 수 있습니다.");
                    }
                    if (before.roles().contains("ATTENDANCE_ADMIN") && !selected.contains("ATTENDANCE_ADMIN")
                            && employees.isEffectiveAdmin(actor.tenantId(), employeeId, commands.today())
                            && !employees.hasOtherPermanentAdmin(actor.tenantId(), employeeId, commands.today())) {
                        throw OrgException.invalid("다른 재직 중인 활성 담당자를 지정한 뒤 역할을 회수해 주세요.");
                    }
                    if (!before.roles().equals(selected)) {
                        for (String role : SUPPORTED_ROLES) mapper.setRole(actor.tenantId(), before.id(), role, selected.contains(role));
                        changed(mapper.revokeSessions(actor.tenantId(), before.id()));
                    }
                    changed(employees.advanceVersion(actor.tenantId(), employeeId, employee.version(), actor.accountId()));
                    return new Change(employeeId, before, account(actor.tenantId(), employeeId));
                });
    }

    private Account account(UUID tenantId, UUID employeeId) {
        var row = mapper.account(tenantId, employeeId);
        return row == null ? null : new Account(row.id(), row.employeeId(), row.email(), row.active(),
                row.credentialSet(), row.authVersion(), Set.copyOf(mapper.roles(tenantId, row.id())),
                row.activationExpiresAt(), row.activationFailures());
    }

    private OrgData.Employee requireEmployee(UUID tenantId, UUID employeeId, boolean current) {
        var employee = employees.get(tenantId, employeeId);
        if (employee == null) throw OrgException.notFound();
        var today = commands.today();
        if (current && (!employee.active() || employee.joinedOn().isAfter(today)
                || employee.leftOn() != null && employee.leftOn().isBefore(today))) {
            throw OrgException.invalid("현재 재직 중인 활성 사원만 관리할 수 있습니다.");
        }
        return employee;
    }

    private void changed(int count) {
        if (count != 1) throw OrgException.conflict("다른 변경이 반영되었습니다. 최신 내용을 확인해 주세요.");
    }
}
