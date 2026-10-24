package kr.tna.common.web;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import kr.tna.common.auth.TnaPrincipal;
import kr.tna.common.tenant.TenantTransactions;
import kr.tna.org.EmployeeMapper;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.server.ResponseStatusException;

@Controller
public class TodayController {
    private final TenantTransactions transactions;
    private final EmployeeMapper employees;
    private final Clock clock;
    private final kr.tna.clock.ClockService clocks;

    public TodayController(TenantTransactions transactions, EmployeeMapper employees, Clock clock, kr.tna.clock.ClockService clocks) {
        this.transactions = transactions;
        this.employees = employees;
        this.clock = clock;
        this.clocks = clocks;
    }

    @GetMapping("/")
    String index() { return "redirect:/me/today"; }

    @GetMapping("/login")
    String login() { return "login"; }

    @GetMapping("/me/today")
    String today(@AuthenticationPrincipal TnaPrincipal principal, Model model) {
        var employee = transactions.execute(principal.tenantId(),
                () -> employees.findSelf(principal.tenantId(), principal.employeeId()));
        if (employee == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        model.addAttribute("employee", employee);
        model.addAttribute("attendanceAdmin", principal.roles().contains("ATTENDANCE_ADMIN"));
        model.addAttribute("today", LocalDate.now(clock.withZone(ZoneId.of("Asia/Seoul"))));
        var day=clocks.day(principal,LocalDate.now(clock.withZone(ZoneId.of("Asia/Seoul"))));
        model.addAttribute("day",day);
        model.addAttribute("eventTimes",kr.tna.clock.AttendanceController.times(day));
        model.addAttribute("confirmationForm",kr.tna.clock.AttendanceController.confirmation(day));
        model.addAttribute("inCommand",java.util.UUID.randomUUID());model.addAttribute("outCommand",java.util.UUID.randomUUID());
        return "today";
    }

    @GetMapping("/me/today/status")
    String status() { return "fragments/status :: status"; }
}
