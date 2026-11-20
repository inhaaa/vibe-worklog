package kr.tna.worktime;

import java.util.Map;
import java.util.UUID;
import kr.tna.common.auth.TnaPrincipal;
import kr.tna.org.OrgException;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public final class CheckoutFollowUpController {
    private final CheckoutFollowUpService followUps;

    public CheckoutFollowUpController(CheckoutFollowUpService followUps) { this.followUps = followUps; }

    @PostMapping(value = "/admin/attendance/follow-ups", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    Map<String, UUID> request(@AuthenticationPrincipal TnaPrincipal actor, @RequestBody CheckoutFollowUpService.Form form) {
        return Map.of("id", followUps.request(actor, form));
    }

    @ExceptionHandler(OrgException.class)
    ResponseEntity<?> error(OrgException error) {
        return ResponseEntity.status(error.status()).body(Map.of("message", error.getMessage()));
    }
}
