package kr.tna.schedule;

import java.util.*;
import java.util.function.Supplier;
import java.util.stream.IntStream;
import kr.tna.common.auth.TnaPrincipal;
import kr.tna.org.*;
import kr.tna.schedule.ScheduleData.*;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.servlet.view.RedirectView;

@Controller
public class ScheduleController {
    private final ScheduleService schedules;
    private final EmployeeService employees;
    private final OrgCommands commands;
    public ScheduleController(ScheduleService schedules, EmployeeService employees, OrgCommands commands) {
        this.schedules=schedules;this.employees=employees;this.commands=commands;
    }
    @GetMapping("/admin/schedules")
    ModelAndView list(@AuthenticationPrincipal TnaPrincipal actor) { return listPage(actor,null); }
    @PostMapping("/admin/schedules")
    ModelAndView create(@AuthenticationPrincipal TnaPrincipal actor,@ModelAttribute("form") TemplateForm form,BindingResult binding) {
        check(binding);return submit(()->schedules.create(actor,form),"/admin/schedules/",()->listPage(actor,form));
    }
    @GetMapping("/admin/schedules/{id}")
    ModelAndView detail(@AuthenticationPrincipal TnaPrincipal actor,@PathVariable UUID id) {return detailPage(actor,id,null);}
    @PostMapping("/admin/schedules/{id}/versions")
    ModelAndView revise(@AuthenticationPrincipal TnaPrincipal actor,@PathVariable UUID id,@ModelAttribute("form") TemplateForm form,BindingResult binding) {
        check(binding);return submit(()->schedules.revise(actor,id,form),"/admin/schedules/",()->detailPage(actor,id,form));
    }
    @GetMapping("/admin/employees/{id}/schedule")
    ModelAndView assignment(@AuthenticationPrincipal TnaPrincipal actor,@PathVariable UUID id) {return assignmentPage(actor,id,null);}
    @PostMapping("/admin/employees/{id}/schedule")
    ModelAndView assign(@AuthenticationPrincipal TnaPrincipal actor,@PathVariable UUID id,@ModelAttribute("form") AssignmentForm form,BindingResult binding) {
        check(binding);try {schedules.assign(actor,id,form);return redirect("/admin/employees/"+id+"/schedule");}
        catch(OrgException e) {return invalid(e,()->assignmentPage(actor,id,form));}
    }
    private ModelAndView listPage(TnaPrincipal actor,TemplateForm form) {
        return new ModelAndView("schedule/list").addObject("templates",schedules.list(actor))
            .addObject("form",form!=null?form:defaults("","",null)).addObject("today",commands.today());
    }
    private ModelAndView detailPage(TnaPrincipal actor,UUID id,TemplateForm form) {
        var template=schedules.get(actor,id);var versions=schedules.versions(actor,id);
        Map<UUID,List<Day>> days=new LinkedHashMap<>();
        for(var v:versions) days.put(v.id(),schedules.days(actor,v.id()));
        return new ModelAndView("schedule/detail").addObject("template",template).addObject("versions",versions)
            .addObject("versionDays",days).addObject("today",commands.today())
            .addObject("form",form!=null?form:defaults(template.code(),template.name(),template.version()));
    }
    private ModelAndView assignmentPage(TnaPrincipal actor,UUID id,AssignmentForm form) {
        var employee=employees.get(actor,id);Map<UUID,String> names=new HashMap<>();
        for(var t:schedules.list(actor)) names.put(t.id(),t.name());
        return new ModelAndView("schedule/assignment").addObject("employee",employee).addObject("assignments",schedules.assignments(actor,id))
            .addObject("versions",schedules.assignableVersions(actor)).addObject("templateNames",names).addObject("today",commands.today())
            .addObject("form",form!=null?form:new AssignmentForm(null,commands.today(),employee.version(),"",UUID.randomUUID()));
    }
    private TemplateForm defaults(String code,String name,Long version) {
        var days=IntStream.rangeClosed(1,7).mapToObj(i->new DayForm(i,i<6,i<6?"09:00":"",i<6?"18:00":"",i<6?"12:00-13:00":"")).toList();
        return new TemplateForm(code,name,commands.today(),days,version,"",UUID.randomUUID());
    }
    private ModelAndView submit(Supplier<UUID> action,String prefix,Supplier<ModelAndView> page) {
        try{return redirect(prefix+action.get());}catch(OrgException e){return invalid(e,page);}
    }
    private ModelAndView invalid(OrgException e,Supplier<ModelAndView> page) {
        if(e.status()!=409&&e.status()!=422)throw e;
        var view=page.get().addObject("error",e.getMessage());view.setStatus(HttpStatusCode.valueOf(e.status()));return view;
    }
    private ModelAndView redirect(String path) {var r=new RedirectView(path,true);r.setStatusCode(HttpStatus.SEE_OTHER);return new ModelAndView(r);}
    private void check(BindingResult b) {if(b.hasErrors())throw new OrgException(400,"일정 입력 형식을 확인해 주세요.");}
}
