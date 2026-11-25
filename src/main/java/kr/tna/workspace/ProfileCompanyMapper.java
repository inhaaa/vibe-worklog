package kr.tna.workspace;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import kr.tna.workspace.ProfileCompanyData.*;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface ProfileCompanyMapper {
    @Select("SELECT pg_advisory_xact_lock_shared(hashtextextended(CAST(#{tenantId} AS text),0))")
    Object lock(@Param("tenantId") UUID tenantId);

    @Select("""
            SELECT e.employee_no,e.display_name AS employee_name,r.display_name AS company_name,a.email,
              e.joined_on,e.left_on,o.name AS organization_name,t.name AS schedule_name,p.display_name AS approver_name,
              COALESCE(p.active AND p.joined_on<=#{today} AND (p.left_on IS NULL OR p.left_on>=#{today})
                AND EXISTS(SELECT 1 FROM tna.tenant_account pa JOIN tna.role_grant g
                  ON g.tenant_id=pa.tenant_id AND g.account_id=pa.id
                  WHERE pa.tenant_id=e.tenant_id AND pa.employee_id=p.id AND pa.active AND pa.credential_set
                    AND g.role='APPROVER' AND g.active),false) AS approver_eligible,
              he.display_name AS organization_head_name,
              COALESCE(o.active AND he.active AND he.joined_on<=#{today}
                AND (he.left_on IS NULL OR he.left_on>=#{today}),false) AS organization_head_eligible,
              COALESCE(o.active AND he.active AND he.joined_on<=#{today} AND (he.left_on IS NULL OR he.left_on>=#{today})
                AND EXISTS(SELECT 1 FROM tna.tenant_account ha JOIN tna.role_grant hg
                  ON hg.tenant_id=ha.tenant_id AND hg.account_id=ha.id
                  WHERE ha.tenant_id=e.tenant_id AND ha.employee_id=he.id AND ha.active AND ha.credential_set
                    AND hg.role='APPROVER' AND hg.active),false) AS organization_head_approver_eligible
            FROM tna.employee e
            JOIN tna.tenant_account a ON a.tenant_id=e.tenant_id AND a.employee_id=e.id
            JOIN platform.tenant_registry r ON r.id=e.tenant_id
            LEFT JOIN tna.employee_assignment ea ON ea.tenant_id=e.tenant_id AND ea.employee_id=e.id
              AND ea.valid_from<=#{today} AND (ea.valid_to IS NULL OR ea.valid_to>#{today})
            LEFT JOIN tna.organization o ON o.tenant_id=ea.tenant_id AND o.id=ea.organization_id
            LEFT JOIN tna.organization_head_assignment oh ON oh.tenant_id=o.tenant_id AND oh.organization_id=o.id
              AND oh.superseded_at IS NULL AND oh.valid_from<=#{today} AND (oh.valid_to IS NULL OR oh.valid_to>#{today})
            LEFT JOIN tna.employee he ON he.tenant_id=oh.tenant_id AND he.id=oh.employee_id
            LEFT JOIN tna.effective_schedule_assignment sa ON sa.tenant_id=e.tenant_id AND sa.employee_id=e.id
              AND sa.valid_from<=#{today} AND (sa.valid_to IS NULL OR sa.valid_to>#{today})
            LEFT JOIN tna.schedule_version sv ON sv.tenant_id=sa.tenant_id AND sv.id=sa.schedule_version_id
            LEFT JOIN tna.schedule_template t ON t.tenant_id=sv.tenant_id AND t.id=sv.template_id
            LEFT JOIN tna.approver_assignment ap ON ap.tenant_id=e.tenant_id AND ap.employee_id=e.id
              AND ap.superseded_at IS NULL AND ap.valid_from<=#{today} AND (ap.valid_to IS NULL OR ap.valid_to>#{today})
            LEFT JOIN tna.employee p ON p.tenant_id=ap.tenant_id AND p.id=ap.approver_employee_id
            WHERE e.tenant_id=#{tenantId} AND e.id=#{employeeId} AND a.id=#{accountId}
            """)
    ProfileRow profile(@Param("tenantId") UUID tenantId, @Param("accountId") UUID accountId,
            @Param("employeeId") UUID employeeId, @Param("today") LocalDate today);

    @Select("SELECT role FROM tna.role_grant WHERE tenant_id=#{tenantId} AND account_id=#{accountId} AND active ORDER BY role")
    List<String> roles(@Param("tenantId") UUID tenantId, @Param("accountId") UUID accountId);

    @Select("SELECT id,code,name FROM tna.organization WHERE tenant_id=#{tenantId} ORDER BY code,id")
    List<OrganizationItem> organizations(@Param("tenantId") UUID tenantId);

    @Select("SELECT EXISTS(SELECT 1 FROM tna.organization WHERE tenant_id=#{tenantId} AND id=#{id})")
    boolean organizationExists(@Param("tenantId") UUID tenantId, @Param("id") UUID id);

    String EMPLOYEE_SELECT = """
            SELECT e.id,e.employee_no,e.display_name AS employee_name,o.name AS organization_name
            FROM tna.employee e
            LEFT JOIN tna.employee_assignment ea ON ea.tenant_id=e.tenant_id AND ea.employee_id=e.id
              AND ea.valid_from<=#{today} AND (ea.valid_to IS NULL OR ea.valid_to>#{today})
            LEFT JOIN tna.organization o ON o.tenant_id=ea.tenant_id AND o.id=ea.organization_id
            WHERE e.tenant_id=#{tenantId}
              AND (#{organizationId}::uuid IS NULL OR ea.organization_id=#{organizationId})
            """;

    @Select(EMPLOYEE_SELECT + """
              AND (#{query}='' OR strpos(lower(e.employee_no),lower(#{query}))>0
                   OR strpos(lower(e.display_name),lower(#{query}))>0)
            ORDER BY e.employee_no,e.id LIMIT 51 OFFSET #{offset}
            """)
    List<EmployeeItem> employees(@Param("tenantId") UUID tenantId, @Param("today") LocalDate today,
            @Param("organizationId") UUID organizationId, @Param("query") String query, @Param("offset") int offset);

    @Select(EMPLOYEE_SELECT + " AND e.id=#{employeeId}")
    EmployeeItem employee(@Param("tenantId") UUID tenantId, @Param("today") LocalDate today,
            @Param("organizationId") UUID organizationId, @Param("employeeId") UUID employeeId);

    @Select("""
            SELECT e.id AS employee_id,e.employee_no,e.display_name AS employee_name,o.name AS organization_name,
              recent.id AS recent_request_id,recent.status AS recent_request_state,eff.request_id AS effective_request_id
            FROM (
              SELECT employee_id
              FROM tna.attendance_event
              WHERE tenant_id=#{tenantId} AND assigned_work_date=#{date}
              GROUP BY employee_id HAVING COUNT(*)=1 AND bool_and(event_kind='IN')
            ) source
            JOIN tna.employee e ON e.tenant_id=#{tenantId} AND e.id=source.employee_id
              AND e.joined_on<=#{date} AND (e.left_on IS NULL OR e.left_on>=#{date})
            LEFT JOIN tna.employee_assignment ea ON ea.tenant_id=e.tenant_id AND ea.employee_id=e.id
              AND ea.valid_from<=#{date} AND (ea.valid_to IS NULL OR ea.valid_to>#{date})
            LEFT JOIN tna.organization o ON o.tenant_id=ea.tenant_id AND o.id=ea.organization_id
            LEFT JOIN tna.request_effect eff ON eff.tenant_id=e.tenant_id AND eff.employee_id=e.id
              AND eff.work_date=#{date} AND eff.effect_kind='CORRECTION' AND eff.effect_status='EFFECTIVE'
            LEFT JOIN LATERAL (
              SELECT r.id,r.status FROM tna.approval_request r
              WHERE r.tenant_id=e.tenant_id AND r.target_employee_id=e.id
                AND r.kind='ATTENDANCE_CORRECTION' AND r.from_date=#{date}
              ORDER BY (r.status='PENDING') DESC,r.submitted_at DESC,r.id LIMIT 1
            ) recent ON true
            WHERE (#{organizationId}::uuid IS NULL OR ea.organization_id=#{organizationId})
              AND (#{query}='' OR strpos(lower(e.employee_no),lower(#{query}))>0
                   OR strpos(lower(e.display_name),lower(#{query}))>0)
            ORDER BY e.employee_no,e.id
            """)
    List<CheckoutCandidate> checkoutCandidates(@Param("tenantId") UUID tenantId, @Param("date") LocalDate date,
            @Param("organizationId") UUID organizationId, @Param("query") String query);

    @Select("""
            SELECT r.id,e.employee_no,e.display_name AS employee_name,o.name AS organization_name,r.kind,r.status,
              r.from_date,r.to_date,
              COALESCE(days.quantity_days,0) AS quantity_days,
              COALESCE(f.effect_status,'NONE') AS effect_status,r.submitted_at,
              CASE WHEN r.kind IN ('LEAVE_DAY','CANCEL_LEAVE') THEN COALESCE(days.leave_part,'FULL') END AS leave_part,
              CASE WHEN lv.recognition_rule='COMPANY_LEAVE' THEN lv.name END AS company_leave_name
            FROM tna.approval_request r
            LEFT JOIN tna.attendance_type_version lv ON lv.tenant_id=r.tenant_id AND lv.id=r.attendance_type_version_id
            LEFT JOIN LATERAL (
              SELECT sum(d.quantity_days) AS quantity_days, min(d.leave_part) AS leave_part FROM tna.leave_request_day d
              WHERE d.tenant_id=r.tenant_id AND d.request_id=COALESCE(r.original_request_id,r.id)
                AND d.leave_date>=#{from} AND d.leave_date<=#{to}
            ) days ON true
            JOIN tna.employee e ON e.tenant_id=r.tenant_id AND e.id=r.target_employee_id
            LEFT JOIN tna.employee_assignment ea ON ea.tenant_id=e.tenant_id AND ea.employee_id=e.id
              AND ea.valid_from<=#{today} AND (ea.valid_to IS NULL OR ea.valid_to>#{today})
            LEFT JOIN tna.organization o ON o.tenant_id=ea.tenant_id AND o.id=ea.organization_id
            LEFT JOIN tna.request_effect f ON f.tenant_id=r.tenant_id AND f.request_id=r.id
            WHERE r.tenant_id=#{tenantId} AND r.from_date<=#{to} AND r.to_date>=#{from}
              AND (#{organizationId}::uuid IS NULL OR ea.organization_id=#{organizationId})
              AND (#{status}='' OR r.status=#{status})
              AND (#{scope}='' OR (#{scope}='LEAVE' AND r.kind IN ('LEAVE_DAY','CANCEL_LEAVE'))
                               OR (#{scope}='CORRECTION' AND r.kind='ATTENDANCE_CORRECTION'))
            ORDER BY r.submitted_at DESC,r.id LIMIT 51 OFFSET #{offset}
            """)
    List<CompanyRequest> requests(@Param("tenantId") UUID tenantId, @Param("today") LocalDate today,
            @Param("organizationId") UUID organizationId, @Param("status") String status,
            @Param("from") LocalDate from, @Param("to") LocalDate to, @Param("offset") int offset, @Param("scope") String scope);

    // ponytail: 한 달 최대 1000행, 페이지 없음. 넘으면 페이지를 추가한다.
    @Select("""
            SELECT r.employee_id, e.employee_no, e.display_name AS employee_name, r.work_date,
              r.late_millis, r.early_leave_millis, r.absence
            FROM (SELECT DISTINCT ON (employee_id, work_date) employee_id, work_date, late_millis, early_leave_millis, absence
                  FROM tna.day_result WHERE tenant_id=#{tenantId} AND work_date>=#{from} AND work_date<#{to}
                  ORDER BY employee_id, work_date, result_revision DESC) r
            JOIN tna.employee e ON e.tenant_id=#{tenantId} AND e.id=r.employee_id
            WHERE r.late_millis>0 OR r.early_leave_millis>0 OR r.absence IS NOT NULL
            ORDER BY r.work_date DESC, e.employee_no
            LIMIT 1000
            """)
    List<AttendanceExceptionQueries.Result> attendanceExceptions(@Param("tenantId") UUID tenantId,
            @Param("from") LocalDate from, @Param("to") LocalDate to);
}
