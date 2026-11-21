package kr.tna.worktime;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.UUID;
import kr.tna.clock.ClockMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.cursor.Cursor;

@Mapper
public interface MonthEndReviewMapper extends ClosedAttendanceMonths {
    interface DayEvidence {
        UUID employeeId();
        LocalDate workDate();
        long inputRevision();
        long reviewRevision();
        UUID scheduleVersionId();
        Boolean workday();
        LocalTime startTime();
        LocalTime endTime();
        String breaks();
        String workMode();
        LocalTime coreStart();
        LocalTime coreEnd();
        Integer standardMinutes();
        String settlementPeriod();
        UUID calendarVersionId();
        String dayKind();
        String events();
        String correction();
        String confirmations();
    }

    record Candidate(UUID employeeId, String employeeNo, String employeeName, String organizationName, UUID organizationId,
            LocalDate joinedOn, LocalDate leftOn, LocalDate workDate, UUID resultId, String storedSource,
            Long storedInput, Long storedReview, long inputRevision, long reviewRevision,
            UUID scheduleVersionId, Boolean workday, LocalTime startTime, LocalTime endTime, String breaks,
            String workMode, LocalTime coreStart, LocalTime coreEnd, Integer standardMinutes, String settlementPeriod,
            UUID calendarVersionId, String dayKind, UUID policyId, UUID assessmentId, boolean reviewed,
            String events, String correction, String confirmations, String leaveEffects, UUID pendingRequestId, boolean openCheckoutFollowUp) implements DayEvidence { }

    // The month list and follow-up inbox use the same effective clock and confirmation evidence.
    String DAY_EVIDENCE_JOINS = """
            LEFT JOIN LATERAL (
                SELECT sd.version_id,sd.workday,sd.start_time,sd.end_time,sp.*,
                    (SELECT jsonb_agg(jsonb_build_object('startTime',b.start_time,'endTime',b.end_time) ORDER BY b.sequence)::text
                     FROM tna.schedule_break b WHERE b.tenant_id=sd.tenant_id AND b.version_id=sd.version_id AND b.weekday=sd.weekday) AS breaks
                FROM tna.effective_schedule_assignment a JOIN tna.schedule_version v ON v.tenant_id=a.tenant_id AND v.id=a.schedule_version_id
                JOIN tna.schedule_day sd ON sd.tenant_id=v.tenant_id AND sd.version_id=v.id AND sd.weekday=extract(isodow FROM d.work_date)
                CROSS JOIN LATERAL (SELECT
            """ + ClockMapper.SELECTIVE_POLICY_COLUMNS + """
                    FROM (SELECT sd.tenant_id,sd.version_id,sd.weekday,sd.workday) d
            """ + ClockMapper.SELECTIVE_POLICY_JOINS + """
                ) sp
                WHERE a.tenant_id=#{tenantId} AND a.employee_id=d.employee_id AND v.valid_from<=d.work_date
                  AND a.valid_from<=d.work_date AND (a.valid_to IS NULL OR a.valid_to>d.work_date) LIMIT 1
            ) s ON true
            LEFT JOIN LATERAL (
                SELECT cd.version_id,cd.kind FROM tna.calendar_day cd JOIN tna.calendar_version v
                  ON v.tenant_id=cd.tenant_id AND v.id=cd.version_id AND v.valid_from<=d.work_date AND v.valid_to>d.work_date
                WHERE cd.tenant_id=#{tenantId} AND cd.work_date=d.work_date LIMIT 1
            ) cal ON true
            LEFT JOIN LATERAL (
                SELECT input_revision,review_revision FROM tna.day_input_revision
                WHERE tenant_id=#{tenantId} AND employee_id=d.employee_id AND work_date=d.work_date LIMIT 1
            ) rev ON true
            LEFT JOIN LATERAL (
                SELECT jsonb_agg(jsonb_build_object('id',v.id,'kind',v.event_kind,'occurredAt',v.occurred_at,
                    'workDate',v.assigned_work_date) ORDER BY v.occurred_at,v.id)::text AS events
                FROM tna.attendance_event v WHERE v.tenant_id=#{tenantId} AND v.employee_id=d.employee_id AND v.assigned_work_date=d.work_date
            ) rec ON true
            LEFT JOIN LATERAL (
                SELECT f.request_id,jsonb_build_object('effectId',f.id,'requestId',f.request_id,'inAction',c.in_action,'outAction',c.out_action,
                    'requestedInAt',c.requested_in_at,'requestedOutAt',c.requested_out_at,'beforeSnapshot',c.before_snapshot::text,
                    'validatedInputRefs',f.validated_input_refs::text,'effectiveAt',f.effective_at)::text AS correction
                FROM tna.request_effect f
                JOIN tna.approval_request ar ON ar.tenant_id=f.tenant_id AND ar.id=f.request_id AND ar.target_employee_id=f.employee_id
                JOIN tna.correction_detail c ON c.tenant_id=f.tenant_id AND c.request_id=f.request_id AND c.work_date=f.work_date
                WHERE f.tenant_id=#{tenantId} AND f.employee_id=d.employee_id AND f.work_date=d.work_date
                  AND f.effect_kind='CORRECTION' AND f.effect_status='EFFECTIVE' AND ar.kind='ATTENDANCE_CORRECTION' AND ar.status='APPROVED'
                LIMIT 1
            ) cor ON true
            LEFT JOIN LATERAL (
                SELECT jsonb_agg(jsonb_build_object('id',w.id,'confirmedInputRevision',w.confirmed_input_revision,
                    'reviewRevision',w.review_revision,'presenceAsWork',w.presence_as_work,'breaksMatch',w.breaks_match,
                    'sourceFingerprint',w.source_fingerprint,'breaks',w.break_snapshot,'confirmedAt',w.confirmed_at)
                    ORDER BY w.review_revision DESC,w.id DESC)::text AS confirmations
                FROM tna.work_confirmation w WHERE w.tenant_id=#{tenantId} AND w.employee_id=d.employee_id AND w.work_date=d.work_date
                    AND w.confirmed_input_revision=coalesce(rev.input_revision,0)
            ) conf ON true
            """;

