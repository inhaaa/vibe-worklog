package kr.tna.worktime;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;
import kr.tna.codes.CompanyLeavePolicy;
import kr.tna.worktime.WorktimeData.*;
import org.apache.ibatis.annotations.*;

@Mapper
public interface WorktimeMapper extends ClosedAttendanceMonths {
    record SettlementRow(UUID employeeId, LocalDate workDate, long inputRevision, long reviewRevision,
            UUID scheduleVersionId, Boolean workday, LocalTime startTime, LocalTime endTime, String breaks,
            String workMode, LocalTime coreStart, LocalTime coreEnd, Integer standardMinutes, String settlementPeriod,
            UUID calendarVersionId, String dayKind, String events, String correction, String confirmations,
            UUID policyId, UUID assessmentId, String leaveEffects, String pendingCorrections,
            String storedSource, Long storedInput, Long storedReview, Long actualWorkMillis, Double storedLeaveDays, Double storedPaidLeaveDays,
            String reasonCodes, String savedSelectiveInputs, boolean closed, boolean unknownMembership)
            implements MonthEndReviewMapper.DayEvidence { }

    record MonthDayRow(UUID employeeId, LocalDate workDate, long inputRevision, long reviewRevision,
            UUID scheduleVersionId, Boolean workday, LocalTime startTime, LocalTime endTime, String breaks,
            String workMode, LocalTime coreStart, LocalTime coreEnd, Integer standardMinutes, String settlementPeriod,
            UUID calendarVersionId, String dayKind, String events, String correction, String confirmations,
            UUID policyId, UUID assessmentId, String leaveEffects, String pendingCorrections,
            boolean generalRuleReviewed, boolean mondayWeekReviewed) implements MonthEndReviewMapper.DayEvidence { }

    // Full ISO boundary weeks of one month cover at most 42 dates.
    String MONTH_DATES = """
            WITH dates AS (
                SELECT #{employeeId}::uuid AS employee_id,#{from}::date+n AS work_date
                FROM generate_series(0,41) n WHERE #{from}::date+n<=#{through}
            )
            """;
    String MONTH_DAY_COLUMNS = """
                d.employee_id,d.work_date,coalesce(rev.input_revision,0) AS input_revision,
                coalesce(rev.review_revision,0) AS review_revision,s.version_id AS schedule_version_id,s.workday,
                s.start_time,s.end_time,s.breaks,s.work_mode,s.core_start,s.core_end,s.standard_minutes,s.settlement_period,
                cal.version_id AS calendar_version_id,cal.kind AS day_kind,rec.events,cor.correction,conf.confirmations,
                policy.id AS policy_id,assessment.id AS assessment_id,l.effects AS leave_effects,p.ids AS pending_corrections,
            """;
    String MONTH_DAY_JOINS = MonthEndReviewMapper.DAY_EVIDENCE_JOINS + MonthEndReviewMapper.LEAVE_AND_POLICY_JOINS + """
            LEFT JOIN LATERAL (
                SELECT jsonb_agg(id ORDER BY id)::text AS ids FROM tna.approval_request
                WHERE tenant_id=#{tenantId} AND target_employee_id=d.employee_id AND kind='ATTENDANCE_CORRECTION'
                    AND status='PENDING' AND from_date=d.work_date
            ) p ON true
            """;

    @Select(MONTH_DATES + " SELECT " + MONTH_DAY_COLUMNS + """
                coalesce(assessment.general_rule_reviewed,false) AS general_rule_reviewed,
                coalesce(policy.monday_week_reviewed,false) AS monday_week_reviewed
            FROM dates d
            """ + MONTH_DAY_JOINS + " ORDER BY d.work_date")
    List<MonthDayRow> monthDays(@Param("tenantId") UUID tenantId, @Param("employeeId") UUID employeeId,
            @Param("from") LocalDate from, @Param("through") LocalDate through);

