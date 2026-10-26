package kr.tna.common.web;

import kr.tna.common.auth.TnaPrincipal;
import kr.tna.workspace.WorkspaceQueries;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class TodayController {
    private final WorkspaceQueries workspace;
    public TodayController(WorkspaceQueries workspace) { this.workspace = workspace; }

    @GetMapping("/me/today")
    String today(@AuthenticationPrincipal TnaPrincipal principal, Model model) {
        var home = workspace.home(principal);
        model.addAttribute("employee", home.employee());
        model.addAttribute("workspaceHome", home);
        model.addAttribute("attendanceAdmin", principal.roles().contains("ATTENDANCE_ADMIN"));
        model.addAttribute("approver",principal.roles().contains("APPROVER"));
        var day = home.today().snapshot();
        model.addAttribute("today", day.workDate());
        model.addAttribute("day",day);
        model.addAttribute("eventTimes",kr.tna.clock.AttendanceController.times(day));
        model.addAttribute("confirmationForm",kr.tna.clock.AttendanceController.confirmation(day));
        model.addAttribute("inCommand",java.util.UUID.randomUUID());model.addAttribute("outCommand",java.util.UUID.randomUUID());
        return "today";
    }

    @GetMapping("/me/today/status")
    String status() { return "fragments/status :: status"; }
}
