package kr.tna.worktime;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import kr.tna.common.auth.TnaPrincipal;
import kr.tna.common.tenant.CompanyReadAccess;
import kr.tna.org.OrgCommands;
import kr.tna.org.OrgException;
import kr.tna.worktime.CheckoutFollowUpService.DayKey;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

@Service
public final class MonthEndBatchService {
    public record Selection(String month, UUID organizationId, String q, String reason, Integer page, List<DayKey> rows) {
        String fingerprint() {
            return OrgCommands.fingerprint(month, organizationId, q, reason, page, rows.stream()
                    .map(key -> OrgCommands.fingerprint(key.employeeId(), key.workDate())).toList());
        }
    }
    public record Command(String month, UUID organizationId, String q, String reason, Integer page, List<DayKey> rows,
            String previewToken, String calculationReason, UUID commandId) {
        Selection selection() { return new Selection(month, organizationId, q, reason, page, rows); }
    }
    public record Preview(String previewToken, int weekCount, List<PreviewRow> rows) { }
    public record PreviewRow(UUID employeeId, String employeeNo, String employeeName, LocalDate workDate,
            LocalDate weekStart, LocalDate weekEnd, List<String> reasons) { }
    public record Result(UUID batchId, int weekCount, List<ResultRow> rows) { }
    public record ResultRow(UUID employeeId, String employeeNo, String employeeName, LocalDate workDate, UUID resultId,
            Long actualWorkMillis, List<String> reasons) { }
    private record Week(UUID employeeId, LocalDate start) { }

    private static final String ACTION = "MONTH_END_RECALCULATE";
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final MonthEndReviewQueries queries;
    private final WorktimeService worktime;
    private final WorktimeMapper mapper;
    private final OrgCommands administration;
    private final CompanyReadAccess company;

    public MonthEndBatchService(MonthEndReviewQueries queries, WorktimeService worktime, WorktimeMapper mapper,
            OrgCommands administration, CompanyReadAccess company) {
        this.queries = queries;
        this.worktime = worktime;
        this.mapper = mapper;
        this.administration = administration;
        this.company = company;
    }

    public Preview preview(TnaPrincipal actor, Selection form) {
        var selection = checkedSelection(actor, form);
        return company.read(actor, () -> currentPreview(actor, selection));
    }

    public Result execute(TnaPrincipal actor, Command command) {
        var selection = checkedSelection(actor, command == null ? null : command.selection());
        String token = OrgCommands.requiredText(command.previewToken(), 128, "미리보기 식별값");
        String reason = OrgCommands.requiredText(command.calculationReason(), 500, "재집계 사유");
        String hash = OrgCommands.fingerprint(ACTION, selection.fingerprint(), token, reason);
        UUID batchId = administration.execute(actor, command.commandId(), ACTION, "CALCULATION_BATCH", hash, reason, () -> {
            var preview = currentPreview(actor, selection);
            if (!token.equals(preview.previewToken())) throw changed();
            var result = calculate(actor, preview, reason);
            return new OrgCommands.Change(result.batchId(), null, result);
        });
        // Use the same employee and administrator checks as the month list, including on a replay.
        return company.read(actor, () -> administration.read(actor, () -> {
            var receipt = mapper.monthEndReceipt(actor.tenantId(), command.commandId(), batchId);
            if (receipt == null) throw new OrgException(503, "재집계 결과를 확인할 수 없습니다. 같은 명령으로 다시 확인해 주세요.");
            return JSON.readValue(receipt, Result.class);
        }));
    }

    private static Selection checkedSelection(TnaPrincipal actor, Selection form) {
        if (actor == null || !actor.roles().contains("ATTENDANCE_ADMIN")) throw OrgException.forbidden();
        if (form == null || form.month() == null || form.month().isBlank() || form.page() == null) {
            throw OrgException.invalid("조회 월과 페이지를 확인해 주세요.");
        }
        if (form.rows() == null || form.rows().isEmpty() || form.rows().size() > 50) {
            throw OrgException.invalid("현재 페이지에서 1~50건을 선택해 주세요.");
        }
        var unique = new HashSet<DayKey>();
        for (var row : form.rows()) {
            if (row == null || row.employeeId() == null || row.workDate() == null || !unique.add(row)) {
                throw OrgException.invalid("사원과 근무일이 있는 행을 중복 없이 선택해 주세요.");
            }
        }
        var ordered = unique.stream().sorted(Comparator.comparing(DayKey::employeeId).thenComparing(DayKey::workDate)).toList();
        return new Selection(form.month(), form.organizationId(), Objects.requireNonNullElse(form.q(), "").strip(),
                Objects.requireNonNullElse(form.reason(), "ALL"), form.page(), ordered);
    }

