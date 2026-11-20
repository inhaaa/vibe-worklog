package kr.tna.request;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import kr.tna.clock.ClockData;
import kr.tna.request.RequestData.*;

public final class AnnualRequestData {
    private AnnualRequestData() { }
    public record Filter(Integer year,UUID typeId,String status,int page,int authoredPage) { }
    public record View(Annual annual,Compose compose,List<Bucket> buckets,LeaveForm form) { }
    public record CorrectionInput(@org.springframework.format.annotation.DateTimeFormat(iso=org.springframework.format.annotation.DateTimeFormat.ISO.DATE) LocalDate workDate,
            Long baseInputRevision,String sourceFingerprint,String inAction,String requestedInAt,String outAction,String requestedOutAt,
            Boolean presenceAsWork,Boolean breaksConfirmed,String actualBreaks,UUID replacesRequestId,UUID predecessorRequestId,String reason,UUID commandId) { }
    public record TypeChoice(UUID id,String code,String name,String systemKind,boolean supported) { }
    public record TypeSummary(UUID typeId,String code,String name,String kind,long requestCount,long pendingCount,
            long approvedCount,long rejectedCount,long withdrawnCount,double usedDays,double plannedDays,double pendingDays) { }
    public record BucketYear(UUID bucketId,Integer entitlementYear,String classificationStatus,String basis,
            LocalDate usableFrom,LocalDate usableTo,double balanceDays,double availableDays,java.math.BigDecimal openingAdjustmentDays,String availability) { }
    public record Annual(int year,LocalDate today,Instant asOf,UUID typeId,String status,int page,boolean hasNext,
            int authoredPage,boolean authoredHasNext,String coverage,double usedDays,double plannedDays,double pendingDays,
            double currentAvailableDays,java.math.BigDecimal registeredDays,Double attributedBalanceDays,int unknownBucketCount,
            boolean attributionPartial,List<TypeChoice> types,List<TypeSummary> summaries,List<RequestView> requests,
            List<RequestView> authoredRequests,List<BucketYear> attributionBuckets,UUID createdId,Integer outsideYear,List<UUID> canResubmitRequestIds) { }
    public record Preview(Double quantityDays,double availableDays,Double selectedBucketAvailableDays,
            AttendancePolicy policy,ApprovalPreview approval,boolean eligible,String message) { }
    public record Compose(String mode,String kind,LeaveForm leaveForm,CorrectionForm correctionForm,
            ClockData.DaySnapshot day,UUID currentCorrectionRequestId,Preview preview,ApprovalPreview approval,boolean conflict) { }
}
