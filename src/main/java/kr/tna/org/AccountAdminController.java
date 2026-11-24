package kr.tna.org;

import java.time.LocalDate;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import kr.tna.common.auth.TnaPrincipal;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.servlet.view.RedirectView;

@Controller
public class AccountAdminController {
    private final AccountService accounts;
    private final RoleDirectoryService directory;
    private final ApproverService approvers;
    private final EmployeeService employees;
    private final OrgCommands commands;

    public AccountAdminController(AccountService accounts, ApproverService approvers, EmployeeService employees,
                                  OrgCommands commands, RoleDirectoryService directory) {
        this.accounts = accounts; this.approvers = approvers; this.employees = employees;
        this.commands = commands; this.directory = directory;
    }

    @GetMapping("/admin/access")
    ModelAndView list(@AuthenticationPrincipal TnaPrincipal actor,
            @RequestParam(defaultValue = "ATTENDANCE_ADMIN") String role,
            @RequestParam(defaultValue = "") String q, @RequestParam(defaultValue = "0") int page) {
        return new ModelAndView("org/access-list").addObject("roleDirectory", directory.directory(actor, role, q, page));
    }

    @GetMapping(value = "/admin/access/roles/{role}/candidates", produces = "application/json")
    @ResponseBody
    ResponseEntity<RoleDirectoryService.Candidates> candidates(@AuthenticationPrincipal TnaPrincipal actor,
            @PathVariable String role, @RequestParam(defaultValue = "") String q,
            @RequestParam(defaultValue = "0") int page) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(directory.candidates(actor, role, q, page));
    }

    @GetMapping("/admin/employees/{id}/access")
    ModelAndView access(@AuthenticationPrincipal TnaPrincipal actor, @PathVariable UUID id) {
        return page(actor, id, null, null, null);
    }

    @PostMapping("/admin/employees/{id}/account-reset")
    ModelAndView reset(@AuthenticationPrincipal TnaPrincipal actor, @PathVariable UUID id,
            @ModelAttribute("resetForm") AccountData.ResetForm form, BindingResult binding) {
        check(binding);
        return submit(() -> accounts.reset(actor, id, form), () -> page(actor, id, form, null, null), "?reset");
    }

    @PostMapping("/admin/employees/{id}/roles")
    ModelAndView roles(@AuthenticationPrincipal TnaPrincipal actor, @PathVariable UUID id,
            @ModelAttribute("rolesForm") AccountData.RolesForm form, BindingResult binding) {
        check(binding);
        return submit(() -> accounts.roles(actor, id, form),
                () -> page(actor, id, null, form, null), "");
    }

    @PostMapping("/admin/approver-assignments")
    ModelAndView approver(@AuthenticationPrincipal TnaPrincipal actor,
            @ModelAttribute("approverForm") AccountData.ApproverForm form, BindingResult binding) {
        check(binding);
        if (form.employeeId() == null) throw OrgException.invalid("대상 사원을 선택해 주세요.");
        return submit(() -> approvers.set(actor, form),
                () -> page(actor, form.employeeId(), null, null, form), "");
    }

    private ModelAndView page(TnaPrincipal actor, UUID id, AccountData.ResetForm reset,
                             AccountData.RolesForm roles, AccountData.ApproverForm approver) {
        var employee = employees.get(actor, id);
        var account = accounts.get(actor, id);
        var status = accounts.status(actor, id);
        var history = approvers.history(actor, id);
        LocalDate today = commands.today();
        var current = history.stream().filter(a -> a.supersededAt() == null && a.validTo() == null)
                .findFirst().orElse(null);
        var form = approver != null ? approver : new AccountData.ApproverForm(id,
                current == null ? null : current.approverEmployeeId(), today, employee.version(), "", UUID.randomUUID());
        var candidates = approvers.candidates(actor);
        return new ModelAndView("org/access").addObject("employee", employee).addObject("account", account)
                .addObject("accountStatus", status.label()).addObject("accountState", status.name())
                .addObject("ownAccount", id.equals(actor.employeeId()))
                .addObject("eligibleEmployee", employee.active() && !employee.joinedOn().isAfter(today)
                        && (employee.leftOn() == null || !employee.leftOn().isBefore(today)))
                .addObject("resetForm", reset != null ? reset : new AccountData.ResetForm(
                        employee.version(), "", UUID.randomUUID()))
                .addObject("rolesForm", roles != null ? roles : new AccountData.RolesForm(
                        account == null ? Set.of("EMPLOYEE") : account.roles(), employee.version(), "", UUID.randomUUID()))
                .addObject("approverForm", form).addObject("assignments", history).addObject("currentAssignment", current)
                .addObject("candidates", candidates)
                .addObject("selectedApproverMissing", form.approverEmployeeId() != null
                        && candidates.stream().noneMatch(e -> !e.id().equals(id)
                                && e.id().equals(form.approverEmployeeId())));
    }

    private ModelAndView submit(Supplier<UUID> action, Supplier<ModelAndView> invalid, String suffix) {
        try {
            var redirect = new RedirectView("/admin/employees/" + action.get() + "/access" + suffix, true);
            redirect.setStatusCode(HttpStatus.SEE_OTHER);
            return new ModelAndView(redirect);
        } catch (OrgException error) {
            if (error.status() != 409 && error.status() != 422) throw error;
            var view = invalid.get().addObject("error", error.getMessage());
            view.setStatus(HttpStatusCode.valueOf(error.status()));
            return view;
        }
    }

    private void check(BindingResult binding) {
        if (binding.hasErrors()) throw new OrgException(400, "날짜·식별자·버전의 입력 형식을 확인해 주세요.");
    }
}
