package kr.tna.worktime;

import java.util.Map;
import java.util.UUID;
import kr.tna.common.auth.TnaPrincipal;
import kr.tna.org.OrgException;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public final class SelectiveSettlementController {
    private final WorktimeService worktime;

    public SelectiveSettlementController(WorktimeService worktime) { this.worktime = worktime; }

    @GetMapping(path = "/worktime/selective-settlements", produces = MediaType.APPLICATION_JSON_VALUE)
    SelectiveSettlement.Overview own(@AuthenticationPrincipal TnaPrincipal actor, @RequestParam String month) {
        return worktime.ownSettlements(actor, month);
    }

    @GetMapping(path = "/admin/attendance/month-close/selective-settlements", produces = MediaType.APPLICATION_JSON_VALUE)
    SelectiveSettlement.Overview admin(@AuthenticationPrincipal TnaPrincipal actor, @RequestParam UUID employeeId, @RequestParam String month) {
        return worktime.adminSettlements(actor, employeeId, month);
    }

    @ExceptionHandler(OrgException.class)
    ResponseEntity<?> error(OrgException error) {
        return ResponseEntity.status(error.status()).body(Map.of("message", error.getMessage()));
    }
}
