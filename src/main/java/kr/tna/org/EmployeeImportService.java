package kr.tna.org;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import kr.tna.common.auth.TnaPrincipal;
import kr.tna.org.EmployeeImportRows.FieldError;
import kr.tna.org.EmployeeImportRows.Parsed;
import kr.tna.org.EmployeeImportRows.Row;
import org.springframework.stereotype.Service;

@Service
public class EmployeeImportService {
    public record PageModel(List<EmployeeImportMapper.Organization> organizations, int maxRows) { }
    public record RowResult(String clientRowId, String employeeNo, List<FieldError> errors) { }
    public record Preview(boolean valid, List<RowResult> rows) { }
    record Checked(Parsed parsed, String employeeNo, UUID organizationId, List<FieldError> errors) { }

    private final OrgCommands commands;
    private final EmployeeAdminMapper employees;
    private final EmployeeImportMapper mapper;
    private final EmployeePersonnelMapper personnel;
    private final AccountProvisioning provisioning;
    private final kr.tna.common.auth.LocalDemoLogin localDemo;

    public EmployeeImportService(OrgCommands commands, EmployeeAdminMapper employees, EmployeeImportMapper mapper,
            EmployeePersonnelMapper personnel, AccountProvisioning provisioning, kr.tna.common.auth.LocalDemoLogin localDemo) {
        this.commands = commands;
        this.employees = employees;
        this.mapper = mapper;
        this.personnel = personnel;
        this.provisioning = provisioning;
        this.localDemo = localDemo;
    }

    public PageModel page(TnaPrincipal actor) {
        return commands.read(actor, () -> new PageModel(mapper.activeOrganizations(actor.tenantId()), EmployeeImportRows.MAX_ROWS));
    }

    public Preview preview(TnaPrincipal actor, List<Row> rows) {
        List<Row> input = rows == null ? List.of() : rows;
        EmployeeImportRows.checkCount(input.size());
        return commands.readShared(actor, () -> summarize(check(actor.tenantId(), input)));
    }

    List<Checked> check(UUID tenant, List<Row> rows) {
        LocalDate today = commands.today();
        var organizations = new HashMap<String, UUID>();
        for (var organization : mapper.activeOrganizations(tenant)) organizations.put(organization.code(), organization.id());
        var existing = new HashSet<>(employees.employeeNumbers(tenant));
        var parsed = rows.stream().map(row -> EmployeeImportRows.check(row, today)).toList();
        var manual = new HashMap<String, Integer>();
        for (var row : parsed) if (row.employeeNo() != null) manual.merge(row.employeeNo(), 1, Integer::sum);
        var issued = new HashSet<>(existing);
        issued.addAll(manual.keySet());
        var clientIds = new HashSet<String>();
        var checked = new ArrayList<Checked>();
        for (var row : parsed) {
            var errors = new ArrayList<>(row.errors());
            if (row.clientRowId() == null || row.clientRowId().isBlank() || !clientIds.add(row.clientRowId())) {
                errors.add(new FieldError("clientRowId", "행 식별자가 없거나 겹칩니다."));
            }
            String number = row.employeeNo();
            if (number != null && existing.contains(number)) {
                errors.add(new FieldError("employeeNo", "이미 등록된 사번입니다."));
            } else if (number != null && manual.get(number) > 1) {
                errors.add(new FieldError("employeeNo", "붙여넣은 행 안에서 사번이 겹칩니다."));
            } else if (number == null && row.joinedOn() != null) {
                try {
                    number = EmployeeNumbers.next(row.joinedOn(), issued);
                    issued.add(number);
                } catch (OrgException error) {
                    errors.add(new FieldError("employeeNo", error.getMessage()));
                }
            }
            UUID organization = row.organizationCode() == null ? null : organizations.get(row.organizationCode());
            if (row.organizationCode() != null && organization == null) {
                errors.add(new FieldError("organizationCode", "활성 조직 코드가 아닙니다."));
            }
            if (row.joinedOn() != null && errors.stream().noneMatch(error -> error.field().equals("joinedOn"))) {
                try {
                    employees.requireOpen(tenant, row.joinedOn(), null);
                } catch (OrgException error) {
                    errors.add(new FieldError("joinedOn", error.getMessage()));
                }
            }
            checked.add(new Checked(row, number, organization, List.copyOf(errors)));
        }
        return checked;
    }

