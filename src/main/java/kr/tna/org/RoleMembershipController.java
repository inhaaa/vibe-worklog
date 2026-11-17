package kr.tna.org;

import java.util.List;
import kr.tna.common.auth.TnaPrincipal;
import kr.tna.org.RoleMembershipData.Form;
import kr.tna.org.RoleMembershipData.Outcome;
import kr.tna.org.RoleMembershipData.Problem;
import kr.tna.org.RoleMembershipData.Rejected;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public final class RoleMembershipController {
    private final RoleMembershipService memberships;

    public RoleMembershipController(RoleMembershipService memberships) {
        this.memberships = memberships;
    }

    @PostMapping(path = "/admin/access/roles/{role}/members/bulk",
            consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<Outcome> change(@AuthenticationPrincipal TnaPrincipal actor, @PathVariable String role,
                                    @RequestBody Form command) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(memberships.change(actor, role, command));
    }

    @ExceptionHandler(OrgException.class)
    ResponseEntity<Problem> orgError(OrgException error) {
        Problem problem = error instanceof Rejected rejected ? rejected.problem()
                : new Problem(error.getMessage(), List.of(), List.of());
        return ResponseEntity.status(error.status()).cacheControl(CacheControl.noStore()).body(problem);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<Problem> unreadable() {
        return orgError(new OrgException(400, "권한 변경 요청 형식을 확인해 주세요."));
    }
}
