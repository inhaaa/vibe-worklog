package kr.tna.org;

import java.time.Clock;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import kr.tna.common.auth.TnaPrincipal;
import org.springframework.stereotype.Service;

@Service
public class AccountDirectoryService {
    static final List<String> STATES = List.of("all", "NONE", "PENDING", "BIRTH_DATE_REQUIRED", "LOCKED", "EXPIRED", "ACTIVE", "PREVIOUS_INVITATION");

    public record Item(UUID employeeId, String employeeNo, String displayName, String organizationName,
                       String state, String label, boolean self) { }
    public record Option(String value, String label) { }
    public record Directory(List<Item> items, String q, String status, int page, boolean hasNext, List<Option> statuses) { }

    private final OrgCommands commands;
    private final AccountDirectoryMapper mapper;
    private final Clock clock;
    private final AccountService accountService;
    private final AccountAdminMapper accountAdmin;
    private final AccountProvisioning provisioning;
    private final PublicDemoIdentityGuard identityGuard;

    public AccountDirectoryService(OrgCommands commands, AccountDirectoryMapper mapper, Clock clock,
                                   AccountService accountService, AccountAdminMapper accountAdmin,
                                   AccountProvisioning provisioning, PublicDemoIdentityGuard identityGuard) {
        this.commands = commands;
        this.mapper = mapper;
        this.clock = clock;
        this.accountService = accountService;
        this.accountAdmin = accountAdmin;
        this.provisioning = provisioning;
        this.identityGuard = identityGuard;
    }

    public record BulkForm(UUID commandId, String reason, List<UUID> employeeIds) { }
    public record BulkResult(int count) { }
    public record EmployeeError(UUID employeeId, String message) { }

    public static final class BulkProblem extends OrgException {
        private final List<EmployeeError> errors;
        BulkProblem(List<EmployeeError> errors) {
            super(422, "선택한 사원 중 처리할 수 없는 사원이 있습니다.");
            this.errors = errors;
        }
        public List<EmployeeError> errors() { return errors; }
    }

    public BulkResult create(TnaPrincipal actor, BulkForm form) { return bulk(actor, form, true); }

    public BulkResult reset(TnaPrincipal actor, BulkForm form) { return bulk(actor, form, false); }

    private BulkResult bulk(TnaPrincipal actor, BulkForm form, boolean create) {
        if (identityGuard.publicDemo()) throw new OrgException(403, "공개 데모에서는 계정을 바꿀 수 없습니다.");
        if (form == null || form.commandId() == null) throw OrgException.invalid("명령 식별자가 필요합니다.");
        String reason = OrgCommands.requiredText(form.reason(), 500, create ? "생성 사유" : "초기화 사유");
        var ids = form.employeeIds() == null ? List.<UUID>of() : form.employeeIds();
        if (ids.isEmpty() || ids.size() > 100 || ids.contains(null) || Set.copyOf(ids).size() != ids.size()) {
            throw OrgException.invalid("1~100명을 중복 없이 선택해 주세요.");
        }
        for (UUID id : ids) identityGuard.requireMutable(actor, id);
        var sorted = ids.stream().sorted().toList();
        String action = create ? "ACCOUNT_BULK_PROVISIONED" : "ACCOUNT_BULK_RESET";
        UUID batch = UUID.randomUUID();
        commands.execute(actor, form.commandId(), action, "EMPLOYEE_BATCH",
                OrgCommands.fingerprint(action, reason, sorted.toString()), reason, () -> {
            UUID tenant = actor.tenantId();
            var errors = new java.util.ArrayList<EmployeeError>();
            var work = new java.util.ArrayList<Runnable>();
            for (UUID id : sorted) {
                try {
                    var employee = accountService.requireEmployee(tenant, id, true);
                    var account = accountAdmin.account(tenant, id);
                    if (create && account != null) errors.add(new EmployeeError(id, "이미 계정이 있습니다."));
                    else if (!create && account == null) errors.add(new EmployeeError(id, "계정이 없습니다. 계정 생성을 사용하세요."));
                    else if (!create && id.equals(actor.employeeId())) errors.add(new EmployeeError(id, "본인 계정은 초기화할 수 없습니다."));
                    else if (create) work.add(() -> provisioning.provision(tenant, id, employee.joinedOn()));
                    else work.add(() -> provisioning.reopen(tenant, account.id()));
                } catch (OrgException error) {
                    errors.add(new EmployeeError(id, error.getMessage()));
                }
            }
            if (!errors.isEmpty()) throw new BulkProblem(List.copyOf(errors));
            work.forEach(Runnable::run);
            return new OrgCommands.Change(batch, java.util.Map.of("employeeIds", sorted, "count", 0),
                    java.util.Map.of("employeeIds", sorted, "count", sorted.size()));
        });
        return new BulkResult(sorted.size());
    }

    public Directory directory(TnaPrincipal actor, String q, String status, int page) {
        String query = q == null ? "" : q.strip();
        if (query.length() > 80) throw OrgException.invalid("검색어는 80자 이하로 입력해 주세요.");
        if (!STATES.contains(status)) throw new OrgException(400, "상태 값을 확인해 주세요.");
        int offset = OrgCommands.offset(page);
        String pattern = "%" + query.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
        return commands.readShared(actor, () -> {
            var rows = mapper.list(actor.tenantId(), pattern, status, commands.today(), clock.instant(), offset);
            var items = rows.stream().limit(50).map(row -> new Item(row.employeeId(), row.employeeNo(), row.displayName(),
                    row.organizationName(), row.state(), AccountStatus.valueOf(row.state()).label(),
                    row.employeeId().equals(actor.employeeId()))).toList();
            var statuses = STATES.stream().map(value -> new Option(value,
                    value.equals("all") ? "전체" : AccountStatus.valueOf(value).label())).toList();
            return new Directory(items, query, status, page, rows.size() > 50, statuses);
        });
    }
}
