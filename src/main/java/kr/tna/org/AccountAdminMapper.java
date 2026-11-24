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
