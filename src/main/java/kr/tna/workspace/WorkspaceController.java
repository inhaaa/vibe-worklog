package kr.tna.workspace;

import java.time.LocalDate;
import java.time.YearMonth;
import kr.tna.common.auth.TnaPrincipal;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.ModelAndView;

@Controller
public final class WorkspaceController {
    private final WorkspaceQueries queries;
    public WorkspaceController(WorkspaceQueries queries) { this.queries = queries; }

    @GetMapping("/me/schedule")
    ModelAndView schedule(@AuthenticationPrincipal TnaPrincipal actor,
            @RequestParam(required = false) @DateTimeFormat(pattern = "uuuu-MM") YearMonth month,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        var calendar = queries.calendar(actor, month, date);
        return new ModelAndView("workspace/schedule").addObject("workspaceCalendar", calendar)
                .addObject("selectedDay", calendar.selectedDay().snapshot());
    }
}
