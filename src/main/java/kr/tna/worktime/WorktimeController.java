package kr.tna.worktime;

import java.time.*;
import java.util.*;
import java.util.function.Supplier;
import kr.tna.common.auth.TnaPrincipal;
import kr.tna.org.OrgCommands;
import kr.tna.org.OrgException;
import kr.tna.worktime.WorktimeData.*;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.servlet.view.RedirectView;

@Controller
public class WorktimeController {
    private final WorktimeService worktime;private final OrgCommands commands;
    public WorktimeController(WorktimeService worktime,OrgCommands commands){this.worktime=worktime;this.commands=commands;}
    @GetMapping("/admin/attendance") ModelAndView overview(@AuthenticationPrincipal TnaPrincipal a,@RequestParam(required=false) UUID employeeId,@RequestParam(required=false) @DateTimeFormat(pattern="uuuu-MM") YearMonth month){return monthPage(a,employeeId==null?a.employeeId():employeeId,month==null?YearMonth.from(commands.today()):month,true);}
    @GetMapping("/me/worktime") ModelAndView mine(@AuthenticationPrincipal TnaPrincipal a,@RequestParam(required=false) @DateTimeFormat(pattern="uuuu-MM") YearMonth month){return monthPage(a,a.employeeId(),month==null?YearMonth.from(commands.today()):month,false);}
    @GetMapping("/worktime/results/{id}") ModelAndView result(@AuthenticationPrincipal TnaPrincipal actor,@PathVariable UUID id){
        var detail=worktime.detail(actor,id);var r=detail.day().result();var times=new LinkedHashMap<String,String>();
        times.put("소정근로",duration(r.scheduledMillis()));times.put("체류",duration(r.presenceMillis()));times.put("확인 실근로",duration(r.actualWorkMillis()));
        times.put("확인 휴게",duration(r.confirmedBreakMillis()));times.put("일정 밖",duration(r.outsideScheduleMillis()));times.put("일8시간 초과 참고",duration(r.over8hMillis()));
        times.put("야간",duration(r.nightWorkMillis()));times.put("휴일",duration(r.holidayWorkMillis()));times.put("법정 최종 연장",duration(r.legalOvertimeMillis()));
        return new ModelAndView("worktime/result").addObject("detail",detail).addObject("times",times).addObject("month",YearMonth.from(detail.day().workDate()))
                .addObject("admin",actor.roles().contains("ATTENDANCE_ADMIN"));
    }
    @PostMapping("/admin/calculations") ModelAndView calculate(@AuthenticationPrincipal TnaPrincipal a,@RequestParam UUID employeeId,@RequestParam @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate from,@RequestParam @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate to,@RequestParam UUID commandId,@RequestParam String reason){return submit(()->worktime.recalculate(a,employeeId,from,to,commandId,reason),()->monthPage(a,employeeId,YearMonth.from(from),true),"/admin/attendance?employeeId="+employeeId+"&month="+YearMonth.from(from),false);}
    @GetMapping("/admin/work-policies") ModelAndView policies(@AuthenticationPrincipal TnaPrincipal a,@RequestParam(required=false) UUID employeeId){return policyPage(a,employeeId==null?a.employeeId():employeeId,null,null);}
    @PostMapping("/admin/work-policies") ModelAndView policy(@AuthenticationPrincipal TnaPrincipal a,@ModelAttribute("policyForm") PolicyForm f,BindingResult b){check(b);return submit(()->worktime.reviewPolicy(a,f),()->policyPage(a,a.employeeId(),f,null),"/admin/work-policies",false);}
    @PostMapping("/admin/work-assessments") ModelAndView assessment(@AuthenticationPrincipal TnaPrincipal a,@ModelAttribute("assessmentForm") AssessmentForm f,BindingResult b){check(b);return submit(()->worktime.assess(a,f),()->policyPage(a,f.employeeId(),null,f),"/admin/work-policies?employeeId="+f.employeeId(),false);}
    @PostMapping("/admin/reports/exports") ModelAndView export(@AuthenticationPrincipal TnaPrincipal a,@RequestParam UUID employeeId,@RequestParam @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate from,@RequestParam @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate to,@RequestParam UUID commandId,@RequestParam String reason){return submit(()->worktime.prepareExport(a,employeeId,from,to,commandId,reason),()->monthPage(a,employeeId,YearMonth.from(from),true),"/admin/reports/exports/",true);}
    @GetMapping("/admin/reports/exports/{id}/download") ResponseEntity<byte[]> download(@AuthenticationPrincipal TnaPrincipal a,@PathVariable UUID id){return ResponseEntity.ok().contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")).header(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename=attendance-review.xlsx").cacheControl(CacheControl.noStore()).body(worktime.download(a,id));}
    @GetMapping("/admin/audit") ModelAndView audit(@AuthenticationPrincipal TnaPrincipal a,@RequestParam(required=false) UUID before,@RequestParam(required=false) UUID after){
        var page=worktime.audits(a,before,after);return new ModelAndView("worktime/audit").addObject("page",page).addObject("audits",page.rows());
    }
    @GetMapping("/admin/audit/{id}") ModelAndView auditDetail(@AuthenticationPrincipal TnaPrincipal a,@PathVariable UUID id){
        return new ModelAndView("worktime/audit-detail").addObject("audit",worktime.auditDetail(a,id));
    }
    private ModelAndView monthPage(TnaPrincipal a,UUID employeeId,YearMonth month,boolean admin){var view=worktime.month(a,employeeId,month);return new ModelAndView("worktime/month").addObject("overview",view).addObject("employeeId",employeeId).addObject("admin",admin).addObject("employees",admin?worktime.employees(a):List.of()).addObject("from",month.atDay(1)).addObject("to",month.atEndOfMonth()).addObject("calculateCommand",UUID.randomUUID()).addObject("exportCommand",UUID.randomUUID());}
    private ModelAndView policyPage(TnaPrincipal a,UUID employeeId,PolicyForm p,AssessmentForm f){var today=commands.today();return new ModelAndView("worktime/policies").addObject("employees",worktime.employees(a)).addObject("policies",worktime.policies(a)).addObject("assessments",worktime.assessments(a,employeeId)).addObject("employeeId",employeeId).addObject("policyForm",p!=null?p:new PolicyForm(today,today.plusYears(1),false,"","",UUID.randomUUID())).addObject("assessmentForm",f!=null?f:new AssessmentForm(employeeId,today,today.plusYears(1),false,"","",UUID.randomUUID()));}
    private ModelAndView submit(Supplier<UUID> action,Supplier<ModelAndView> page,String target,boolean appendId){try{UUID id=action.get();var r=new RedirectView(appendId?target+id+"/download":target,true);r.setStatusCode(HttpStatus.SEE_OTHER);return new ModelAndView(r);}catch(OrgException e){if(e.status()!=409&&e.status()!=422)throw e;var view=page.get().addObject("error",e.getMessage());view.setStatus(HttpStatusCode.valueOf(e.status()));return view;}}
    private void check(BindingResult b){if(b.hasErrors())throw new OrgException(400,"기간·사원·검토 입력 형식을 확인해 주세요.");}
    public static String duration(Long ms){if(ms==null)return "-";long seconds=ms/1000;return String.format(Locale.ROOT,"%d:%02d:%02d",seconds/3600,(seconds/60)%60,seconds%60);}
}
