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

    public EmployeeImportService(OrgCommands commands, EmployeeAdminMapper employees, EmployeeImportMapper mapper) {
        this.commands = commands;
        this.employees = employees;
        this.mapper = mapper;
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
}