    String SAME_DAY_APPROVED_LEAVE = """
                FROM tna.request_effect f JOIN tna.approval_request ar ON ar.tenant_id=f.tenant_id AND ar.id=f.request_id
                JOIN tna.leave_request_day ld ON ld.tenant_id=ar.tenant_id AND ld.request_id=ar.id
                LEFT JOIN tna.attendance_type_version lv ON lv.tenant_id=ar.tenant_id AND lv.id=ar.attendance_type_version_id
                WHERE f.tenant_id=#{tenantId} AND f.employee_id=d.employee_id AND f.effect_kind='LEAVE' AND f.effect_status='EFFECTIVE'
                  AND ar.status='APPROVED' AND ld.leave_date=d.work_date AND ld.quantity_days>0
                """;

    String LEAVE_AND_POLICY_JOINS = """
            LEFT JOIN LATERAL (
                SELECT jsonb_agg(jsonb_build_object('id',f.id,'quantityDays',ld.quantity_days,
                    'recognitionRule',lv.recognition_rule,'payKind',lv.pay_kind) ORDER BY f.id)::text AS effects
            """ + SAME_DAY_APPROVED_LEAVE + """
            ) l ON true
            LEFT JOIN LATERAL (SELECT id,monday_week_reviewed FROM tna.work_policy_version WHERE tenant_id=#{tenantId}
                AND valid_from<=d.work_date AND (valid_to IS NULL OR valid_to>d.work_date) ORDER BY revision DESC LIMIT 1) policy ON true
            LEFT JOIN LATERAL (SELECT id,general_rule_reviewed FROM tna.employment_rule_assessment
                WHERE tenant_id=#{tenantId} AND employee_id=d.employee_id AND valid_from<=d.work_date
                  AND (valid_to IS NULL OR valid_to>d.work_date) ORDER BY revision DESC LIMIT 1) assessment ON true
            """;

