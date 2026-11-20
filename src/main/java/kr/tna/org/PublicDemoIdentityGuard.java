package kr.tna.org;

import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
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

    public void requireMutableEmployees(TnaPrincipal actor, Supplier<List<UUID>> affected) {
        if (demo.publicDemo() && actor != null && LocalDemoLogin.TENANT_ID.equals(actor.tenantId())) {
            affected.get().forEach(id -> requireMutable(actor, id));
        }
    }

    boolean protects(TnaPrincipal actor, UUID employeeId) {
        return demo.publicDemo() && actor != null && employeeId != null && LocalDemoLogin.TENANT_ID.equals(actor.tenantId())
                && LocalDemoLogin.SHARED_EMPLOYEE_IDS.contains(employeeId);
    }

    boolean publicDemo() { return demo.publicDemo(); }
}
