package kr.tna.org;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface AccountAdminMapper {
    @Select("""
            SELECT id,employee_id,email,active,credential_set,auth_version,activation_expires_at,activation_failures
            FROM tna.tenant_account WHERE tenant_id=#{tenantId} AND employee_id=#{employeeId}
            """)
    AccountRow account(@Param("tenantId") UUID tenantId, @Param("employeeId") UUID employeeId);

    @Select("SELECT role FROM tna.role_grant WHERE tenant_id=#{tenantId} AND account_id=#{accountId} AND active ORDER BY role")
    List<String> roles(@Param("tenantId") UUID tenantId, @Param("accountId") UUID accountId);

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

    @Insert("""
            INSERT INTO tna.tenant_account(tenant_id,id,employee_id,active,activation_expires_at)
            VALUES (#{tenantId},#{accountId},#{employeeId},false,#{expiresAt})
            """)
    int insertPendingAccount(@Param("tenantId") UUID tenantId, @Param("accountId") UUID accountId,
                             @Param("employeeId") UUID employeeId, @Param("expiresAt") Instant expiresAt);

    @Update("""
            UPDATE tna.tenant_account
            SET activation_expires_at=#{expiresAt},activation_failures=0,auth_version=auth_version+1
            WHERE tenant_id=#{tenantId} AND id=#{accountId}
            """)
    int reopenActivation(@Param("tenantId") UUID tenantId, @Param("accountId") UUID accountId,
                         @Param("expiresAt") Instant expiresAt);

    record AccountRow(UUID id, UUID employeeId, String email, boolean active, boolean credentialSet,
                      long authVersion, Instant activationExpiresAt, int activationFailures) { }
}
