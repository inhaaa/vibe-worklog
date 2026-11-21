package kr.tna.request;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public final class LeaveExitData {
    private LeaveExitData() { }

    public record Row(UUID bucketId, String sourceReference, LocalDate usableFrom, LocalDate usableTo, Integer entitlementYear,
            BigDecimal openingDays, BigDecimal grantDays, BigDecimal carryDays, BigDecimal usedDays,
            BigDecimal restoredDays, BigDecimal adjustmentDays, BigDecimal expireDays,
            BigDecimal balanceDays, BigDecimal settlementDays, String status) { }

    public record Preview(UUID employeeId, String employeeNo, String employeeName, LocalDate joinedOn, LocalDate leftOn,
            List<Row> rows, BigDecimal balanceDays, BigDecimal settlementDays, List<LocalDate> missingMonths,
            boolean priorHistoryReviewRequired, List<String> blockers, String token, boolean canPost, UUID settlementId, String reason) { }

    public record PostForm(UUID employeeId, String token, String reason, UUID commandId, Boolean priorHistoryConfirmed) { }
}
