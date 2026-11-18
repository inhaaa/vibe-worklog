package kr.tna.request;

import java.math.BigDecimal;
import java.time.LocalDate;
import kr.tna.org.OrgCommands;
import kr.tna.org.OrgException;
import kr.tna.request.RequestData.AdjustmentForm;
import kr.tna.request.RequestData.Bucket;
import kr.tna.request.RequestData.BucketForm;

final class LeaveBalanceEntries {
    private static final BigDecimal HALF = new BigDecimal("0.5");
    private static final BigDecimal LIMIT = BigDecimal.valueOf(100_000);
    private LeaveBalanceEntries() { }

    static BucketForm opening(BucketForm form, LocalDate today) {
        if (form == null || form.employeeId() == null) throw invalid("employeeNo", "대상 사원을 선택해 주세요.");
        effectiveDate(form.baseDate(), today);
        if (form.usableFrom() == null) throw invalid("usableFrom", "사용 시작일을 입력해 주세요.");
        if (form.usableTo() != null && form.usableTo().isBefore(form.usableFrom()))
            throw invalid("usableTo", "사용 종료일은 시작일 이후여야 합니다.");
        return new BucketForm(form.employeeId(), form.baseDate(), form.usableFrom(), form.usableTo(), quantity(form.quantityDays(), true),
                text("sourceReference", form.sourceReference(), "기초 근거"), text("reason", form.reason(), "등록 사유"), form.commandId());
    }

    static AdjustmentForm adjustment(AdjustmentForm form, LocalDate today) {
        if (form == null) throw invalid("quantityDays", "조정할 일수를 입력해 주세요.");
        effectiveDate(form.effectiveOn(), today);
        return new AdjustmentForm(quantity(form.quantityDays(), false), form.effectiveOn(), form.expectedVersion(),
                text("sourceReference", form.sourceReference(), "조정 근거"), text("reason", form.reason(), "조정 사유"), form.commandId());
    }

    static void checkAdjustment(Bucket bucket, AdjustmentForm form) {
        if (form.effectiveOn().isBefore(bucket.baseDate())) throw invalid("effectiveOn", "기초 기준일 이전에는 조정할 수 없습니다.");
        if (BigDecimal.valueOf(bucket.balanceDays()).add(form.quantityDays()).signum() < 0)
            throw invalid("quantityDays", "조정 후 잔여가 음수가 될 수 없습니다.");
    }

    private static void effectiveDate(LocalDate date, LocalDate today) {
        if (date == null || date.isAfter(today)) throw invalid("effectiveOn", "오늘까지의 기준일을 입력해 주세요.");
    }

    private static BigDecimal quantity(BigDecimal input, boolean opening) {
        if (input == null || input.abs().compareTo(LIMIT) > 0 || (opening ? input.signum() < 0 : input.signum() == 0))
            throw invalid("quantityDays", opening ? "기초 일수는 0~100000일로 입력해 주세요." : "조정 일수는 0을 제외한 ±100000일 이내로 입력해 주세요.");
        var normalized = input.stripTrailingZeros();
        if (normalized.scale() > 1 || normalized.remainder(HALF).signum() != 0)
            throw invalid("quantityDays", "일수는 0.5일 단위로 입력해 주세요.");
        // The stored fingerprint of an integer command uses a plain integer, including 10 and 100.
        return normalized.scale() < 0 ? normalized.setScale(0) : normalized;
    }

    private static String text(String field, String value, String label) {
        try { return OrgCommands.requiredText(value, 500, label); }
        catch (OrgException error) { throw invalid(field, error.getMessage()); }
    }

    static FieldProblem invalid(String field, String message) { return new FieldProblem(field, 422, message); }
    static final class FieldProblem extends OrgException {
        final String field;
        FieldProblem(String field, int status, String message) { super(status, message); this.field = field; }
    }
}
