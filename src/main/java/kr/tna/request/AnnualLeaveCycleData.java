package kr.tna.request;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public final class AnnualLeaveCycleData {
    public static final int MIN_YEAR = 1900;
    public static final int MAX_YEAR = 9998;

    private AnnualLeaveCycleData() { }

    public static int currentYear(LocalDate today, Rule rule) {
        return today.getYear() - (rule != null && today.getMonthValue() < rule.fiscalStartMonth() ? 1 : 0);
    }

    public record Rule(long version, int fiscalStartMonth, BigDecimal grantDays, BigDecimal carryLimitDays, String basis) { }
    public record RuleForm(Long expectedVersion, String basis, Integer fiscalStartMonth, BigDecimal carryLimitDays,
            String reason, UUID commandId) { }
    /** reviewedEmployeeIds: 담당자가 골라 법정 일수로 함께 반영할 확인 필요 사원. 없으면 부여 대상만 반영한다. */
    public record PostForm(Integer year, String token, String reason, UUID commandId, List<UUID> reviewedEmployeeIds) {
        public PostForm(Integer year, String token, String reason, UUID commandId) { this(year, token, reason, commandId, null); }
    }
    public record Row(UUID employeeId, String employeeNo, String employeeName, LocalDate joinedOn, LocalDate grantOn, int serviceYear,
            LocalDate periodStart, LocalDate periodEnd, int scheduledDays, BigDecimal absenceDays, int unresolvedDays,
            BigDecimal attendanceRate, String decision, String reason,
            BigDecimal previousDays, BigDecimal grantDays, BigDecimal carryDays, BigDecimal expireDays) { }
    public record Preview(int year, String month, LocalDate periodStart, LocalDate periodEnd, Rule rule, List<Row> rows,
            int postedCount, int existingCount, int laterCount, int pendingCount, int reviewCount, String token, boolean canPost) { }
    public record Entry(UUID id, UUID bucketId, String employeeNo, Integer entitlementYear, String entryKind,
            BigDecimal signedQuantityDays, LocalDate effectiveOn, LocalDate leaveDate, String sourceReference, String reason,
            String monthlyDecision) { }
    public record ExitReceipt(LocalDate leftOn, BigDecimal settlementDays, String reason) { }
    public record Ledger(int year, LocalDate today, BigDecimal availableDays, List<Entry> entries, ExitReceipt exitReceipt) { }
}
