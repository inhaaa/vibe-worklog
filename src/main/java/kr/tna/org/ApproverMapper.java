package kr.tna.org;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import kr.tna.org.AccountData.ApproverAssignment;
import kr.tna.org.OrgData.Employee;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface ApproverMapper {
    @Select("""
            SELECT s.id, s.employee_id, s.approver_employee_id, p.display_name AS approver_name,
                   s.valid_from, s.valid_to, s.superseded_at, s.reason, s.version,
                   (s.superseded_at IS NULL AND s.valid_from<=#{today}
                    AND (s.valid_to IS NULL OR s.valid_to>#{today})
                    AND e.active AND e.joined_on<=#{today} AND (e.left_on IS NULL OR e.left_on>=#{today})
                    AND p.active AND p.joined_on<=#{today} AND (p.left_on IS NULL OR p.left_on>=#{today})
                    AND EXISTS (
                        SELECT 1 FROM tna.tenant_account a
                        JOIN tna.role_grant r ON r.tenant_id=a.tenant_id AND r.account_id=a.id
                        WHERE a.tenant_id=#{tenantId} AND a.employee_id=p.id AND a.active AND a.credential_set
                          AND r.role='APPROVER' AND r.active)) AS eligible
            FROM tna.approver_assignment s
            JOIN tna.employee p ON p.tenant_id=s.tenant_id AND p.id=s.approver_employee_id
            JOIN tna.employee e ON e.tenant_id=s.tenant_id AND e.id=s.employee_id
            WHERE s.tenant_id=#{tenantId} AND s.employee_id=#{employeeId}
            ORDER BY s.valid_from DESC, s.created_at DESC, s.id
            """)
    List<ApproverAssignment> history(@Param("tenantId") UUID tenantId, @Param("employeeId") UUID employeeId,
                                      @Param("today") LocalDate today);

    @Select("""
            SELECT e.id, e.employee_no, e.display_name, e.joined_on, e.left_on, e.active, e.version
            FROM tna.employee e
            JOIN tna.tenant_account a ON a.tenant_id=e.tenant_id AND a.employee_id=e.id
            JOIN tna.role_grant r ON r.tenant_id=a.tenant_id AND r.account_id=a.id
            WHERE e.tenant_id=#{tenantId} AND e.active AND e.joined_on<=#{today}
              AND (e.left_on IS NULL OR e.left_on>=#{today})
              AND a.active AND a.credential_set AND r.role='APPROVER' AND r.active
            ORDER BY e.employee_no, e.id
            """)
    List<Employee> candidates(@Param("tenantId") UUID tenantId, @Param("today") LocalDate today);

    @Select("""
            SELECT EXISTS (
                SELECT 1 FROM tna.employee e
                JOIN tna.tenant_account a ON a.tenant_id=e.tenant_id AND a.employee_id=e.id
                JOIN tna.role_grant r ON r.tenant_id=a.tenant_id AND r.account_id=a.id
                WHERE e.tenant_id=#{tenantId} AND e.id=#{employeeId} AND e.active
                  AND e.joined_on<=#{today} AND (e.left_on IS NULL OR e.left_on>=#{today})
                  AND a.active AND a.credential_set AND r.role='APPROVER' AND r.active)
            """)
    boolean eligible(@Param("tenantId") UUID tenantId, @Param("employeeId") UUID employeeId,
                      @Param("today") LocalDate today);

    @Update("""
            UPDATE tna.approver_assignment
            SET valid_to=CASE WHEN valid_from<#{today} THEN #{today} ELSE valid_to END,
                superseded_at=CASE WHEN valid_from=#{today} THEN CURRENT_TIMESTAMP ELSE superseded_at END,
                version=version+1, updated_at=CURRENT_TIMESTAMP, updated_by=#{actorId}
            WHERE tenant_id=#{tenantId} AND id=#{id} AND version=#{expectedVersion}
              AND superseded_at IS NULL AND valid_from<=#{today}
              AND (valid_to IS NULL OR valid_to>#{today})
            """)
    int close(@Param("tenantId") UUID tenantId, @Param("id") UUID id, @Param("today") LocalDate today,
              @Param("expectedVersion") long expectedVersion, @Param("actorId") UUID actorId);

    @Insert("""
            INSERT INTO tna.approver_assignment
                (tenant_id,id,employee_id,approver_employee_id,valid_from,reason,created_by,updated_by)
            VALUES (#{tenantId},#{id},#{employeeId},#{approverEmployeeId},#{validFrom},#{reason},#{actorId},#{actorId})
            """)
    int insert(@Param("tenantId") UUID tenantId, @Param("id") UUID id, @Param("employeeId") UUID employeeId,
               @Param("approverEmployeeId") UUID approverEmployeeId, @Param("validFrom") LocalDate validFrom,
               @Param("reason") String reason, @Param("actorId") UUID actorId);
}
