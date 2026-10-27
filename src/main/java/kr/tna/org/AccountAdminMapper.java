package kr.tna.org;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface AccountAdminMapper {
    @Select("""
            SELECT e.id AS employee_id,e.employee_no,e.display_name AS employee_name,
                coalesce(string_agg(g.role,',' ORDER BY g.role) FILTER (WHERE g.active),'') AS granted_roles,
                e.active AND e.joined_on<=#{today} AND (e.left_on IS NULL OR e.left_on>=#{today})
                  AND coalesce(a.active AND a.credential_set,false) AS active
            FROM tna.employee e
            LEFT JOIN tna.tenant_account a ON a.tenant_id=e.tenant_id AND a.employee_id=e.id
            LEFT JOIN tna.role_grant g ON g.tenant_id=a.tenant_id AND g.account_id=a.id
            WHERE e.tenant_id=#{tenantId} AND (e.employee_no ILIKE #{search} OR e.display_name ILIKE #{search})
            GROUP BY e.id,e.employee_no,e.display_name,e.active,e.joined_on,e.left_on,a.active,a.credential_set
            ORDER BY e.employee_no,e.id LIMIT 51 OFFSET #{offset}
            """)
    List<DirectoryRow> directory(@Param("tenantId") UUID tenantId, @Param("search") String search,
                                @Param("offset") int offset, @Param("today") LocalDate today);

    @Select("""
            SELECT id,employee_id,email,active,credential_set,auth_version
            FROM tna.tenant_account WHERE tenant_id=#{tenantId} AND employee_id=#{employeeId}
            """)
    AccountRow account(@Param("tenantId") UUID tenantId, @Param("employeeId") UUID employeeId);

    @Select("SELECT role FROM tna.role_grant WHERE tenant_id=#{tenantId} AND account_id=#{accountId} AND active ORDER BY role")
    List<String> roles(@Param("tenantId") UUID tenantId, @Param("accountId") UUID accountId);

    @Select("SELECT company_code FROM platform.tenant_registry WHERE id=#{tenantId} AND active")
    String companyCode(@Param("tenantId") UUID tenantId);

    @Insert("""
            INSERT INTO tna.tenant_account(tenant_id,id,employee_id,email,active)
            VALUES (#{tenantId},#{accountId},#{employeeId},#{email},false)
            """)
    int insertAccount(@Param("tenantId") UUID tenantId, @Param("accountId") UUID accountId,
                      @Param("employeeId") UUID employeeId, @Param("email") String email);

    @Insert("""
            INSERT INTO tna.role_grant(tenant_id,account_id,role,active)
            VALUES (#{tenantId},#{accountId},#{role},#{active})
            ON CONFLICT(tenant_id,account_id,role) DO UPDATE SET active=EXCLUDED.active
            WHERE role_grant.tenant_id=#{tenantId}
            """)
    int setRole(@Param("tenantId") UUID tenantId, @Param("accountId") UUID accountId,
                @Param("role") String role, @Param("active") boolean active);

    @Update("""
            UPDATE tna.tenant_account SET auth_version=auth_version+1
            WHERE tenant_id=#{tenantId} AND id=#{accountId}
            """)
    int revokeSessions(@Param("tenantId") UUID tenantId, @Param("accountId") UUID accountId);

    @Update("""
            UPDATE tna.account_token SET revoked_at=#{now}
            WHERE tenant_id=#{tenantId} AND account_id=#{accountId} AND purpose='INVITE'
              AND used_at IS NULL AND revoked_at IS NULL
            """)
    int revokeInvitations(@Param("tenantId") UUID tenantId, @Param("accountId") UUID accountId,
                          @Param("now") Instant now);

    @Insert("""
            INSERT INTO tna.account_token
                (tenant_id,id,account_id,purpose,token_hash,email,issued_auth_version,expires_at,created_by)
            VALUES (#{tenantId},#{id},#{accountId},'INVITE',#{tokenHash},#{email},#{authVersion},#{expiresAt},#{actorId})
            """)
    int insertInvitation(@Param("tenantId") UUID tenantId, @Param("id") UUID id,
            @Param("accountId") UUID accountId, @Param("tokenHash") String tokenHash,
            @Param("email") String email, @Param("authVersion") long authVersion,
            @Param("expiresAt") Instant expiresAt, @Param("actorId") UUID actorId);

    record AccountRow(UUID id, UUID employeeId, String email, boolean active,
                      boolean credentialSet, long authVersion) { }
    record DirectoryRow(UUID employeeId, String employeeNo, String employeeName, String grantedRoles, boolean active) { }
    record AccessRow(UUID employeeId, String employeeNo, String employeeName, Set<String> roles, boolean active) { }
}
