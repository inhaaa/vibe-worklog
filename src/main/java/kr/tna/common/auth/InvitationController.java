package kr.tna.common.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import kr.tna.org.OrgException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.servlet.view.RedirectView;

@Controller
public class InvitationController {
    private static final Logger LOG = LoggerFactory.getLogger(InvitationController.class);
    private final InvitationAcceptance invitations;
    public InvitationController(InvitationAcceptance invitations) { this.invitations = invitations; }

    @GetMapping({"/invite/{companyCode}", "/invite"})
    ModelAndView form(@PathVariable(name = "companyCode", required = false) String pathCode,
                      @RequestParam(name = "companyCode", defaultValue = "") String formCode) {
        return new ModelAndView("invite").addObject("companyCode", pathCode == null ? formCode : pathCode)
                .addObject("inviteByCompany", pathCode == null);
    }

    @PostMapping({"/invite/{companyCode}/accept", "/invite/accept"})
    ModelAndView accept(@PathVariable(name = "companyCode", required = false) String pathCode,
            @RequestParam(name = "companyCode", defaultValue = "") String formCode,
            @RequestParam(defaultValue = "") String email, @RequestParam(defaultValue = "") String token,
            @RequestParam(defaultValue = "") String password, @RequestParam(defaultValue = "") String confirmation,
            HttpServletRequest request, HttpServletResponse response) {
        try {
            if (!password.equals(confirmation)) {
                LOG.warn("event=invitation_accept outcome=rejected");
                throw OrgException.invalid("비밀번호와 확인값이 일치하지 않습니다.");
            }
            invitations.accept(pathCode == null ? formCode : pathCode, email, token, password, request.getRemoteAddr());
            var session = request.getSession(false);
            if (session != null) session.invalidate();
            SecurityContextHolder.clearContext();
            var redirect = new RedirectView("/login?invited", true);
            redirect.setStatusCode(HttpStatus.SEE_OTHER);
            return new ModelAndView(redirect);
        } catch (OrgException error) {
            if (error.status() == 429) response.setHeader("Retry-After", "300");
            var view = form(pathCode, formCode).addObject("error", error.getMessage());
            view.setStatus(HttpStatusCode.valueOf(error.status()));
            return view;
        }
    }
}
