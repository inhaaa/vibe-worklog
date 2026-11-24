package kr.tna.clock;

import java.util.Map;
import kr.tna.common.auth.TnaPrincipal;
import kr.tna.org.OrgException;
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
@RequestMapping(value = "/admin/holidays", produces = MediaType.APPLICATION_JSON_VALUE)
class HolidayApi {
    record PreviewForm(Integer year) { }

    private final HolidayService holidays;

    HolidayApi(HolidayService holidays) { this.holidays = holidays; }

    @PostMapping(value = "/public-preview", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<HolidayService.Preview> preview(@RequestBody PreviewForm form) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(holidays.publicHolidays(form.year()));
    }

    @PostMapping(value = "/save", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<HolidayService.Result> save(@AuthenticationPrincipal TnaPrincipal actor, @RequestBody HolidayService.Form form) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(holidays.save(actor, form));
    }

    @ExceptionHandler(OrgException.class)
    ResponseEntity<Map<String, Object>> rejected(OrgException error) {
        Map<String, Object> body = error instanceof HolidayService.HolidayProblem problem
                ? Map.of("message", error.getMessage(), "errors", problem.errors()) : Map.of("message", error.getMessage());
        return ResponseEntity.status(error.status()).cacheControl(CacheControl.noStore()).body(body);
    }

    @ExceptionHandler(DataAccessException.class)
    ResponseEntity<Map<String, Object>> unavailable() {
        return ResponseEntity.status(503).cacheControl(CacheControl.noStore())
                .body(Map.of("message", "일시적으로 처리할 수 없습니다. 잠시 후 다시 확인해 주세요."));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<Map<String, Object>> unreadable() {
        return ResponseEntity.badRequest().body(Map.of("message", "요청 형식을 확인해 주세요."));
    }
}
