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

    public record Rule(long version, int fiscalStartMonth, BigDecimal grantDays, BigDecimal carryLimitDays) { }
    public record RuleForm(Long expectedVersion, Integer fiscalStartMonth, BigDecimal grantDays, BigDecimal carryLimitDays,
            String reason, UUID commandId) { }
    public record PostForm(Integer year, String token, String reason, UUID commandId) { }
    public record Row(UUID employeeId, String employeeNo, String employeeName, BigDecimal previousDays,
            BigDecimal grantDays, BigDecimal carryDays, BigDecimal expireDays) { }
    public record Preview(int year, LocalDate periodStart, LocalDate periodEnd, Rule rule, List<Row> rows,
            int postedCount, int existingCount, int laterCount, int pendingCount, String token, boolean canPost) { }
    public record Entry(UUID id, UUID bucketId, String employeeNo, Integer entitlementYear, String entryKind,
            BigDecimal signedQuantityDays, LocalDate effectiveOn, LocalDate leaveDate, String sourceReference, String reason) { }
    public record Ledger(int year, LocalDate today, BigDecimal availableDays, List<Entry> entries) { }
}
