package kr.tna.org;

import kr.tna.common.auth.TnaPrincipal;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.ModelAndView;

@Controller
class AccountDirectoryController {
    private final AccountDirectoryService accounts;

    AccountDirectoryController(AccountDirectoryService accounts) { this.accounts = accounts; }

    @GetMapping("/admin/accounts")
    ModelAndView list(@AuthenticationPrincipal TnaPrincipal actor, @RequestParam(defaultValue = "") String q,
                      @RequestParam(defaultValue = "all") String status, @RequestParam(defaultValue = "0") int page) {
        return new ModelAndView("org/accounts").addObject("accountDirectory", accounts.directory(actor, q, status, page));
    }
}
