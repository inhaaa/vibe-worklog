package kr.tna.workspace;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.UUID;
import kr.tna.common.auth.TnaPrincipal;
import kr.tna.worktime.CheckoutFollowUpService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.ModelAndView;

@Controller
public final class WorkspaceController {
    private final WorkspaceQueries queries;
    private final CheckoutFollowUpService followUps;
    public WorkspaceController(WorkspaceQueries queries, CheckoutFollowUpService followUps) {
        this.queries = queries;
        this.followUps = followUps;
    }

    @GetMapping("/me/home")
    ModelAndView home(@AuthenticationPrincipal TnaPrincipal actor, @RequestParam(required = false) Integer clockError) {
        var scope = UUID.nameUUIDFromBytes(("worklog.home-widget:" + actor.tenantId() + ":" + actor.accountId())
                .getBytes(StandardCharsets.UTF_8));
        var home = WorkspaceHomeData.from(queries.home(actor));
        var page = new ModelAndView("workspace/home")
                .addObject("workspaceHome", home)
                .addObject("checkoutFollowUps", followUps.mine(actor, 0, 3))
                .addObject("homeWidgetScope", scope.toString())
                .addObject("clockError", clockError);
        if (home.today().inRecordedAt() == null) page.addObject("clockInCommand", UUID.randomUUID());
        if (home.today().outRecordedAt() == null) page.addObject("clockOutCommand", UUID.randomUUID());
        return page;
    }

    @GetMapping("/me/attendance/follow-ups")
    ModelAndView followUps(@AuthenticationPrincipal TnaPrincipal actor, @RequestParam(defaultValue = "0") int page) {
        return new ModelAndView("attendance/follow-ups").addObject("checkoutFollowUps", followUps.mine(actor, page, 20));
    }

    @GetMapping("/me/schedule")
    ModelAndView schedule(@AuthenticationPrincipal TnaPrincipal actor,
            @RequestParam(required = false) @DateTimeFormat(pattern = "uuuu-MM") YearMonth month,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        var calendar = queries.calendar(actor, month, date);
        return new ModelAndView("workspace/schedule").addObject("workspaceCalendar", calendar)
                .addObject("selectedDay", calendar.selectedDay().snapshot());
    }
}
