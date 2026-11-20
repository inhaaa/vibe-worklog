package kr.tna.clock;

import jakarta.servlet.http.HttpServletRequest;
import java.time.*;
import java.util.*;
import java.util.function.Supplier;
import kr.tna.common.auth.TnaPrincipal;
import kr.tna.common.web.SeoulTimeDisplay;
import kr.tna.clock.AttendanceSettingsData.*;
import kr.tna.clock.ClockData.*;
import kr.tna.org.*;
import kr.tna.worktime.WorktimeData.DayView;
import kr.tna.worktime.WorktimeService;
import kr.tna.worktime.SelectiveSettlementReturn;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
    private static final Logger LOG = LoggerFactory.getLogger(AttendanceController.class);
    private static final SeoulTimeDisplay TIME_DISPLAY = new SeoulTimeDisplay();
    private final AttendanceSettingsService settings;private final ClockService clocks;private final OrgCommands commands;private final WorktimeService worktime;
    private final SelectiveSettlementReturn settlements;
    public AttendanceController(AttendanceSettingsService settings, ClockService clocks, OrgCommands commands,
            WorktimeService worktime, SelectiveSettlementReturn settlements) {
        this.settings = settings;
        this.clocks = clocks;
        this.commands = commands;
        this.worktime = worktime;
        this.settlements = settlements;
    }
    @GetMapping("/admin/networks") ModelAndView networks(@AuthenticationPrincipal TnaPrincipal actor){return networkPage(actor,null);}
    @PostMapping("/admin/networks/changes") ModelAndView addNetwork(@AuthenticationPrincipal TnaPrincipal actor,@ModelAttribute("form") NetworkForm form,BindingResult b){check(b);return submit(()->settings.addNetwork(actor,form),"/admin/networks",()->networkPage(actor,form));}
    @PostMapping("/admin/networks/{id}/close") ModelAndView closeNetwork(@AuthenticationPrincipal TnaPrincipal actor,@PathVariable UUID id,@RequestParam Long expectedVersion,@RequestParam String reason,@RequestParam UUID commandId){return submit(()->settings.closeNetwork(actor,id,expectedVersion,reason,commandId),"/admin/networks",()->networkPage(actor,null));}
    @GetMapping("/admin/calendar")
    ModelAndView calendar(@AuthenticationPrincipal TnaPrincipal actor,
            @RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(required=false) String settlementReturn) {
        String origin = settlements.calendar(actor, settlementReturn, date);
        var form = origin == null ? null : new CalendarForm(date, date, false, "", "", "", UUID.randomUUID());
        return calendarPage(actor, form, date, origin);
    }
    @PostMapping("/admin/calendar/versions")
    ModelAndView calendarVersion(@AuthenticationPrincipal TnaPrincipal actor, @ModelAttribute("form") CalendarForm form,
            BindingResult binding, @RequestParam(required=false) String settlementReturn,
            @RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate date) {
        check(binding);
        String origin = settlements.calendar(actor, settlementReturn, date);
        return submit(() -> settings.calendar(actor,form), origin != null ? origin : "/admin/calendar",
                () -> calendarPage(actor, form, date, origin));
    }
    @GetMapping("/me/attendance")
    ModelAndView attendance(@AuthenticationPrincipal TnaPrincipal actor,
            @RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(required=false) String view,
            @RequestParam(required=false) @DateTimeFormat(pattern="uuuu-MM-dd") LocalDate from,
            @RequestParam(required=false) @DateTimeFormat(pattern="uuuu-MM-dd") LocalDate to,
            @RequestParam(required=false) String missingOnly, @RequestParam(required=false) String settlementReturn) {
        if (view == null && from == null && to == null && missingOnly == null)
            return SelectiveSettlementReturn.attach(history(actor, date), settlements.employee(actor, settlementReturn, date));
        if (!"period".equals(view) || date != null || (from == null) != (to == null)
                || (missingOnly != null && !"true".equals(missingOnly))) {
            throw new OrgException(400, "조회 방식과 시작일·종료일을 확인해 주세요.");
        }
        var today = commands.today();
        boolean missing = "true".equals(missingOnly);
        var page = new ModelAndView("clock/period");
        AttendanceHistory.Range range;
        try {
            range = AttendanceHistory.Range.of(today, from, to);
        } catch (OrgException invalid) {
            page.setStatus(HttpStatusCode.valueOf(422));
            return page.addObject("attendancePeriod", new AttendanceHistory.Period(today, from, to, missing, List.of()))
                    .addObject("error", invalid.getMessage());
        }
        var rows = worktime.attendanceHistory(actor, range);
        if (missing) rows = rows.stream().filter(row -> row.recordStatus() == AttendanceHistory.RecordStatus.MISSING).toList();
        return page.addObject("attendancePeriod", new AttendanceHistory.Period(today, range.from(), range.to(), missing, rows));
    }
    ModelAndView history(TnaPrincipal actor, LocalDate date) {return dayPage(actor,date==null?commands.today():date,null);}
    @PostMapping("/me/attendance/events") ModelAndView event(@AuthenticationPrincipal TnaPrincipal actor,@RequestParam String eventKind,@RequestParam UUID commandId,@RequestParam(required=false) String returnTo,HttpServletRequest request){
        if(!"home".equals(returnTo)){clocks.record(actor,eventKind,commandId,request.getRemoteAddr());return redirect("/me/attendance");}
        try{clocks.record(actor,eventKind,commandId,request.getRemoteAddr());return redirect("/me/home");}catch(OrgException e){return redirect("/me/home?clockError="+e.status());}
    }
    @PostMapping("/me/work-confirmations")
    ModelAndView confirm(@AuthenticationPrincipal TnaPrincipal actor, @ModelAttribute("confirmationForm") ConfirmationForm form,
            BindingResult binding, @RequestParam(required=false) String settlementReturn) {
        check(binding);
        String origin = settlements.employee(actor, settlementReturn, form.workDate());
        return submit(() -> { UUID id = clocks.confirm(actor,form); calculate(actor,form); return id; },
                origin != null ? origin : "/me/attendance?date=" + form.workDate(),
                () -> SelectiveSettlementReturn.attach(dayPage(actor,form.workDate(),form), origin));
    }
    private ModelAndView networkPage(TnaPrincipal actor,NetworkForm form){return new ModelAndView("clock/networks").addObject("pageCommand",UUID.randomUUID()).addObject("networks",settings.networks(actor)).addObject("form",form!=null?form:new NetworkForm("","",false,"",UUID.randomUUID()));}
    private ModelAndView calendarPage(TnaPrincipal actor, CalendarForm form, LocalDate date, String origin) {
        var page = new ModelAndView("clock/calendar").addObject("calendars",settings.calendars(actor))
                .addObject("form", form != null ? form : new CalendarForm(commands.today(),commands.today().plusDays(30),false,"","","",UUID.randomUUID()));
        if (origin != null) page.addObject("settlementWorkDate", date);
        return SelectiveSettlementReturn.attach(page, origin);
    }
    private ModelAndView dayPage(TnaPrincipal actor,LocalDate date,ConfirmationForm form){
        var d=clocks.day(actor,date);LocalDate today=commands.today();boolean isToday=today.equals(d.workDate());
        var confirmationBreaks=clocks.confirmableBreaks(d);
        boolean canConfirm=confirmationBreaks.isPresent();
        var page=new ModelAndView("clock/history").addObject("day",d).addObject("eventTimes",times(d))
                .addObject("confirmationForm",form!=null?form:confirmation(d)).addObject("today",today).addObject("isToday",isToday)
                .addObject("canConfirm",canConfirm).addObject("confirmationBreaks",confirmationBreaks.orElse(List.of()));
        page.addObject("actualWorkMillis",d.confirmation()==null?null:worktime.days(actor,actor.employeeId(),d.workDate(),d.workDate()).stream()
                .filter(DayView::current).findFirst().map(view->view.result().actualWorkMillis()).orElse(null));
        var effective=d.effectiveEvents()!=null?d.effectiveEvents():d.events();
        boolean past=d.workDate().isBefore(today)||isToday&&effective.stream().anyMatch(e->"OUT".equals(e.kind()));
        page.addObject("correctionSuggested",d.confirmation()==null&&!canConfirm&&past&&worktime.correctionSuggested(actor,d.workDate()));
        if(isToday)for(String kind:List.of("IN","OUT"))if(d.events().stream().noneMatch(e->kind.equals(e.kind())))
            page.addObject(kind.equals("IN")?"inCommand":"outCommand",UUID.randomUUID());
        return page;
    }
    /** 확인은 이미 저장됐다. 계산 실패는 그날을 확인 전으로 남기고 담당자 재집계로 복구한다. */
    private void calculate(TnaPrincipal actor,ConfirmationForm form){
        try{worktime.calculateConfirmedDay(actor,form.workDate(),form.commandId());}
        catch(RuntimeException failed){LOG.warn("event=worktime_auto_calculate outcome=failed status={} cause={}",failed instanceof OrgException e?e.status():500,failed.getClass().getSimpleName());}
    }
    public static ConfirmationForm confirmation(DaySnapshot d){return new ConfirmationForm(d.workDate(),d.inputRevision(),d.reviewRevision(),false,false,d.sourceFingerprint(),UUID.randomUUID());}
    public static Map<UUID,String> times(DaySnapshot d){Map<UUID,String> values=new HashMap<>();for(var e:d.events())values.put(e.id(),TIME_DISPLAY.instant(e.occurredAt()));for(var e:d.effectiveEvents())values.put(e.id(),TIME_DISPLAY.instant(e.occurredAt()));return values;}
    private ModelAndView submit(Supplier<UUID> fn,String target,Supplier<ModelAndView> page){try{fn.get();return redirect(target);}catch(OrgException e){if(e.status()!=409&&e.status()!=422)throw e;var v=page.get().addObject("error",e.getMessage());v.setStatus(HttpStatusCode.valueOf(e.status()));return v;}}
    private ModelAndView redirect(String path){var r=new RedirectView(path,true);r.setStatusCode(HttpStatus.SEE_OTHER);return new ModelAndView(r);}
    private void check(BindingResult b){if(b.hasErrors())throw new OrgException(400,"날짜·식별자·버전 형식을 확인해 주세요.");}
}
