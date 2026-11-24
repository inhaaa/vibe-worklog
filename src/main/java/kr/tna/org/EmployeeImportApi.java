package kr.tna.org;

import java.util.List;
import java.util.Map;
import kr.tna.common.auth.TnaPrincipal;
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
@RequestMapping(value = "/admin/employees/import", produces = MediaType.APPLICATION_JSON_VALUE)
class EmployeeImportApi {
    record PreviewForm(List<EmployeeImportRows.Row> rows) { }

    private final EmployeeImportService imports;

    EmployeeImportApi(EmployeeImportService imports) { this.imports = imports; }

    @PostMapping(value = "/preview", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<EmployeeImportService.Preview> preview(@AuthenticationPrincipal TnaPrincipal actor, @RequestBody PreviewForm form) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(imports.preview(actor, form.rows()));
    }

    @PostMapping(value = "/save", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<EmployeeImportService.Result> save(@AuthenticationPrincipal TnaPrincipal actor,
                                                      @RequestBody EmployeeImportService.Form form) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(imports.save(actor, form));
    }

    @ExceptionHandler(OrgException.class)
    ResponseEntity<?> rejected(OrgException error) {
        Object body = error instanceof EmployeeImportService.ImportProblem problem ? problem.preview() : Map.of("message", error.getMessage());
        return ResponseEntity.status(error.status()).cacheControl(CacheControl.noStore()).body(body);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<Map<String, String>> unreadable() {
        return ResponseEntity.badRequest().body(Map.of("message", "요청 형식을 확인해 주세요."));
    }
}
