package kr.tna.org;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import kr.tna.common.auth.InvitationDelivery;
import kr.tna.common.auth.TnaPrincipal;
import kr.tna.org.AccountData.Account;
import kr.tna.org.AccountData.InvitationForm;
import kr.tna.org.AccountData.RolesForm;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import static kr.tna.org.OrgCommands.*;

@Service
public final class AccountService {
    static final Set<String> SUPPORTED_ROLES = Set.of("EMPLOYEE", "APPROVER", "ATTENDANCE_ADMIN", "COMPANY_REPRESENTATIVE");
    private static final SecureRandom RANDOM = new SecureRandom();
    private final AccountAdminMapper mapper;
    private final EmployeeAdminMapper employees;
    private final OrgCommands commands;
    private final InvitationDelivery delivery;
    private final Clock clock;
    private final PublicDemoIdentityGuard identityGuard;

    public AccountService(AccountAdminMapper mapper, EmployeeAdminMapper employees, OrgCommands commands,
                          InvitationDelivery delivery, Clock clock, PublicDemoIdentityGuard identityGuard) {
        this.mapper = mapper;
        this.employees = employees;
        this.commands = commands;
        this.delivery = delivery;
        this.clock = clock;
        this.identityGuard = identityGuard;
    }

    public Account get(TnaPrincipal actor, UUID employeeId) {
        return commands.read(actor, () -> {
            requireEmployee(actor.tenantId(), employeeId, false);
            return account(actor.tenantId(), employeeId);
        });
    }

    public UUID invite(TnaPrincipal actor, UUID employeeId, InvitationForm form) {
        identityGuard.requireMutable(actor, employeeId);
        String email = email(form.email());
        String reason = requiredText(form.reason(), 500, "초대 사유");
        String action = "ACCOUNT_INVITE";
        return commands.execute(actor, form.commandId(), action, "EMPLOYEE",
                fingerprint(action, employeeId, email, form.expectedVersion(), reason), reason, () -> {
                    var employee = requireEmployee(actor.tenantId(), employeeId, true);
                    checkVersion(form.expectedVersion(), employee.version());
                    if (!delivery.enabled()) {
                        throw new OrgException(503, "초대 전달 경로가 준비되지 않았습니다.");
                    }
                    Account before = account(actor.tenantId(), employeeId);
                    if (before != null && (before.active() || before.credentialSet())) {
                        throw OrgException.invalid("이미 비밀번호가 설정된 계정은 초대할 수 없습니다.");
                    }
                    if (before != null && !before.email().equals(email)) {
                        throw OrgException.invalid("초대 대기 계정의 이메일은 변경할 수 없습니다.");
                    }
                    UUID accountId = before == null ? UUID.randomUUID() : before.id();
                    long authVersion = before == null ? 1 : before.authVersion() + 1;
                    if (before == null) {
                        mapper.insertAccount(actor.tenantId(), accountId, employeeId, email);
                        mapper.setRole(actor.tenantId(), accountId, "EMPLOYEE", true);
                    } else {
                        changed(mapper.revokeSessions(actor.tenantId(), accountId));
                    }
                    var now = clock.instant();
                    var expiresAt = now.plus(Duration.ofHours(24)).truncatedTo(ChronoUnit.MILLIS);
                    mapper.revokeInvitations(actor.tenantId(), accountId, now);
                    byte[] random = new byte[32];
                    RANDOM.nextBytes(random);
                    String token = Base64.getUrlEncoder().withoutPadding().encodeToString(random);
                    mapper.insertInvitation(actor.tenantId(), UUID.randomUUID(), accountId, tokenHash(token),
                            email, authVersion, expiresAt, actor.accountId());
                    changed(employees.advanceVersion(actor.tenantId(), employeeId, employee.version(), actor.accountId()));
                    var envelope = new InvitationDelivery.Envelope(mapper.companyCode(actor.tenantId()), email, token, expiresAt);
                    TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                        @Override public void afterCommit() { delivery.deliver(envelope); }
                    });
                    return new Change(employeeId, before, account(actor.tenantId(), employeeId));
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
                    if (before == null || !before.active() || !before.credentialSet()) {
                        throw OrgException.invalid("초대를 수락한 활성 계정의 역할만 변경할 수 있습니다.");
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
                row.credentialSet(), row.authVersion(), Set.copyOf(mapper.roles(tenantId, row.id())));
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

    private String email(String value) {
        String normalized = requiredText(value, 254, "이메일").toLowerCase(Locale.ROOT);
        if (!InvitationDelivery.isSingleMailbox(normalized)) {
            throw OrgException.invalid("공백 없이 올바른 이메일을 입력해 주세요.");
        }
        return normalized;
    }

    private void changed(int count) {
        if (count != 1) throw OrgException.conflict("다른 변경이 반영되었습니다. 최신 내용을 확인해 주세요.");
    }

    private String tokenHash(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.US_ASCII)));
        } catch (NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); }
    }
}
