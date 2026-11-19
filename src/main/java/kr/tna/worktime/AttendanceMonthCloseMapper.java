package kr.tna.worktime;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface AttendanceMonthCloseMapper {
    record Event(long version, String status, Instant changedAt, String actorName, String reason) { }
    record Totals(long employeeCount, long pendingRequests, long openFollowUps) { }
    record FollowUpDay(String employeeNo, LocalDate workDate) { }

    String EVENT_COLUMNS = "version, status, changed_at, actor_name, reason";

    @Select("SELECT " + EVENT_COLUMNS + " FROM tna.attendance_month_closure "
            + "WHERE tenant_id=#{tenantId} AND month_start=#{month} ORDER BY version DESC LIMIT #{limit}")
    List<Event> history(@Param("tenantId") UUID tenantId, @Param("month") LocalDate month, @Param("limit") int limit);

    @Select("SELECT " + EVENT_COLUMNS + " FROM tna.attendance_month_closure "
            + "WHERE tenant_id=#{tenantId} AND id=#{id} AND month_start=#{month}")
    Event receipt(@Param("tenantId") UUID tenantId, @Param("id") UUID id, @Param("month") LocalDate month);

    @Select("""
            SELECT (SELECT count(*) FROM tna.employee WHERE tenant_id=#{tenantId}
                        AND joined_on<=#{through} AND (left_on IS NULL OR left_on>=#{from})) AS employee_count,
                   (SELECT count(*) FROM tna.approval_request WHERE tenant_id=#{tenantId} AND status='PENDING'
                        AND from_date<=#{through} AND to_date>=#{from}) AS pending_requests,
                   (SELECT count(*) FROM tna.checkout_follow_up WHERE tenant_id=#{tenantId} AND resolved_at IS NULL
                        AND work_date BETWEEN #{from} AND #{through}) AS open_follow_ups
            """)
    Totals totals(@Param("tenantId") UUID tenantId, @Param("from") LocalDate from, @Param("through") LocalDate through);

    @Select("""
            SELECT e.employee_no,f.work_date FROM tna.checkout_follow_up f
            JOIN tna.employee e ON e.tenant_id=f.tenant_id AND e.id=f.employee_id
            WHERE f.tenant_id=#{tenantId} AND f.resolved_at IS NULL AND f.work_date BETWEEN #{from} AND #{through}
              AND (f.work_date<e.joined_on OR f.work_date>e.left_on)
            ORDER BY f.work_date,e.employee_no,e.id LIMIT 1
            """)
    FollowUpDay outOfEmploymentFollowUp(@Param("tenantId") UUID tenantId, @Param("from") LocalDate from,
            @Param("through") LocalDate through);

    @Insert("""
            INSERT INTO tna.attendance_month_closure(tenant_id,id,month_start,version,status,reason,actor_account_id,actor_name)
            SELECT tenant_id,#{id},#{month},#{version},#{status},#{reason},#{actorId},display_name
            FROM tna.employee WHERE tenant_id=#{tenantId} AND id=#{employeeId}
            """)
    int append(@Param("tenantId") UUID tenantId, @Param("id") UUID id, @Param("month") LocalDate month,
            @Param("version") long version, @Param("status") String status, @Param("reason") String reason,
            @Param("actorId") UUID actorId, @Param("employeeId") UUID employeeId);
}
