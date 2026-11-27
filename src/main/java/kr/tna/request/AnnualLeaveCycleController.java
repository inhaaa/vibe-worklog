package kr.tna.request;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Set;
import kr.tna.common.auth.LocalDemoLogin;
import kr.tna.common.auth.TnaPrincipal;
import kr.tna.org.OrgCommands;
import kr.tna.org.OrgException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.ModelAndView;

@Controller
public final class AnnualLeaveCycleController {
    static final String ADMIN_PATH = "/admin/leave-balances/annual-cycle";
    private final AnnualLeaveCycleService cycles;
    private final OrgCommands commands;
    private final LocalDemoLogin demo;

    public AnnualLeaveCycleController(AnnualLeaveCycleService cycles, OrgCommands commands, LocalDemoLogin demo) {
        this.cycles = cycles;
        this.commands = commands;
        this.demo = demo;
    }

    @GetMapping(ADMIN_PATH)
    ModelAndView administration(@AuthenticationPrincipal TnaPrincipal actor, @RequestParam(required = false) Integer year,
            HttpServletRequest request) {
        checkYearQuery(request, year);
        var policy = cycles.rule(actor);
        var today = commands.today();
        return new ModelAndView("request/annual-cycle").addObject("policy", policy).addObject("today", today)
                .addObject("year", year == null ? AnnualLeaveCycleData.currentYear(today, policy) : year)
                .addObject("yearSelected", year != null).addObject("month", java.time.YearMonth.from(today).toString())
                .addObject("publicDemo", demo.publicDemo());
    }

    @GetMapping("/me/leave-ledger")
    ModelAndView ownLedger(@AuthenticationPrincipal TnaPrincipal actor, @RequestParam(required = false) Integer year,
            HttpServletRequest request) {
        checkYearQuery(request, year);
        return new ModelAndView("request/personal-ledger").addObject("ledger", cycles.ledger(actor, year));
    }

    private static void checkYearQuery(HttpServletRequest request, Integer year) {
        var parameters = request.getParameterMap();
        if (!parameters.isEmpty() && (!parameters.keySet().equals(Set.of("year")) || parameters.get("year").length != 1)) {
            throw new OrgException(400, "조회할 연도 하나를 입력해 주세요.");
        }
        if (year != null && (year < AnnualLeaveCycleData.MIN_YEAR || year > AnnualLeaveCycleData.MAX_YEAR)) {
            throw OrgException.invalid("조회 연도는 1900~9998 범위로 입력해 주세요.");
        }
    }
}
