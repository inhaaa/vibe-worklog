package kr.tna.common.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import kr.tna.org.OrgException;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.servlet.support.RequestContextUtils;
import org.springframework.web.servlet.view.RedirectView;

@Controller
public final class FirstLoginController {
    private final FirstLoginService service;
    public FirstLoginController(FirstLoginService service) { this.service = service; }

    @GetMapping("/first-login")
    ModelAndView form(HttpServletRequest request) {
        var flash = RequestContextUtils.getInputFlashMap(request);
        return view(flash == null ? "" : String.valueOf(flash.get("companyCode")),
                flash == null ? "" : String.valueOf(flash.get("employeeNo")));
    }

    @PostMapping("/first-login")
    ModelAndView complete(@RequestParam(defaultValue = "") String companyCode, @RequestParam(defaultValue = "") String employeeNo,
                          @RequestParam(defaultValue = "") String birthDate, @RequestParam(defaultValue = "") String password,
                          @RequestParam(defaultValue = "") String confirmation,
                          HttpServletRequest request, HttpServletResponse response) {
        try {
            if (!password.equals(confirmation)) throw OrgException.invalid("비밀번호와 확인값이 일치하지 않습니다.");
            service.activate(companyCode, employeeNo, date(birthDate), password, request.getRemoteAddr());
            var session = request.getSession(false);
            if (session != null) session.invalidate();
            SecurityContextHolder.clearContext();
            var redirect = new RedirectView("/login?activated", true);
            redirect.setStatusCode(HttpStatus.SEE_OTHER);
            return new ModelAndView(redirect);
        } catch (OrgException error) {
            if (error.status() == 429) response.setHeader("Retry-After", "300");
            var view = view(companyCode, employeeNo).addObject("error", error.getMessage());
            view.setStatus(HttpStatusCode.valueOf(error.status()));
            return view;
        }
    }

    private static ModelAndView view(String companyCode, String employeeNo) {
        return new ModelAndView("first-login").addObject("companyCode", companyCode).addObject("employeeNo", employeeNo);
    }

    private static LocalDate date(String value) {
        try { return LocalDate.parse(value); } catch (DateTimeParseException invalid) { return null; }
    }
}