    private Preview currentPreview(TnaPrincipal actor, Selection selection) {
        var batch = queries.readForBatch(actor, selection.month(), selection.organizationId(), selection.q(), selection.reason(), selection.page());
        if (batch.page().error() != null) throw OrgException.invalid(batch.page().error());
        var month = batch.page().month();
        var monthWeeks = CalculationWindow.weeks(month.atDay(1), month.atEndOfMonth());
        var closed = mapper.closedMonths(actor.tenantId(), monthWeeks.start(), monthWeeks.end());
        if (closed.contains(month.atDay(1))) throw ClosedAttendanceMonths.closedMonth(month.atDay(1));
        var selected = Set.copyOf(selection.rows());
        var rows = new ArrayList<PreviewRow>();
        var bases = new ArrayList<String>();
        for (var row : batch.page().rows()) {
            if (!selected.contains(row.dayKey())) continue;
            var basis = batch.bases().get(row.dayKey());
            if (basis == null) throw changed();
            var window = CalculationWindow.weeks(row.workDate(), row.workDate()).openDates(row.workDate(), row.workDate(), closed);
            rows.add(new PreviewRow(row.employeeId(), row.employeeNo(), row.employeeName(), row.workDate(),
                    window.start(), window.end(), row.reasons()));
            bases.add(OrgCommands.fingerprint(row.employeeId(), row.workDate(), row.employeeNo(), row.employeeName(),
                    row.reasons(), basis, window.start(), window.end()));
        }
        if (rows.size() != selected.size()) throw changed();
        String token = OrgCommands.fingerprint("MONTH_END_PREVIEW_1", actor.tenantId(), actor.accountId(), selection.fingerprint(), bases);
        return new Preview(token, weeks(rows).size(), List.copyOf(rows));
    }

    private Result calculate(TnaPrincipal actor, Preview preview, String reason) {
        var saved = new HashMap<DayKey, WorktimeMapper.ResultRow>();
        for (var entry : weeks(preview.rows()).entrySet()) {
            var dates = entry.getValue();
            var from = Collections.min(dates);
            var through = Collections.max(dates);
            var employee = entry.getKey().employeeId();
            UUID run = worktime.recalculate(actor, employee, from, through, UUID.randomUUID(), reason);
            for (var row : mapper.latest(actor.tenantId(), employee, from, through)) {
                if (run.equals(row.runId())) saved.put(new DayKey(row.employeeId(), row.workDate()), row);
            }
        }
        var rows = new ArrayList<ResultRow>();
        for (var row : preview.rows()) {
            var result = saved.get(new DayKey(row.employeeId(), row.workDate()));
            if (result == null) throw changed();
            var reasons = new ArrayList<String>(2);
            if (result.actualWorkMillis() == null) reasons.add("UNCONFIRMED");
            if (Arrays.asList(JSON.readValue(result.reasonCodes(), String[].class)).contains("PENDING_CORRECTION")) reasons.add("PENDING_CORRECTION");
            rows.add(new ResultRow(row.employeeId(), row.employeeNo(), row.employeeName(), row.workDate(),
                    result.id(), result.actualWorkMillis(), List.copyOf(reasons)));
        }
        return new Result(UUID.randomUUID(), preview.weekCount(), List.copyOf(rows));
    }

    private static Map<Week, List<LocalDate>> weeks(List<PreviewRow> rows) {
        var weeks = new LinkedHashMap<Week, List<LocalDate>>();
        for (var row : rows) {
            var week = new Week(row.employeeId(), WorktimeCalculator.weekStart(row.workDate()));
            weeks.computeIfAbsent(week, ignored -> new ArrayList<>()).add(row.workDate());
        }
        return weeks;
    }

    private static OrgException changed() {
        return OrgException.conflict("선택한 행이나 계산 근거가 변경됐습니다. 목록을 새로 조회한 뒤 다시 미리보기해 주세요.");
    }
}
