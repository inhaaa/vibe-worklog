package kr.tna.worktime;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;
import kr.tna.worktime.CheckoutFollowUpService.DayKey;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface CheckoutFollowUpMapper {
    record OpenRow(UUID id, UUID employeeId, LocalDate workDate, String reason, UUID lastRequestId, String requestStatus,
            long inputRevision, long reviewRevision, UUID scheduleVersionId, Boolean workday, LocalTime startTime,
            LocalTime endTime, String breaks, UUID calendarVersionId, String dayKind, String events,
            String correction, String confirmations, double approvedLeaveDays, UUID effectiveRequestId) implements MonthEndReviewMapper.DayEvidence { }

    @Insert("""
            INSERT INTO tna.checkout_follow_up(tenant_id,id,employee_id,work_date,reason,requested_by)
            VALUES (#{tenantId},#{id},#{employeeId},#{workDate},#{reason},#{requestedBy})
            ON CONFLICT (tenant_id,employee_id,work_date) WHERE resolved_at IS NULL DO NOTHING
            """)
    int insert(@Param("tenantId") UUID tenantId, @Param("id") UUID id, @Param("employeeId") UUID employeeId,
            @Param("workDate") LocalDate workDate, @Param("reason") String reason, @Param("requestedBy") UUID requestedBy);

    String WITH_EVIDENCE = """
            SELECT d.id,d.employee_id,d.work_date,d.reason,d.last_request_id,r.status AS request_status,
                   coalesce(rev.input_revision,0) AS input_revision,coalesce(rev.review_revision,0) AS review_revision,
                   s.version_id AS schedule_version_id,s.workday,s.start_time,s.end_time,s.breaks,
                   cal.version_id AS calendar_version_id,cal.kind AS day_kind,rec.events,cor.correction,conf.confirmations,
                   (SELECT coalesce(sum(ld.quantity_days),0)
            """ + MonthEndReviewMapper.SAME_DAY_APPROVED_LEAVE + """
                   ) AS approved_leave_days,cor.request_id AS effective_request_id
            FROM selected d
            LEFT JOIN tna.approval_request r ON r.tenant_id=#{tenantId} AND r.id=d.last_request_id
                AND r.target_employee_id=d.employee_id AND r.kind='ATTENDANCE_CORRECTION' AND r.from_date=d.work_date
            """ + MonthEndReviewMapper.DAY_EVIDENCE_JOINS;

    @Select("""
            WITH selected AS MATERIALIZED (
                SELECT id,employee_id,work_date,reason,last_request_id,requested_at FROM tna.checkout_follow_up
                WHERE tenant_id=#{tenantId} AND employee_id=#{employeeId} AND resolved_at IS NULL
                ORDER BY requested_at DESC,id DESC LIMIT #{limit} OFFSET #{offset}
            )
            """ + WITH_EVIDENCE + " ORDER BY d.requested_at DESC,d.id DESC")
    List<OpenRow> mine(@Param("tenantId") UUID tenantId, @Param("employeeId") UUID employeeId,
            @Param("limit") int limit, @Param("offset") int offset);

    @Select("""
            <script>
            WITH selected AS MATERIALIZED (
                SELECT id,employee_id,work_date,reason,last_request_id FROM tna.checkout_follow_up
                WHERE tenant_id=#{tenantId} AND resolved_at IS NULL AND (employee_id,work_date) IN
                <foreach collection="days" item="day" open="(" separator="," close=")">
                    (#{day.employeeId},#{day.workDate})
                </foreach>
            )
            <![CDATA[
            """ + WITH_EVIDENCE + "]]></script>")
    List<OpenRow> forDays(@Param("tenantId") UUID tenantId, @Param("days") List<DayKey> days);
}
