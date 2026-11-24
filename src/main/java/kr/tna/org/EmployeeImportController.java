package kr.tna.org;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import kr.tna.common.auth.TnaPrincipal;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.servlet.ModelAndView;

@Controller
class EmployeeImportController {
    private static final String HEADER = "사번,이름,입사일,생년월일,조직코드,직급,직책\r\n";
    private final EmployeeImportService imports;

    EmployeeImportController(EmployeeImportService imports) { this.imports = imports; }

    @GetMapping("/admin/employees/import")
    ModelAndView page(@AuthenticationPrincipal TnaPrincipal actor) {
        return new ModelAndView("org/employee-import").addObject("employeeImport", imports.page(actor));
    }

    @GetMapping("/admin/employees/import/template")
    ResponseEntity<byte[]> template() {
        var bytes = new ByteArrayOutputStream();
        bytes.writeBytes(new byte[] {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF});
        bytes.writeBytes(HEADER.getBytes(StandardCharsets.UTF_8));
        return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=employee-import.csv")
                .cacheControl(CacheControl.noStore()).contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .body(bytes.toByteArray());
    }
}