    // Each source is scoped to the company and month. The cursor keeps one sorted stream for all employees.
    @Options(fetchSize = 128)
    @Select("""
            WITH dates AS (
                SELECT #{from}::date + n AS work_date FROM generate_series(0,30) n
                WHERE #{from}::date + n <= #{through}
            ), selected AS MATERIALIZED (
                SELECT e.id AS employee_id,e.employee_no,e.display_name AS employee_name,o.name AS organization_name,o.id AS organization_id,
                       e.joined_on,e.left_on,d.work_date,r.id AS result_id,r.source_fingerprint AS stored_source,
                       r.input_revision AS stored_input,r.review_revision AS stored_review,p.id AS pending_request_id,fu.id IS NOT NULL AS open_checkout_follow_up
                FROM tna.employee e JOIN dates d ON d.work_date>=e.joined_on AND (e.left_on IS NULL OR d.work_date<=e.left_on)
                LEFT JOIN tna.employee_assignment a ON a.tenant_id=e.tenant_id AND a.employee_id=e.id
                    AND a.valid_from<=d.work_date AND (a.valid_to IS NULL OR a.valid_to>d.work_date)
                LEFT JOIN tna.organization o ON o.tenant_id=a.tenant_id AND o.id=a.organization_id
                LEFT JOIN LATERAL (
                    SELECT id,source_fingerprint,input_revision,review_revision FROM tna.day_result
                    WHERE tenant_id=e.tenant_id AND employee_id=e.id AND work_date=d.work_date ORDER BY result_revision DESC LIMIT 1
                ) r ON true
                LEFT JOIN tna.approval_request p ON p.tenant_id=e.tenant_id AND p.target_employee_id=e.id
                    AND p.from_date=d.work_date AND p.kind='ATTENDANCE_CORRECTION' AND p.status='PENDING'
                LEFT JOIN tna.checkout_follow_up fu ON fu.tenant_id=e.tenant_id AND fu.employee_id=e.id
                    AND fu.work_date=d.work_date AND fu.resolved_at IS NULL
                WHERE e.tenant_id=#{tenantId} AND (#{organizationId}::uuid IS NULL OR a.organization_id=#{organizationId})
                  AND (#{q}='' OR strpos(lower(e.employee_no),lower(#{q}))>0 OR strpos(lower(e.display_name),lower(#{q}))>0)
                  AND (#{reason}<>'PENDING_CORRECTION' OR p.id IS NOT NULL)
                  AND (#{reason}<>'UNCALCULATED' OR r.id IS NULL) AND (#{reason}<>'STALE' OR r.id IS NOT NULL)
                ORDER BY d.work_date DESC,e.employee_no,e.id
            )
            SELECT d.employee_id,d.employee_no,d.employee_name,d.organization_name,d.organization_id,d.joined_on,d.left_on,
                d.work_date,d.result_id,d.stored_source,d.stored_input,d.stored_review,
                coalesce(rev.input_revision,0) AS input_revision,coalesce(rev.review_revision,0) AS review_revision,
                s.version_id AS schedule_version_id,s.workday,s.start_time,s.end_time,s.breaks,
                s.work_mode,s.core_start,s.core_end,s.standard_minutes,s.settlement_period,
                cal.version_id AS calendar_version_id,cal.kind AS day_kind,
                policy.id AS policy_id,assessment.id AS assessment_id,coalesce(assessment.general_rule_reviewed,false) AS reviewed,
                rec.events,cor.correction,conf.confirmations,l.effects AS leave_effects,d.pending_request_id,d.open_checkout_follow_up
            FROM selected d
            """ + DAY_EVIDENCE_JOINS + LEAVE_AND_POLICY_JOINS + """
            -- 근거가 확정된 휴무일은 저장된 결과나 출퇴근·정정 자료가 있을 때만 점검한다.
            WHERE s.workday IS DISTINCT FROM false OR s.breaks IS NOT NULL OR cal.version_id IS NULL OR d.result_id IS NOT NULL
                   OR rec.events IS NOT NULL OR cor.correction IS NOT NULL OR d.pending_request_id IS NOT NULL OR d.open_checkout_follow_up
            ORDER BY d.work_date DESC,d.employee_no,d.employee_id
            """)
    Cursor<Candidate> candidates(@Param("tenantId") UUID tenantId, @Param("from") LocalDate from,
            @Param("through") LocalDate through, @Param("organizationId") UUID organizationId,
            @Param("q") String q, @Param("reason") String reason);
}
