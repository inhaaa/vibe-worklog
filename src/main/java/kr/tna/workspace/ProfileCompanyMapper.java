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
                    AND g.role='APPROVER' AND g.active),false) AS approver_eligible
            FROM tna.employee e
            JOIN tna.tenant_account a ON a.tenant_id=e.tenant_id AND a.employee_id=e.id
            JOIN platform.tenant_registry r ON r.id=e.tenant_id
            LEFT JOIN tna.employee_assignment ea ON ea.tenant_id=e.tenant_id AND ea.employee_id=e.id
              AND ea.valid_from<=#{today} AND (ea.valid_to IS NULL OR ea.valid_to>#{today})
            LEFT JOIN tna.organization o ON o.tenant_id=ea.tenant_id AND o.id=ea.organization_id
            LEFT JOIN tna.schedule_assignment sa ON sa.tenant_id=e.tenant_id AND sa.employee_id=e.id
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
            SELECT r.id,e.employee_no,e.display_name AS employee_name,o.name AS organization_name,r.kind,r.status,
              r.from_date,r.to_date,
              COALESCE((SELECT sum(d.quantity_days) FROM tna.leave_request_day d
                WHERE d.tenant_id=r.tenant_id AND d.request_id=COALESCE(r.original_request_id,r.id)
                  AND d.leave_date>=#{from} AND d.leave_date<=#{to}),0)::integer AS quantity_days,
              COALESCE(f.effect_status,'NONE') AS effect_status,r.submitted_at
            FROM tna.approval_request r
            JOIN tna.employee e ON e.tenant_id=r.tenant_id AND e.id=r.target_employee_id
            LEFT JOIN tna.employee_assignment ea ON ea.tenant_id=e.tenant_id AND ea.employee_id=e.id
              AND ea.valid_from<=#{today} AND (ea.valid_to IS NULL OR ea.valid_to>#{today})
            LEFT JOIN tna.organization o ON o.tenant_id=ea.tenant_id AND o.id=ea.organization_id
            LEFT JOIN tna.request_effect f ON f.tenant_id=r.tenant_id AND f.request_id=r.id
            WHERE r.tenant_id=#{tenantId} AND r.from_date<=#{to} AND r.to_date>=#{from}
              AND (#{organizationId}::uuid IS NULL OR ea.organization_id=#{organizationId})
              AND (#{status}='' OR r.status=#{status})
            ORDER BY r.submitted_at DESC,r.id LIMIT 51 OFFSET #{offset}
            """)
    List<CompanyRequest> requests(@Param("tenantId") UUID tenantId, @Param("today") LocalDate today,
            @Param("organizationId") UUID organizationId, @Param("status") String status,
            @Param("from") LocalDate from, @Param("to") LocalDate to, @Param("offset") int offset);
}
