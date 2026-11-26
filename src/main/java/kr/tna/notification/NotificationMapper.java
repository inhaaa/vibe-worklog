package kr.tna.notification;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface NotificationMapper {
    record Stored(UUID id, UUID requestId, String kind, String requestKind, String typeName, String requesterName,
            LocalDate fromDate, LocalDate toDate, Instant createdAt, boolean read) { }
    record Pending(UUID id, String kind, int mailAttempts, String email, UUID requestId, String requestKind, String typeName,
            LocalDate fromDate, LocalDate toDate, String requesterName, String companyName, String eventReason) { }

    String STORED = """
            SELECT n.id, n.request_id, n.kind, r.kind AS request_kind, atv.name AS type_name, e.display_name AS requester_name,
              r.from_date, r.to_date, n.created_at, (n.read_at IS NOT NULL) AS read
            FROM tna.notification n
            JOIN tna.approval_request r ON r.tenant_id=n.tenant_id AND r.id=n.request_id
            JOIN tna.employee e ON e.tenant_id=r.tenant_id AND e.id=r.target_employee_id
            LEFT JOIN tna.attendance_type_version atv ON atv.tenant_id=r.tenant_id AND atv.id=r.attendance_type_version_id
            """;

    @Select(STORED + " WHERE n.tenant_id=#{tenantId} AND n.recipient_employee_id=#{employeeId} ORDER BY n.created_at DESC, n.id DESC LIMIT 100")
    List<Stored> recent(@Param("tenantId") UUID tenantId, @Param("employeeId") UUID employeeId);

    @Select("""
            SELECT count(*)::int FROM (SELECT 1 FROM tna.notification
              WHERE tenant_id=#{tenantId} AND recipient_employee_id=#{employeeId} AND read_at IS NULL LIMIT 100) unread""")
    int unreadCount(@Param("tenantId") UUID tenantId, @Param("employeeId") UUID employeeId);

    @Update("""
            UPDATE tna.notification SET read_at=CURRENT_TIMESTAMP
            WHERE tenant_id=#{tenantId} AND recipient_employee_id=#{employeeId} AND request_id=#{requestId} AND read_at IS NULL""")
    int markRequestRead(@Param("tenantId") UUID tenantId, @Param("employeeId") UUID employeeId, @Param("requestId") UUID requestId);

    @Update("UPDATE tna.notification SET read_at=CURRENT_TIMESTAMP WHERE tenant_id=#{tenantId} AND recipient_employee_id=#{employeeId} AND read_at IS NULL")
    int markAllRead(@Param("tenantId") UUID tenantId, @Param("employeeId") UUID employeeId);

    // 메일 받는 주소: 재직(활성) 사원의 활성 계정 이메일만. 없으면 NULL → 건너뜀.
    @Select("""
            SELECT n.id, n.kind, n.mail_attempts, CASE WHEN rec.active AND a.active THEN a.email END AS email,
              r.id AS request_id, r.kind AS request_kind, atv.name AS type_name, r.from_date, r.to_date,
              req.display_name AS requester_name, t.display_name AS company_name, ev.reason AS event_reason
            FROM tna.notification n
            JOIN tna.approval_request r ON r.tenant_id=n.tenant_id AND r.id=n.request_id
            JOIN tna.employee req ON req.tenant_id=r.tenant_id AND req.id=r.target_employee_id
            JOIN tna.employee rec ON rec.tenant_id=n.tenant_id AND rec.id=n.recipient_employee_id
            JOIN tna.approval_event ev ON ev.tenant_id=n.tenant_id AND ev.id=n.event_id
            JOIN platform.tenant_registry t ON t.id=n.tenant_id
            LEFT JOIN tna.tenant_account a ON a.tenant_id=n.tenant_id AND a.employee_id=n.recipient_employee_id
            LEFT JOIN tna.attendance_type_version atv ON atv.tenant_id=r.tenant_id AND atv.id=r.attendance_type_version_id
            WHERE n.tenant_id=#{tenantId} AND n.mail_state='PENDING' AND n.mail_next_at<=CURRENT_TIMESTAMP
            ORDER BY n.mail_next_at, n.id LIMIT #{limit}""")
    List<Pending> pendingMail(@Param("tenantId") UUID tenantId, @Param("limit") int limit);

    @Update("UPDATE tna.notification SET mail_state=#{state}, mail_next_at=NULL WHERE tenant_id=#{tenantId} AND id=#{id} AND mail_state='PENDING'")
    int finishMail(@Param("tenantId") UUID tenantId, @Param("id") UUID id, @Param("state") String state);

    @Update("""
            UPDATE tna.notification SET mail_attempts=#{attempts},
              mail_state=CASE WHEN #{attempts}>=3 THEN 'FAILED' ELSE 'PENDING' END,
              mail_next_at=CASE WHEN #{attempts}>=3 THEN NULL
                ELSE CURRENT_TIMESTAMP + make_interval(mins => CASE WHEN #{attempts}=1 THEN 1 ELSE 5 END) END
            WHERE tenant_id=#{tenantId} AND id=#{id} AND mail_state='PENDING'""")
    int retryMail(@Param("tenantId") UUID tenantId, @Param("id") UUID id, @Param("attempts") int attempts);
}
