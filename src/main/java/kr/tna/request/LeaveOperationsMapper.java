package kr.tna.request;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import kr.tna.request.LeaveOperationsData.Counts;
import kr.tna.request.LeaveOperationsData.LeaveType;
import kr.tna.request.LeaveOperationsData.Row;
import kr.tna.request.LeaveOperationsData.Search;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface LeaveOperationsMapper {
    @Select("""
            SELECT t.code,v.name,t.system_kind FROM tna.attendance_type t JOIN LATERAL (
              SELECT name FROM tna.attendance_type_version
              WHERE tenant_id=t.tenant_id AND type_id=t.id AND effective_from<=#{today}
              ORDER BY effective_from DESC,revision DESC LIMIT 1
            ) v ON true
            WHERE t.tenant_id=#{tenantId} AND t.system_kind='ANNUAL_LEAVE' ORDER BY t.code
            """)
    List<LeaveType> leaveTypes(@Param("tenantId") UUID tenantId, @Param("today") LocalDate today);

    String REQUESTS = """
            FROM tna.approval_request r
            LEFT JOIN tna.approval_request original ON r.kind='CANCEL_LEAVE'
              AND original.tenant_id=r.tenant_id AND original.id=r.original_request_id
              AND original.kind='LEAVE_DAY' AND original.target_employee_id=r.target_employee_id
            JOIN tna.attendance_type_version v ON v.tenant_id=r.tenant_id AND v.id=
              CASE WHEN r.kind='CANCEL_LEAVE' THEN original.attendance_type_version_id ELSE r.attendance_type_version_id END
            JOIN tna.attendance_type t ON t.tenant_id=v.tenant_id AND t.id=v.type_id
            JOIN tna.employee employee ON employee.tenant_id=r.tenant_id AND employee.id=r.target_employee_id
            LEFT JOIN tna.employee_assignment ea ON ea.tenant_id=employee.tenant_id AND ea.employee_id=employee.id
              AND ea.valid_from<=#{today} AND (ea.valid_to IS NULL OR ea.valid_to>#{today})
            """;

    String FILTER = """
            WHERE r.tenant_id=#{tenantId} AND r.kind IN ('LEAVE_DAY','CANCEL_LEAVE') AND t.system_kind='ANNUAL_LEAVE'
              AND (#{search.leaveTypeCode}='' OR t.code=#{search.leaveTypeCode})
              AND (#{search.requestKind}='' OR r.kind=#{search.requestKind})
              AND (#{search.from}::date IS NULL OR COALESCE(original.to_date,r.to_date)>=#{search.from})
              AND (#{search.to}::date IS NULL OR COALESCE(original.from_date,r.from_date)<=#{search.to})
              AND (#{search.organizationId}::uuid IS NULL OR ea.organization_id=#{search.organizationId})
              AND (#{search.q}='' OR strpos(lower(employee.employee_no),lower(#{search.q}))>0
                   OR strpos(lower(employee.display_name),lower(#{search.q}))>0)
            """;

    String DECISION = """
            LEFT JOIN LATERAL (
              SELECT e.created_at AS decided_at,actor.display_name AS decided_by_name FROM tna.approval_event e
              JOIN tna.tenant_account account ON account.tenant_id=e.tenant_id AND account.id=e.actor_account_id
              JOIN tna.employee actor ON actor.tenant_id=account.tenant_id AND actor.id=account.employee_id
              WHERE e.tenant_id=r.tenant_id AND e.request_id=r.id AND r.status IN ('APPROVED','REJECTED')
                AND e.event_kind=r.status AND e.to_status=r.status
              ORDER BY e.created_at DESC,e.id DESC LIMIT 1
            ) decision ON true
            """;

    @Select("SELECT decision.decided_by_name FROM tna.approval_request r " + DECISION + """
            WHERE r.tenant_id=#{tenantId} AND r.id=#{requestId} AND r.kind IN ('LEAVE_DAY','CANCEL_LEAVE')
            """)
    String decidedByName(@Param("tenantId") UUID tenantId, @Param("requestId") UUID requestId);

    @Select("""
            SELECT count(*) FILTER (WHERE r.status='PENDING') AS pending,
              count(*) FILTER (WHERE r.status='APPROVED') AS approved,
              count(*) FILTER (WHERE r.status='REJECTED') AS rejected, count(*) AS total
            """ + REQUESTS + FILTER)
    Counts counts(@Param("tenantId") UUID tenantId, @Param("today") LocalDate today, @Param("search") Search search);

    @Select("""
            SELECT r.id,employee.employee_no,employee.display_name AS employee_name,o.name AS organization_name,
              v.name AS leave_type_name,r.kind AS request_kind,COALESCE(original.from_date,r.from_date) AS "from",
              COALESCE(original.to_date,r.to_date) AS "to",
              COALESCE((SELECT sum(d.quantity_days) FROM tna.leave_request_day d
                WHERE d.tenant_id=r.tenant_id AND d.request_id=COALESCE(original.id,r.id)),0)::integer AS quantity_days,
              r.status,COALESCE(eff.effect_status,'NONE') AS effect_status,approver.display_name AS approver_name,
              decision.decided_by_name,r.submitted_at,decision.decided_at,r.original_request_id,r.current_step,r.total_steps
            """ + REQUESTS + """
            LEFT JOIN tna.organization o ON o.tenant_id=ea.tenant_id AND o.id=ea.organization_id
            JOIN tna.employee approver ON approver.tenant_id=r.tenant_id AND approver.id=r.current_approver_id
            LEFT JOIN tna.request_effect eff ON eff.tenant_id=r.tenant_id AND eff.request_id=r.id
              AND r.kind='LEAVE_DAY' AND eff.effect_kind='LEAVE'
            """ + DECISION + FILTER + """
              AND (#{search.status}='ALL' OR r.status=#{search.status})
            ORDER BY CASE WHEN #{search.status}='PENDING' THEN r.submitted_at END ASC,
              CASE WHEN #{search.status} IN ('APPROVED','REJECTED') THEN decision.decided_at END DESC NULLS LAST,
              CASE WHEN #{search.status}='ALL' THEN r.submitted_at END DESC,r.id
            LIMIT 51 OFFSET #{search.offset}
            """)
    List<Row> rows(@Param("tenantId") UUID tenantId, @Param("today") LocalDate today, @Param("search") Search search);
}