    @Select(MONTH_DATES + """
            , closures AS (
                SELECT DISTINCT ON (month_start) id,month_start,status,changed_at FROM tna.attendance_month_closure
                WHERE tenant_id=#{tenantId} AND month_start>=date_trunc('month',#{from}::date::timestamp)::date
                  AND month_start<=#{through}::date ORDER BY month_start,version DESC
            )
            SELECT
            """ + MONTH_DAY_COLUMNS + """
                r.source_fingerprint AS stored_source,r.input_revision AS stored_input,r.review_revision AS stored_review,
                r.actual_work_millis,r.leave_days AS stored_leave_days,
                coalesce((r.input_refs#>>'{0,paidLeaveDays}')::numeric,r.leave_days) AS stored_paid_leave_days,r.reason_codes::text AS reason_codes,
                CASE WHEN r.input_refs#>>'{0,clock,schedule,workMode}'='SELECTIVE' THEN r.input_refs::text END AS saved_selective_inputs,
                c.id IS NOT NULL AS closed,m.employee_id IS NOT NULL AND m.result_ids IS NULL AS unknown_membership
            FROM dates d
            LEFT JOIN closures c ON c.month_start=date_trunc('month',d.work_date::timestamp)::date AND c.status='CLOSED'
            LEFT JOIN tna.attendance_month_selective_member m ON m.tenant_id=#{tenantId} AND m.closure_id=c.id
                AND m.employee_id=d.employee_id
            """ + MONTH_DAY_JOINS + """
            LEFT JOIN LATERAL (
                SELECT source_fingerprint,input_revision,review_revision,actual_work_millis,leave_days,reason_codes,input_refs
                FROM tna.day_result dr WHERE dr.tenant_id=#{tenantId} AND dr.employee_id=d.employee_id AND dr.work_date=d.work_date
                    AND (c.id IS NULL OR dr.id=ANY(m.result_ids) OR
                         (m.employee_id IS NOT NULL AND m.result_ids IS NULL AND dr.calculated_at<=c.changed_at))
                ORDER BY dr.result_revision DESC LIMIT 1
            ) r ON true
            WHERE s.work_mode='SELECTIVE' OR r.input_refs#>>'{0,clock,schedule,workMode}'='SELECTIVE'
            ORDER BY d.work_date
            """)
    List<SettlementRow> settlementDays(@Param("tenantId") UUID tenantId, @Param("employeeId") UUID employeeId,
            @Param("from") LocalDate from, @Param("through") LocalDate through);

    @Select("""
            SELECT after_state::text FROM tna.org_audit_event
            WHERE tenant_id=#{tenantId} AND command_id=#{commandId} AND target_id=#{batchId}
            """)
    String monthEndReceipt(@Param("tenantId") UUID tenantId, @Param("commandId") UUID commandId, @Param("batchId") UUID batchId);

    // Retain the effective correction that produced the saved result.
    @Update("""
            UPDATE tna.checkout_follow_up follow_up
               SET resolved_at=statement_timestamp(),resolved_result_id=result.id,last_request_id=effect.request_id
              FROM tna.day_result result
              JOIN tna.request_effect effect ON effect.tenant_id=result.tenant_id AND effect.employee_id=result.employee_id
                   AND effect.work_date=result.work_date AND effect.effect_kind='CORRECTION' AND effect.effect_status='EFFECTIVE'
              JOIN tna.approval_request approved ON approved.tenant_id=effect.tenant_id AND approved.id=effect.request_id
                   AND approved.target_employee_id=effect.employee_id AND approved.kind='ATTENDANCE_CORRECTION' AND approved.status='APPROVED'
             WHERE follow_up.tenant_id=#{tenantId} AND follow_up.employee_id=#{employeeId} AND follow_up.work_date=#{date}
               AND follow_up.resolved_at IS NULL AND follow_up.last_request_id IS NOT NULL
               AND result.tenant_id=follow_up.tenant_id AND result.employee_id=follow_up.employee_id
               AND result.work_date=follow_up.work_date AND result.id=#{resultId} AND result.actual_work_millis IS NOT NULL
            """)
    int settleCheckoutFollowUp(@Param("tenantId") UUID tenantId, @Param("employeeId") UUID employeeId,
            @Param("date") LocalDate date, @Param("resultId") UUID resultId);

