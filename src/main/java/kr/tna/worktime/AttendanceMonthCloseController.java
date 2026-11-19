package kr.tna.worktime;

import java.util.Map;
import kr.tna.common.auth.TnaPrincipal;
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
@RequestMapping(path = "/admin/attendance/month-close", produces = MediaType.APPLICATION_JSON_VALUE)
public final class AttendanceMonthCloseController {
    private final AttendanceMonthCloseService months;

    public AttendanceMonthCloseController(AttendanceMonthCloseService months) { this.months = months; }

    @GetMapping
    AttendanceMonthCloseService.Status status(@AuthenticationPrincipal TnaPrincipal actor, @RequestParam(required = false) String month) {
        return months.status(actor, month);
    }

    @PostMapping(path = "/check", consumes = MediaType.APPLICATION_JSON_VALUE)
    AttendanceMonthCloseService.Check check(@AuthenticationPrincipal TnaPrincipal actor, @RequestBody AttendanceMonthCloseService.CheckForm form) {
        return months.check(actor, form);
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    AttendanceMonthCloseService.Receipt change(@AuthenticationPrincipal TnaPrincipal actor, @RequestBody AttendanceMonthCloseService.Command form) {
        return months.change(actor, form);
    }

    @ExceptionHandler(OrgException.class)
    ResponseEntity<?> error(OrgException error) {
        return ResponseEntity.status(error.status()).body(Map.of("message", error.getMessage()));
    }
}
