package kr.tna.org;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import kr.tna.org.OrgData.Assignment;
import kr.tna.org.OrgData.Employee;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface EmployeeAdminMapper {
    @Select("""
            SELECT id, employee_no, display_name, joined_on, left_on, active, version
            FROM tna.employee
            WHERE tenant_id=#{tenantId}
              AND (employee_no ILIKE #{search} OR display_name ILIKE #{search})
            ORDER BY employee_no, id LIMIT 51 OFFSET #{offset}
            """)
    List<Employee> list(@Param("tenantId") UUID tenantId, @Param("search") String search,
                        @Param("offset") int offset);

    @Select("""
            SELECT id, employee_no, display_name, joined_on, left_on, active, version
            FROM tna.employee WHERE tenant_id=#{tenantId} AND id=#{id}
            """)
    Employee get(@Param("tenantId") UUID tenantId, @Param("id") UUID id);

    @Insert("""
            INSERT INTO tna.employee
                (tenant_id,id,employee_no,display_name,joined_on,left_on,active,version,
                 created_at,created_by,updated_at,updated_by)
            VALUES (#{tenantId},#{employee.id},#{employee.employeeNo},#{employee.displayName},
                    #{employee.joinedOn},#{employee.leftOn},#{employee.active},0,
                    CURRENT_TIMESTAMP,#{actorId},CURRENT_TIMESTAMP,#{actorId})
            """)
    int insert(@Param("tenantId") UUID tenantId, @Param("employee") Employee employee,
               @Param("actorId") UUID actorId);

    @Update("""
            UPDATE tna.employee SET display_name=#{employee.displayName}, joined_on=#{employee.joinedOn},
                left_on=#{employee.leftOn}, active=#{employee.active}, version=version+1,
                updated_at=CURRENT_TIMESTAMP, updated_by=#{actorId}
            WHERE tenant_id=#{tenantId} AND id=#{employee.id} AND version=#{expectedVersion}
            """)
    int update(@Param("tenantId") UUID tenantId, @Param("employee") Employee employee,
               @Param("expectedVersion") long expectedVersion, @Param("actorId") UUID actorId);

    @Update("""
            UPDATE tna.tenant_account SET auth_version=auth_version+1
            WHERE tenant_id=#{tenantId} AND employee_id=#{employeeId}
            """)
    int revokeSessions(@Param("tenantId") UUID tenantId, @Param("employeeId") UUID employeeId);

    @Select("""
            SELECT EXISTS (
                SELECT 1 FROM tna.employee e
                JOIN tna.tenant_account a ON a.tenant_id=e.tenant_id AND a.employee_id=e.id
                JOIN tna.role_grant r ON r.tenant_id=a.tenant_id AND r.account_id=a.id
                WHERE e.tenant_id=#{tenantId} AND e.id=#{employeeId} AND e.active AND a.active
                  AND e.joined_on<=#{today} AND (e.left_on IS NULL OR e.left_on>=#{today})
                  AND r.role='ATTENDANCE_ADMIN')
            """)
    boolean isEffectiveAdmin(@Param("tenantId") UUID tenantId, @Param("employeeId") UUID employeeId,
                              @Param("today") LocalDate today);

    @Select("""
            SELECT EXISTS (
                SELECT 1 FROM tna.employee e
                JOIN tna.tenant_account a ON a.tenant_id=e.tenant_id AND a.employee_id=e.id
                JOIN tna.role_grant r ON r.tenant_id=a.tenant_id AND r.account_id=a.id
                WHERE e.tenant_id=#{tenantId} AND e.id<>#{employeeId} AND e.active AND a.active
                  AND e.joined_on<=#{today} AND e.left_on IS NULL AND r.role='ATTENDANCE_ADMIN')
            """)
    boolean hasOtherPermanentAdmin(@Param("tenantId") UUID tenantId, @Param("employeeId") UUID employeeId,
                                    @Param("today") LocalDate today);

    @Select("SELECT active FROM tna.organization WHERE tenant_id=#{tenantId} AND id=#{id}")
    Boolean organizationActive(@Param("tenantId") UUID tenantId, @Param("id") UUID id);

    @Select("""
            SELECT a.id, a.employee_id, a.organization_id, o.name AS organization_name,
                   a.valid_from, a.valid_to, a.reason, a.version
            FROM tna.employee_assignment a
            JOIN tna.organization o ON o.tenant_id=a.tenant_id AND o.id=a.organization_id
            WHERE a.tenant_id=#{tenantId} AND a.employee_id=#{employeeId}
            ORDER BY a.valid_from DESC, a.id
            """)
    List<Assignment> assignments(@Param("tenantId") UUID tenantId, @Param("employeeId") UUID employeeId);

    @Update("""
            UPDATE tna.employee_assignment SET valid_to=#{validTo}, version=version+1,
                updated_at=CURRENT_TIMESTAMP, updated_by=#{actorId}
            WHERE tenant_id=#{tenantId} AND id=#{id} AND valid_to IS NULL AND version=#{expectedVersion}
            """)
    int closeAssignment(@Param("tenantId") UUID tenantId, @Param("id") UUID id,
                        @Param("validTo") LocalDate validTo, @Param("expectedVersion") long expectedVersion,
                        @Param("actorId") UUID actorId);

    @Insert("""
            INSERT INTO tna.employee_assignment
                (tenant_id,id,employee_id,organization_id,valid_from,reason,created_by,updated_by)
            VALUES (#{tenantId},#{id},#{employeeId},#{organizationId},#{validFrom},#{reason},#{actorId},#{actorId})
            """)
    int insertAssignment(@Param("tenantId") UUID tenantId, @Param("id") UUID id,
                         @Param("employeeId") UUID employeeId, @Param("organizationId") UUID organizationId,
                         @Param("validFrom") LocalDate validFrom, @Param("reason") String reason,
                         @Param("actorId") UUID actorId);

    @Update("""
            UPDATE tna.employee SET version=version+1, updated_at=CURRENT_TIMESTAMP, updated_by=#{actorId}
            WHERE tenant_id=#{tenantId} AND id=#{id} AND version=#{expectedVersion}
            """)
    int advanceVersion(@Param("tenantId") UUID tenantId, @Param("id") UUID id,
                       @Param("expectedVersion") long expectedVersion, @Param("actorId") UUID actorId);
}
