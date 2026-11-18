package kr.tna.request;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import kr.tna.request.RequestData.*;
import org.apache.ibatis.annotations.*;

@Mapper
public interface LeaveWorkflowMapper extends FormStepMapper {
    String REQUEST_SELECT="""
        SELECT r.id,r.target_employee_id,e.display_name AS target_employee_name,r.author_account_id,
          r.kind,r.status,r.current_approver_id,p.display_name AS approver_name,r.approver_assignment_id,
          r.approver_assignment_version,r.submitted_at,r.reason,r.predecessor_request_id,r.original_request_id,
          r.leave_bucket_id,r.from_date,r.to_date,r.exceptional,r.version,
          COALESCE(f.effect_status,'NONE') AS effect_status,
          COALESCE((SELECT sum(d.quantity_days) FROM tna.leave_request_day d WHERE d.tenant_id=r.tenant_id AND d.request_id=r.id),0)::integer AS quantity_days,
          r.route_snapshot_id,r.attendance_type_version_id,
          COALESCE(fm.code,CASE r.kind WHEN 'LEAVE_DAY' THEN 'L01' WHEN 'CANCEL_LEAVE' THEN 'L02' ELSE 'A01' END) AS form_code,
          COALESCE(fv.name,CASE r.kind WHEN 'LEAVE_DAY' THEN '휴가 신청서' WHEN 'CANCEL_LEAVE' THEN '휴가 취소 신청서' ELSE '출퇴근 정정 신청서' END) AS form_name,
          r.current_step,r.total_steps,(r.form_version_id IS NULL) AS legacy_form
        FROM tna.approval_request r
        LEFT JOIN tna.request_form_version fv ON fv.tenant_id=r.tenant_id AND fv.id=r.form_version_id
        LEFT JOIN tna.request_form fm ON fm.tenant_id=fv.tenant_id AND fm.id=fv.form_id
        JOIN tna.employee e ON e.tenant_id=r.tenant_id AND e.id=r.target_employee_id
        JOIN tna.employee p ON p.tenant_id=r.tenant_id AND p.id=r.current_approver_id
        LEFT JOIN tna.request_effect f ON f.tenant_id=r.tenant_id AND f.request_id=r.id
        """;
    String BUCKET_SELECT="""
        SELECT b.id,b.employee_id,e.display_name AS employee_name,b.base_date,b.usable_from,b.usable_to,
          b.source_reference,b.version,
          COALESCE((SELECT sum(l.signed_quantity_days) FROM tna.leave_ledger l WHERE l.tenant_id=b.tenant_id AND l.bucket_id=b.id),0)::integer AS balance_days,
          CASE WHEN b.base_date<=#{today} AND b.usable_from<=#{today} AND(b.usable_to IS NULL OR b.usable_to>=#{today}) THEN
            COALESCE((SELECT sum(l.signed_quantity_days) FROM tna.leave_ledger l WHERE l.tenant_id=b.tenant_id AND l.bucket_id=b.id),0) ELSE 0 END::integer AS available_days,
          COALESCE((SELECT sum(d.quantity_days) FROM tna.approval_request r JOIN tna.leave_request_day d ON d.tenant_id=r.tenant_id AND d.request_id=r.id
            WHERE r.tenant_id=b.tenant_id AND r.leave_bucket_id=b.id AND r.kind='LEAVE_DAY' AND r.status='PENDING'),0)::integer AS pending_days,
          COALESCE((SELECT count(*) FROM tna.leave_ledger l JOIN tna.request_effect f ON f.tenant_id=l.tenant_id AND f.id=l.request_effect_id
            WHERE l.tenant_id=b.tenant_id AND l.bucket_id=b.id AND l.entry_kind='USE' AND l.leave_date>#{today} AND f.effect_status='EFFECTIVE'),0)::integer AS future_approved_days
        FROM tna.leave_bucket b JOIN tna.employee e ON e.tenant_id=b.tenant_id AND e.id=b.employee_id
        """;
    @Select(BUCKET_SELECT+" WHERE b.tenant_id=#{tenantId} AND b.employee_id=#{employeeId} ORDER BY b.usable_to NULLS LAST,b.usable_from,b.id")
    List<Bucket> listBuckets(@Param("tenantId") UUID tenantId,@Param("employeeId") UUID employeeId,@Param("today") LocalDate today);
    @Select(BUCKET_SELECT+" WHERE b.tenant_id=#{tenantId} AND b.id=#{id}")
    Bucket findBucket(@Param("tenantId") UUID tenantId,@Param("id") UUID id,@Param("today") LocalDate today);
    @Insert("""
        INSERT INTO tna.leave_bucket(tenant_id,id,employee_id,base_date,usable_from,usable_to,source_reference,created_by)
        VALUES (#{tenantId},#{id},#{f.employeeId},#{f.baseDate},#{f.usableFrom},#{f.usableTo},#{f.sourceReference},#{actorId})
        """)
    int insertBucket(@Param("tenantId") UUID tenantId,@Param("id") UUID id,@Param("f") BucketForm form,@Param("actorId") UUID actorId);
    @Update("UPDATE tna.leave_bucket SET version=version+1 WHERE tenant_id=#{tenantId} AND id=#{id} AND version=#{version}")
    int advanceBucket(@Param("tenantId") UUID tenantId,@Param("id") UUID id,@Param("version") long version);
    @Select("SELECT id,bucket_id,employee_id,entry_kind,signed_quantity_days,effective_on,request_effect_id,leave_date,reverses_entry_id,source_reference,reason,created_at FROM tna.leave_ledger WHERE tenant_id=#{tenantId} AND bucket_id=#{bucketId} ORDER BY created_at,id")
    List<LedgerEntry> ledger(@Param("tenantId") UUID tenantId,@Param("bucketId") UUID bucketId);
    @Select("SELECT id,bucket_id,employee_id,entry_kind,signed_quantity_days,effective_on,request_effect_id,leave_date,reverses_entry_id,source_reference,reason,created_at FROM tna.leave_ledger WHERE tenant_id=#{tenantId} AND request_effect_id=#{effectId} AND entry_kind='USE' ORDER BY leave_date,id")
    List<LedgerEntry> uses(@Param("tenantId") UUID tenantId,@Param("effectId") UUID effectId);
    @Insert("""
        INSERT INTO tna.leave_ledger(tenant_id,id,bucket_id,employee_id,entry_kind,signed_quantity_days,effective_on,
            request_effect_id,leave_date,reverses_entry_id,source_reference,reason,created_by)
        VALUES(#{tenantId},#{id},#{bucketId},#{employeeId},#{kind},#{quantity},#{effectiveOn},#{effectId},#{leaveDate},#{reverses},#{source},#{reason},#{actorId})
        """)
    int insertLedger(@Param("tenantId") UUID tenantId,@Param("id") UUID id,@Param("bucketId") UUID bucketId,
        @Param("employeeId") UUID employeeId,@Param("kind") String kind,@Param("quantity") int quantity,
        @Param("effectiveOn") LocalDate effectiveOn,@Param("effectId") UUID effectId,@Param("leaveDate") LocalDate leaveDate,
        @Param("reverses") UUID reverses,@Param("source") String source,@Param("reason") String reason,@Param("actorId") UUID actorId);
    @Select(REQUEST_SELECT+" WHERE r.tenant_id=#{tenantId} AND r.id=#{id}")
    RequestView findRequest(@Param("tenantId") UUID tenantId,@Param("id") UUID id);
    @Select(REQUEST_SELECT+" WHERE r.tenant_id=#{tenantId} AND (#{mode}='ALL' OR (#{mode}='MINE' AND(r.author_account_id=#{accountId} OR r.target_employee_id=#{employeeId})) OR (#{mode}='INBOX' AND r.current_approver_id=#{employeeId})) ORDER BY r.submitted_at DESC,r.id")
    List<RequestView> requests(@Param("tenantId") UUID tenantId,@Param("accountId") UUID accountId,@Param("employeeId") UUID employeeId,@Param("mode") String mode);
    String SCOPE_FILTER = """
        AND (#{scope}='' OR (#{scope}='LEAVE' AND r.kind IN ('LEAVE_DAY','CANCEL_LEAVE'))
                         OR (#{scope}='CORRECTION' AND r.kind='ATTENDANCE_CORRECTION'))
        """;
    @Select(REQUEST_SELECT + " WHERE r.tenant_id=#{tenantId} " + SCOPE_FILTER + " ORDER BY r.submitted_at DESC,r.id")
    List<RequestView> adminRequests(@Param("tenantId") UUID tenantId, @Param("scope") String scope);
    @Select(REQUEST_SELECT+"""
        WHERE r.tenant_id=#{tenantId} AND (
          (NOT #{completed} AND r.current_approver_id=#{employeeId} AND r.status='PENDING') OR
          (#{completed} AND EXISTS(SELECT 1 FROM tna.approval_event ae
            WHERE ae.tenant_id=#{tenantId} AND ae.request_id=r.id AND ae.actor_account_id=#{accountId}
              AND ae.event_kind IN ('APPROVED','REJECTED','STEP_APPROVED'))))
        ORDER BY r.submitted_at DESC,r.id LIMIT 51 OFFSET #{offset}
        """)
    List<RequestView> approvalPage(@Param("tenantId") UUID tenantId,@Param("accountId") UUID accountId,
            @Param("employeeId") UUID employeeId,@Param("completed") boolean completed,@Param("offset") int offset);
    @Select("SELECT pg_advisory_xact_lock_shared(hashtextextended(CAST(#{tenantId} AS text),0))")
    Object readLock(@Param("tenantId") UUID tenantId);
    @Insert("""
        INSERT INTO tna.approval_request(tenant_id,id,target_employee_id,author_account_id,kind,status,current_approver_id,
          approver_assignment_id,approver_assignment_version,submitted_at,reason,predecessor_request_id,original_request_id,
          leave_bucket_id,from_date,to_date,exceptional,client_command_id,route_snapshot_id,attendance_type_version_id,form_version_id,total_steps)
        VALUES(#{tenantId},#{r.id},#{r.targetEmployeeId},#{r.authorAccountId},#{r.kind},'PENDING',#{r.currentApproverId},
          #{r.approverAssignmentId},#{r.approverAssignmentVersion},#{r.submittedAt},#{r.reason},#{r.predecessorRequestId},
          #{r.originalRequestId},#{r.leaveBucketId},#{r.fromDate},#{r.toDate},#{r.exceptional},#{commandId},#{r.routeSnapshotId},#{r.attendanceTypeVersionId},#{formVersionId},#{totalSteps})
        """)
    int insertRequest(@Param("tenantId") UUID tenantId,@Param("r") RequestView request,@Param("commandId") UUID commandId,@Param("formVersionId") UUID formVersionId,@Param("totalSteps") int totalSteps);
    @Update("UPDATE tna.approval_request SET status=#{status},version=version+1 WHERE tenant_id=#{tenantId} AND id=#{id} AND status='PENDING' AND version=#{version}")
    int transition(@Param("tenantId") UUID tenantId,@Param("id") UUID id,@Param("version") long version,@Param("status") String status);
    @Update("UPDATE tna.approval_request SET current_approver_id=#{approverId},version=version+1 WHERE tenant_id=#{tenantId} AND id=#{id} AND status='PENDING' AND version=#{version}")
    int reassign(@Param("tenantId") UUID tenantId,@Param("id") UUID id,@Param("version") long version,@Param("approverId") UUID approverId);
    @Select("SELECT leave_date,quantity_days,schedule_version_id,calendar_version_id,zero_reason FROM tna.leave_request_day WHERE tenant_id=#{tenantId} AND request_id=#{requestId} ORDER BY leave_date")
    List<RequestDay> requestDays(@Param("tenantId") UUID tenantId,@Param("requestId") UUID requestId);
    @Insert("INSERT INTO tna.leave_request_day(tenant_id,id,request_id,leave_date,quantity_days,schedule_version_id,calendar_version_id,zero_reason) VALUES(#{tenantId},#{id},#{requestId},#{d.leaveDate},#{d.quantityDays},#{d.scheduleVersionId},#{d.calendarVersionId},#{d.zeroReason})")
    int insertDay(@Param("tenantId") UUID tenantId,@Param("id") UUID id,@Param("requestId") UUID requestId,@Param("d") RequestDay day);
    @Select("""
        SELECT EXISTS(SELECT 1 FROM tna.approval_request r JOIN tna.leave_request_day d ON d.tenant_id=r.tenant_id AND d.request_id=r.id
          LEFT JOIN tna.request_effect f ON f.tenant_id=r.tenant_id AND f.request_id=r.id
          WHERE r.tenant_id=#{tenantId} AND r.target_employee_id=#{employeeId} AND r.kind='LEAVE_DAY' AND d.leave_date=#{date}
            AND d.quantity_days=1 AND (#{exceptId}::uuid IS NULL OR r.id<>#{exceptId})
            AND(r.status='PENDING' OR (r.status='APPROVED' AND f.effect_status='EFFECTIVE')))
        """)
    boolean overlappingLeave(@Param("tenantId") UUID tenantId,@Param("employeeId") UUID employeeId,@Param("date") LocalDate date,@Param("exceptId") UUID exceptId);
    @Select("SELECT EXISTS(SELECT 1 FROM tna.approval_request WHERE tenant_id=#{tenantId} AND original_request_id=#{id} AND kind='CANCEL_LEAVE' AND status='PENDING')")
    boolean pendingCancellation(@Param("tenantId") UUID tenantId,@Param("id") UUID id);
    @Select("SELECT EXISTS(SELECT 1 FROM tna.approval_request WHERE tenant_id=#{tenantId} AND target_employee_id=#{employeeId} AND kind='ATTENDANCE_CORRECTION' AND from_date=#{date} AND status='PENDING')")
    boolean pendingCorrection(@Param("tenantId") UUID tenantId,@Param("employeeId") UUID employeeId,@Param("date") LocalDate date);
    @Select("""
        SELECT id FROM tna.approval_request
        WHERE tenant_id=#{tenantId} AND target_employee_id=#{employeeId}
          AND kind='ATTENDANCE_CORRECTION' AND from_date=#{date} AND status='PENDING'
        ORDER BY submitted_at DESC,id LIMIT 1
        """)
    UUID pendingCorrectionId(@Param("tenantId") UUID tenantId,@Param("employeeId") UUID employeeId,@Param("date") LocalDate date);
    @Select("SELECT work_date,base_input_revision,source_fingerprint,in_action,out_action,requested_in_at,requested_out_at,presence_as_work,breaks_confirmed,actual_breaks::text AS actual_breaks,replaces_request_id,before_snapshot::text AS before_snapshot FROM tna.correction_detail WHERE tenant_id=#{tenantId} AND request_id=#{id}")
    CorrectionDetail correction(@Param("tenantId") UUID tenantId,@Param("id") UUID id);
    @Insert("""
        INSERT INTO tna.correction_detail(tenant_id,id,request_id,work_date,base_input_revision,source_fingerprint,in_action,out_action,
            requested_in_at,requested_out_at,presence_as_work,breaks_confirmed,actual_breaks,replaces_request_id,before_snapshot)
        VALUES(#{tenantId},#{id},#{requestId},#{d.workDate},#{d.baseInputRevision},#{d.sourceFingerprint},#{d.inAction},#{d.outAction},
            #{d.requestedInAt},#{d.requestedOutAt},#{d.presenceAsWork},#{d.breaksConfirmed},CAST(#{d.actualBreaks} AS jsonb),#{d.replacesRequestId},CAST(#{d.beforeSnapshot} AS jsonb))
        """)
    int insertCorrection(@Param("tenantId") UUID tenantId,@Param("id") UUID id,@Param("requestId") UUID requestId,@Param("d") CorrectionDetail detail);
    @Select("SELECT id,request_id,employee_id,work_date,effect_kind,effect_status,validated_input_refs::text AS validated_input_refs FROM tna.request_effect WHERE tenant_id=#{tenantId} AND request_id=#{requestId}")
    Effect requestEffect(@Param("tenantId") UUID tenantId,@Param("requestId") UUID requestId);
    @Select("SELECT id,request_id,employee_id,work_date,effect_kind,effect_status,validated_input_refs::text AS validated_input_refs FROM tna.request_effect WHERE tenant_id=#{tenantId} AND employee_id=#{employeeId} AND work_date=#{date} AND effect_kind='CORRECTION' AND effect_status='EFFECTIVE'")
    Effect currentCorrection(@Param("tenantId") UUID tenantId,@Param("employeeId") UUID employeeId,@Param("date") LocalDate date);
    @Insert("""
        INSERT INTO tna.request_effect(tenant_id,id,request_id,employee_id,work_date,effect_kind,effect_status,
          validated_input_refs,reverses_effect_id,supersedes_effect_id,created_by)
        VALUES(#{tenantId},#{id},#{requestId},#{employeeId},#{date},#{kind},'EFFECTIVE',CAST(#{refs} AS jsonb),#{reverses},#{supersedes},#{actorId})
        """)
    int insertEffect(@Param("tenantId") UUID tenantId,@Param("id") UUID id,@Param("requestId") UUID requestId,
        @Param("employeeId") UUID employeeId,@Param("date") LocalDate date,@Param("kind") String kind,@Param("refs") String refs,
        @Param("reverses") UUID reverses,@Param("supersedes") UUID supersedes,@Param("actorId") UUID actorId);
    @Update("UPDATE tna.request_effect SET effect_status=#{status} WHERE tenant_id=#{tenantId} AND id=#{id} AND effect_status='EFFECTIVE'")
    int endEffect(@Param("tenantId") UUID tenantId,@Param("id") UUID id,@Param("status") String status);
    @Select("SELECT event_kind,actor_account_id,from_status,to_status,reason,previous_approver_id,new_approver_id,created_at,step_number FROM tna.approval_event WHERE tenant_id=#{tenantId} AND request_id=#{requestId} ORDER BY created_at,id")
    List<ApprovalHistory> history(@Param("tenantId") UUID tenantId,@Param("requestId") UUID requestId);
    @Insert("INSERT INTO tna.approval_event(tenant_id,id,request_id,event_kind,actor_account_id,from_status,to_status,reason,previous_approver_id,new_approver_id,step_number) VALUES(#{tenantId},#{id},#{requestId},#{kind},#{actorId},#{from},#{to},#{reason},#{previous},#{next},#{step})")
    int insertHistory(@Param("tenantId") UUID tenantId,@Param("id") UUID id,@Param("requestId") UUID requestId,
        @Param("kind") String kind,@Param("actorId") UUID actorId,@Param("from") String from,@Param("to") String to,
        @Param("reason") String reason,@Param("previous") UUID previous,@Param("next") UUID next,@Param("step") Integer step);
    @Select("SELECT id AS assignment_id,version AS assignment_version,approver_employee_id FROM tna.approver_assignment WHERE tenant_id=#{tenantId} AND employee_id=#{employeeId} AND superseded_at IS NULL AND valid_from<=#{today} AND(valid_to IS NULL OR valid_to>#{today})")
    ApproverBinding binding(@Param("tenantId") UUID tenantId,@Param("employeeId") UUID employeeId,@Param("today") LocalDate today);
    @Select("""
        SELECT a.id AS assignment_id,a.version AS assignment_version,a.organization_id,o.active AS organization_active
        FROM tna.employee_assignment a JOIN tna.organization o ON o.tenant_id=a.tenant_id AND o.id=a.organization_id
        WHERE a.tenant_id=#{tenantId} AND a.employee_id=#{employeeId}
          AND a.valid_from<=#{today} AND(a.valid_to IS NULL OR a.valid_to>#{today})
        """)
    RouteOrganization routeOrganization(@Param("tenantId") UUID tenantId,@Param("employeeId") UUID employeeId,@Param("today") LocalDate today);
    @Select("""
        SELECT a.id AS assignment_id,a.version AS assignment_version,a.scope_kind,v.id AS route_version_id,
          r.code AS route_code,v.name AS route_name,v.revision,v.approver_kind,v.employee_id,r.active AS route_active
        FROM tna.approval_route_assignment a
        JOIN tna.approval_route_version v ON v.tenant_id=a.tenant_id AND v.id=a.route_version_id
        JOIN tna.approval_route r ON r.tenant_id=v.tenant_id AND r.id=v.route_id
        WHERE a.tenant_id=#{tenantId} AND a.scope_kind=#{scopeKind}
          AND((#{scopeKind}='EMPLOYEE' AND a.employee_id=#{employeeId})
            OR(#{scopeKind}='ORGANIZATION' AND a.organization_id=#{organizationId}))
          AND a.superseded_at IS NULL AND a.valid_from<=#{today} AND(a.valid_to IS NULL OR a.valid_to>#{today})
        """)
    RouteBinding routeBinding(@Param("tenantId") UUID tenantId,@Param("scopeKind") String scopeKind,
            @Param("employeeId") UUID employeeId,@Param("organizationId") UUID organizationId,@Param("today") LocalDate today);
    @Select("""
        SELECT id,version,employee_id FROM tna.organization_head_assignment
        WHERE tenant_id=#{tenantId} AND organization_id=#{organizationId} AND superseded_at IS NULL
          AND valid_from<=#{today} AND(valid_to IS NULL OR valid_to>#{today})
        """)
    RouteHead routeHead(@Param("tenantId") UUID tenantId,@Param("organizationId") UUID organizationId,@Param("today") LocalDate today);
    @Insert("""
        INSERT INTO tna.approval_route_snapshot(tenant_id,id,request_id,route_version_id,assignment_id,assignment_version,
          organization_id,organization_assignment_id,organization_assignment_version,organization_head_assignment_id,
          organization_head_assignment_version,resolved_approver_employee_id,resolved_at)
        VALUES(#{tenantId},#{id},#{requestId},#{routeVersionId},#{assignmentId},#{assignmentVersion},
          #{organizationId},#{organizationAssignmentId},#{organizationAssignmentVersion},#{headAssignmentId},
          #{headAssignmentVersion},#{approverId},#{at})
        """)
    int insertRouteSnapshot(@Param("tenantId") UUID tenantId,@Param("id") UUID id,@Param("requestId") UUID requestId,
            @Param("routeVersionId") UUID routeVersionId,@Param("assignmentId") UUID assignmentId,
            @Param("assignmentVersion") long assignmentVersion,@Param("organizationId") UUID organizationId,
            @Param("organizationAssignmentId") UUID organizationAssignmentId,@Param("organizationAssignmentVersion") Long organizationAssignmentVersion,
            @Param("headAssignmentId") UUID headAssignmentId,@Param("headAssignmentVersion") Long headAssignmentVersion,
            @Param("approverId") UUID approverId,@Param("at") Instant at);
    @Select("""
        SELECT r.code AS route_code,v.name AS route_name,v.revision,COALESCE(a.scope_kind,'FORM') AS scope_kind,v.approver_kind,
          o.name AS organization_name,e.display_name AS resolved_approver_name,s.resolved_at
        FROM tna.approval_route_snapshot s
        JOIN tna.approval_route_version v ON v.tenant_id=s.tenant_id AND v.id=s.route_version_id
        JOIN tna.approval_route r ON r.tenant_id=v.tenant_id AND r.id=v.route_id
        LEFT JOIN tna.approval_route_assignment a ON a.tenant_id=s.tenant_id AND a.id=s.assignment_id
        JOIN tna.employee e ON e.tenant_id=s.tenant_id AND e.id=s.resolved_approver_employee_id
        LEFT JOIN tna.organization o ON o.tenant_id=s.tenant_id AND o.id=s.organization_id
        WHERE s.tenant_id=#{tenantId} AND s.request_id=#{requestId}
        """)
    RouteSnapshot routeSnapshot(@Param("tenantId") UUID tenantId,@Param("requestId") UUID requestId);
    String ATTENDANCE_POLICY_SELECT="""
        SELECT v.id,v.type_id,t.code,t.system_kind,v.revision,v.effective_from,v.enabled,v.name,
          v.unit,v.pay_kind,v.deduction_rule,v.recognition_rule,v.basis
        FROM tna.attendance_type_version v
        JOIN tna.attendance_type t ON t.tenant_id=v.tenant_id AND t.id=v.type_id
        """;
    @Select(ATTENDANCE_POLICY_SELECT+" WHERE v.tenant_id=#{tenantId} AND v.id=#{id}")
    AttendancePolicy attendancePolicy(@Param("tenantId") UUID tenantId,@Param("id") UUID id);
    @Select(ATTENDANCE_POLICY_SELECT+"""
        WHERE v.tenant_id=#{tenantId} AND t.system_kind=#{systemKind} AND v.effective_from<=#{date}
        ORDER BY v.effective_from DESC,v.revision DESC LIMIT 1
        """)
    AttendancePolicy effectiveAttendancePolicy(@Param("tenantId") UUID tenantId,@Param("systemKind") String systemKind,@Param("date") LocalDate date);
    @Select("""
        SELECT EXISTS(SELECT 1 FROM tna.attendance_type_version
          WHERE tenant_id=#{tenantId} AND type_id=#{typeId} AND effective_from>#{from} AND effective_from<=#{to})
        """)
    boolean attendancePolicyBoundary(@Param("tenantId") UUID tenantId,@Param("typeId") UUID typeId,
            @Param("from") LocalDate from,@Param("to") LocalDate to);
    @Insert("""
        INSERT INTO tna.day_input_revision(tenant_id,employee_id,work_date,input_revision,review_revision,recalculation_required)
        VALUES(#{tenantId},#{employeeId},#{date},#{factDelta},1,true)
        ON CONFLICT(tenant_id,employee_id,work_date) DO UPDATE SET
          input_revision=day_input_revision.input_revision+#{factDelta},review_revision=day_input_revision.review_revision+1,recalculation_required=true
        WHERE day_input_revision.tenant_id=#{tenantId}
        """)
    int reviseDay(@Param("tenantId") UUID tenantId,@Param("employeeId") UUID employeeId,@Param("date") LocalDate date,@Param("factDelta") int factDelta);
    @Select("SELECT employee_id::text FROM tna.tenant_account WHERE tenant_id=#{tenantId} AND id=#{accountId}")
    String authorEmployee(@Param("tenantId") UUID tenantId,@Param("accountId") UUID accountId);
    record ApproverBinding(UUID assignmentId,long assignmentVersion,UUID approverEmployeeId) { }
    record RouteOrganization(UUID assignmentId,long assignmentVersion,UUID organizationId,boolean organizationActive) { }
    record RouteBinding(UUID assignmentId,long assignmentVersion,String scopeKind,UUID routeVersionId,String routeCode,
            String routeName,long revision,String approverKind,UUID employeeId,boolean routeActive) { }
    record RouteHead(UUID id,long version,UUID employeeId) { }
    record Effect(UUID id,UUID requestId,UUID employeeId,LocalDate workDate,String effectKind,String effectStatus,String validatedInputRefs) { }
}
