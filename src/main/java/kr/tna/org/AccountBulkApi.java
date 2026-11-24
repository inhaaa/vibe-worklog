package kr.tna.org;

import java.util.Map;
import kr.tna.common.auth.TnaPrincipal;
import org.springframework.dao.DataAccessException;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(value = "/admin/accounts", produces = MediaType.APPLICATION_JSON_VALUE)
class AccountBulkApi {
    private final AccountDirectoryService accounts;

    AccountBulkApi(AccountDirectoryService accounts) { this.accounts = accounts; }

    @PostMapping(value = "/create", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<AccountDirectoryService.BulkResult> create(@AuthenticationPrincipal TnaPrincipal actor,
                                                              @RequestBody AccountDirectoryService.BulkForm form) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(accounts.create(actor, form));
    }

    @PostMapping(value = "/reset", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<AccountDirectoryService.BulkResult> reset(@AuthenticationPrincipal TnaPrincipal actor,
                                                             @RequestBody AccountDirectoryService.BulkForm form) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(accounts.reset(actor, form));
    }

    @ExceptionHandler(OrgException.class)
    ResponseEntity<Map<String, Object>> rejected(OrgException error) {
        Map<String, Object> body = error instanceof AccountDirectoryService.BulkProblem problem
                ? Map.of("message", error.getMessage(), "errors", problem.errors()) : Map.of("message", error.getMessage());
        return ResponseEntity.status(error.status()).cacheControl(CacheControl.noStore()).body(body);
    }

    @ExceptionHandler(DataAccessException.class)
    ResponseEntity<Map<String, String>> unavailable() {
        return ResponseEntity.status(503).cacheControl(CacheControl.noStore())
                .body(Map.of("message", "일시적으로 처리할 수 없습니다. 잠시 후 다시 확인해 주세요."));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<Map<String, String>> unreadable() {
        return ResponseEntity.badRequest().cacheControl(CacheControl.noStore()).body(Map.of("message", "요청 형식을 확인해 주세요."));
    }
}
