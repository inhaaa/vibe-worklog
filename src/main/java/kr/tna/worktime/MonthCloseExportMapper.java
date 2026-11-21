package kr.tna.worktime;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface MonthCloseExportMapper {
    record Event(UUID id, long version, String status, Instant changedAt, String actorName, boolean capturedAtClose, boolean includesPaidLeave) {}
    record Department(UUID organizationId, String organizationCode, String organizationName) {}
    record Row(UUID employeeId, String employeeNo, String employeeName, UUID organizationId, String organizationCode,
            String organizationName, int employedDays, Integer workedDays, Long actualMillis, double leaveDays, Long paidLeaveMillis) {}

    @Select("""
            SELECT id,version,status,changed_at,actor_name,export_at_close AS captured_at_close,
                   paid_leave_snapshot IS NOT NULL AS includes_paid_leave
            FROM tna.attendance_month_closure WHERE tenant_id=#{tenantId} AND month_start=#{month}
            ORDER BY version DESC LIMIT 1
            """)
    Event latest(@Param("tenantId") UUID tenantId, @Param("month") LocalDate month);

    @Select("""
            SELECT DISTINCT organization_id,organization_code,coalesce(organization_name,'소속 미등록') AS organization_name
            FROM tna.attendance_month_export_row
            WHERE tenant_id=#{tenantId} AND closure_id=#{eventId}
            ORDER BY organization_name NULLS LAST,organization_code,organization_id
            """)
    List<Department> departments(@Param("tenantId") UUID tenantId, @Param("eventId") UUID eventId);

    @Select("""
            WITH paid AS (
                SELECT d.employee,d.organization,CASE WHEN count(d.millis)=count(*) THEN sum(d.millis)::bigint END AS millis
                FROM tna.attendance_month_closure c CROSS JOIN LATERAL
                     jsonb_to_recordset(c.paid_leave_snapshot) AS d(employee uuid,organization uuid,millis bigint)
                WHERE c.tenant_id=#{tenantId} AND c.id=#{eventId}
                GROUP BY d.employee,d.organization
            )
            SELECT r.employee_id,r.employee_no,r.employee_name,r.organization_id,r.organization_code,r.organization_name,
                   r.employed_days,r.worked_days,r.actual_millis,r.leave_days,
                   CASE WHEN paid.employee IS NULL THEN 0 ELSE paid.millis END AS paid_leave_millis
            FROM tna.attendance_month_export_row r LEFT JOIN paid
              ON paid.employee=r.employee_id AND paid.organization IS NOT DISTINCT FROM r.organization_id
            WHERE r.tenant_id=#{tenantId} AND r.closure_id=#{eventId}
              AND (#{company} OR r.organization_id IS NOT DISTINCT FROM CAST(#{organizationId} AS uuid))
            ORDER BY r.organization_name NULLS LAST,r.organization_code,r.organization_id,r.employee_no,r.employee_id
            """)
    List<Row> rows(@Param("tenantId") UUID tenantId, @Param("eventId") UUID eventId, @Param("company") boolean company,
            @Param("organizationId") UUID organizationId);
}
