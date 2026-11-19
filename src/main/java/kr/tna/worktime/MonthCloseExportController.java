package kr.tna.worktime;

import java.util.Map;
import java.util.UUID;
import kr.tna.common.auth.TnaPrincipal;
import kr.tna.org.OrgException;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/admin/attendance/month-close")
public final class MonthCloseExportController {
    private static final MediaType XLSX = MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
    private final MonthCloseExportService exports;
    public MonthCloseExportController(MonthCloseExportService exports) { this.exports = exports; }

    @GetMapping(path="/export-options", produces=MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<MonthCloseExportService.Options> options(@AuthenticationPrincipal TnaPrincipal actor,
            @RequestParam String month, @RequestParam long version) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(exports.options(actor,month,version));
    }

    @GetMapping("/export")
    ResponseEntity<byte[]> download(@AuthenticationPrincipal TnaPrincipal actor, @RequestParam String month,
            @RequestParam long version, @RequestParam String scope, @RequestParam(required=false) UUID organizationId) {
        var file = exports.workbook(actor,month,version,scope,organizationId);
        return ResponseEntity.ok().contentType(XLSX).cacheControl(CacheControl.noStore())
                .header(HttpHeaders.CONTENT_DISPOSITION,ContentDisposition.attachment().filename(file.name()).build().toString())
                .body(file.bytes());
    }

    @ExceptionHandler(OrgException.class)
    ResponseEntity<?> error(OrgException error) {
        return ResponseEntity.status(error.status()).cacheControl(CacheControl.noStore()).contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("message",error.getMessage()));
    }
}
