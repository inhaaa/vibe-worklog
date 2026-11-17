package kr.tna.worktime;

import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;

public final class WorktimeData {
    private WorktimeData() { }
    public record EmployeeChoice(UUID id,String employeeNo,String displayName) { }
    public record PolicyForm(@DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate from,
            @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate to,boolean mondayWeekReviewed,
            String basis,String reason,UUID commandId) { }
    public record AssessmentForm(UUID employeeId,@DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate from,
            @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate to,boolean generalRuleReviewed,
            String basis,String reason,UUID commandId) { }
    public record PolicyReview(UUID id,int revision,LocalDate validFrom,LocalDate validTo,boolean mondayWeekReviewed,String basis,Instant createdAt) { }
    public record AssessmentReview(UUID id,UUID employeeId,int revision,LocalDate validFrom,LocalDate validTo,boolean generalRuleReviewed,String basis,Instant createdAt) { }
    public record DayView(UUID id,UUID runId,UUID employeeId,String employeeNo,String employeeName,
            LocalDate workDate,long resultRevision,String ruleVersion,Instant calculatedAt,String sourceFingerprint,
            boolean current,WorktimeCalculator.DayResult result,boolean pending,int approvedLeaveDays,boolean employed) { }
    public record RunEvidence(UUID id,UUID requestedBy,LocalDate fromDate,LocalDate toDate,
            LocalDate expandedFrom,LocalDate expandedTo,String engineVersion,String status,Instant startedAt,Instant finishedAt) { }
    public record PolicyEvidence(UUID id,int revision,LocalDate validFrom,LocalDate validTo,boolean mondayWeekReviewed) { }
    public record AssessmentEvidence(UUID id,int revision,LocalDate validFrom,LocalDate validTo,boolean generalRuleReviewed) { }
    public record EmploymentEvidence(LocalDate joinedOn,LocalDate leftOn) { }
    public record ResultDetail(DayView day,boolean latestVersion,UUID latestResultId,RunEvidence run,
            kr.tna.clock.ClockData.DaySnapshot clock,PolicyEvidence policy,AssessmentEvidence assessment,
            EmploymentEvidence employment,List<String> leaveEffects,List<String> pendingCorrections,boolean completeEvidence) { }
    public record PeriodView(LocalDate from,LocalDate to,boolean current,WorktimeCalculator.PeriodResult result,List<UUID> dayResultIds,int pendingDays) { }
    public record MonthView(YearMonth month,List<DayView> days,List<PeriodView> weeks,PeriodView summary) { }
    public record AuditPage(List<AuditRow> rows,UUID previousCursor,UUID nextCursor) { }
    public record AuditDetail(UUID id,UUID commandId,UUID requestTraceId,UUID actorAccountId,String action,
            String targetKind,UUID targetId,String reason,Instant at,java.util.Map<String,String> beforeSummary,
            java.util.Map<String,String> afterSummary) { }
    public record AuditRow(UUID id,UUID actorAccountId,String action,String targetKind,UUID targetId,String reason,Instant at) { }
}
