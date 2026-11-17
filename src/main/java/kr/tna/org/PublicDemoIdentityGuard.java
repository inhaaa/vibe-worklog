package kr.tna.org;

import java.util.UUID;
import kr.tna.common.auth.LocalDemoLogin;
import kr.tna.common.auth.TnaPrincipal;
import org.springframework.stereotype.Component;

@Component
public final class PublicDemoIdentityGuard {
    private final LocalDemoLogin demo;

    public PublicDemoIdentityGuard(LocalDemoLogin demo) {
        this.demo = demo;
    }

    public void requireMutable(TnaPrincipal actor, UUID employeeId) {
        if (protects(actor, employeeId)) {
            throw new OrgException(403, "공유 데모 계정의 사원 정보와 계정은 변경할 수 없습니다.");
        }
    }

    boolean protects(TnaPrincipal actor, UUID employeeId) {
        return demo.publicDemo() && actor != null && employeeId != null && LocalDemoLogin.TENANT_ID.equals(actor.tenantId())
                && LocalDemoLogin.SHARED_EMPLOYEE_IDS.contains(employeeId);
    }

    boolean publicDemo() { return demo.publicDemo(); }
}
