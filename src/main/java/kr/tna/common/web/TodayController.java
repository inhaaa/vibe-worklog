package kr.tna.common.web;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class TodayController {
    @GetMapping("/me/today")
    String today() { return "redirect:/me/attendance"; }

    @GetMapping("/me/today/status")
    String status() { return "fragments/status :: status"; }
}
