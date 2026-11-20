package kr.tna.request;

import static kr.tna.request.LeaveBalanceEntries.invalid;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import kr.tna.common.auth.TnaPrincipal;
import kr.tna.org.BulkData;
import kr.tna.org.EmployeeAdminMapper;
import kr.tna.org.OrgCommands;
import kr.tna.org.OrgData.Employee;
import kr.tna.org.OrgException;
import kr.tna.request.LeaveBalanceEntries.FieldProblem;
import kr.tna.request.RequestData.AdjustmentForm;
import kr.tna.request.RequestData.Bucket;
import kr.tna.request.RequestData.BucketForm;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

@Service
public final class LeaveBalanceBatchService {
    private static final String ACTION = "LEAVE_BALANCE_BULK";
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final OrgCommands commands;
    private final EmployeeAdminMapper employees;
    private final LeaveWorkflowMapper buckets;
    private final LeaveWorkflowService workflow;

    public LeaveBalanceBatchService(OrgCommands commands, EmployeeAdminMapper employees, LeaveWorkflowMapper buckets, LeaveWorkflowService workflow) {
        this.commands = commands;
        this.employees = employees;
        this.buckets = buckets;
        this.workflow = workflow;
    }

    public record Form(UUID commandId, List<Row> rows) { }
    // Keep the original text of cell values so each invalid cell can be reported against its own row.
    public record Row(String clientRowId, String operation, String employeeNo, String effectiveOn, String quantityDays,
            String sourceReference, String reason, String usableFrom, String usableTo, String bucketId, String expectedVersion) { }
    public record FieldError(String field, String message) { }
    public record RowPreview(String clientRowId, String employeeName, BigDecimal beforeDays, BigDecimal afterDays,
            Long expectedVersion, List<FieldError> errors) { }
    public record Preview(boolean valid, List<RowPreview> rows) { }
    private record Checked(RowPreview preview, BucketForm opening, AdjustmentForm adjustment, Bucket before, int status) { }
    private record OpeningKey(UUID employee, LocalDate baseDate, LocalDate from, LocalDate to, String source) { }

    public List<Bucket> options(TnaPrincipal actor, String employeeNo) {
        return commands.readShared(actor, () -> {
            Employee employee = employees.byNumber(actor.tenantId(), employeeNo.strip());
            if (employee == null) throw OrgException.notFound();
            if (actor.employeeId().equals(employee.id())) throw OrgException.forbidden();
            return buckets.listBuckets(actor.tenantId(), employee.id(), commands.today());
        });
    }

    public Preview preview(TnaPrincipal actor, Form form) {
        return commands.readShared(actor, () -> summarize(check(actor, rows(form), false)));
    }

    public BulkData.Result save(TnaPrincipal actor, Form form) {
        var rows = rows(form);
        String hash = OrgCommands.fingerprint(ACTION, JSON.writeValueAsString(rows));
        return commands.executeBatch(actor, form.commandId(), ACTION, "LEAVE_BUCKET", hash, "사원별 연차 기초·조정 일괄 등록", () -> {
            var checked = check(actor, rows, true);
            var preview = summarize(checked);
            if (!preview.valid()) {
                boolean conflict = checked.stream().anyMatch(row -> row.status() == 409);
                throw new BatchProblem(conflict ? 409 : 422, preview);
            }
            var changes = new ArrayList<BulkData.Change>(checked.size());
            for (var row : checked) {
                var after = row.opening() != null ? workflow.appendOpening(actor, row.opening())
                        : workflow.appendAdjustment(actor, row.before(), row.adjustment());
                changes.add(new BulkData.Change(row.preview().clientRowId(), after.id(), after.version(), row.before(), after));
            }
            return changes;
        });
    }

    static final class BatchProblem extends OrgException {
        final Preview preview;
        BatchProblem(int status, Preview preview) { super(status, "표시된 행을 확인해 주세요."); this.preview = preview; }
    }

    private static List<Row> rows(Form form) {
        if (form == null || form.commandId() == null) throw OrgException.invalid("명령 식별자가 필요합니다.");
        if (form.rows() == null || form.rows().isEmpty() || form.rows().size() > 50)
            throw OrgException.invalid("한 번에 1~50행을 입력해 주세요.");
        return form.rows();
    }

    private List<Checked> check(TnaPrincipal actor, List<Row> rows, boolean saving) {
        var checked = new ArrayList<Checked>(rows.size());
        var ids = new HashSet<String>();
        var openings = new HashSet<OpeningKey>();
        var adjustments = new HashSet<UUID>();
        LocalDate today = commands.today();
        for (var row : rows) checked.add(checkRow(actor, row, today, saving, ids, openings, adjustments));
        return checked;
    }

