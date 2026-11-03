package kr.tna.request;

import java.time.*;
import java.util.*;
import java.util.function.Supplier;
import kr.tna.common.auth.TnaPrincipal;
import kr.tna.clock.*;
import kr.tna.org.*;
import kr.tna.request.RequestData.*;
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
    public RequestController(LeaveWorkflowService flow,ClockService clocks,EmployeeService employees,ApproverService approvers,OrgCommands commands,Clock clock,AnnualRequestService annual){this.flow=flow;this.clocks=clocks;this.employees=employees;this.approvers=approvers;this.commands=commands;this.clock=clock;this.annual=annual;}
    @GetMapping("/approvals") ModelAndView inbox(@AuthenticationPrincipal TnaPrincipal a,@RequestParam(defaultValue="0") int page){return approvalPage(a,false,page);}
    @GetMapping("/approvals/completed") ModelAndView completed(@AuthenticationPrincipal TnaPrincipal a,@RequestParam(defaultValue="0") int page){return approvalPage(a,true,page);}
    @GetMapping("/admin/requests") ModelAndView adminRequests(@AuthenticationPrincipal TnaPrincipal a){return listing("고객사 신청 현황",flow.adminRequests(a));}
    @GetMapping("/requests/{id}") ModelAndView detail(@AuthenticationPrincipal TnaPrincipal a,@PathVariable UUID id){return detailPage(a,id);}
    @PostMapping(value="/me/requests/leave",params="!year") ModelAndView leave(@AuthenticationPrincipal TnaPrincipal a,@ModelAttribute("form") LeaveForm f,BindingResult b){check(b);return submit(()->flow.submitLeave(a,f),()->minePage(a,f),"/requests/");}
    @GetMapping("/me/requests/correction") ModelAndView correction(@AuthenticationPrincipal TnaPrincipal a,@RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate date){return correctionPage(a,a.employeeId(),date==null?commands.today():date,false,null);}
    @GetMapping("/admin/requests/exceptions/new") ModelAndView exception(@AuthenticationPrincipal TnaPrincipal a,@RequestParam UUID employeeId,@RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate date){return correctionPage(a,employeeId,date==null?commands.today():date,true,null);}
    @PostMapping(value="/me/requests/correction",params="!year") ModelAndView correct(@AuthenticationPrincipal TnaPrincipal a,@ModelAttribute("form") CorrectionForm f,BindingResult b){check(b);var safe=correctionForm(f,null,false);return submit(()->flow.submitCorrection(a,safe),()->correctionPage(a,a.employeeId(),f.workDate(),false,safe),"/requests/");}
    @PostMapping("/admin/requests/exceptions") ModelAndView exceptional(@AuthenticationPrincipal TnaPrincipal a,@ModelAttribute("form") CorrectionForm f,BindingResult b){check(b);var safe=correctionForm(f,f.targetEmployeeId(),true);return submit(()->flow.submitCorrection(a,safe),()->correctionPage(a,f.targetEmployeeId(),f.workDate(),true,safe),"/requests/");}
    @PostMapping("/requests/{id}/approve") ModelAndView approve(@AuthenticationPrincipal TnaPrincipal a,@PathVariable UUID id,@RequestParam Long expectedVersion,@RequestParam String reason,@RequestParam UUID commandId){return submit(()->flow.decide(a,id,new DecisionForm("APPROVE",expectedVersion,reason,commandId)),()->detailPage(a,id),"/requests/");}
    @PostMapping("/requests/{id}/reject") ModelAndView reject(@AuthenticationPrincipal TnaPrincipal a,@PathVariable UUID id,@RequestParam Long expectedVersion,@RequestParam String reason,@RequestParam UUID commandId){return submit(()->flow.decide(a,id,new DecisionForm("REJECT",expectedVersion,reason,commandId)),()->detailPage(a,id),"/requests/");}
    @PostMapping("/requests/{id}/withdraw") ModelAndView withdraw(@AuthenticationPrincipal TnaPrincipal a,@PathVariable UUID id,@RequestParam Long expectedVersion,@RequestParam String reason,@RequestParam UUID commandId){return submit(()->flow.withdraw(a,id,new DecisionForm("WITHDRAW",expectedVersion,reason,commandId)),()->detailPage(a,id),"/requests/");}
    @PostMapping("/requests/{id}/cancellations") ModelAndView cancellation(@AuthenticationPrincipal TnaPrincipal a,@PathVariable UUID id,@RequestParam Long expectedVersion,@RequestParam String reason,@RequestParam UUID commandId){return submit(()->flow.requestCancellation(a,id,new CancelForm(false,expectedVersion,reason,commandId)),()->detailPage(a,id),"/requests/");}
    @PostMapping("/admin/requests/{id}/cancellation-exceptions") ModelAndView exceptionalCancellation(@AuthenticationPrincipal TnaPrincipal a,@PathVariable UUID id,@RequestParam Long expectedVersion,@RequestParam String reason,@RequestParam UUID commandId){return submit(()->flow.requestCancellation(a,id,new CancelForm(true,expectedVersion,reason,commandId)),()->detailPage(a,id),"/requests/");}
    @PostMapping("/admin/requests/{id}/reassign") ModelAndView reassign(@AuthenticationPrincipal TnaPrincipal a,@PathVariable UUID id,@ModelAttribute ReassignForm f,BindingResult b){check(b);return submit(()->flow.reassign(a,id,f),()->detailPage(a,id),"/requests/");}
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
        boolean cancelableEffect=r.kind().equals("LEAVE_DAY")&&r.status().equals("APPROVED")&&r.effectStatus().equals("EFFECTIVE");
        boolean owningAuthor=a.accountId().equals(r.authorAccountId())&&a.employeeId().equals(r.targetEmployeeId());
        return new ModelAndView("request/detail").addObject("pageCommand",UUID.randomUUID()).addObject("detail",detail).addObject("request",r).addObject("admin",admin)
                .addObject("ownRequest",a.employeeId().equals(r.targetEmployeeId()))
                .addObject("canDecide",pending&&a.roles().contains("APPROVER")&&a.employeeId().equals(r.currentApproverId())&&!a.employeeId().equals(r.targetEmployeeId())&&!a.accountId().equals(r.authorAccountId()))
                .addObject("canWithdraw",pending&&a.accountId().equals(r.authorAccountId()))
                .addObject("canCancel",cancelableEffect&&owningAuthor&&beforeLeaveStarts(a,detail))
                .addObject("canCancelException",admin&&cancelableEffect).addObject("candidates",admin?approvers.candidates(a):List.of());
    }
    private boolean beforeLeaveStarts(TnaPrincipal actor,RequestDetail detail){
        LocalDate today=commands.today();
        for(var day:detail.days())if(day.quantityDays()==1){
            if(day.leaveDate().isBefore(today))return false;
            if(day.leaveDate().isAfter(today))return true;
            var snapshot=clocks.day(actor,day.leaveDate());
            return snapshot.schedule()!=null&&snapshot.schedule().startTime()!=null
                    &&clock.instant().isBefore(day.leaveDate().atTime(snapshot.schedule().startTime()).atZone(ZoneId.of("Asia/Seoul")).toInstant());
        }
        return false;
    }
    private ModelAndView balancesPage(TnaPrincipal a,UUID id,BucketForm f){var employee=employees.get(a,id);return new ModelAndView("request/balances").addObject("employee",employee).addObject("employees",employees.options(a)).addObject("buckets",flow.buckets(a,id)).addObject("form",f!=null?f:new BucketForm(id,commands.today(),commands.today(),commands.today().plusYears(1),null,"","",UUID.randomUUID()));}
    private ModelAndView correctionPage(TnaPrincipal a,UUID employee,LocalDate date,boolean exceptional,CorrectionForm f){if(employee==null||date==null)throw OrgException.invalid("대상 사원과 날짜가 필요합니다.");var day=exceptional?commands.read(a,()->clocks.snapshot(a.tenantId(),employee,date)):clocks.day(a,date);return new ModelAndView("request/correction").addObject("actions",List.of("KEEP","SET","CLEAR")).addObject("day",day).addObject("exceptional",exceptional).addObject("eventTimes",AttendanceController.times(day)).addObject("form",f!=null?f:new CorrectionForm(exceptional?employee:null,date,day.inputRevision(),day.sourceFingerprint(),"KEEP","","KEEP","",false,false,"",null,null,exceptional,"",UUID.randomUUID()));}
    private CorrectionForm correctionForm(CorrectionForm f,UUID target,boolean exceptional){return new CorrectionForm(target,f.workDate(),f.baseInputRevision(),f.sourceFingerprint(),f.inAction(),f.requestedInAt(),f.outAction(),f.requestedOutAt(),f.presenceAsWork(),f.breaksConfirmed(),f.actualBreaks(),f.replacesRequestId(),f.predecessorRequestId(),exceptional,f.reason(),f.commandId());}
    private ModelAndView submit(Supplier<UUID> fn,Supplier<ModelAndView> page,String prefix){try{var r=new RedirectView(prefix+fn.get(),true);r.setStatusCode(HttpStatus.SEE_OTHER);return new ModelAndView(r);}catch(OrgException e){if(e.status()!=409&&e.status()!=422)throw e;var view=page.get().addObject("error",e.getMessage());view.setStatus(HttpStatusCode.valueOf(e.status()));return view;}}
    private void check(BindingResult b){if(b.hasErrors())throw new OrgException(400,"신청 입력 형식을 확인해 주세요.");}
}
