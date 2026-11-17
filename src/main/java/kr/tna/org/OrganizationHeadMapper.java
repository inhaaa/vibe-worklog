package kr.tna.org;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import kr.tna.org.OrganizationHeadData.*;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface OrganizationHeadMapper {
    @Select("SELECT pg_advisory_xact_lock_shared(hashtextextended(CAST(#{tenantId} AS text),0))")
    Object lock(@Param("tenantId") UUID tenantId);

    @Select("SELECT id,code,name,active,version FROM tna.organization WHERE tenant_id=#{tenantId} ORDER BY code,id")
    List<OrganizationOption> organizations(@Param("tenantId") UUID tenantId);

    @Select("SELECT id,code,name,active,version FROM tna.organization WHERE tenant_id=#{tenantId} AND id=#{id}")
    OrganizationOption organization(@Param("tenantId") UUID tenantId, @Param("id") UUID id);

    @Select("SELECT id,employee_no,display_name,joined_on,left_on,active,version FROM tna.employee WHERE tenant_id=#{tenantId} AND id=#{id}")
    OrgData.Employee employee(@Param("tenantId") UUID tenantId, @Param("id") UUID id);

    @Select("""
            SELECT id,employee_no,display_name AS employee_name,joined_on,left_on FROM tna.employee
            WHERE tenant_id=#{tenantId} AND active AND joined_on<=#{today} AND (left_on IS NULL OR left_on>=#{today})
              AND (#{query}='' OR strpos(lower(employee_no),lower(#{query}))>0 OR strpos(lower(display_name),lower(#{query}))>0)
            ORDER BY employee_no,id
            """)
    List<Candidate> candidates(@Param("tenantId") UUID tenantId, @Param("today") LocalDate today, @Param("query") String query);

    String EMPLOYEE_ELIGIBLE = "(o.active AND e.active AND e.joined_on<=#{today} AND (e.left_on IS NULL OR e.left_on>=#{today}))";
    String APPROVER_ELIGIBLE = EMPLOYEE_ELIGIBLE + """
            AND EXISTS(SELECT 1 FROM tna.tenant_account a JOIN tna.role_grant g
              ON g.tenant_id=a.tenant_id AND g.account_id=a.id
              WHERE a.tenant_id=e.tenant_id AND a.employee_id=e.id AND a.active AND a.credential_set
                AND g.role='APPROVER' AND g.active)
            """;
    String HISTORY_SELECT = """
            SELECT h.id,h.organization_id,h.employee_id,e.employee_no,e.display_name AS employee_name,
              h.valid_from,h.valid_to,h.superseded_at,h.reason,h.version,
            """ + EMPLOYEE_ELIGIBLE + " AS eligible," + APPROVER_ELIGIBLE + """
              AS approver_eligible FROM tna.organization_head_assignment h
            JOIN tna.employee e ON e.tenant_id=h.tenant_id AND e.id=h.employee_id
            JOIN tna.organization o ON o.tenant_id=h.tenant_id AND o.id=h.organization_id
            WHERE h.tenant_id=#{tenantId} AND h.organization_id=#{organizationId}
            """;

    @Select(HISTORY_SELECT + " ORDER BY h.valid_from DESC,h.created_at DESC,h.id")
    List<HeadAssignment> history(@Param("tenantId") UUID tenantId, @Param("organizationId") UUID organizationId,
            @Param("today") LocalDate today);

    @Select(HISTORY_SELECT + " AND h.superseded_at IS NULL AND h.valid_from<=#{date} AND (h.valid_to IS NULL OR h.valid_to>#{date})")
    HeadAssignment covering(@Param("tenantId") UUID tenantId, @Param("organizationId") UUID organizationId,
            @Param("date") LocalDate date, @Param("today") LocalDate today);

    @Select("""
            SELECT min(valid_from) FROM tna.organization_head_assignment WHERE tenant_id=#{tenantId}
              AND organization_id=#{organizationId} AND superseded_at IS NULL AND valid_from>#{date}
            """)
    LocalDate nextStart(@Param("tenantId") UUID tenantId, @Param("organizationId") UUID organizationId, @Param("date") LocalDate date);

    @Update("""
            UPDATE tna.organization_head_assignment
            SET valid_to=CASE WHEN valid_from<#{date} THEN #{date} ELSE valid_to END,
              superseded_at=CASE WHEN valid_from=#{date} THEN CURRENT_TIMESTAMP ELSE superseded_at END,
              version=version+1,updated_at=CURRENT_TIMESTAMP,updated_by=#{actorId}
            WHERE tenant_id=#{tenantId} AND id=#{id} AND version=#{version} AND superseded_at IS NULL
              AND valid_from<=#{date} AND (valid_to IS NULL OR valid_to>#{date})
            """)
    int close(@Param("tenantId") UUID tenantId, @Param("id") UUID id, @Param("date") LocalDate date,
            @Param("version") long version, @Param("actorId") UUID actorId);

    @Update("""
            UPDATE tna.organization_head_assignment
            SET valid_to=#{to},version=version+1,updated_at=CURRENT_TIMESTAMP,updated_by=#{actorId}
            WHERE tenant_id=#{tenantId} AND id=#{id} AND version=#{version} AND superseded_at IS NULL
            """)
    int changeEnd(@Param("tenantId") UUID tenantId, @Param("id") UUID id, @Param("to") LocalDate to,
            @Param("version") long version, @Param("actorId") UUID actorId);

    @Insert("""
            INSERT INTO tna.organization_head_assignment
              (tenant_id,id,organization_id,employee_id,valid_from,valid_to,reason,created_by,updated_by)
            VALUES (#{tenantId},#{id},#{organizationId},#{employeeId},#{from},#{to},#{reason},#{actorId},#{actorId})
            """)
    int insert(@Param("tenantId") UUID tenantId, @Param("id") UUID id, @Param("organizationId") UUID organizationId,
            @Param("employeeId") UUID employeeId, @Param("from") LocalDate from, @Param("to") LocalDate to,
            @Param("reason") String reason, @Param("actorId") UUID actorId);

    @Update("""
            UPDATE tna.organization SET version=version+1,updated_at=CURRENT_TIMESTAMP,updated_by=#{actorId}
            WHERE tenant_id=#{tenantId} AND id=#{id} AND version=#{version}
            """)
    int advanceOrganization(@Param("tenantId") UUID tenantId, @Param("id") UUID id, @Param("version") long version,
            @Param("actorId") UUID actorId);

    @Select("""
            SELECT o.id,o.code,o.name,o.parent_id,o.active,e.employee_no AS head_employee_no,
              e.display_name AS head_employee_name,COALESCE(
            """ + EMPLOYEE_ELIGIBLE + ",false) AS head_eligible,COALESCE(" + APPROVER_ELIGIBLE + """
              ,false) AS head_approver_eligible FROM tna.organization o
            LEFT JOIN tna.organization_head_assignment h ON h.tenant_id=o.tenant_id AND h.organization_id=o.id
              AND h.superseded_at IS NULL AND h.valid_from<=#{today} AND (h.valid_to IS NULL OR h.valid_to>#{today})
            LEFT JOIN tna.employee e ON e.tenant_id=h.tenant_id AND e.id=h.employee_id
            WHERE o.tenant_id=#{tenantId} ORDER BY o.code,o.id
            """)
    List<OrganizationNode> tree(@Param("tenantId") UUID tenantId, @Param("today") LocalDate today);

    @Select("""
            SELECT e.id,e.employee_no,e.display_name AS employee_name,o.name AS organization_name
            FROM tna.employee_assignment a JOIN tna.employee e ON e.tenant_id=a.tenant_id AND e.id=a.employee_id
            JOIN tna.organization o ON o.tenant_id=a.tenant_id AND o.id=a.organization_id
            WHERE a.tenant_id=#{tenantId} AND a.organization_id=#{organizationId}
              AND a.valid_from<=#{today} AND (a.valid_to IS NULL OR a.valid_to>#{today})
              AND e.active AND e.joined_on<=#{today} AND (e.left_on IS NULL OR e.left_on>=#{today})
              AND (#{query}='' OR strpos(lower(e.employee_no),lower(#{query}))>0 OR strpos(lower(e.display_name),lower(#{query}))>0)
            ORDER BY e.employee_no,e.id LIMIT 51 OFFSET #{offset}
            """)
    List<Member> members(@Param("tenantId") UUID tenantId, @Param("organizationId") UUID organizationId,
            @Param("today") LocalDate today, @Param("query") String query, @Param("offset") int offset);

    @Select("""
            <script>
            SELECT m.id AS employee_id,o.name AS organization_name,e.display_name AS organization_head_name,
              COALESCE(o.active AND e.active AND e.joined_on &lt;= #{today}
                AND (e.left_on IS NULL OR e.left_on>=#{today}),false) AS organization_head_eligible,
              COALESCE(o.active AND e.active AND e.joined_on &lt;= #{today} AND (e.left_on IS NULL OR e.left_on>=#{today})
                AND EXISTS(SELECT 1 FROM tna.tenant_account ha JOIN tna.role_grant hg
                  ON hg.tenant_id=ha.tenant_id AND hg.account_id=ha.id
                  WHERE ha.tenant_id=m.tenant_id AND ha.employee_id=e.id AND ha.active AND ha.credential_set
                    AND hg.role='APPROVER' AND hg.active),false) AS organization_head_approver_eligible,
              t.name AS schedule_name
            FROM tna.employee m
            LEFT JOIN tna.employee_assignment a ON a.tenant_id=m.tenant_id AND a.employee_id=m.id
              AND a.valid_from &lt;= #{today} AND (a.valid_to IS NULL OR a.valid_to>#{today})
            LEFT JOIN tna.organization o ON o.tenant_id=a.tenant_id AND o.id=a.organization_id
            LEFT JOIN tna.organization_head_assignment h ON h.tenant_id=o.tenant_id AND h.organization_id=o.id
              AND h.superseded_at IS NULL AND h.valid_from &lt;= #{today} AND (h.valid_to IS NULL OR h.valid_to>#{today})
            LEFT JOIN tna.employee e ON e.tenant_id=h.tenant_id AND e.id=h.employee_id
            LEFT JOIN tna.schedule_assignment sa ON sa.tenant_id=m.tenant_id AND sa.employee_id=m.id
              AND sa.valid_from &lt;= #{today} AND (sa.valid_to IS NULL OR sa.valid_to>#{today})
            LEFT JOIN tna.schedule_version v ON v.tenant_id=sa.tenant_id AND v.id=sa.schedule_version_id
            LEFT JOIN tna.schedule_template t ON t.tenant_id=v.tenant_id AND t.id=v.template_id
            WHERE m.tenant_id=#{tenantId} AND m.id IN
              <foreach collection="employeeIds" item="employeeId" open="(" separator="," close=")">
                #{employeeId}
              </foreach>
            </script>
            """)
    List<EmployeeContextRow> employeeContexts(@Param("tenantId") UUID tenantId, @Param("employeeIds") List<UUID> employeeIds,
            @Param("today") LocalDate today);
}
