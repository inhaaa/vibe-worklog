package kr.tna.org;

import java.util.UUID;
import java.util.function.Supplier;
import kr.tna.common.auth.TnaPrincipal;
import kr.tna.org.OrganizationHeadData.HeadForm;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.servlet.view.RedirectView;

@Controller
public final class OrganizationHeadController {
    private final OrganizationHeadService service;

    public OrganizationHeadController(OrganizationHeadService service) { this.service = service; }

    @GetMapping("/admin/organization-heads")
    ModelAndView management(@AuthenticationPrincipal TnaPrincipal actor, @RequestParam(required = false) UUID organizationId,
            @RequestParam(defaultValue = "") String q) {
        return page(actor, organizationId, q, null);
    }

    @PostMapping("/admin/organization-heads")
    ModelAndView set(@AuthenticationPrincipal TnaPrincipal actor, @ModelAttribute HeadForm form, BindingResult binding,
            @RequestParam(defaultValue = "") String q) {
        check(binding);
        return submit(() -> service.set(actor, form), () -> page(actor, form.organizationId(), q, form));
    }

    @PostMapping("/admin/organization-heads/end")
    ModelAndView end(@AuthenticationPrincipal TnaPrincipal actor, @ModelAttribute HeadForm form, BindingResult binding,
            @RequestParam(defaultValue = "") String q) {
        check(binding);
        return submit(() -> service.end(actor, form), () -> page(actor, form.organizationId(), q, form));
    }

    @GetMapping("/company/organizations")
    ModelAndView tree(@AuthenticationPrincipal TnaPrincipal actor, @RequestParam(required = false) UUID organizationId,
            @RequestParam(defaultValue = "") String q, @RequestParam(defaultValue = "0") int page) {
        return new ModelAndView("company/organizations").addObject("companyOrganizations", service.tree(actor, organizationId, q, page));
    }

    private ModelAndView page(TnaPrincipal actor, UUID organizationId, String q, HeadForm form) {
        return new ModelAndView("org/organization-heads").addObject("organizationHeads", service.management(actor, organizationId, q, form));
    }

    private static ModelAndView submit(Supplier<UUID> action, Supplier<ModelAndView> invalid) {
        try {
            var redirect = new RedirectView("/admin/organization-heads?organizationId=" + action.get(), true);
            redirect.setStatusCode(HttpStatus.SEE_OTHER);
            return new ModelAndView(redirect);
        } catch (OrgException error) {
            if (error.status() != 409 && error.status() != 422) throw error;
            var view = invalid.get().addObject("error", error.getMessage());
            view.setStatus(HttpStatusCode.valueOf(error.status()));
            return view;
        }
    }

    private static void check(BindingResult binding) {
        if (binding.hasErrors()) throw new OrgException(400, "날짜·식별자·버전의 입력 형식을 확인해 주세요.");
    }
}
