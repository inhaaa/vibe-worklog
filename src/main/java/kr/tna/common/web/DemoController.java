package kr.tna.common.web;

import java.util.Map;
import kr.tna.common.auth.LocalDemoLogin;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
public final class DemoController {
    private final LocalDemoLogin localDemo;

    public DemoController(LocalDemoLogin localDemo) {
        this.localDemo = localDemo;
    }

    @GetMapping("/")
    String index(RedirectAttributes redirectAttributes) {
        return localDemo.enabled() ? "redirect:/demo" : "redirect:/me/today";
    }

    @GetMapping("/login")
    String login(@RequestParam Map<String, String> parameters, Model model,
            RedirectAttributes redirectAttributes) {
        if (localDemo.enabled()) {
            String flags = parameters.containsKey("error") ? "?error" : "";
            if (parameters.containsKey("logout")) flags += flags.isEmpty() ? "?logout" : "&logout";
            return "redirect:/demo" + flags;
        }
        model.addAttribute("localDemo", false);
        return "login";
    }

    @GetMapping("/demo")
    String demo(Model model) {
        if (!localDemo.enabled()) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        model.addAttribute("demoLogin", LocalDemoLogin.LOGIN);
        model.addAttribute("demoPassword", LocalDemoLogin.PASSWORD);
        return "demo";
    }
}
