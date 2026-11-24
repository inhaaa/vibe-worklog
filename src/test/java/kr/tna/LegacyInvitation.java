package kr.tna;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.UUID;
import kr.tna.common.auth.InvitationDelivery;
import org.springframework.jdbc.core.JdbcTemplate;

/** 발급 기능이 사라진 이전 초대 링크를 관리용 SQL로 만들어 수락 경로만 검증한다. */
final class LegacyInvitation {
    private LegacyInvitation() { }

    static InvitationDelivery.Envelope seed(JdbcTemplate admin, UUID tenant, String company, UUID employee,
                                            String email, UUID createdBy) {
        UUID account = UUID.randomUUID();
        admin.update("INSERT INTO tna.tenant_account(tenant_id,id,employee_id,email,active) VALUES (?,?,?,?,false)",
                tenant, account, employee, email);
        admin.update("INSERT INTO tna.role_grant(tenant_id,account_id,role) VALUES (?,?,'EMPLOYEE')", tenant, account);
        String token = UUID.randomUUID().toString().replace("-", "") + "abcdefghijk";
        Instant expires = Instant.now().plus(24, ChronoUnit.HOURS).truncatedTo(ChronoUnit.MILLIS);
        admin.update("""
                INSERT INTO tna.account_token(tenant_id,id,account_id,purpose,token_hash,email,issued_auth_version,expires_at,created_by)
                SELECT tenant_id,?,id,'INVITE',?,email,auth_version,?,? FROM tna.tenant_account WHERE tenant_id=? AND id=?
                """, UUID.randomUUID(), hash(token), Timestamp.from(expires), createdBy, tenant, account);
        admin.update("UPDATE tna.employee SET version=version+1 WHERE tenant_id=? AND id=?", tenant, employee);
        return new InvitationDelivery.Envelope(company, email, token, expires);
    }

    private static String hash(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.US_ASCII)));
        } catch (java.security.NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); }
    }
}