    static Preview summarize(List<Checked> checked) {
        var rows = checked.stream().map(c -> new RowResult(c.parsed().clientRowId(), c.employeeNo(), c.errors())).toList();
        return new Preview(rows.stream().allMatch(row -> row.errors().isEmpty()), rows);
    }

    static final String ACTION = "EMPLOYEE_IMPORT";
    private static final tools.jackson.databind.json.JsonMapper JSON = tools.jackson.databind.json.JsonMapper.builder().build();

    public record Form(UUID commandId, String reason, List<Row> rows) { }
    public record Result(UUID batchId, int employees, int accounts, List<String> employeeNos) { }

    public static final class ImportProblem extends OrgException {
        private final Preview preview;
        ImportProblem(Preview preview) {
            super(422, "행별 오류를 고친 뒤 다시 저장해 주세요.");
            this.preview = preview;
        }
        public Preview preview() { return preview; }
    }

    public Result save(TnaPrincipal actor, Form form) {
        if (localDemo.publicDemo()) throw new OrgException(403, "공개 데모에서는 저장할 수 없습니다.");
        if (form == null || form.commandId() == null) throw OrgException.invalid("명령 식별자가 필요합니다.");
        String reason = OrgCommands.requiredText(form.reason(), 500, "등록 사유");
        List<Row> rows = form.rows() == null ? List.of() : form.rows();
        EmployeeImportRows.checkCount(rows.size());
        // 생년월일은 날짜 SHA-256이 쉽게 역산되므로 입력 해시에서 뺀다(ADR 0008).
        String hash = OrgCommands.fingerprint(ACTION, reason, rows.stream().map(row -> String.join("\u001f",
                String.valueOf(row.clientRowId()), String.valueOf(row.employeeNo()), String.valueOf(row.displayName()),
                String.valueOf(row.joinedOn()), String.valueOf(row.organizationCode()), String.valueOf(row.gradeName()),
                String.valueOf(row.positionName()))).toList().toString());
        UUID batch = UUID.randomUUID();
        commands.execute(actor, form.commandId(), ACTION, "EMPLOYEE_BATCH", hash, reason, () -> {
            UUID tenant = actor.tenantId();
            var checked = check(tenant, rows);
            var preview = summarize(checked);
            if (!preview.valid()) throw new ImportProblem(preview);
            LocalDate today = commands.today();
            var numbers = new ArrayList<String>();
            for (var row : checked) {
                var values = row.parsed();
                var employee = new OrgData.Employee(UUID.randomUUID(), row.employeeNo(), values.displayName(),
                        values.joinedOn(), null, true, 0);
                employees.insert(tenant, employee, actor.accountId());
                employees.insertAssignment(tenant, UUID.randomUUID(), employee.id(), row.organizationId(), today, reason, actor.accountId());
                personnel.save(tenant, employee.id(), new EmployeePersonnelData.Stored(values.birthDate(), values.gradeName(),
                        values.positionName(), null, null, null, 1), 0, actor.accountId());
                provisioning.provision(tenant, employee.id(), values.joinedOn());
                numbers.add(row.employeeNo());
            }
            return new OrgCommands.Change(batch, java.util.Map.of("employees", 0),
                    new Result(batch, numbers.size(), numbers.size(), List.copyOf(numbers)));
        });
        return commands.read(actor, () -> JSON.readValue(mapper.result(actor.tenantId(), form.commandId()), Result.class));
    }
}
