package kr.tna.org;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import kr.tna.org.EmployeePersonnelData.Header;
import kr.tna.org.EmployeePersonnelData.Stored;
import kr.tna.org.EmployeePersonnelData.StatusHistory;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface EmployeePersonnelMapper {
    @Select("SELECT pg_advisory_xact_lock_shared(hashtextextended(CAST(#{tenantId} AS text),0))")
    Object lock(@Param("tenantId") UUID tenantId);

    @Select("""
            SELECT e.id AS employee_id,e.employee_no,e.display_name AS employee_name,
                r.display_name AS company_name,o.name AS organization_name,e.joined_on,e.left_on,
                p.birth_date,p.grade_name,p.position_name,p.job_name,
                CASE WHEN e.joined_on>#{today} THEN 'PRE_JOINING'
                     WHEN e.left_on<#{today} THEN 'LEFT'
                     ELSE coalesce(s.employment_status,'UNKNOWN') END AS employment_status,
                CASE WHEN p.photo_content_type IS NOT NULL THEN #{photoUrl} || '?v=' || p.version::text ELSE NULL END AS photo_url,
                CAST(#{asOf} AS timestamptz) AS as_of
            FROM tna.employee e JOIN platform.tenant_registry r ON r.id=e.tenant_id
            LEFT JOIN tna.employee_personnel p ON p.tenant_id=e.tenant_id AND p.employee_id=e.id
            LEFT JOIN tna.employee_assignment a ON a.tenant_id=e.tenant_id AND a.employee_id=e.id
                AND a.valid_from<=#{today} AND (a.valid_to IS NULL OR a.valid_to>#{today})
            LEFT JOIN tna.organization o ON o.tenant_id=a.tenant_id AND o.id=a.organization_id
            LEFT JOIN LATERAL (
                SELECT employment_status FROM tna.employee_employment_status s
                WHERE s.tenant_id=e.tenant_id AND s.employee_id=e.id AND s.effective_from<=#{today}
                ORDER BY s.effective_from DESC,s.revision DESC LIMIT 1
            ) s ON true
            WHERE e.tenant_id=#{tenantId} AND e.id=#{employeeId}
            """)
    Header header(@Param("tenantId") UUID tenantId, @Param("employeeId") UUID employeeId,
            @Param("today") LocalDate today, @Param("asOf") Instant asOf, @Param("photoUrl") String photoUrl);

    @Select("""
            SELECT birth_date,grade_name,position_name,job_name,photo_content_type,photo_bytes,version
            FROM tna.employee_personnel WHERE tenant_id=#{tenantId} AND employee_id=#{employeeId}
            """)
    Stored stored(@Param("tenantId") UUID tenantId, @Param("employeeId") UUID employeeId);

    @Select("""
            SELECT id,employment_status,effective_from,revision,reason,created_at
            FROM tna.employee_employment_status WHERE tenant_id=#{tenantId} AND employee_id=#{employeeId}
            ORDER BY effective_from DESC,revision DESC
            """)
    List<StatusHistory> history(@Param("tenantId") UUID tenantId, @Param("employeeId") UUID employeeId);

    @Insert("""
            INSERT INTO tna.employee_personnel(tenant_id,employee_id,birth_date,grade_name,position_name,
                job_name,photo_content_type,photo_bytes,version,updated_by)
            VALUES (#{tenantId},#{employeeId},#{data.birthDate},#{data.gradeName},#{data.positionName},
                #{data.jobName},#{data.photoContentType},#{data.photoBytes},#{data.version},#{actorId})
            ON CONFLICT (tenant_id,employee_id) DO UPDATE SET birth_date=excluded.birth_date,
                grade_name=excluded.grade_name,position_name=excluded.position_name,job_name=excluded.job_name,
                photo_content_type=excluded.photo_content_type,photo_bytes=excluded.photo_bytes,
                version=excluded.version,updated_at=CURRENT_TIMESTAMP,updated_by=excluded.updated_by
            WHERE tna.employee_personnel.version=#{expectedVersion}
            """)
    int save(@Param("tenantId") UUID tenantId, @Param("employeeId") UUID employeeId,
            @Param("data") Stored data, @Param("expectedVersion") long expectedVersion,
            @Param("actorId") UUID actorId);

    @Insert("""
            INSERT INTO tna.employee_employment_status(tenant_id,id,employee_id,employment_status,
                effective_from,revision,reason,created_by)
            VALUES (#{tenantId},#{id},#{employeeId},#{status},#{effectiveFrom},#{revision},#{reason},#{actorId})
            """)
    int appendStatus(@Param("tenantId") UUID tenantId, @Param("id") UUID id,
            @Param("employeeId") UUID employeeId, @Param("status") String status,
            @Param("effectiveFrom") LocalDate effectiveFrom, @Param("revision") long revision,
            @Param("reason") String reason, @Param("actorId") UUID actorId);
}
