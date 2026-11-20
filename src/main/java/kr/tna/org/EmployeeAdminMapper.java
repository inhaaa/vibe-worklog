package kr.tna.org;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import kr.tna.org.OrgData.Assignment;
import kr.tna.org.OrgData.Employee;
import kr.tna.worktime.ClosedAttendanceMonths;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface EmployeeAdminMapper extends ClosedAttendanceMonths {
    String DIRECTORY_FILTER = """
              AND (e.employee_no ILIKE #{search} OR e.display_name ILIKE #{search})
              AND (#{status}='all' OR e.active=(#{status}='active'))
              AND (CAST(#{organizationId} AS uuid) IS NULL OR EXISTS (
                  SELECT 1 FROM tna.employee_assignment a
                  WHERE a.tenant_id=e.tenant_id AND a.employee_id=e.id
                    AND a.organization_id=#{organizationId} AND #{today}>=a.valid_from
                    AND (a.valid_to IS NULL OR a.valid_to>#{today})))
            """;

    @Select("""
            SELECT a.employee_id FROM tna.tenant_account a
            WHERE a.tenant_id=#{tenantId} AND a.active AND a.credential_set
              AND EXISTS(SELECT 1 FROM tna.role_grant g WHERE g.tenant_id=a.tenant_id AND g.account_id=a.id
                  AND g.active AND g.role='ATTENDANCE_ADMIN')
              AND EXISTS(SELECT 1 FROM tna.role_grant g WHERE g.tenant_id=a.tenant_id AND g.account_id=a.id
                  AND g.active AND g.role='EMPLOYEE')
            """)
    List<UUID> administratorEmployees(@Param("tenantId") UUID tenantId);

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
            SELECT e.id,e.employee_no,e.display_name,e.joined_on,e.left_on,e.active,e.version
            FROM tna.employee e
            WHERE e.tenant_id=#{tenantId}
            """ + DIRECTORY_FILTER + """
            ORDER BY e.employee_no,e.id LIMIT 51 OFFSET #{offset}
            """)
    List<Employee> directory(@Param("tenantId") UUID tenantId, @Param("search") String search,
                             @Param("offset") int offset, @Param("organizationId") UUID organizationId,
                             @Param("status") String status, @Param("today") LocalDate today);

    record PersonnelBasic(UUID employeeId, UUID onboardingId, String gradeName, String positionName,
                          String jobName, String employmentStatus) { }

    @Select("""
            <script>
            SELECT e.id AS employee_id,b.id AS onboarding_id,p.grade_name,p.position_name,p.job_name,s.employment_status
            FROM tna.employee e
            LEFT JOIN tna.employee_onboarding b ON b.tenant_id=e.tenant_id AND b.employee_id=e.id AND b.state='CONFIRMED'
            LEFT JOIN tna.employee_personnel p ON p.tenant_id=e.tenant_id AND p.employee_id=e.id
            LEFT JOIN LATERAL (
                SELECT x.employment_status FROM tna.employee_employment_status x
                WHERE x.tenant_id=e.tenant_id AND x.employee_id=e.id AND #{today}>=x.effective_from
                ORDER BY x.effective_from DESC,x.revision DESC LIMIT 1
            ) s ON TRUE
            WHERE e.tenant_id=#{tenantId} AND e.id IN
            <foreach collection="employeeIds" item="id" open="(" separator="," close=")">#{id}</foreach>
            </script>
            """)
    List<PersonnelBasic> personnelBasics(@Param("tenantId") UUID tenantId,
            @Param("employeeIds") List<UUID> employeeIds, @Param("today") LocalDate today);

    @Select("""
            <script>
            SELECT e.id AS employee_id,e.employee_no,e.display_name,e.joined_on,e.left_on,e.active,
                   o.code AS organization_code,o.name AS organization_name,t.name AS schedule_name,
                   p.grade_name,p.position_name,p.job_name
            FROM tna.employee e
            LEFT JOIN tna.employee_assignment a ON a.tenant_id=e.tenant_id AND a.employee_id=e.id
              AND #{today}>=a.valid_from AND (a.valid_to IS NULL OR a.valid_to>#{today})
            LEFT JOIN tna.organization o ON o.tenant_id=a.tenant_id AND o.id=a.organization_id
            LEFT JOIN tna.effective_schedule_assignment sa ON sa.tenant_id=e.tenant_id AND sa.employee_id=e.id
              AND #{today}>=sa.valid_from AND (sa.valid_to IS NULL OR sa.valid_to>#{today})
            LEFT JOIN tna.schedule_version v ON v.tenant_id=sa.tenant_id AND v.id=sa.schedule_version_id
            LEFT JOIN tna.schedule_template t ON t.tenant_id=v.tenant_id AND t.id=v.template_id
            LEFT JOIN tna.employee_personnel p ON p.tenant_id=e.tenant_id AND p.employee_id=e.id
            WHERE e.tenant_id=#{tenantId}
            <choose>
            <when test="employeeIds != null">
              AND e.id IN <foreach collection="employeeIds" item="id" open="(" separator="," close=")">#{id}</foreach>
            </when>
            <otherwise>
            """ + DIRECTORY_FILTER + """
            </otherwise>
            </choose>
            ORDER BY e.employee_no,e.id LIMIT 1001
            </script>
            """)
    List<PersonnelExportService.Row> personnelExport(@Param("tenantId") UUID tenantId,
            @Param("employeeIds") List<UUID> employeeIds, @Param("search") String search,
            @Param("organizationId") UUID organizationId, @Param("status") String status, @Param("today") LocalDate today);

    @Select("""
            <script>
            SELECT a.employee_id,o.name AS organization_name
            FROM tna.employee_assignment a
            JOIN tna.organization o ON o.tenant_id=a.tenant_id AND o.id=a.organization_id
            WHERE a.tenant_id=#{tenantId} AND a.valid_from &lt;= #{today}
              AND (a.valid_to IS NULL OR a.valid_to>#{today})
              AND a.employee_id IN
              <foreach collection="employeeIds" item="employeeId" open="(" separator="," close=")">
                #{employeeId}
              </foreach>
            </script>
            """)
    List<OrgData.EmployeeOrganization> currentOrganizations(@Param("tenantId") UUID tenantId,
            @Param("employeeIds") List<UUID> employeeIds, @Param("today") LocalDate today);

    @Select("""
            SELECT id, employee_no, display_name, joined_on, left_on, active, version
            FROM tna.employee WHERE tenant_id=#{tenantId} ORDER BY employee_no, id
            """)
    List<Employee> options(@Param("tenantId") UUID tenantId);

    @Select("SELECT employee_no FROM tna.employee WHERE tenant_id=#{tenantId}")
    List<String> employeeNumbers(@Param("tenantId") UUID tenantId);

    @Select("""
            SELECT id, employee_no, display_name, joined_on, left_on, active, version
            FROM tna.employee WHERE tenant_id=#{tenantId} AND id=#{id}
            """)
    Employee get(@Param("tenantId") UUID tenantId, @Param("id") UUID id);

    @Select("""
            SELECT work_date FROM tna.checkout_follow_up
            WHERE tenant_id=#{tenantId} AND employee_id=#{before.id} AND resolved_at IS NULL
              AND work_date BETWEEN #{before.joinedOn} AND coalesce(#{before.leftOn}::date, 'infinity'::date)
              AND work_date NOT BETWEEN #{joined} AND coalesce(#{left}::date, 'infinity'::date)
            ORDER BY work_date LIMIT 1
            """)
    LocalDate firstNewlyExcludedFollowUp(@Param("tenantId") UUID tenantId, @Param("before") Employee before,
            @Param("joined") LocalDate joined, @Param("left") LocalDate left);

    @Select("""
            SELECT id,employee_no,display_name,joined_on,left_on,active,version FROM tna.employee
            WHERE tenant_id=#{tenantId} AND employee_no=#{employeeNo}
            """)
    Employee byNumber(@Param("tenantId") UUID tenantId, @Param("employeeNo") String employeeNo);

    @Select("""
            <script>
            SELECT id,employee_no,display_name,joined_on,left_on,active,version
            FROM tna.employee WHERE tenant_id=#{tenantId} AND id IN
            <foreach collection="ids" item="id" open="(" separator="," close=")">#{id}</foreach>
            </script>
            """)
    List<Employee> getMany(@Param("tenantId") UUID tenantId, @Param("ids") List<UUID> ids);

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
                  AND r.active AND r.role='ATTENDANCE_ADMIN')
            """)
    boolean isEffectiveAdmin(@Param("tenantId") UUID tenantId, @Param("employeeId") UUID employeeId,
                              @Param("today") LocalDate today);

    @Select("""
            SELECT EXISTS (
                SELECT 1 FROM tna.employee e
                JOIN tna.tenant_account a ON a.tenant_id=e.tenant_id AND a.employee_id=e.id
                JOIN tna.role_grant r ON r.tenant_id=a.tenant_id AND r.account_id=a.id
                WHERE e.tenant_id=#{tenantId} AND e.id<>#{employeeId} AND e.active AND a.active
                  AND e.joined_on<=#{today} AND e.left_on IS NULL AND r.active AND r.role='ATTENDANCE_ADMIN')
            """)
    boolean hasOtherPermanentAdmin(@Param("tenantId") UUID tenantId, @Param("employeeId") UUID employeeId,
                                    @Param("today") LocalDate today);

    @Select("""
            SELECT EXISTS (
                SELECT 1 FROM tna.employee e
                JOIN tna.tenant_account a ON a.tenant_id=e.tenant_id AND a.employee_id=e.id
                JOIN tna.role_grant r ON r.tenant_id=a.tenant_id AND r.account_id=a.id
                WHERE e.tenant_id=#{tenantId} AND e.active AND a.active AND a.credential_set
                  AND e.joined_on<=#{today} AND e.left_on IS NULL AND r.active AND r.role='ATTENDANCE_ADMIN')
            """)
    boolean hasPermanentAdmin(@Param("tenantId") UUID tenantId, @Param("today") LocalDate today);

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
