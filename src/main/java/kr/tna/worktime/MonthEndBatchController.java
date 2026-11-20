package kr.tna.worktime;

import java.util.Map;
import kr.tna.common.auth.TnaPrincipal;
import kr.tna.org.OrgException;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(path = "/admin/calculations/month-end", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
public final class MonthEndBatchController {
    private final MonthEndBatchService batches;

    public MonthEndBatchController(MonthEndBatchService batches) { this.batches = batches; }

    @PostMapping("/preview")
    MonthEndBatchService.Preview preview(@AuthenticationPrincipal TnaPrincipal actor, @RequestBody MonthEndBatchService.Selection form) {
        return batches.preview(actor, form);
    }

    @PostMapping
    MonthEndBatchService.Result execute(@AuthenticationPrincipal TnaPrincipal actor, @RequestBody MonthEndBatchService.Command form) {
        return batches.execute(actor, form);
    }

    @ExceptionHandler(OrgException.class)
    ResponseEntity<?> error(OrgException error) {
        return ResponseEntity.status(error.status()).body(Map.of("message", error.getMessage()));
    }
}
