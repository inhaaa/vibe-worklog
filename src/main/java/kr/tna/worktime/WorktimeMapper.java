package kr.tna.worktime;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import kr.tna.worktime.WorktimeData.*;
import org.apache.ibatis.annotations.*;

@Mapper
public interface WorktimeMapper {
    @Select("SELECT pg_advisory_xact_lock_shared(hashtextextended(CAST(#{tenantId} AS text),0))")
    Object lock(@Param("tenantId") UUID tenantId);
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
        SELECT f.id::text AS id, d.quantity_days FROM tna.request_effect f
        JOIN tna.approval_request r ON r.tenant_id=f.tenant_id AND r.id=f.request_id
        JOIN tna.leave_request_day d ON d.tenant_id=r.tenant_id AND d.request_id=r.id
        WHERE f.tenant_id=#{tenantId} AND f.employee_id=#{employeeId} AND f.effect_kind='LEAVE' AND f.effect_status='EFFECTIVE'
          AND r.status='APPROVED' AND d.leave_date=#{date} AND d.quantity_days>0 ORDER BY f.id
        """)
    List<LeaveEffect> leaveEffects(@Param("tenantId") UUID tenantId,@Param("employeeId") UUID employeeId,@Param("date") LocalDate date);
    record LeaveEffect(String id, double quantityDays) {
        public static double totalDays(List<LeaveEffect> effects) {
            return effects.stream().mapToDouble(LeaveEffect::quantityDays).sum();
        }
    }
    @Select("SELECT id::text FROM tna.approval_request WHERE tenant_id=#{tenantId} AND target_employee_id=#{employeeId} AND kind='ATTENDANCE_CORRECTION' AND status='PENDING' AND from_date=#{date} ORDER BY id")
    List<String> pendingCorrections(@Param("tenantId") UUID tenantId,@Param("employeeId") UUID employeeId,@Param("date") LocalDate date);
    @Insert("INSERT INTO tna.calculation_run(tenant_id,id,employee_id,requested_by,from_date,to_date,expanded_from,expanded_to,engine_version) VALUES(#{tenantId},#{id},#{employeeId},#{actorId},#{from},#{to},#{expandedFrom},#{expandedTo},'CALC-1')")
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
          leave_days,reason_codes,input_refs)
        VALUES(#{tenantId},#{id},#{runId},#{employeeId},#{r.workDate},#{revision},#{r.inputRevision},#{r.reviewRevision},
          #{source},#{policyId},#{assessmentId},'CALC-1',#{r.status},#{r.scheduledMillis},#{r.presenceMillis},#{r.actualWorkMillis},
          #{r.confirmedBreakMillis},#{r.outsideScheduleMillis},#{r.over8hMillis},#{r.nightWorkMillis},#{r.holidayWorkMillis},NULL,
          #{r.leaveDays},CAST(#{reasons} AS jsonb),CAST(#{refs} AS jsonb))
        """)
    int insertResult(@Param("tenantId") UUID tenantId,@Param("id") UUID id,@Param("runId") UUID runId,
        @Param("employeeId") UUID employeeId,@Param("revision") long revision,@Param("r") WorktimeCalculator.DayResult result,
        @Param("source") String source,@Param("policyId") UUID policyId,@Param("assessmentId") UUID assessmentId,
        @Param("reasons") String reasons,@Param("refs") String refs);
    @Select("""
        SELECT DISTINCT ON(work_date) id,run_id,employee_id,work_date,result_revision,input_revision,review_revision,
          source_fingerprint,rule_version,status,scheduled_millis,presence_millis,actual_work_millis,confirmed_break_millis,
          outside_schedule_millis,over_8h_millis,night_work_millis,holiday_work_millis,legal_overtime_millis,leave_days,
          reason_codes::text AS reason_codes,calculated_at
        FROM tna.day_result WHERE tenant_id=#{tenantId} AND employee_id=#{employeeId} AND work_date>=#{from} AND work_date<=#{to}
        ORDER BY work_date,result_revision DESC
        """)
    List<ResultRow> latest(@Param("tenantId") UUID tenantId,@Param("employeeId") UUID employeeId,@Param("from") LocalDate from,@Param("to") LocalDate to);
    @Select("""
        SELECT id,run_id,employee_id,work_date,result_revision,input_revision,review_revision,
          source_fingerprint,rule_version,status,scheduled_millis,presence_millis,actual_work_millis,confirmed_break_millis,
          outside_schedule_millis,over_8h_millis,night_work_millis,holiday_work_millis,legal_overtime_millis,leave_days,
          reason_codes::text AS reason_codes,calculated_at
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
    record ExportEvidence(String clockJson,String policyVersionId,String assessmentId) { }
    record ResultRow(UUID id,UUID runId,UUID employeeId,LocalDate workDate,long resultRevision,long inputRevision,
        long reviewRevision,String sourceFingerprint,String ruleVersion,String status,Long scheduledMillis,Long presenceMillis,
        Long actualWorkMillis,Long confirmedBreakMillis,Long outsideScheduleMillis,Long over8hMillis,Long nightWorkMillis,
        Long holidayWorkMillis,Long legalOvertimeMillis,double leaveDays,String reasonCodes,Instant calculatedAt) { }
    record SnapshotRow(UUID id,UUID requestedBy,UUID employeeId,LocalDate fromDate,LocalDate toDate,Instant asOf,byte[] workbook) { }
}
