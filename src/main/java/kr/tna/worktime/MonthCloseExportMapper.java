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
    record Event(UUID id, long version, String status, Instant changedAt, String actorName, boolean capturedAtClose) {}
    record Department(UUID organizationId, String organizationCode, String organizationName) {}
    record Row(UUID employeeId, String employeeNo, String employeeName, UUID organizationId, String organizationCode,
            String organizationName, int employedDays, Integer workedDays, Long actualMillis, double leaveDays) {}

    @Select("""
            SELECT id,version,status,changed_at,actor_name,export_at_close AS captured_at_close
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
            SELECT employee_id,employee_no,employee_name,organization_id,organization_code,organization_name,
                   employed_days,worked_days,actual_millis,leave_days
            FROM tna.attendance_month_export_row WHERE tenant_id=#{tenantId} AND closure_id=#{eventId}
              AND (#{company} OR organization_id IS NOT DISTINCT FROM CAST(#{organizationId} AS uuid))
            ORDER BY organization_name NULLS LAST,organization_code,organization_id,employee_no,employee_id
            """)
    List<Row> rows(@Param("tenantId") UUID tenantId, @Param("eventId") UUID eventId, @Param("company") boolean company,
            @Param("organizationId") UUID organizationId);
}
