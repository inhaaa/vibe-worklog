package kr.tna.org;

import java.util.Set;
import java.util.UUID;
import kr.tna.common.auth.LocalDemoLogin;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

@Component
final class RoleManagementProtection {
    static final String SHARED_DEMO_RESTRICTION = "공유 데모 보호";

    private final boolean publicDemo;

    RoleManagementProtection(Environment environment) {
        publicDemo = environment.matchesProfiles("public-demo");
    }

    Set<UUID> protectedEmployeeIds(UUID tenantId) {
        return publicDemo && LocalDemoLogin.TENANT_ID.equals(tenantId) ? LocalDemoLogin.SHARED_EMPLOYEE_IDS : Set.of();
    }

    void requireMutable(UUID tenantId, UUID employeeId) {
        if (protectedEmployeeIds(tenantId).contains(employeeId)) {
            throw new OrgException(403, "공유 데모 계정의 권한은 변경할 수 없습니다.");
        }
    }
}
