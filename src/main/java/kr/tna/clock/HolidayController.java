package kr.tna.clock;

import java.net.URI;
import kr.tna.common.auth.TnaPrincipal;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.ModelAndView;

@Controller
public class HolidayController {
    private final HolidayService holidays;

    HolidayController(HolidayService holidays) { this.holidays = holidays; }

    @GetMapping("/admin/holidays")
    ModelAndView page(@AuthenticationPrincipal TnaPrincipal actor, @RequestParam(required = false) Integer year) {
        return new ModelAndView("clock/holidays").addObject("holidays", holidays.page(actor, year));
    }

    /** 옛 '근무일·휴일' 주소. 쿼리(date, settlementReturn)는 쓰지 않는다. */
    @GetMapping("/admin/calendar")
    ResponseEntity<Void> calendar() {
        return ResponseEntity.status(HttpStatus.SEE_OTHER).location(URI.create("/admin/holidays")).build();
    }
}
