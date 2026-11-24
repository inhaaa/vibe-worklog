package kr.tna.org;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface AccountDirectoryMapper {
    record Row(UUID employeeId, String employeeNo, String displayName, String organizationName, String state) { }

    /** AccountStatus.of와 같은 순서. 바꾸면 AccountDirectoryWebIT의 대조 테스트도 함께 바꾼다. */
    @Select("""
            SELECT employee_id, employee_no, display_name, organization_name, state FROM (
              SELECT e.id AS employee_id, e.employee_no, e.display_name, o.name AS organization_name,
                CASE WHEN a.id IS NULL THEN 'NONE'
                     WHEN a.activation_expires_at IS NULL THEN CASE WHEN a.active THEN 'ACTIVE' ELSE 'PREVIOUS_INVITATION' END
                     WHEN NOT EXISTS (SELECT 1 FROM tna.employee_personnel p WHERE p.tenant_id=e.tenant_id
                                        AND p.employee_id=e.id AND p.birth_date IS NOT NULL) THEN 'BIRTH_DATE_REQUIRED'
                     WHEN a.activation_failures >= 5 THEN 'LOCKED'
                     WHEN a.activation_expires_at <= #{now} THEN 'EXPIRED'
                     ELSE 'PENDING' END AS state
              FROM tna.employee e
              LEFT JOIN tna.tenant_account a ON a.tenant_id=e.tenant_id AND a.employee_id=e.id
              LEFT JOIN tna.employee_assignment s ON s.tenant_id=e.tenant_id AND s.employee_id=e.id
                   AND s.valid_from <= #{today} AND (s.valid_to IS NULL OR s.valid_to > #{today})
              LEFT JOIN tna.organization o ON o.tenant_id=s.tenant_id AND o.id=s.organization_id
              WHERE e.tenant_id=#{tenantId} AND e.active AND (e.left_on IS NULL OR e.left_on >= #{today})
                AND (e.employee_no ILIKE #{pattern} OR e.display_name ILIKE #{pattern})
            ) listed
            WHERE #{status}='all' OR state=#{status}
            ORDER BY employee_no, employee_id
            LIMIT 51 OFFSET #{offset}
            """)
    List<Row> list(@Param("tenantId") UUID tenantId, @Param("pattern") String pattern, @Param("status") String status,
                   @Param("today") LocalDate today, @Param("now") Instant now, @Param("offset") int offset);
}