    private Checked checkRow(TnaPrincipal actor, Row row, LocalDate today, boolean saving, Set<String> ids,
            Set<OpeningKey> openings, Set<UUID> adjustments) {
        String rowId = row == null ? null : row.clientRowId();
        Employee employee = null;
        Bucket before = null;
        try {
            if (rowId == null || rowId.isBlank() || rowId.length() > 80 || rowId.codePoints().anyMatch(Character::isISOControl) || !ids.add(rowId))
                throw invalid("clientRowId", "각 행에 서로 다른 식별자가 필요합니다.");
            if (row.employeeNo() != null) employee = employees.byNumber(actor.tenantId(), row.employeeNo().strip());
            if (employee == null) throw invalid("employeeNo", "사번을 확인해 주세요.");
            if (actor.employeeId().equals(employee.id())) throw invalid("employeeNo", "본인의 연차 잔여는 다른 담당자가 처리해야 합니다.");
            var effective = date(row.effectiveOn(), "effectiveOn");
            var quantity = decimal(row.quantityDays());
            if ("OPENING".equals(row.operation())) {
                if (!blank(row.bucketId()) || !blank(row.expectedVersion())) throw invalid("bucketId", "기초 등록은 새 묶음을 만듭니다.");
                var form = LeaveBalanceEntries.opening(new BucketForm(employee.id(), effective, date(row.usableFrom(), "usableFrom"),
                        date(row.usableTo(), "usableTo"), quantity, row.sourceReference(), row.reason(), null), today);
                if (!openings.add(new OpeningKey(employee.id(), effective, form.usableFrom(), form.usableTo(), form.sourceReference())))
                    throw invalid("employeeNo", "같은 사원의 기준일·사용 기간·근거가 같은 기초 행이 중복되었습니다.");
                var preview = new RowPreview(rowId, employee.displayName(), BigDecimal.ZERO, form.quantityDays(), null, List.of());
                return new Checked(preview, form, null, null, 200);
            }
            if (!"ADJUSTMENT".equals(row.operation())) throw invalid("operation", "기초 또는 조정을 선택해 주세요.");
            if (!blank(row.usableFrom()) || !blank(row.usableTo())) throw invalid("usableFrom", "조정할 묶음의 사용 기간은 변경할 수 없습니다.");
            UUID id = bucketId(row.bucketId());
            var selected = buckets.findBucket(actor.tenantId(), id, today);
            if (selected == null || !selected.employeeId().equals(employee.id())) throw invalid("bucketId", "선택한 사원의 연차 묶음을 확인해 주세요.");
            before = selected;
            if (!adjustments.add(id)) throw invalid("bucketId", "같은 묶음에 대한 조정이 중복되었습니다.");
            if (saving || !blank(row.expectedVersion())) checkVersion(row.expectedVersion(), before.version());
            var form = LeaveBalanceEntries.adjustment(new AdjustmentForm(quantity, effective, before.version(), row.sourceReference(), row.reason(), null), today);
            LeaveBalanceEntries.checkAdjustment(before, form);
            var balance = BigDecimal.valueOf(before.balanceDays());
            var preview = new RowPreview(rowId, employee.displayName(), balance, balance.add(form.quantityDays()), before.version(), List.of());
            return new Checked(preview, null, form, before, 200);
        } catch (FieldProblem problem) {
            var preview = new RowPreview(rowId, employee == null ? null : employee.displayName(),
                    before == null ? null : BigDecimal.valueOf(before.balanceDays()), null, before == null ? null : before.version(),
                    List.of(new FieldError(problem.field, problem.getMessage())));
            return new Checked(preview, null, null, null, problem.status());
        }
    }

    private static Preview summarize(List<Checked> checked) {
        return new Preview(checked.stream().allMatch(row -> row.status() == 200), checked.stream().map(Checked::preview).toList());
    }
    private static boolean blank(String value) { return value == null || value.isBlank(); }
    private static LocalDate date(String value, String field) {
        try { return blank(value) ? null : LocalDate.parse(value); }
        catch (DateTimeParseException e) { throw invalid(field, "올바른 날짜를 입력해 주세요."); }
    }
    private static BigDecimal decimal(String value) {
        try { return blank(value) ? null : new BigDecimal(value.strip()); }
        catch (NumberFormatException e) { throw invalid("quantityDays", "일수를 숫자로 입력해 주세요."); }
    }
    private static UUID bucketId(String value) {
        if (!blank(value)) {
            try {
                UUID id = UUID.fromString(value);
                if (id.toString().equalsIgnoreCase(value)) return id;
            } catch (IllegalArgumentException ignored) { }
        }
        throw invalid("bucketId", "조정할 연차 묶음을 선택해 주세요.");
    }
    private static void checkVersion(String value, long actual) {
        try { OrgCommands.checkVersion(blank(value) ? null : Long.valueOf(value), actual); }
        catch (NumberFormatException invalidVersion) { throw invalid("expectedVersion", "미리보기의 묶음 버전을 확인해 주세요."); }
        catch (OrgException error) { throw new FieldProblem("expectedVersion", error.status(), error.getMessage()); }
    }
}
