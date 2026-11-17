package kr.tna.workspace;

import java.time.YearMonth;
import java.util.UUID;
import kr.tna.common.auth.TnaPrincipal;
import kr.tna.request.RequestScope;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.ModelAndView;

@Controller
public final class ProfileCompanyController {
    private final ProfileCompanyQueries queries;
    private final kr.tna.org.EmployeePersonalDetailsService details;

    public ProfileCompanyController(ProfileCompanyQueries queries, kr.tna.org.EmployeePersonalDetailsService details) {
        this.queries = queries; this.details = details;
    }

    @GetMapping("/me/profile")
    ModelAndView profile(@AuthenticationPrincipal TnaPrincipal actor) {
        return new ModelAndView("workspace/profile").addObject("profile", queries.profile(actor))
                .addObject("personalDetails", details.self(actor));
    }

    @GetMapping("/company/attendance")
    ModelAndView attendance(@AuthenticationPrincipal TnaPrincipal actor,
            @RequestParam(required = false) @DateTimeFormat(pattern = "uuuu-MM") YearMonth month,
            @RequestParam(required = false) UUID organizationId, @RequestParam(required = false) UUID employeeId,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "") String q) {
        return new ModelAndView("company/attendance").addObject("companyAttendance",
                queries.attendance(actor, month, organizationId, employeeId, page, q));
    }

    @GetMapping("/company/requests")
    ModelAndView requests(@AuthenticationPrincipal TnaPrincipal actor, @RequestParam(required = false) Integer year,
            @RequestParam(required = false) UUID organizationId, @RequestParam(defaultValue = "") String status,
            @RequestParam(defaultValue = "0") int page, @RequestParam(required = false) String scope) {
        return new ModelAndView("company/requests").addObject("companyRequests",
                queries.requests(actor, year, organizationId, status, page, RequestScope.parse(scope)));
    }
}
