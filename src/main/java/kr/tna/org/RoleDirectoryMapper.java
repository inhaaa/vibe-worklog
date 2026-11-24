package kr.tna.org;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface RoleDirectoryMapper {
    String ACCOUNTS = """
            FROM tna.employee e
            JOIN tna.tenant_account a ON a.tenant_id=e.tenant_id AND a.employee_id=e.id
            """;
    String GRANTED = """
            JOIN tna.role_grant g ON g.tenant_id=a.tenant_id AND g.account_id=a.id AND g.active
            WHERE e.tenant_id=#{tenantId}
            """;
    String SEARCH = """
            AND (e.employee_no ILIKE #{search} OR e.display_name ILIKE #{search})
            """;
    // Used inside MyBatis XML script so SQL comparisons are escaped.
    String ORGANIZATION = """
            LEFT JOIN tna.employee_assignment s ON s.tenant_id=e.tenant_id AND s.employee_id=e.id
              AND s.valid_from &lt;= #{today} AND (s.valid_to IS NULL OR s.valid_to&gt;#{today})
            LEFT JOIN tna.organization o ON o.tenant_id=s.tenant_id AND o.id=s.organization_id
            """;
    String EMPLOYEE_COLUMNS = """
            SELECT e.id AS employee_id,e.employee_no,e.display_name AS employee_name,o.name AS organization_name,
                   e.version,e.active AS employee_active,e.joined_on,e.left_on,a.active AS account_active,a.credential_set,
                   a.activation_expires_at IS NOT NULL AS account_pending
            """;

    @Select("SELECT g.role,count(*) AS member_count " + ACCOUNTS + GRANTED + " GROUP BY g.role")
    List<RoleCount> roleCounts(@Param("tenantId") UUID tenantId);

    @Select("SELECT count(*) " + ACCOUNTS + GRANTED + " AND g.role=#{role} " + SEARCH)
    long searchCount(@Param("tenantId") UUID tenantId, @Param("role") String role, @Param("search") String search);

    @Select("<script>" + EMPLOYEE_COLUMNS + ACCOUNTS + ORGANIZATION + GRANTED + " AND g.role=#{role} " + SEARCH
            + " ORDER BY e.employee_no,e.id LIMIT 50 OFFSET #{offset}</script>")
    List<EmployeeRow> members(@Param("tenantId") UUID tenantId, @Param("role") String role,
            @Param("search") String search, @Param("today") LocalDate today, @Param("offset") int offset);

    @Select("<script>" + EMPLOYEE_COLUMNS + ACCOUNTS + ORGANIZATION + """
            WHERE e.tenant_id=#{tenantId} AND e.active AND e.joined_on&lt;=#{today}
              AND (e.left_on IS NULL OR e.left_on&gt;=#{today}) AND (a.active AND a.credential_set OR a.activation_expires_at IS NOT NULL)
              AND NOT EXISTS (
                  SELECT 1 FROM tna.role_grant g
                  WHERE g.tenant_id=a.tenant_id AND g.account_id=a.id AND g.role=#{role} AND g.active
              )
            <if test="!protectedIds.isEmpty()">
              AND e.id NOT IN
              <foreach collection="protectedIds" item="id" open="(" separator="," close=")">#{id}</foreach>
            </if>
            """ + SEARCH + " ORDER BY e.employee_no,e.id LIMIT 51 OFFSET #{offset}</script>")
    List<EmployeeRow> candidates(@Param("tenantId") UUID tenantId, @Param("role") String role,
            @Param("search") String search, @Param("today") LocalDate today, @Param("offset") int offset,
            @Param("protectedIds") Set<UUID> protectedIds);

    record RoleCount(String role, long memberCount) { }
    record EmployeeRow(UUID employeeId, String employeeNo, String employeeName, String organizationName, long version,
            boolean employeeActive, LocalDate joinedOn, LocalDate leftOn, boolean accountActive, boolean credentialSet,
            boolean accountPending) { }
}
