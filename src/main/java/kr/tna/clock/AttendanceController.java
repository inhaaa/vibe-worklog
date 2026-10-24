package kr.tna.clock;

import jakarta.servlet.http.HttpServletRequest;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.function.Supplier;
import kr.tna.common.auth.TnaPrincipal;
import kr.tna.clock.AttendanceSettingsData.*;
import kr.tna.clock.ClockData.*;
import kr.tna.org.*;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.servlet.view.RedirectView;

@Controller
public class AttendanceController {
    private final AttendanceSettingsService settings;private final ClockService clocks;private final OrgCommands commands;
    public AttendanceController(AttendanceSettingsService settings,ClockService clocks,OrgCommands commands){this.settings=settings;this.clocks=clocks;this.commands=commands;}
    @GetMapping("/admin/networks") ModelAndView networks(@AuthenticationPrincipal TnaPrincipal actor){return networkPage(actor,null);}
    @PostMapping("/admin/networks/changes") ModelAndView addNetwork(@AuthenticationPrincipal TnaPrincipal actor,@ModelAttribute("form") NetworkForm form,BindingResult b){check(b);return submit(()->settings.addNetwork(actor,form),"/admin/networks",()->networkPage(actor,form));}
    @PostMapping("/admin/networks/{id}/close") ModelAndView closeNetwork(@AuthenticationPrincipal TnaPrincipal actor,@PathVariable UUID id,@RequestParam Long expectedVersion,@RequestParam String reason,@RequestParam UUID commandId){return submit(()->settings.closeNetwork(actor,id,expectedVersion,reason,commandId),"/admin/networks",()->networkPage(actor,null));}
    @GetMapping("/admin/calendar") ModelAndView calendar(@AuthenticationPrincipal TnaPrincipal actor){return calendarPage(actor,null);}
    @PostMapping("/admin/calendar/versions") ModelAndView calendarVersion(@AuthenticationPrincipal TnaPrincipal actor,@ModelAttribute("form") CalendarForm form,BindingResult b){check(b);return submit(()->settings.calendar(actor,form),"/admin/calendar",()->calendarPage(actor,form));}
    @GetMapping("/me/attendance") ModelAndView history(@AuthenticationPrincipal TnaPrincipal actor,@RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate date){return dayPage(actor,date==null?commands.today():date,null);}
    @PostMapping("/me/attendance/events") ModelAndView event(@AuthenticationPrincipal TnaPrincipal actor,@RequestParam String eventKind,@RequestParam UUID commandId,HttpServletRequest request){clocks.record(actor,eventKind,commandId,request.getRemoteAddr());return redirect("/me/today");}
    @PostMapping("/me/work-confirmations") ModelAndView confirm(@AuthenticationPrincipal TnaPrincipal actor,@ModelAttribute("confirmationForm") ConfirmationForm form,BindingResult b){check(b);return submit(()->clocks.confirm(actor,form),"/me/attendance?date="+form.workDate(),()->dayPage(actor,form.workDate(),form));}
    private ModelAndView networkPage(TnaPrincipal actor,NetworkForm form){return new ModelAndView("clock/networks").addObject("pageCommand",UUID.randomUUID()).addObject("networks",settings.networks(actor)).addObject("form",form!=null?form:new NetworkForm("","",false,"",UUID.randomUUID()));}
    private ModelAndView calendarPage(TnaPrincipal actor,CalendarForm form){return new ModelAndView("clock/calendar").addObject("calendars",settings.calendars(actor)).addObject("form",form!=null?form:new CalendarForm(commands.today(),commands.today().plusDays(30),false,"","","",UUID.randomUUID()));}
    private ModelAndView dayPage(TnaPrincipal actor,LocalDate date,ConfirmationForm form){var d=clocks.day(actor,date);return new ModelAndView("clock/history").addObject("day",d).addObject("eventTimes",times(d)).addObject("confirmationForm",form!=null?form:confirmation(d));}
    public static ConfirmationForm confirmation(DaySnapshot d){return new ConfirmationForm(d.workDate(),d.inputRevision(),d.reviewRevision(),false,false,d.sourceFingerprint(),UUID.randomUUID());}
    public static Map<UUID,String> times(DaySnapshot d){var f=DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss.SSS").withZone(ZoneId.of("Asia/Seoul"));Map<UUID,String> values=new HashMap<>();for(var e:d.events())values.put(e.id(),f.format(e.occurredAt()));for(var e:d.effectiveEvents())values.put(e.id(),f.format(e.occurredAt()));return values;}
    private ModelAndView submit(Supplier<UUID> fn,String target,Supplier<ModelAndView> page){try{fn.get();return redirect(target);}catch(OrgException e){if(e.status()!=409&&e.status()!=422)throw e;var v=page.get().addObject("error",e.getMessage());v.setStatus(HttpStatusCode.valueOf(e.status()));return v;}}
    private ModelAndView redirect(String path){var r=new RedirectView(path,true);r.setStatusCode(HttpStatus.SEE_OTHER);return new ModelAndView(r);}
    private void check(BindingResult b){if(b.hasErrors())throw new OrgException(400,"날짜·식별자·버전 형식을 확인해 주세요.");}
}
