package kr.tna.org;

import java.util.UUID;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import kr.tna.common.auth.TnaPrincipal;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.servlet.view.RedirectView;

@Controller
public class OrgAdminController {
    private final OrganizationService organizations;
    private final EmployeeService employees;
    private final OrganizationHeadService heads;
    private final OrgCommands commands;
    private final EmployeeAdminMapper employeeMapper;
    private final boolean publicDemo;

    public OrgAdminController(OrganizationService organizations, EmployeeService employees, OrganizationHeadService heads,
            OrgCommands commands, EmployeeAdminMapper employeeMapper, Environment environment) {
        this.organizations = organizations;
        this.employees = employees;
        this.heads = heads;
        this.commands = commands;
        this.employeeMapper = employeeMapper;
        this.publicDemo = environment.acceptsProfiles(Profiles.of("public-demo"));
    }

    @GetMapping("/admin/organizations")
    ModelAndView organizations(@AuthenticationPrincipal TnaPrincipal actor,
            @RequestParam(defaultValue = "") String q, @RequestParam(defaultValue = "0") int page) {
        return organizationList(actor, q, page, null);
    }

    @PostMapping("/admin/organizations")
    ModelAndView createOrganization(@AuthenticationPrincipal TnaPrincipal actor,
            @ModelAttribute("form") OrgData.OrganizationForm form, BindingResult binding) {
        checkBinding(binding);
        return submit(() -> organizations.create(actor, form), "/admin/organizations/",
                error -> organizationList(actor, "", 0, form));
    }

    @GetMapping("/admin/organizations/{id}")
    ModelAndView organization(@AuthenticationPrincipal TnaPrincipal actor, @PathVariable UUID id) {
        return organizationDetail(actor, id, null);
    }

    @PostMapping("/admin/organizations/{id}/changes")
    ModelAndView changeOrganization(@AuthenticationPrincipal TnaPrincipal actor, @PathVariable UUID id,
            @ModelAttribute("form") OrgData.OrganizationForm form, BindingResult binding) {
        checkBinding(binding);
        return submit(() -> organizations.update(actor, id, form), "/admin/organizations/",
                error -> organizationDetail(actor, id, form));
    }

    @GetMapping("/admin/employees")
    ModelAndView employees(@AuthenticationPrincipal TnaPrincipal actor,
            @RequestParam(defaultValue = "") String q, @RequestParam(defaultValue = "0") int page,
            @RequestParam(required = false) UUID organizationId,
            @RequestParam(defaultValue = "all") String status,
            @RequestParam(required = false) UUID employeeId) {
        return employeeList(actor, q, page, null, organizationId, status, employeeId);
    }

    @PostMapping("/admin/employees")
    ModelAndView createEmployee(@AuthenticationPrincipal TnaPrincipal actor,
            @ModelAttribute("form") OrgData.EmployeeForm form, BindingResult binding) {
        checkBinding(binding);
        return submit(() -> employees.create(actor, form), "/admin/employees/",
                error -> employeeList(actor, "", 0, form));
    }

    @GetMapping("/admin/employees/{id}")
    ModelAndView employee(@AuthenticationPrincipal TnaPrincipal actor, @PathVariable UUID id) {
        return employeeDetail(actor, id, null, null);
    }

    @PostMapping("/admin/employees/{id}/changes")
    ModelAndView changeEmployee(@AuthenticationPrincipal TnaPrincipal actor, @PathVariable UUID id,
            @ModelAttribute("form") OrgData.EmployeeForm form, BindingResult binding) {
        checkBinding(binding);
        return submit(() -> employees.update(actor, id, form), "/admin/employees/",
                error -> employeeDetail(actor, id, form, null));
    }

    @PostMapping("/admin/employees/{id}/assignments")
    ModelAndView assign(@AuthenticationPrincipal TnaPrincipal actor, @PathVariable UUID id,
            @ModelAttribute("assignmentForm") OrgData.AssignmentForm form, BindingResult binding) {
        checkBinding(binding);
        return submit(() -> employees.assign(actor, id, form), "/admin/employees/",
                error -> employeeDetail(actor, id, null, form));
    }

    ModelAndView organizationList(TnaPrincipal actor, String q, int page, OrgData.OrganizationForm form) {
        var list = organizations.list(actor, q, page);
        var view = new ModelAndView("org/organizations")
                .addObject("organizations", list.stream().limit(50).toList())
                .addObject("hasNext", list.size() > 50).addObject("q", q).addObject("page", page)
                .addObject("organizationSheet", BulkData.organizations(UUID.randomUUID(), "",
                        list.stream().limit(50).toList(), List.of(), List.of()))
                .addObject("organizationHeadsById", heads.organizationSheetHeads(actor))
                .addObject("form", form != null ? form : new OrgData.OrganizationForm(
                        "", "", null, true, null, "", UUID.randomUUID()));
        return options(view, actor);
    }

