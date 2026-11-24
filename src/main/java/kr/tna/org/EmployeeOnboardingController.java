package kr.tna.org;

import jakarta.servlet.http.HttpServletRequest;
import java.time.LocalDate;
import java.util.Set;
import java.util.UUID;
import kr.tna.common.auth.TnaPrincipal;
import kr.tna.org.EmployeeOnboardingData.*;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.servlet.view.RedirectView;

@Controller
@RequestMapping(EmployeeOnboardingController.PATH)
public class EmployeeOnboardingController {
    static final String PATH = "/admin/employees/onboarding";
    private static final Set<String> CREATE = Set.of("displayName", "joinedOn", "birthDate", "leftOn", "organizationId",
            "scheduleVersionId", "commandId", "reason", "_csrf");
    private static final Set<String> EDIT = Set.of("displayName", "joinedOn", "birthDate", "leftOn", "organizationId",
            "scheduleVersionId", "expectedVersion", "commandId", "reason", "_csrf");
    private static final Set<String> CONFIRM = Set.of("organizationId", "scheduleVersionId", "employeeNo",
            "expectedVersion", "expectedOrganizationVersion", "expectedTemplateVersion", "commandId", "reason", "_csrf");
    private static final Set<String> WITHDRAW = Set.of("expectedVersion", "commandId", "reason", "_csrf");

    private final EmployeeOnboardingService onboarding;

    public EmployeeOnboardingController(EmployeeOnboardingService onboarding) {
        this.onboarding = onboarding;
    }

    @GetMapping({"", "/{id}"})
    ModelAndView view(@AuthenticationPrincipal TnaPrincipal actor, @PathVariable(required = false) UUID id,
                      @RequestParam(defaultValue = "") String q, @RequestParam(required = false) State state,
                      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                      @RequestParam(defaultValue = "0") int page) {
        return page(onboarding.view(actor, q, state, from, to, page, id, null, null, false));
    }

    @PostMapping
    ModelAndView create(@AuthenticationPrincipal TnaPrincipal actor, @ModelAttribute Form form,
                        BindingResult binding, HttpServletRequest request) {
        return submit(actor, null, form, Action.CREATE, binding, request, CREATE);
    }

    @PostMapping("/{id}/changes")
    ModelAndView edit(@AuthenticationPrincipal TnaPrincipal actor, @PathVariable UUID id, @ModelAttribute Form form,
                      BindingResult binding, HttpServletRequest request) {
        return submit(actor, id, form, Action.EDIT, binding, request, EDIT);
    }

    @PostMapping("/{id}/confirm")
    ModelAndView confirm(@AuthenticationPrincipal TnaPrincipal actor, @PathVariable UUID id, @ModelAttribute Form form,
                         BindingResult binding, HttpServletRequest request) {
        return submit(actor, id, form, Action.CONFIRM, binding, request, CONFIRM);
    }

    @PostMapping("/{id}/withdraw")
    ModelAndView withdraw(@AuthenticationPrincipal TnaPrincipal actor, @PathVariable UUID id, @ModelAttribute Form form,
                          BindingResult binding, HttpServletRequest request) {
        return submit(actor, id, form, Action.WITHDRAW, binding, request, WITHDRAW);
    }

    private ModelAndView submit(TnaPrincipal actor, UUID id, Form form, Action action, BindingResult binding,
                                HttpServletRequest request, Set<String> allowed) {
        if (binding.hasErrors() || request.getParameterMap().entrySet().stream()
                .anyMatch(entry -> !allowed.contains(entry.getKey()) || entry.getValue().length != 1)) {
            throw new OrgException(400, "입력 항목과 날짜·식별자 형식을 확인해 주세요.");
        }
        try {
            UUID target = switch (action) {
                case CREATE, EDIT -> onboarding.save(actor, id, form);
                case CONFIRM -> onboarding.confirm(actor, id, form);
                case WITHDRAW -> onboarding.withdraw(actor, id, form);
            };
            var redirect = new RedirectView(PATH + "/" + target, true);
            redirect.setStatusCode(HttpStatus.SEE_OTHER);
            return new ModelAndView(redirect);
        } catch (OrgException exception) {
            if (exception.status() != 409 && exception.status() != 422 && exception.status() != 503) throw exception;
            try {
                var view = page(onboarding.view(actor, "", null, null, null, 0, id, form, action, exception.status() == 503));
                view.setStatus(HttpStatusCode.valueOf(exception.status()));
                return view.addObject("error", exception.getMessage());
            } catch (DataAccessException unavailable) {
                if (exception.status() == 503) throw exception;
                throw unavailable;
            }
        }
    }

    private ModelAndView page(View view) {
        return new ModelAndView("org/onboarding").addObject("onboarding", view);
    }
}
