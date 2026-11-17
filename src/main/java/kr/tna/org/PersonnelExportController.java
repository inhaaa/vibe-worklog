package kr.tna.org;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import kr.tna.common.auth.TnaPrincipal;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public final class PersonnelExportController {
    private final PersonnelExportService exports;

    PersonnelExportController(PersonnelExportService exports) {
        this.exports = exports;
    }

    @PostMapping("/admin/employees/exports")
    ResponseEntity<byte[]> download(@AuthenticationPrincipal TnaPrincipal actor, @RequestParam String scope,
            @RequestParam(required = false) List<UUID> employeeIds, @RequestParam(required = false) String q,
            @RequestParam(required = false) UUID organizationId, @RequestParam(required = false) String status,
            HttpServletRequest request) {
        var fields = Set.of("scope", "employeeIds", "q", "organizationId", "status", "_csrf");
        if (request.getParameterMap().entrySet().stream().anyMatch(entry -> !fields.contains(entry.getKey())
                || !entry.getKey().equals("employeeIds") && entry.getValue().length != 1)) {
            throw new OrgException(400, "내보내기 요청의 입력 형식을 확인해 주세요.");
        }
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=personnel.xlsx")
                .cacheControl(CacheControl.noStore())
                .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .body(exports.download(actor, scope, employeeIds, q, organizationId, status));
    }

    @ExceptionHandler(OrgException.class)
    ResponseEntity<String> error(OrgException exception) {
        return ResponseEntity.status(exception.status()).cacheControl(CacheControl.noStore())
                .contentType(MediaType.TEXT_PLAIN).body(exception.getMessage());
    }
}
