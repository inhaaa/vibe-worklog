package kr.tna.approval;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import kr.tna.approval.ApprovalRouteData.*;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface ApprovalRouteMapper {
    @Select("SELECT pg_advisory_xact_lock_shared(hashtextextended(CAST(#{tenantId} AS text),0))")
    Object lock(@Param("tenantId") UUID tenantId);

    String ROUTE_SELECT = """
            SELECT r.id,r.code,r.name,r.active,r.version,v.id AS route_version_id,v.revision,v.approver_kind,
              v.employee_id,e.display_name AS employee_name
            FROM tna.approval_route r JOIN LATERAL (
              SELECT id,revision,approver_kind,employee_id FROM tna.approval_route_version
              WHERE tenant_id=r.tenant_id AND route_id=r.id ORDER BY revision DESC LIMIT 1
            ) v ON true LEFT JOIN tna.employee e ON e.tenant_id=r.tenant_id AND e.id=v.employee_id
            WHERE r.tenant_id=#{tenantId}
            """;
    @Select(ROUTE_SELECT + " AND (#{q}='' OR strpos(lower(r.code),lower(#{q}))>0 OR strpos(lower(r.name),lower(#{q}))>0) ORDER BY r.code,r.id LIMIT 51 OFFSET #{offset}")
    List<Route> list(@Param("tenantId") UUID tenantId, @Param("q") String q, @Param("offset") int offset);
    @Select(ROUTE_SELECT + " AND r.id=#{id}")
    Route get(@Param("tenantId") UUID tenantId, @Param("id") UUID id);
    @Select(ROUTE_SELECT + " AND r.code=#{code}")
    Route code(@Param("tenantId") UUID tenantId, @Param("code") String code);

    String VERSION_SELECT = """
            SELECT v.id,v.route_id,r.code,v.name,r.active,v.revision,v.approver_kind,v.employee_id,
              e.display_name AS employee_name,v.reason,v.created_at
            FROM tna.approval_route_version v JOIN tna.approval_route r ON r.tenant_id=v.tenant_id AND r.id=v.route_id
            LEFT JOIN tna.employee e ON e.tenant_id=v.tenant_id AND e.id=v.employee_id
            WHERE v.tenant_id=#{tenantId}
            """;
    @Select(VERSION_SELECT + " ORDER BY r.code,v.revision DESC,v.id")
    List<RouteVersion> versions(@Param("tenantId") UUID tenantId);
    @Select(VERSION_SELECT + " AND v.id=#{id}")
    RouteVersion version(@Param("tenantId") UUID tenantId, @Param("id") UUID id);

    @Insert("""
            INSERT INTO tna.approval_route(tenant_id,id,code,name,active,created_by,updated_by)
            VALUES (#{tenantId},#{r.id},#{r.code},#{r.name},#{r.active},#{actorId},#{actorId})
            """)
    int insert(@Param("tenantId") UUID tenantId, @Param("r") Route route, @Param("actorId") UUID actorId);
    @Update("""
            UPDATE tna.approval_route SET name=#{r.name},active=#{r.active},version=version+1,
              updated_at=CURRENT_TIMESTAMP,updated_by=#{actorId}
            WHERE tenant_id=#{tenantId} AND id=#{r.id} AND version=#{expectedVersion}
            """)
    int update(@Param("tenantId") UUID tenantId, @Param("r") Route route,
            @Param("expectedVersion") long expectedVersion, @Param("actorId") UUID actorId);
    @Insert("""
            INSERT INTO tna.approval_route_version(tenant_id,id,route_id,revision,name,approver_kind,employee_id,reason,created_by)
            VALUES (#{tenantId},#{r.routeVersionId},#{r.id},#{r.revision},#{r.name},#{r.approverKind},#{r.employeeId},#{reason},#{actorId})
            """)
    int insertVersion(@Param("tenantId") UUID tenantId, @Param("r") Route route,
            @Param("reason") String reason, @Param("actorId") UUID actorId);

    String ASSIGNMENT_SELECT = """
            SELECT a.id,a.route_version_id,r.code AS route_code,v.name AS route_name,v.revision,v.approver_kind,
              e.display_name AS approver_employee_name,a.scope_kind,a.employee_id,a.organization_id,
              a.valid_from,a.valid_to,a.superseded_at,a.reason,a.version,r.active AS route_active
            FROM tna.approval_route_assignment a JOIN tna.approval_route_version v ON v.tenant_id=a.tenant_id AND v.id=a.route_version_id
            JOIN tna.approval_route r ON r.tenant_id=v.tenant_id AND r.id=v.route_id
            LEFT JOIN tna.employee e ON e.tenant_id=v.tenant_id AND e.id=v.employee_id
            WHERE a.tenant_id=#{tenantId} AND a.scope_kind=#{scopeKind}
              AND (a.employee_id=#{targetId} OR a.organization_id=#{targetId})
            """;
    @Select(ASSIGNMENT_SELECT + " ORDER BY a.valid_from DESC,a.created_at DESC,a.id")
    List<RouteAssignment> history(@Param("tenantId") UUID tenantId, @Param("scopeKind") String scopeKind, @Param("targetId") UUID targetId);
    @Select(ASSIGNMENT_SELECT + " AND a.superseded_at IS NULL AND a.valid_from<=#{date} AND (a.valid_to IS NULL OR a.valid_to>#{date})")
    RouteAssignment covering(@Param("tenantId") UUID tenantId, @Param("scopeKind") String scopeKind,
            @Param("targetId") UUID targetId, @Param("date") LocalDate date);
    @Select("""
            SELECT min(valid_from) FROM tna.approval_route_assignment WHERE tenant_id=#{tenantId}
              AND scope_kind=#{scopeKind} AND (employee_id=#{targetId} OR organization_id=#{targetId})
              AND superseded_at IS NULL AND valid_from>#{date}
            """)
    LocalDate nextStart(@Param("tenantId") UUID tenantId, @Param("scopeKind") String scopeKind,
            @Param("targetId") UUID targetId, @Param("date") LocalDate date);
    @Update("""
            UPDATE tna.approval_route_assignment
            SET valid_to=CASE WHEN valid_from<#{date} THEN #{date} ELSE valid_to END,
              superseded_at=CASE WHEN valid_from=#{date} THEN CURRENT_TIMESTAMP ELSE superseded_at END,
              version=version+1,updated_at=CURRENT_TIMESTAMP,updated_by=#{actorId}
            WHERE tenant_id=#{tenantId} AND id=#{id} AND version=#{version} AND superseded_at IS NULL
              AND valid_from<=#{date} AND (valid_to IS NULL OR valid_to>#{date})
            """)
    int close(@Param("tenantId") UUID tenantId, @Param("id") UUID id, @Param("date") LocalDate date,
            @Param("version") long version, @Param("actorId") UUID actorId);
    @Insert("""
            INSERT INTO tna.approval_route_assignment(tenant_id,id,route_version_id,scope_kind,employee_id,organization_id,
              valid_from,valid_to,reason,created_by,updated_by)
            VALUES (#{tenantId},#{id},#{f.routeVersionId},#{f.scopeKind},#{f.employeeId},#{f.organizationId},#{f.validFrom},#{to},#{reason},#{actorId},#{actorId})
            """)
    int insertAssignment(@Param("tenantId") UUID tenantId, @Param("id") UUID id, @Param("f") AssignmentForm form,
            @Param("to") LocalDate to, @Param("reason") String reason, @Param("actorId") UUID actorId);
    @Update("""
            UPDATE tna.organization SET version=version+1,updated_at=CURRENT_TIMESTAMP,updated_by=#{actorId}
            WHERE tenant_id=#{tenantId} AND id=#{id} AND version=#{version}
            """)
    int advanceOrganization(@Param("tenantId") UUID tenantId, @Param("id") UUID id,
            @Param("version") long version, @Param("actorId") UUID actorId);
}
