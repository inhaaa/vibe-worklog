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
    public record DecisionForm(String decision,Long expectedVersion,String reason,UUID commandId,Integer expectedStep) {
        public DecisionForm(String decision,Long expectedVersion,String reason,UUID commandId){this(decision,expectedVersion,reason,commandId,null);}
    }
    public record CancelForm(boolean exceptional,Long expectedVersion,String reason,UUID commandId) { }
    public record ReassignForm(UUID approverEmployeeId,Long expectedVersion,String reason,UUID commandId,Integer expectedStep) {
        public ReassignForm(UUID approverEmployeeId,Long expectedVersion,String reason,UUID commandId){this(approverEmployeeId,expectedVersion,reason,commandId,null);}
    }
    public record RequestView(UUID id,UUID targetEmployeeId,String targetEmployeeName,UUID authorAccountId,
            String kind,String status,UUID currentApproverId,String approverName,UUID approverAssignmentId,
            Long approverAssignmentVersion,Instant submittedAt,String reason,UUID predecessorRequestId,
            UUID originalRequestId,UUID leaveBucketId,LocalDate fromDate,LocalDate toDate,boolean exceptional,
            long version,String effectStatus,int quantityDays,UUID routeSnapshotId,UUID attendanceTypeVersionId,
            String formCode,String formName,int currentStep,int totalSteps,boolean legacyForm) {
        @org.apache.ibatis.annotations.AutomapConstructor public RequestView { }
        public RequestView(UUID id,UUID targetEmployeeId,String targetEmployeeName,UUID authorAccountId,
            String kind,String status,UUID currentApproverId,String approverName,UUID approverAssignmentId,
            Long approverAssignmentVersion,Instant submittedAt,String reason,UUID predecessorRequestId,
            UUID originalRequestId,UUID leaveBucketId,LocalDate fromDate,LocalDate toDate,boolean exceptional,
            long version,String effectStatus,int quantityDays,UUID routeSnapshotId,UUID attendanceTypeVersionId) {
            this(id,targetEmployeeId,targetEmployeeName,authorAccountId,kind,status,currentApproverId,approverName,
                 approverAssignmentId,approverAssignmentVersion,submittedAt,reason,predecessorRequestId,originalRequestId,leaveBucketId,fromDate,toDate,exceptional,version,effectStatus,quantityDays,routeSnapshotId,attendanceTypeVersionId,null,null,1,1,true);
        }
        public RequestView(UUID id,UUID targetEmployeeId,String targetEmployeeName,UUID authorAccountId,
                String kind,String status,UUID currentApproverId,String approverName,UUID approverAssignmentId,
                Long approverAssignmentVersion,Instant submittedAt,String reason,UUID predecessorRequestId,
                UUID originalRequestId,UUID leaveBucketId,LocalDate fromDate,LocalDate toDate,boolean exceptional,
                long version,String effectStatus,int quantityDays,UUID routeSnapshotId) {
            this(id,targetEmployeeId,targetEmployeeName,authorAccountId,kind,status,currentApproverId,approverName,
                    approverAssignmentId,approverAssignmentVersion,submittedAt,reason,predecessorRequestId,
                    originalRequestId,leaveBucketId,fromDate,toDate,exceptional,version,effectStatus,quantityDays,routeSnapshotId,null);
        }
        public RequestView(UUID id,UUID targetEmployeeId,String targetEmployeeName,UUID authorAccountId,
                String kind,String status,UUID currentApproverId,String approverName,UUID approverAssignmentId,
                long approverAssignmentVersion,Instant submittedAt,String reason,UUID predecessorRequestId,
                UUID originalRequestId,UUID leaveBucketId,LocalDate fromDate,LocalDate toDate,boolean exceptional,
                long version,String effectStatus,int quantityDays) {
            this(id,targetEmployeeId,targetEmployeeName,authorAccountId,kind,status,currentApproverId,approverName,
                    approverAssignmentId,approverAssignmentVersion,submittedAt,reason,predecessorRequestId,
                    originalRequestId,leaveBucketId,fromDate,toDate,exceptional,version,effectStatus,quantityDays,null,null);
        }
    }
    public record RequestDay(LocalDate leaveDate,int quantityDays,UUID scheduleVersionId,UUID calendarVersionId,String zeroReason) { }
    public record CorrectionDetail(LocalDate workDate,long baseInputRevision,String sourceFingerprint,
            String inAction,String outAction,Instant requestedInAt,Instant requestedOutAt,boolean presenceAsWork,
            boolean breaksConfirmed,String actualBreaks,UUID replacesRequestId,String beforeSnapshot) { }
    public record CorrectionTimes(List<Instant> in, List<Instant> out) { }
    public record CorrectionComparison(CorrectionTimes source, CorrectionTimes current, CorrectionTimes proposed) { }
    public record ApprovalHistory(String eventKind,UUID actorAccountId,String fromStatus,String toStatus,
            String reason,UUID previousApproverId,UUID newApproverId,Instant createdAt,Integer stepNumber) { }
    public record PreviewStep(int stepNumber,String role,UUID approverId,String approverName,String organizationName) {}
    public record DecisionStep(int stepNumber,String role,String originalApproverName,String approverName,
            String status,String decidedByName,Instant decidedAt,String reason) {}
    public record ApprovalPreview(String source,String routeName,String approverName,boolean eligible,String message,
            String formCode,String formName,LocalDate basisDate,List<PreviewStep> steps,String routeToken) {
        public ApprovalPreview(String source,String routeName,String approverName,boolean eligible,String message) {
            this(source,routeName,approverName,eligible,message,null,null,null,List.of(),null);
        }
    }
    public record RouteSnapshot(String routeCode,String routeName,long revision,String scopeKind,String approverKind,
            String organizationName,String resolvedApproverName,Instant resolvedAt) { }
    public record ApprovalPage(List<RequestView> requests,int page,boolean hasNext,boolean completed) { }
    public record AttendancePolicy(UUID id,UUID typeId,String code,String systemKind,long revision,
            LocalDate effectiveFrom,boolean enabled,String name,String unit,String payKind,String deductionRule,
            String recognitionRule,String basis) { }
    public record RequestDetail(RequestView request,List<RequestDay> days,CorrectionDetail correction,
            List<ApprovalHistory> history,RouteSnapshot routeSnapshot,AttendancePolicy attendancePolicy,
            CorrectionComparison correctionComparison,List<DecisionStep> steps,boolean reassignmentRequired) {
        public RequestDetail(RequestView request,List<RequestDay> days,CorrectionDetail correction,
                List<ApprovalHistory> history,RouteSnapshot routeSnapshot,AttendancePolicy attendancePolicy,CorrectionComparison comparison) {
            this(request,days,correction,history,routeSnapshot,attendancePolicy,comparison,List.of(),false);
        }
        public RequestDetail(RequestView request,List<RequestDay> days,CorrectionDetail correction,
                List<ApprovalHistory> history,RouteSnapshot routeSnapshot,AttendancePolicy attendancePolicy) {
            this(request,days,correction,history,routeSnapshot,attendancePolicy,null);
        }
        public RequestDetail(RequestView request,List<RequestDay> days,CorrectionDetail correction,
                List<ApprovalHistory> history,RouteSnapshot routeSnapshot) {
            this(request,days,correction,history,routeSnapshot,null);
        }
        public RequestDetail(RequestView request,List<RequestDay> days,CorrectionDetail correction,List<ApprovalHistory> history) {
            this(request,days,correction,history,null,null);
        }
    }
}