    private ModelAndView organizationDetail(TnaPrincipal actor, UUID id, OrgData.OrganizationForm form) {
        var org = organizations.get(actor, id);
        return options(new ModelAndView("org/organization").addObject("organization", org)
                .addObject("form", form != null ? form : new OrgData.OrganizationForm(
                        org.code(), org.name(), org.parentId(), org.active(), org.version(), "", UUID.randomUUID())), actor);
    }

    private ModelAndView employeeList(TnaPrincipal actor, String q, int page, OrgData.EmployeeForm form) {
        return employeeList(actor, q, page, form, null, "all", null);
    }

    ModelAndView employeeList(TnaPrincipal actor, String q, int page, OrgData.EmployeeForm form,
            UUID organizationId, String status, UUID employeeId) {
        var directory = employees.directory(actor, q, page, organizationId, status, employeeId);
        var list = directory.employees();
        var view = new ModelAndView("org/employees").addObject("employees", list)
                .addObject("employeeSheet", BulkData.employees(UUID.randomUUID(), "", list, List.of(), List.of()))
                .addObject("employeeOrganizations", directory.organizationNames())
                .addObject("selectedEmployee", directory.selectedEmployee())
                .addObject("pageEmployeeCount", list.size())
                .addObject("pageActiveCount", (int) list.stream().filter(OrgData.Employee::active).count())
                .addObject("pageInactiveCount", (int) list.stream().filter(employee -> !employee.active()).count())
                .addObject("organizationId", organizationId).addObject("status", status)
                .addObject("hasNext", directory.hasNext()).addObject("q", q).addObject("page", page)
                .addObject("form", form != null ? form : new OrgData.EmployeeForm(
                        "", "", null, null, true, null, "", UUID.randomUUID()));
        return options(employeeContext(view, actor, list), actor);
    }

    ModelAndView employeeContext(ModelAndView view, TnaPrincipal actor, List<OrgData.Employee> employees) {
        var contexts = new LinkedHashMap<UUID, OrganizationHeadData.EmployeeContext>();
        var basics = new LinkedHashMap<UUID, EmployeeAdminMapper.PersonnelBasic>();
        var asOf = commands.today();
        for (int offset = 0; offset < employees.size(); offset += 50) {
            var ids = employees.subList(offset, Math.min(offset + 50, employees.size())).stream().map(OrgData.Employee::id).toList();
            contexts.putAll(heads.employeeSheetContext(actor, ids));
            commands.read(actor, () -> employeeMapper.personnelBasics(actor.tenantId(), ids, asOf))
                    .forEach(detail -> basics.put(detail.employeeId(), detail));
        }
        return view.addObject("employeeContext", contexts).addObject("personnelBasics", basics)
                .addObject("personnelAsOf", asOf).addObject("publicDemo", publicDemo);
    }

    private ModelAndView employeeDetail(TnaPrincipal actor, UUID id,
            OrgData.EmployeeForm form, OrgData.AssignmentForm assignmentForm) {
        var employee = employees.get(actor, id);
        return options(new ModelAndView("org/employee").addObject("employee", employee)
                .addObject("assignments", employees.assignments(actor, id))
                .addObject("form", form != null ? form : new OrgData.EmployeeForm(
                        employee.employeeNo(), employee.displayName(), employee.joinedOn(), employee.leftOn(),
                        employee.active(), employee.version(), "", UUID.randomUUID()))
                .addObject("assignmentForm", assignmentForm != null ? assignmentForm : new OrgData.AssignmentForm(
                        null, null, employee.version(), "", UUID.randomUUID())), actor);
    }

    private ModelAndView options(ModelAndView view, TnaPrincipal actor) {
        var options = organizations.options(actor);
        return view.addObject("options", options).addObject("orgNames", options.stream().collect(
                Collectors.toMap(OrgData.Organization::id, OrgData.Organization::name)));
    }

    private ModelAndView submit(Supplier<UUID> action, String path, Function<OrgException, ModelAndView> invalid) {
        try {
            RedirectView redirect = new RedirectView(path + action.get(), true);
            redirect.setStatusCode(HttpStatus.SEE_OTHER);
            return new ModelAndView(redirect);
        } catch (OrgException exception) {
            if (exception.status() != 409 && exception.status() != 422) throw exception;
            ModelAndView view = invalid.apply(exception);
            view.addObject("error", exception.getMessage());
            view.setStatus(HttpStatusCode.valueOf(exception.status()));
            return view;
        }
    }

    private void checkBinding(BindingResult binding) {
        if (binding.hasErrors()) throw new OrgException(400, "날짜·식별자·버전의 입력 형식을 확인해 주세요.");
    }
}
