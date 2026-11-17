package kr.tna.org;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface OrganizationAdminMapper {
    @Select("""
            SELECT id, code, name, parent_id, active, version
            FROM tna.organization
            WHERE tenant_id = #{tenantId}
              AND (#{query} = '' OR strpos(lower(code), lower(#{query})) > 0
                   OR strpos(lower(name), lower(#{query})) > 0)
            ORDER BY code, id LIMIT 51 OFFSET #{offset}
            """)
    List<OrgData.Organization> list(@Param("tenantId") UUID tenantId, @Param("query") String query,
                                   @Param("offset") int offset);

    @Select("""
            SELECT id, code, name, parent_id, active, version
            FROM tna.organization WHERE tenant_id = #{tenantId} ORDER BY code, id
            """)
    List<OrgData.Organization> options(@Param("tenantId") UUID tenantId);

    @Select("""
            SELECT id, code, name, parent_id, active, version
            FROM tna.organization WHERE tenant_id = #{tenantId} AND id = #{id}
            """)
    OrgData.Organization find(@Param("tenantId") UUID tenantId, @Param("id") UUID id);

    @Select("SELECT code FROM tna.organization WHERE tenant_id=#{tenantId}")
    List<String> codes(@Param("tenantId") UUID tenantId);

    @Select("""
            SELECT result_row.item->>'clientRowId' AS client_row_id, organization.id, organization.code
            FROM tna.org_audit_event batch
            CROSS JOIN LATERAL jsonb_array_elements(batch.after_state->'rows') WITH ORDINALITY result_row(item, position)
            JOIN tna.organization organization ON organization.tenant_id=batch.tenant_id
                AND organization.id=(result_row.item->>'id')::uuid
            WHERE batch.tenant_id=#{tenantId} AND batch.actor_account_id=#{actorId}
              AND batch.command_id=#{commandId} AND batch.action='ORGANIZATION_BULK' AND batch.target_kind='BATCH'
              AND result_row.item->>'version'='0'
            ORDER BY result_row.position
            """)
    List<OrgData.CreatedOrganization> creationResults(@Param("tenantId") UUID tenantId, @Param("actorId") UUID actorId,
                                                       @Param("commandId") UUID commandId);

    @Insert("""
            INSERT INTO tna.organization
                (tenant_id, id, code, name, parent_id, active, created_at, created_by, updated_at, updated_by)
            VALUES (#{tenantId}, #{organization.id}, #{organization.code}, #{organization.name},
                    #{organization.parentId}, #{organization.active}, #{createdAt}, #{actorId}, #{createdAt}, #{actorId})
            """)
    int insert(@Param("tenantId") UUID tenantId, @Param("organization") OrgData.Organization organization,
               @Param("actorId") UUID actorId, @Param("createdAt") Instant createdAt);

    @Update("""
            UPDATE tna.organization
            SET name = #{organization.name}, parent_id = #{organization.parentId}, active = #{organization.active},
                version = version + 1, updated_at = CURRENT_TIMESTAMP, updated_by = #{actorId}
            WHERE tenant_id = #{tenantId} AND id = #{organization.id} AND version = #{organization.version}
            """)
    int update(@Param("tenantId") UUID tenantId, @Param("organization") OrgData.Organization organization,
               @Param("actorId") UUID actorId);

    @Select("""
            SELECT EXISTS (SELECT 1 FROM tna.organization
                           WHERE tenant_id = #{tenantId} AND parent_id = #{id} AND active)
            """)
    boolean hasActiveChildren(@Param("tenantId") UUID tenantId, @Param("id") UUID id);

    @Select("""
            SELECT EXISTS (SELECT 1 FROM tna.employee_assignment
                           WHERE tenant_id = #{tenantId} AND organization_id = #{id}
                             AND (valid_to IS NULL OR valid_to > #{today}))
            """)
    boolean hasCurrentOrFutureAssignments(@Param("tenantId") UUID tenantId, @Param("id") UUID id,
                                          @Param("today") LocalDate today);
}
