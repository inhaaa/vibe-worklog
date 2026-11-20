package kr.tna.org;

import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import kr.tna.common.auth.TnaPrincipal;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
final class AdminPersonnelExportController {
    private static final MediaType XLSX = MediaType.parseMediaType(
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
    private final AdminPersonnelExportService exports;

    AdminPersonnelExportController(AdminPersonnelExportService exports) {
        this.exports = exports;
    }

    @PostMapping("/admin/employees/{id}/personnel/tabs/{tabId}/export")
    ResponseEntity<byte[]> download(@AuthenticationPrincipal TnaPrincipal actor, @PathVariable UUID id,
            @PathVariable String tabId, HttpServletRequest request) {
        if (request.getParameterMap().entrySet().stream()
                .anyMatch(parameter -> !parameter.getKey().equals("_csrf") || parameter.getValue().length != 1)) {
            throw new OrgException(400, "다운로드 요청의 입력 형식을 확인해 주세요.");
        }
        var tab = PersonnelTab.parse(tabId);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=personnel-" + tab.id() + ".xlsx")
                .cacheControl(CacheControl.noStore())
                .contentType(XLSX)
                .body(exports.download(actor, id, tab));
    }

    @ExceptionHandler(OrgException.class)
    ResponseEntity<String> error(OrgException exception) {
        return ResponseEntity.status(exception.status()).cacheControl(CacheControl.noStore())
                .contentType(MediaType.TEXT_PLAIN).body(exception.getMessage());
    }
}
