package kr.tna.workspace;

import java.time.YearMonth;
import java.util.UUID;
import kr.tna.common.auth.TnaPrincipal;
import kr.tna.request.RequestScope;
import kr.tna.worktime.MonthEndReviewQueries;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.ModelAndView;

@Controller
public final class ProfileCompanyController {
    private final ProfileCompanyQueries queries;
    private final CheckoutOperationsQueries checkout;
    private final MonthEndReviewQueries monthEnd;
    private final kr.tna.org.EmployeePersonalDetailsService details;

    public ProfileCompanyController(ProfileCompanyQueries queries, CheckoutOperationsQueries checkout,
            kr.tna.org.EmployeePersonalDetailsService details, MonthEndReviewQueries monthEnd) {
        this.queries = queries;
        this.checkout = checkout;
        this.monthEnd = monthEnd;
        this.details = details;
    }

    @GetMapping("/me/profile")
    ModelAndView profile(@AuthenticationPrincipal TnaPrincipal actor) {
        return new ModelAndView("workspace/profile").addObject("profile", queries.profile(actor))
                .addObject("personalDetails", details.self(actor));
    }

    @GetMapping(value = "/company/attendance", params = {"view!=checkout", "view!=month-end"})
    ModelAndView attendance(@AuthenticationPrincipal TnaPrincipal actor,
            @RequestParam(required = false) @DateTimeFormat(pattern = "uuuu-MM") YearMonth month,
            @RequestParam(required = false) UUID organizationId, @RequestParam(required = false) UUID employeeId,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "") String q) {
        var attendance = queries.attendance(actor, month, organizationId, employeeId, page, q);
        var pageModel = new ModelAndView("company/attendance").addObject("companyAttendance", attendance.attendance());
        if (attendance.canViewCheckout()) pageModel.addObject("canViewCheckout", true);
        return pageModel;
    }

    @GetMapping(value = "/company/attendance", params = "view=checkout")
    ModelAndView checkout(@AuthenticationPrincipal TnaPrincipal actor,
            @RequestParam(required = false) String date, @RequestParam(required = false) UUID organizationId,
            @RequestParam(defaultValue = "") String q, @RequestParam(defaultValue = "ALL") String status,
            @RequestParam(defaultValue = "0") int page) {
        var result = checkout.read(actor, date, organizationId, q, status, page);
        var view = new ModelAndView("company/attendance")
                .addObject("canViewCheckout", true).addObject("checkoutOperations", result);
        if (result.error() != null) view.setStatus(HttpStatus.UNPROCESSABLE_CONTENT);
        return view;
    }

    @GetMapping(value = "/company/attendance", params = "view=month-end")
    ModelAndView monthEnd(@AuthenticationPrincipal TnaPrincipal actor,
            @RequestParam(required = false) String month, @RequestParam(required = false) UUID organizationId,
            @RequestParam(defaultValue = "") String q, @RequestParam(defaultValue = "ALL") String reason,
            @RequestParam(defaultValue = "0") int page) {
        var result = monthEnd.read(actor, month, organizationId, q, reason, page);
        var view = new ModelAndView("company/attendance")
                .addObject("canViewCheckout", true).addObject("monthEndReview", result);
        if (result.error() != null) view.setStatus(HttpStatus.UNPROCESSABLE_CONTENT);
        return view;
    }

    @GetMapping("/company/requests")
    ModelAndView requests(@AuthenticationPrincipal TnaPrincipal actor, @RequestParam(required = false) Integer year,
            @RequestParam(required = false) UUID organizationId, @RequestParam(defaultValue = "") String status,
            @RequestParam(defaultValue = "0") int page, @RequestParam(required = false) String scope) {
        return new ModelAndView("company/requests").addObject("companyRequests",
                queries.requests(actor, year, organizationId, status, page, RequestScope.parse(scope)));
    }
}
