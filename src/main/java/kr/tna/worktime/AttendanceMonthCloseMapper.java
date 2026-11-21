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

    record BlockerRow(UUID id, UUID employeeId, String employeeNo, String employeeName, String kind,
            LocalDate fromDate, LocalDate toDate, boolean outOfEmployment) { }

    String PENDING_REQUEST = "r.tenant_id=#{tenantId} AND r.status='PENDING' AND r.from_date<=#{through} AND r.to_date>=#{from}";
    String OPEN_FOLLOW_UP = "f.tenant_id=#{tenantId} AND f.resolved_at IS NULL AND f.work_date BETWEEN #{from} AND #{through}";
    String OUTSIDE_EMPLOYMENT = "(f.work_date<e.joined_on OR (e.left_on IS NOT NULL AND f.work_date>e.left_on))";
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
            """ + "(SELECT count(*) FROM tna.approval_request r WHERE " + PENDING_REQUEST + ") AS pending_requests, "
                + "(SELECT count(*) FROM tna.checkout_follow_up f WHERE " + OPEN_FOLLOW_UP + ") AS open_follow_ups")
    Totals totals(@Param("tenantId") UUID tenantId, @Param("from") LocalDate from, @Param("through") LocalDate through);

    @Select("""
            SELECT e.employee_no,f.work_date FROM tna.checkout_follow_up f
            JOIN tna.employee e ON e.tenant_id=f.tenant_id AND e.id=f.employee_id
            WHERE
            """ + OPEN_FOLLOW_UP + " AND " + OUTSIDE_EMPLOYMENT
            + " ORDER BY f.work_date,e.employee_no,e.id LIMIT 1")
    FollowUpDay outOfEmploymentFollowUp(@Param("tenantId") UUID tenantId, @Param("from") LocalDate from,
            @Param("through") LocalDate through);

    @Select("""
            SELECT r.id,e.id AS employee_id,e.employee_no,e.display_name AS employee_name,r.kind,
                   r.from_date,r.to_date,false AS out_of_employment
            FROM tna.approval_request r JOIN tna.employee e ON e.tenant_id=r.tenant_id AND e.id=r.target_employee_id
            WHERE
            """ + PENDING_REQUEST + " ORDER BY r.from_date DESC,e.employee_no,r.id LIMIT #{limit} OFFSET #{offset}")
    List<BlockerRow> pendingRequests(@Param("tenantId") UUID tenantId, @Param("from") LocalDate from,
            @Param("through") LocalDate through, @Param("limit") int limit, @Param("offset") int offset);

    @Select("""
            SELECT f.id,e.id AS employee_id,e.employee_no,e.display_name AS employee_name,'CHECKOUT_FOLLOW_UP' AS kind,
                   f.work_date AS from_date,f.work_date AS to_date,
            """ + OUTSIDE_EMPLOYMENT + """
             AS out_of_employment
            FROM tna.checkout_follow_up f JOIN tna.employee e ON e.tenant_id=f.tenant_id AND e.id=f.employee_id
            WHERE
            """ + OPEN_FOLLOW_UP + " ORDER BY f.work_date DESC,e.employee_no,f.id LIMIT #{limit} OFFSET #{offset}")
    List<BlockerRow> openFollowUps(@Param("tenantId") UUID tenantId, @Param("from") LocalDate from,
            @Param("through") LocalDate through, @Param("limit") int limit, @Param("offset") int offset);

    @Insert("""
            INSERT INTO tna.attendance_month_closure(tenant_id,id,month_start,version,status,reason,actor_account_id,actor_name,paid_leave_snapshot)
            SELECT tenant_id,#{id},#{month},#{version},#{status},#{reason},#{actorId},display_name,CAST(#{paidLeaveSnapshot} AS jsonb)
            FROM tna.employee WHERE tenant_id=#{tenantId} AND id=#{employeeId}
            """)
    int append(@Param("tenantId") UUID tenantId, @Param("id") UUID id, @Param("month") LocalDate month,
            @Param("version") long version, @Param("status") String status, @Param("reason") String reason,
            @Param("actorId") UUID actorId, @Param("employeeId") UUID employeeId, @Param("paidLeaveSnapshot") String paidLeaveSnapshot);
}
