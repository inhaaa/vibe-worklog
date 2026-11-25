package kr.tna.request;

import java.time.*;
import java.util.*;
import java.util.function.Supplier;
import kr.tna.common.auth.TnaPrincipal;
import kr.tna.clock.*;
import kr.tna.org.*;
import kr.tna.request.RequestData.*;
import kr.tna.worktime.MonthCloseReviewReturn;
import kr.tna.worktime.SelectiveSettlementReturn;
import kr.tna.worktime.WorktimeService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.servlet.view.RedirectView;

@Controller
public class RequestController {
    private final LeaveWorkflowService flow;private final ClockService clocks;private final EmployeeService employees;private final ApproverService approvers;private final OrgCommands commands;private final Clock clock;private final AnnualRequestService annual;
    private final LeaveOperationsQueries leaveOperations;
    private final SelectiveSettlementReturn settlements;
    private final WorktimeService worktime;
    public RequestController(LeaveWorkflowService flow, ClockService clocks, EmployeeService employees, ApproverService approvers,
            OrgCommands commands, Clock clock, AnnualRequestService annual, LeaveOperationsQueries leaveOperations,
            SelectiveSettlementReturn settlements, WorktimeService worktime) {
        this.flow = flow;
        this.clocks = clocks;
        this.employees = employees;
        this.approvers = approvers;
        this.commands = commands;
        this.clock = clock;
        this.annual = annual;
        this.leaveOperations = leaveOperations;
        this.settlements = settlements;
        this.worktime = worktime;
    }
    @GetMapping(value="/requests/route-preview",produces=MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody ApprovalPreview routePreview(@AuthenticationPrincipal TnaPrincipal actor,@RequestParam String kind,
            @RequestParam(required=false) UUID targetEmployeeId) {
        return flow.formPreview(actor,targetEmployeeId,kind);
    }
    @GetMapping("/approvals") ModelAndView inbox(@AuthenticationPrincipal TnaPrincipal a,@RequestParam(defaultValue="0") int page){return approvalPage(a,false,page);}
    @GetMapping("/approvals/completed") ModelAndView completed(@AuthenticationPrincipal TnaPrincipal a,@RequestParam(defaultValue="0") int page){return approvalPage(a,true,page);}
    @GetMapping(value = LeaveOperationsQueries.PATH, params = "scope!=LEAVE")
    ModelAndView adminRequests(@AuthenticationPrincipal TnaPrincipal actor, @RequestParam(required=false) String scope) {
        var parsed = RequestScope.parse(scope);
        if (parsed == null) return new ModelAndView(new RedirectView(LeaveOperationsQueries.DEFAULT_URL, true));
        return listing("출퇴근 정정 현황", flow.adminRequests(actor, parsed)).addObject("requestScope", parsed);
    }
    @GetMapping("/requests/{id}")
    ModelAndView detail(@AuthenticationPrincipal TnaPrincipal actor, @PathVariable UUID id,
            @RequestParam(required=false) String returnTo, @RequestParam(required=false) String settlementReturn) {
        var detail = detailPage(actor, id);
        var request = (RequestView) detail.getModel().get("request");
        if (actor.roles().contains("ATTENDANCE_ADMIN") && isLeave(request)) {
            var policy = ((RequestDetail) detail.getModel().get("detail")).attendancePolicy();
            String safeReturn = LeaveOperationsQueries.validReturnTo(returnTo, policy);
            if (safeReturn != null) detail.addObject("leaveReturnTo", safeReturn);
        }
        if (actor.roles().contains("ATTENDANCE_ADMIN")) {
            String back = MonthCloseReviewReturn.request(returnTo, request.fromDate(), request.toDate());
            if (back != null) detail.addObject("monthCloseReturnTo", back);
        }
        return SelectiveSettlementReturn.attach(detail, correctionOrigin(actor, request, settlementReturn));
    }
    private String correctionOrigin(TnaPrincipal actor, RequestView request, String raw) {
        if (!"ATTENDANCE_CORRECTION".equals(request.kind())) return null;
        if (actor.employeeId().equals(request.targetEmployeeId())) {
            String own = settlements.employee(actor, raw, request.fromDate());
            if (own != null) return own;
        }
        return actor.roles().contains("ATTENDANCE_ADMIN")
                ? settlements.admin(actor, raw, request.targetEmployeeId(), request.fromDate()) : null;
    }
    @PostMapping(value="/me/requests/leave",params="!year") ModelAndView leave(@AuthenticationPrincipal TnaPrincipal a,@ModelAttribute("form") LeaveForm f,BindingResult b,@RequestParam(required=false) String routeToken){check(b);return submit(()->flow.submitLeave(a,f,routeToken),()->minePage(a,f),"/requests/");}
    @GetMapping("/me/requests/correction")
    ModelAndView correction(@AuthenticationPrincipal TnaPrincipal actor,
            @RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(required=false) String mode, @RequestParam(required=false) String settlementReturn) {
        return SelectiveSettlementReturn.attach(
                correctionPage(actor, actor.employeeId(), date == null ? commands.today() : date, false, "full".equals(mode), null),
                settlements.employee(actor, settlementReturn, date));
    }
    @GetMapping("/admin/requests/exceptions/new") ModelAndView exception(@AuthenticationPrincipal TnaPrincipal a,@RequestParam UUID employeeId,@RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate date){return correctionPage(a,employeeId,date==null?commands.today():date,true,true,null);}
    @PostMapping(value="/me/requests/correction",params="!year")
    ModelAndView correct(@AuthenticationPrincipal TnaPrincipal actor, @RequestParam(required=false) String mode,
            @ModelAttribute("form") CorrectionForm form, BindingResult binding,@RequestParam(required=false) String routeToken,
            @RequestParam(required=false) String settlementReturn) {
        check(binding);
        boolean checkout = "checkout".equals(mode);
        var safe = correctionForm(form, null, false, checkout);
        String origin = settlements.employee(actor, settlementReturn, form.workDate());
        return submit(() -> recalculated(actor, flow.submitCorrection(actor, safe,routeToken)),
                () -> SelectiveSettlementReturn.attach(correctionPage(actor, actor.employeeId(), form.workDate(), false, !checkout, safe), origin),
                id -> origin != null ? origin : "/requests/" + id);
    }
    @PostMapping("/admin/requests/exceptions") ModelAndView exceptional(@AuthenticationPrincipal TnaPrincipal a,@ModelAttribute("form") CorrectionForm f,BindingResult b,@RequestParam(required=false) String routeToken){check(b);var safe=correctionForm(f,f.targetEmployeeId(),true,false);return submit(()->recalculated(a,flow.submitCorrection(a,safe,routeToken)),()->correctionPage(a,f.targetEmployeeId(),f.workDate(),true,true,safe),"/requests/");}
    @PostMapping("/requests/{id}/approve")
    ModelAndView approve(@AuthenticationPrincipal TnaPrincipal actor, @PathVariable UUID id, @RequestParam Long expectedVersion,
            @RequestParam String reason, @RequestParam UUID commandId, @RequestParam(required=false) Integer expectedStep,
            @RequestParam(required=false) String settlementReturn) {
        var decision = new DecisionForm("APPROVE", expectedVersion, reason, commandId, expectedStep);
        return submitDecision(actor, id, settlementReturn, () -> recalculated(actor, flow.decide(actor, id, decision)));
    }
    @PostMapping("/requests/{id}/reject")
    ModelAndView reject(@AuthenticationPrincipal TnaPrincipal actor, @PathVariable UUID id, @RequestParam Long expectedVersion,
            @RequestParam String reason, @RequestParam UUID commandId, @RequestParam(required=false) Integer expectedStep,
            @RequestParam(required=false) String settlementReturn) {
        var decision = new DecisionForm("REJECT", expectedVersion, reason, commandId, expectedStep);
        return submitDecision(actor, id, settlementReturn, () -> recalculated(actor, flow.decide(actor, id, decision)));
    }
    @PostMapping("/requests/{id}/withdraw")
    ModelAndView withdraw(@AuthenticationPrincipal TnaPrincipal actor, @PathVariable UUID id, @RequestParam Long expectedVersion,
            @RequestParam String reason, @RequestParam UUID commandId, @RequestParam(required=false) Integer expectedStep,
            @RequestParam(required=false) String settlementReturn) {
        var decision = new DecisionForm("WITHDRAW", expectedVersion, reason, commandId, expectedStep);
        return submitDecision(actor, id, settlementReturn, () -> recalculated(actor, flow.withdraw(actor, id, decision)));
    }
    @PostMapping("/requests/{id}/cancellations") ModelAndView cancellation(@AuthenticationPrincipal TnaPrincipal a,@PathVariable UUID id,@RequestParam Long expectedVersion,@RequestParam String reason,@RequestParam UUID commandId,@RequestParam(required=false) String routeToken){return submit(()->recalculated(a,flow.requestCancellation(a,id,new CancelForm(false,expectedVersion,reason,commandId),routeToken)),()->detailPage(a,id).addObject("cancelReason",reason),"/requests/");}
    @PostMapping("/admin/requests/{id}/cancellation-exceptions") ModelAndView exceptionalCancellation(@AuthenticationPrincipal TnaPrincipal a,@PathVariable UUID id,@RequestParam Long expectedVersion,@RequestParam String reason,@RequestParam UUID commandId,@RequestParam(required=false) String routeToken){return submit(()->recalculated(a,flow.requestCancellation(a,id,new CancelForm(true,expectedVersion,reason,commandId),routeToken)),()->detailPage(a,id).addObject("exceptionCancelReason",reason),"/requests/");}
    @PostMapping("/admin/requests/{id}/reassign")
    ModelAndView reassign(@AuthenticationPrincipal TnaPrincipal actor, @PathVariable UUID id, @ModelAttribute ReassignForm form,
            BindingResult binding, @RequestParam(required=false) String settlementReturn) {
        check(binding);
        return submitDecision(actor, id, settlementReturn, () -> flow.reassign(actor, id, form));
    }
    private ModelAndView submitDecision(TnaPrincipal actor, UUID id, String rawOrigin, Supplier<UUID> action) {
        String origin = rawOrigin == null ? null : correctionOrigin(actor, flow.get(actor,id).request(), rawOrigin);
        return submit(action, () -> SelectiveSettlementReturn.attach(detailPage(actor,id), origin),
                saved -> origin != null ? origin : "/requests/" + saved);
    }
    @GetMapping("/admin/leave-balances") ModelAndView balances(@AuthenticationPrincipal TnaPrincipal a,@RequestParam(required=false) UUID employeeId){return balancesPage(a,employeeId==null?a.employeeId():employeeId,null);}
    @PostMapping("/admin/leave-balances/entries") ModelAndView opening(@AuthenticationPrincipal TnaPrincipal a,@ModelAttribute("form") BucketForm f,BindingResult b){check(b);return submit(()->flow.openBucket(a,f),()->balancesPage(a,f.employeeId(),f),"/admin/leave-balances/buckets/");}
    @GetMapping("/admin/leave-balances/buckets/{id}") ModelAndView ledger(@AuthenticationPrincipal TnaPrincipal a,@PathVariable UUID id){var rows=flow.ledger(a,id);return new ModelAndView("request/ledger").addObject("ledger",rows).addObject("bucketId",id).addObject("adjustment",new AdjustmentForm(null,commands.today(),rows.isEmpty()?0:bucketVersion(a,rows.getFirst().employeeId(),id),"","",UUID.randomUUID()));}
    @PostMapping("/admin/leave-balances/buckets/{id}/adjustments") ModelAndView adjust(@AuthenticationPrincipal TnaPrincipal a,@PathVariable UUID id,@ModelAttribute AdjustmentForm f,BindingResult b){check(b);return submit(()->flow.adjust(a,id,f),()->ledger(a,id),"/admin/leave-balances/buckets/");}
    private long bucketVersion(TnaPrincipal a,UUID employee,UUID id){return flow.buckets(a,employee).stream().filter(x->x.id().equals(id)).findFirst().orElseThrow(OrgException::notFound).version();}
    private ModelAndView minePage(TnaPrincipal a,LeaveForm f){return AnnualRequestController.model(annual.view(a,new AnnualRequestData.Filter(null,null,"",0,0),f==null?null:new AnnualRequestService.Draft("MODAL","LEAVE_DAY",null,f.predecessorRequestId(),f,null,false,false),null));}
    private ModelAndView listing(String title,List<RequestView> requests){return new ModelAndView("request/list").addObject("title",title).addObject("requests",requests);}
    private ModelAndView approvalPage(TnaPrincipal a,boolean completed,int page){var data=flow.approvalPage(a,completed,page);return listing(completed?"내가 처리한 결재":"결재 대기함",data.requests()).addObject("page",data.page()).addObject("hasNext",data.hasNext()).addObject("completed",data.completed());}
    private ModelAndView detailPage(TnaPrincipal a,UUID id){
        var detail=flow.get(a,id);var r=detail.request();boolean admin=a.roles().contains("ATTENDANCE_ADMIN");boolean pending=r.status().equals("PENDING");
        boolean overtime=r.kind().equals("OVERTIME");
        boolean cancelableEffect=(overtime||r.kind().equals("LEAVE_DAY"))&&r.status().equals("APPROVED")&&r.effectStatus().equals("EFFECTIVE");
        boolean owningAuthor=a.accountId().equals(r.authorAccountId())&&a.employeeId().equals(r.targetEmployeeId());
        var view = new ModelAndView("request/detail").addObject("pageCommand",UUID.randomUUID()).addObject("detail",detail).addObject("request",r).addObject("admin",admin)
                .addObject("ownRequest",a.employeeId().equals(r.targetEmployeeId()))
                .addObject("canDecide",pending&&!detail.reassignmentRequired()&&a.roles().contains("APPROVER")&&a.employeeId().equals(r.currentApproverId())&&!a.employeeId().equals(r.targetEmployeeId())&&!a.accountId().equals(r.authorAccountId()))
                .addObject("canWithdraw",pending&&a.accountId().equals(r.authorAccountId()))
                .addObject("canCancel",cancelableEffect&&owningAuthor&&(overtime
                        ? clock.instant().isBefore(detail.overtime().workDate().atTime(detail.overtime().startTime()).atZone(ZoneId.of("Asia/Seoul")).toInstant())
                        : beforeLeaveStarts(a,detail)))
                .addObject("canCancelException",admin&&cancelableEffect&&!overtime).addObject("candidates",admin?approvers.candidates(a):List.of());
        if(cancelableEffect&&(owningAuthor||admin))view.addObject("cancelApproval",flow.formPreview(a,r.targetEmployeeId(),overtime?"CANCEL_OVERTIME":"CANCEL_LEAVE"));
        if (admin && isLeave(r) && Set.of("APPROVED", "REJECTED").contains(r.status())) {
            view.addObject("leaveDecidedByName", leaveOperations.decidedByName(a, id));
        }
        return view;
    }
    private static boolean isLeave(RequestView request) {
        return "LEAVE_DAY".equals(request.kind()) || "CANCEL_LEAVE".equals(request.kind());
    }
    private boolean beforeLeaveStarts(TnaPrincipal actor,RequestDetail detail){
        LocalDate today=commands.today();
        for(var day:detail.days())if(day.quantityDays()>0){
            if(day.leaveDate().isBefore(today))return false;
            if(day.leaveDate().isAfter(today))return true;
            var snapshot=clocks.day(actor,day.leaveDate());
            return snapshot.schedule()!=null&&snapshot.schedule().startTime()!=null
                    &&clock.instant().isBefore(day.leaveDate().atTime(snapshot.schedule().startTime()).atZone(ZoneId.of("Asia/Seoul")).toInstant());
        }
        return false;
    }
    private ModelAndView balancesPage(TnaPrincipal a,UUID id,BucketForm f){var employee=employees.get(a,id);return new ModelAndView("request/balances").addObject("employee",employee).addObject("employees",employees.options(a)).addObject("buckets",flow.buckets(a,id)).addObject("form",f!=null?f:new BucketForm(id,commands.today(),commands.today(),commands.today().plusYears(1),null,"","",UUID.randomUUID()));}
    private ModelAndView correctionPage(TnaPrincipal actor, UUID employee, LocalDate date, boolean exceptional,
            boolean full, CorrectionForm submitted) {
        if (employee == null || date == null) throw OrgException.invalid("대상 사원과 날짜가 필요합니다.");
        var evidence = exceptional
                ? new LeaveWorkflowService.CorrectionPageEvidence(
                        commands.read(actor, () -> clocks.snapshot(actor.tenantId(), employee, date)),
                        flow.currentCorrectionRequestId(actor, employee, date), null)
                : flow.correctionPageEvidence(actor, date);
        var day = evidence.day();
        boolean checkout = !exceptional && !full && date.isBefore(commands.today())
                && onlyArrival(day.events()) && onlyArrival(day.effectiveEvents());
        var form = submitted != null ? submitted : new CorrectionForm(exceptional ? employee : null, date,
                day.inputRevision(), day.sourceFingerprint(), "KEEP", "", checkout ? "SET" : "KEEP", "",
                false, false, "", evidence.currentCorrectionId(), null, exceptional, "", UUID.randomUUID());
        return new ModelAndView("request/correction").addObject("actions", List.of("KEEP", "SET", "CLEAR"))
                .addObject("day", day).addObject("exceptional", exceptional)
                .addObject("approval",flow.formPreview(actor,employee,"ATTENDANCE_CORRECTION"))
                .addObject("eventTimes", AttendanceController.times(day)).addObject("form", form)
                .addObject("checkoutOnly", checkout).addObject("pendingCorrectionId", evidence.pendingCorrectionId());
    }
    private static boolean onlyArrival(List<ClockData.Event> events) {
        return events.size() == 1 && "IN".equals(events.getFirst().kind());
    }
    private CorrectionForm correctionForm(CorrectionForm form, UUID target, boolean exceptional, boolean checkout) {
        return new CorrectionForm(target, form.workDate(), form.baseInputRevision(), form.sourceFingerprint(),
                checkout ? "KEEP" : form.inAction(), checkout ? "" : form.requestedInAt(),
                checkout ? "SET" : form.outAction(), form.requestedOutAt(), form.presenceAsWork(), form.breaksConfirmed(),
                form.actualBreaks(), form.replacesRequestId(), form.predecessorRequestId(), exceptional, form.reason(), form.commandId());
    }
    private ModelAndView submit(Supplier<UUID> action,Supplier<ModelAndView> page,String prefix) {
        return submit(action, page, id -> prefix + id);
    }
    private ModelAndView submit(Supplier<UUID> action, Supplier<ModelAndView> page, java.util.function.Function<UUID,String> destination) {
        try {
            var redirect = new RedirectView(destination.apply(action.get()), true);
            redirect.setStatusCode(HttpStatus.SEE_OTHER);
            return new ModelAndView(redirect);
        } catch (OrgException e) {
            if (e.status() != 409 && e.status() != 422) throw e;
            var view = page.get().addObject("error", e.getMessage());
            view.setStatus(HttpStatusCode.valueOf(e.status()));
            return view;
        }
    }
    /** 결재·정정 반영 직후 대상 사원의 재집계 필요 날짜를 계산한다. 계산 실패는 처리 결과를 바꾸지 않는다. */
    private UUID recalculated(TnaPrincipal actor, UUID requestId) {
        worktime.recalculateAfterDecision(actor, requestId);
        return requestId;
    }
    private void check(BindingResult b){if(b.hasErrors())throw new OrgException(400,"신청 입력 형식을 확인해 주세요.");}
}