    @Select("SELECT pg_advisory_xact_lock_shared(hashtextextended(CAST(#{tenantId} AS text),0))")
    Object lock(@Param("tenantId") UUID tenantId);
    @Select("SELECT pg_advisory_xact_lock(hashtextextended(CAST(#{tenantId} AS text),0))")
    Object lockForWrite(@Param("tenantId") UUID tenantId);
    @Select("SELECT target_employee_id,from_date,to_date FROM tna.approval_request WHERE tenant_id=#{tenantId} AND id=#{id}")
    RequestTarget requestTarget(@Param("tenantId") UUID tenantId,@Param("id") UUID id);
    @Select("""
        SELECT work_date FROM tna.day_input_revision
        WHERE tenant_id=#{tenantId} AND employee_id=#{employeeId} AND recalculation_required AND work_date BETWEEN #{from} AND #{to}
        ORDER BY work_date
        """)
    List<LocalDate> recalculationDates(@Param("tenantId") UUID tenantId,@Param("employeeId") UUID employeeId,
            @Param("from") LocalDate from,@Param("to") LocalDate to);
    @Select("SELECT display_name FROM platform.tenant_registry WHERE id=#{tenantId}")
    String companyName(@Param("tenantId") UUID tenantId);
    @Select("""
        SELECT o.name FROM tna.employee_assignment a
        JOIN tna.organization o ON o.tenant_id=a.tenant_id AND o.id=a.organization_id
        WHERE a.tenant_id=#{tenantId} AND a.employee_id=#{employeeId} AND a.valid_from<=#{date}
          AND(a.valid_to IS NULL OR a.valid_to>#{date})
        """)
    String organizationAt(@Param("tenantId") UUID tenantId,@Param("employeeId") UUID employeeId,@Param("date") LocalDate date);
    @Select("""
        SELECT (input_refs->0->'clock')::text AS clock_json,
          input_refs#>>'{0,policy,id}' AS policy_version_id,input_refs#>>'{0,assessment,id}' AS assessment_id
        FROM tna.day_result WHERE tenant_id=#{tenantId} AND id=#{resultId}
        """)
    ExportEvidence exportEvidence(@Param("tenantId") UUID tenantId,@Param("resultId") UUID resultId);
    @Select("SELECT id,employee_no,display_name FROM tna.employee WHERE tenant_id=#{tenantId} ORDER BY employee_no,id")
    List<EmployeeChoice> employees(@Param("tenantId") UUID tenantId);
    @Select("SELECT id,revision,valid_from,valid_to,monday_week_reviewed,basis,created_at FROM tna.work_policy_version WHERE tenant_id=#{tenantId} ORDER BY revision DESC")
    List<PolicyReview> policies(@Param("tenantId") UUID tenantId);
    @Select("SELECT id,revision,valid_from,valid_to,monday_week_reviewed,basis,created_at FROM tna.work_policy_version WHERE tenant_id=#{tenantId} AND valid_from<=#{date} AND(valid_to IS NULL OR valid_to>#{date}) ORDER BY revision DESC LIMIT 1")
    PolicyReview policy(@Param("tenantId") UUID tenantId,@Param("date") LocalDate date);
    @Select("SELECT COALESCE(max(revision),0)+1 FROM tna.work_policy_version WHERE tenant_id=#{tenantId}")
    int nextPolicy(@Param("tenantId") UUID tenantId);
    @Insert("INSERT INTO tna.work_policy_version(tenant_id,id,revision,valid_from,valid_to,monday_week_reviewed,basis,created_by) VALUES(#{tenantId},#{id},#{revision},#{from},#{to},#{reviewed},#{basis},#{actorId})")
    int insertPolicy(@Param("tenantId") UUID tenantId,@Param("id") UUID id,@Param("revision") int revision,
        @Param("from") LocalDate from,@Param("to") LocalDate to,@Param("reviewed") boolean reviewed,@Param("basis") String basis,@Param("actorId") UUID actorId);
    @Select("SELECT id,employee_id,revision,valid_from,valid_to,general_rule_reviewed,basis,created_at FROM tna.employment_rule_assessment WHERE tenant_id=#{tenantId} AND employee_id=#{employeeId} ORDER BY revision DESC")
    List<AssessmentReview> assessments(@Param("tenantId") UUID tenantId,@Param("employeeId") UUID employeeId);
    @Select("SELECT id,employee_id,revision,valid_from,valid_to,general_rule_reviewed,basis,created_at FROM tna.employment_rule_assessment WHERE tenant_id=#{tenantId} AND employee_id=#{employeeId} AND valid_from<=#{date} AND(valid_to IS NULL OR valid_to>#{date}) ORDER BY revision DESC LIMIT 1")
    AssessmentReview assessment(@Param("tenantId") UUID tenantId,@Param("employeeId") UUID employeeId,@Param("date") LocalDate date);
    @Select("SELECT COALESCE(max(revision),0)+1 FROM tna.employment_rule_assessment WHERE tenant_id=#{tenantId} AND employee_id=#{employeeId}")
    int nextAssessment(@Param("tenantId") UUID tenantId,@Param("employeeId") UUID employeeId);
    @Insert("INSERT INTO tna.employment_rule_assessment(tenant_id,id,employee_id,revision,valid_from,valid_to,general_rule_reviewed,basis,created_by) VALUES(#{tenantId},#{id},#{employeeId},#{revision},#{from},#{to},#{reviewed},#{basis},#{actorId})")
    int insertAssessment(@Param("tenantId") UUID tenantId,@Param("id") UUID id,@Param("employeeId") UUID employeeId,
        @Param("revision") int revision,@Param("from") LocalDate from,@Param("to") LocalDate to,
        @Param("reviewed") boolean reviewed,@Param("basis") String basis,@Param("actorId") UUID actorId);
    @Select("""
        SELECT f.id::text AS id,d.quantity_days,v.recognition_rule,v.pay_kind,d.leave_part
        FROM tna.request_effect f
        JOIN tna.approval_request r ON r.tenant_id=f.tenant_id AND r.id=f.request_id
        JOIN tna.leave_request_day d ON d.tenant_id=r.tenant_id AND d.request_id=r.id
        LEFT JOIN tna.attendance_type_version v ON v.tenant_id=r.tenant_id AND v.id=r.attendance_type_version_id
        WHERE f.tenant_id=#{tenantId} AND f.employee_id=#{employeeId} AND f.effect_kind='LEAVE' AND f.effect_status='EFFECTIVE'
          AND r.status='APPROVED' AND d.leave_date=#{date} AND d.quantity_days>0 ORDER BY f.id
        """)
    List<LeaveEffect> leaveEffects(@Param("tenantId") UUID tenantId,@Param("employeeId") UUID employeeId,@Param("date") LocalDate date);
    record LeaveEffect(String id, double quantityDays, String recognitionRule, String payKind, String leavePart) {
        /** FULL 또는 합계 1일 이상이면 FULL, 반차 하나면 AM/PM, 없으면 null. */
        public static String part(List<LeaveEffect> effects) {
            if (effects.isEmpty()) return null;
            if (totalDays(effects) >= 1 || effects.stream().anyMatch(e -> "FULL".equals(e.leavePart()))) return "FULL";
            return effects.getFirst().leavePart();
        }
        public static double totalDays(List<LeaveEffect> effects) {
            return effects.stream().mapToDouble(LeaveEffect::quantityDays).sum();
        }
        public static double paidDays(List<LeaveEffect> effects) {
            return effects.stream().filter(effect -> !CompanyLeavePolicy.RULE.equals(effect.recognitionRule())
                            || "PAID".equals(effect.payKind()))
                    .mapToDouble(LeaveEffect::quantityDays).sum();
        }
    }
    @Select("SELECT id::text FROM tna.approval_request WHERE tenant_id=#{tenantId} AND target_employee_id=#{employeeId} AND kind='ATTENDANCE_CORRECTION' AND status='PENDING' AND from_date=#{date} ORDER BY id")
    List<String> pendingCorrections(@Param("tenantId") UUID tenantId,@Param("employeeId") UUID employeeId,@Param("date") LocalDate date);
    @Insert("INSERT INTO tna.calculation_run(tenant_id,id,employee_id,requested_by,from_date,to_date,expanded_from,expanded_to,engine_version) VALUES(#{tenantId},#{id},#{employeeId},#{actorId},#{from},#{to},#{expandedFrom},#{expandedTo},'CALC-2')")
    int insertRun(@Param("tenantId") UUID tenantId,@Param("id") UUID id,@Param("employeeId") UUID employeeId,
        @Param("actorId") UUID actorId,@Param("from") LocalDate from,@Param("to") LocalDate to,
        @Param("expandedFrom") LocalDate expandedFrom,@Param("expandedTo") LocalDate expandedTo);
    @Update("UPDATE tna.calculation_run SET status='COMPLETED',finished_at=CURRENT_TIMESTAMP WHERE tenant_id=#{tenantId} AND id=#{id} AND status='RUNNING'")
    int completeRun(@Param("tenantId") UUID tenantId,@Param("id") UUID id);
    @Select("SELECT COALESCE(max(result_revision),0)+1 FROM tna.day_result WHERE tenant_id=#{tenantId} AND employee_id=#{employeeId} AND work_date=#{date}")
    long nextResult(@Param("tenantId") UUID tenantId,@Param("employeeId") UUID employeeId,@Param("date") LocalDate date);
    @Insert("""
        INSERT INTO tna.day_result(tenant_id,id,run_id,employee_id,work_date,result_revision,input_revision,review_revision,
          source_fingerprint,policy_version_id,assessment_id,rule_version,status,scheduled_millis,presence_millis,actual_work_millis,
          confirmed_break_millis,outside_schedule_millis,over_8h_millis,night_work_millis,holiday_work_millis,legal_overtime_millis,
          leave_days,reason_codes,input_refs,late_millis,early_leave_millis,absence)
        VALUES(#{tenantId},#{id},#{runId},#{employeeId},#{r.workDate},#{revision},#{r.inputRevision},#{r.reviewRevision},
          #{source},#{policyId},#{assessmentId},'CALC-2',#{r.status},#{r.scheduledMillis},#{r.presenceMillis},#{r.actualWorkMillis},
          #{r.confirmedBreakMillis},#{r.outsideScheduleMillis},#{r.over8hMillis},#{r.nightWorkMillis},#{r.holidayWorkMillis},NULL,
          #{r.leaveDays},CAST(#{reasons} AS jsonb),CAST(#{refs} AS jsonb),
          #{r.attendance.lateMillis},#{r.attendance.earlyLeaveMillis},#{r.attendance.absence})
        """)
    int insertResult(@Param("tenantId") UUID tenantId,@Param("id") UUID id,@Param("runId") UUID runId,
        @Param("employeeId") UUID employeeId,@Param("revision") long revision,@Param("r") WorktimeCalculator.DayResult result,
        @Param("source") String source,@Param("policyId") UUID policyId,@Param("assessmentId") UUID assessmentId,
        @Param("reasons") String reasons,@Param("refs") String refs);
    @Select("""
        SELECT DISTINCT ON(work_date) id,run_id,employee_id,work_date,result_revision,input_revision,review_revision,
          source_fingerprint,rule_version,status,scheduled_millis,presence_millis,actual_work_millis,confirmed_break_millis,
          outside_schedule_millis,over_8h_millis,night_work_millis,holiday_work_millis,legal_overtime_millis,
          late_millis,early_leave_millis,absence,leave_days,
          coalesce((input_refs#>>'{0,paidLeaveDays}')::numeric,leave_days) AS paid_leave_days,
          input_refs#>>'{0,clock,dayKind}' AS saved_day_kind,reason_codes::text AS reason_codes,calculated_at
        FROM tna.day_result WHERE tenant_id=#{tenantId} AND employee_id=#{employeeId} AND work_date>=#{from} AND work_date<=#{to}
        ORDER BY work_date,result_revision DESC
        """)
    List<ResultRow> latest(@Param("tenantId") UUID tenantId,@Param("employeeId") UUID employeeId,@Param("from") LocalDate from,@Param("to") LocalDate to);
    @Select("""
        SELECT id,run_id,employee_id,work_date,result_revision,input_revision,review_revision,
          source_fingerprint,rule_version,status,scheduled_millis,presence_millis,actual_work_millis,confirmed_break_millis,
          outside_schedule_millis,over_8h_millis,night_work_millis,holiday_work_millis,legal_overtime_millis,
          late_millis,early_leave_millis,absence,leave_days,
          coalesce((input_refs#>>'{0,paidLeaveDays}')::numeric,leave_days) AS paid_leave_days,
          input_refs#>>'{0,clock,dayKind}' AS saved_day_kind,reason_codes::text AS reason_codes,calculated_at
        FROM tna.day_result WHERE tenant_id=#{tenantId} AND id=#{id}
          AND(employee_id=#{employeeId} OR #{administrator})
        """)
    ResultRow result(@Param("tenantId") UUID tenantId,@Param("id") UUID id,
        @Param("employeeId") UUID employeeId,@Param("administrator") boolean administrator);
    @Select("SELECT input_refs::text FROM tna.day_result WHERE tenant_id=#{tenantId} AND id=#{id}")
    String resultInput(@Param("tenantId") UUID tenantId,@Param("id") UUID id);
    @Select("""
        SELECT id,requested_by,from_date,to_date,expanded_from,expanded_to,engine_version,status,started_at,finished_at
        FROM tna.calculation_run WHERE tenant_id=#{tenantId} AND id=#{id} AND employee_id=#{employeeId}
        """)
    RunEvidence run(@Param("tenantId") UUID tenantId,@Param("id") UUID id,@Param("employeeId") UUID employeeId);
    @Insert("""
        INSERT INTO tna.day_input_revision(tenant_id,employee_id,work_date,input_revision,review_revision,recalculation_required)
        VALUES(#{tenantId},#{employeeId},#{date},#{input},#{review},false)
        ON CONFLICT(tenant_id,employee_id,work_date) DO UPDATE SET recalculation_required=false
        WHERE day_input_revision.tenant_id=#{tenantId} AND day_input_revision.input_revision=#{input} AND day_input_revision.review_revision=#{review}
        """)
    int calculated(@Param("tenantId") UUID tenantId,@Param("employeeId") UUID employeeId,@Param("date") LocalDate date,
        @Param("input") long input,@Param("review") long review);
    @Insert("INSERT INTO tna.report_snapshot(tenant_id,id,requested_by,employee_id,from_date,to_date,as_of,filters,result_refs,workbook) VALUES(#{tenantId},#{id},#{actorId},#{employeeId},#{from},#{to},#{asOf},CAST(#{filters} AS jsonb),CAST(#{refs} AS jsonb),#{workbook})")
    int insertSnapshot(@Param("tenantId") UUID tenantId,@Param("id") UUID id,@Param("actorId") UUID actorId,
        @Param("employeeId") UUID employeeId,@Param("from") LocalDate from,@Param("to") LocalDate to,
        @Param("asOf") Instant asOf,@Param("filters") String filters,@Param("refs") String refs,@Param("workbook") byte[] workbook);
    @Select("SELECT id,requested_by,employee_id,from_date,to_date,as_of,workbook FROM tna.report_snapshot WHERE tenant_id=#{tenantId} AND id=#{id}")
    SnapshotRow snapshot(@Param("tenantId") UUID tenantId,@Param("id") UUID id);
    @Select("SELECT id,actor_account_id,action,target_kind,target_id,reason,occurred_at AS at FROM tna.org_audit_event WHERE tenant_id=#{tenantId} ORDER BY occurred_at DESC,id LIMIT 100")
    List<AuditRow> audits(@Param("tenantId") UUID tenantId);
    @Select("SELECT id,actor_account_id,action,target_kind,target_id,reason,occurred_at AS at FROM tna.org_audit_event WHERE tenant_id=#{tenantId} AND id=#{id}")
    AuditRow auditCursor(@Param("tenantId") UUID tenantId,@Param("id") UUID id);
    @Select({"<script>",
        "SELECT id,actor_account_id,action,target_kind,target_id,reason,occurred_at AS at FROM tna.org_audit_event WHERE tenant_id=#{tenantId}",
        "<if test='cursorAt != null'>AND (occurred_at,id) &lt; (#{cursorAt},#{cursorId})</if>",
        "ORDER BY occurred_at DESC,id DESC LIMIT 51", "</script>"})
    List<AuditRow> auditOlder(@Param("tenantId") UUID tenantId,@Param("cursorAt") Instant cursorAt,@Param("cursorId") UUID cursorId);
    @Select("SELECT id,actor_account_id,action,target_kind,target_id,reason,occurred_at AS at FROM tna.org_audit_event WHERE tenant_id=#{tenantId} AND (occurred_at,id)>(#{cursorAt},#{cursorId}) ORDER BY occurred_at ASC,id ASC LIMIT 51")
    List<AuditRow> auditNewer(@Param("tenantId") UUID tenantId,@Param("cursorAt") Instant cursorAt,@Param("cursorId") UUID cursorId);
    @Select("SELECT EXISTS(SELECT 1 FROM tna.org_audit_event WHERE tenant_id=#{tenantId} AND (occurred_at,id)<(#{at},#{id}))")
    boolean auditHasOlder(@Param("tenantId") UUID tenantId,@Param("at") Instant at,@Param("id") UUID id);
    @Select("SELECT EXISTS(SELECT 1 FROM tna.org_audit_event WHERE tenant_id=#{tenantId} AND (occurred_at,id)>(#{at},#{id}))")
    boolean auditHasNewer(@Param("tenantId") UUID tenantId,@Param("at") Instant at,@Param("id") UUID id);
    @Select("""
        SELECT id,command_id,request_trace_id,actor_account_id,action,target_kind,target_id,reason,occurred_at AS at,
            before_state::text AS before_json,after_state::text AS after_json
        FROM tna.org_audit_event WHERE tenant_id=#{tenantId} AND id=#{id}
        """)
    AuditDetailRow auditDetailRow(@Param("tenantId") UUID tenantId,@Param("id") UUID id);
    record AuditDetailRow(UUID id,UUID commandId,UUID requestTraceId,UUID actorAccountId,String action,
            String targetKind,UUID targetId,String reason,Instant at,String beforeJson,String afterJson) { }
    record RequestTarget(UUID targetEmployeeId,LocalDate fromDate,LocalDate toDate) { }
    record ExportEvidence(String clockJson,String policyVersionId,String assessmentId) { }
    record ResultRow(UUID id,UUID runId,UUID employeeId,LocalDate workDate,long resultRevision,long inputRevision,
        long reviewRevision,String sourceFingerprint,String ruleVersion,String status,Long scheduledMillis,Long presenceMillis,
        Long actualWorkMillis,Long confirmedBreakMillis,Long outsideScheduleMillis,Long over8hMillis,Long nightWorkMillis,
        Long holidayWorkMillis,Long legalOvertimeMillis,Long lateMillis,Long earlyLeaveMillis,String absence,double leaveDays,double paidLeaveDays,String savedDayKind,String reasonCodes,Instant calculatedAt) { }
    record SnapshotRow(UUID id,UUID requestedBy,UUID employeeId,LocalDate fromDate,LocalDate toDate,Instant asOf,byte[] workbook) { }
}
