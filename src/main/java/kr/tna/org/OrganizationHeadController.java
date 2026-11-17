package kr.tna.org;

import java.util.UUID;
import java.util.function.Supplier;
import kr.tna.common.auth.TnaPrincipal;
import kr.tna.org.OrganizationHeadData.HeadCommand;
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
import org.springframework.web.util.UriComponentsBuilder;

@Controller
public final class OrganizationHeadController {
    private final OrganizationHeadService service;

    public OrganizationHeadController(OrganizationHeadService service) { this.service = service; }

    @GetMapping("/admin/organization-heads")
    ModelAndView management(@AuthenticationPrincipal TnaPrincipal actor, @RequestParam(required = false) UUID organizationId,
            @RequestParam(defaultValue = "") String q, @RequestParam(defaultValue = "false") boolean chart,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "false") boolean saved) {
        if (chart) return chart(actor, organizationId, q, page).addObject("headSaved", saved);
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
        var view = service.tree(actor, organizationId, q, page);
        boolean canManage = actor.roles().contains("ATTENDANCE_ADMIN");
        var model = new ModelAndView("company/organizations").addObject("companyOrganizations", view)
                .addObject("headManagementAllowed", canManage);
        if (canManage && view.organizationId() != null) model.addObject("headOverview", service.overview(actor, view.organizationId()));
        return model;
    }

    @PostMapping("/admin/organization-heads/commands")
    ModelAndView command(@AuthenticationPrincipal TnaPrincipal actor, @ModelAttribute HeadCommand form, BindingResult binding,
            @RequestParam(defaultValue = "") String q, @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "SAVE") CommandIntent intent) {
        check(binding);
        // Validate navigation before changing data so a successful command always has a valid destination.
        OrgCommands.offset(page);
        String memberQuery = q.isBlank() ? "" : OrgCommands.requiredText(q, 100, "검색어");
        try {
            if (intent == CommandIntent.PREVIEW) {
                var preview = service.preview(actor, form);
                return chart(actor, form.organizationId(), memberQuery, page).addObject("headCommand", form).addObject("headPreview", preview);
            }
            UUID organizationId = service.command(actor, form);
            var redirect = new RedirectView(chartUrl(organizationId, memberQuery, page, true), true);
            redirect.setStatusCode(HttpStatus.SEE_OTHER);
            return new ModelAndView(redirect);
        } catch (OrgException error) {
            if ((error.status() != 409 && error.status() != 422) || form.organizationId() == null) throw error;
            var view = chart(actor, form.organizationId(), memberQuery, page).addObject("headCommand", form)
                    .addObject("headError", error.getMessage()).addObject("headConflict", error.status() == 409);
            view.setStatus(HttpStatusCode.valueOf(error.status()));
            return view;
        }
    }

    private ModelAndView chart(TnaPrincipal actor, UUID organizationId, String q, int page) {
        var model = tree(actor, organizationId, q, page);
        var company = (OrganizationHeadData.CompanyView) model.getModel().get("companyOrganizations");
        return model.addObject("organizationHeads", service.management(actor, company.organizationId(), "", null))
                .addObject("headChartUrl", chartUrl(company.organizationId(), company.q(), page, false));
    }

    private static String chartUrl(UUID organization, String q, int page, boolean saved) {
        var url = UriComponentsBuilder.fromPath("/admin/organization-heads").queryParam("chart", true)
                .queryParam("organizationId", organization).queryParam("q", "{query}").queryParam("page", page);
        if (saved) url.queryParam("saved", true);
        return url.encode().buildAndExpand(q).toUriString();
    }

    enum CommandIntent { PREVIEW, SAVE }

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
