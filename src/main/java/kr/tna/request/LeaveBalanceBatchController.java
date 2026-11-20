package kr.tna.request;

import java.util.Map;
import kr.tna.common.auth.TnaPrincipal;
import kr.tna.org.BulkData;
import kr.tna.org.OrgException;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(value="/admin/leave-balances/bulk", produces=MediaType.APPLICATION_JSON_VALUE)
public final class LeaveBalanceBatchController {
    private final LeaveBalanceBatchService service;
    public LeaveBalanceBatchController(LeaveBalanceBatchService service) { this.service = service; }

    @GetMapping("/options")
    Map<String, ?> options(@AuthenticationPrincipal TnaPrincipal actor, @RequestParam String employeeNo) {
        return Map.of("buckets", service.options(actor, employeeNo));
    }
    @PostMapping(value="/preview", consumes=MediaType.APPLICATION_JSON_VALUE)
    LeaveBalanceBatchService.Preview preview(@AuthenticationPrincipal TnaPrincipal actor, @RequestBody LeaveBalanceBatchService.Form form) {
        return service.preview(actor, form);
    }
    @PostMapping(value="/save", consumes=MediaType.APPLICATION_JSON_VALUE)
    BulkData.Result save(@AuthenticationPrincipal TnaPrincipal actor, @RequestBody LeaveBalanceBatchService.Form form) {
        return service.save(actor, form);
    }
    @ExceptionHandler(OrgException.class)
    ResponseEntity<?> invalid(OrgException error) {
        return ResponseEntity.status(error.status()).body(error instanceof LeaveBalanceBatchService.BatchProblem batch
                ? batch.preview : Map.of("message", error.getMessage()));
    }
}
