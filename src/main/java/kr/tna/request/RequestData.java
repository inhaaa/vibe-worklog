package kr.tna.request;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;

public final class RequestData {
    private RequestData() { }
    public record Bucket(UUID id,UUID employeeId,String employeeName,LocalDate baseDate,LocalDate usableFrom,
            LocalDate usableTo,String sourceReference,long version,int balanceDays,int availableDays,int pendingDays,int futureApprovedDays) { }
    public record BucketForm(UUID employeeId,@DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate baseDate,
            @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate usableFrom,
            @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate usableTo,Integer quantityDays,
            String sourceReference,String reason,UUID commandId) { }
    public record AdjustmentForm(Integer quantityDays,@DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate effectiveOn,
            Long expectedVersion,String sourceReference,String reason,UUID commandId) { }
    public record LedgerEntry(UUID id,UUID bucketId,UUID employeeId,String entryKind,int signedQuantityDays,
            LocalDate effectiveOn,UUID requestEffectId,LocalDate leaveDate,UUID reversesEntryId,
            String sourceReference,String reason,Instant createdAt) { }
    public record LeaveForm(UUID bucketId,@DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate from,
            @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate to,UUID predecessorRequestId,String reason,UUID commandId) { }
    public record CorrectionForm(UUID targetEmployeeId,@DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate workDate,
            Long baseInputRevision,String sourceFingerprint,String inAction,String requestedInAt,String outAction,
            String requestedOutAt,boolean presenceAsWork,boolean breaksConfirmed,String actualBreaks,
            UUID replacesRequestId,UUID predecessorRequestId,boolean exceptional,String reason,UUID commandId) { }
    public record DecisionForm(String decision,Long expectedVersion,String reason,UUID commandId) { }
    public record CancelForm(boolean exceptional,Long expectedVersion,String reason,UUID commandId) { }
    public record ReassignForm(UUID approverEmployeeId,Long expectedVersion,String reason,UUID commandId) { }
    public record RequestView(UUID id,UUID targetEmployeeId,String targetEmployeeName,UUID authorAccountId,
            String kind,String status,UUID currentApproverId,String approverName,UUID approverAssignmentId,
            long approverAssignmentVersion,Instant submittedAt,String reason,UUID predecessorRequestId,
            UUID originalRequestId,UUID leaveBucketId,LocalDate fromDate,LocalDate toDate,boolean exceptional,
            long version,String effectStatus,int quantityDays) { }
    public record RequestDay(LocalDate leaveDate,int quantityDays,UUID scheduleVersionId,UUID calendarVersionId,String zeroReason) { }
    public record CorrectionDetail(LocalDate workDate,long baseInputRevision,String sourceFingerprint,
            String inAction,String outAction,Instant requestedInAt,Instant requestedOutAt,boolean presenceAsWork,
            boolean breaksConfirmed,String actualBreaks,UUID replacesRequestId,String beforeSnapshot) { }
    public record ApprovalHistory(String eventKind,UUID actorAccountId,String fromStatus,String toStatus,
            String reason,UUID previousApproverId,UUID newApproverId,Instant createdAt) { }
    public record RequestDetail(RequestView request,List<RequestDay> days,CorrectionDetail correction,List<ApprovalHistory> history) { }
}
