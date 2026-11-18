package kr.tna.request;

import static kr.tna.org.OrgCommands.*;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import kr.tna.clock.ClockData;
import kr.tna.clock.ClockService;
import kr.tna.common.auth.TnaPrincipal;
import kr.tna.common.tenant.BusinessCommands;
import kr.tna.org.ApproverMapper;
import kr.tna.org.EmployeeAdminMapper;
import kr.tna.org.OrgCommands;
import kr.tna.org.OrgCommands.Change;
import kr.tna.org.OrgData.Employee;
import kr.tna.org.OrgException;
import kr.tna.request.RequestData.*;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

@Service
public final class LeaveWorkflowService {
    private static final ZoneId SEOUL=ZoneId.of("Asia/Seoul");
    private static final Set<String> ACTIONS=Set.of("KEEP","SET","CLEAR");
    private final LeaveWorkflowMapper mapper;
    private final BusinessCommands commands;
    private final OrgCommands administration;
    private final EmployeeAdminMapper employees;
    private final ApproverMapper approvers;
    private final ClockService clocks;
    private final Clock clock;
    private final ApprovalRouteResolver routes;
    private final AttendanceTypeResolver attendanceTypes;
    private final FormRouteResolver formRoutes;
    private final JsonMapper json=JsonMapper.builder().build();
    public LeaveWorkflowService(LeaveWorkflowMapper mapper,BusinessCommands commands,OrgCommands administration,
            EmployeeAdminMapper employees,ApproverMapper approvers,ClockService clocks,Clock clock,kr.tna.approval.RequestFormMapper forms) {
        this.mapper=mapper;this.commands=commands;this.administration=administration;this.employees=employees;
        this.approvers=approvers;this.clocks=clocks;this.clock=clock;
        this.routes=new ApprovalRouteResolver(mapper,approvers,employees);
        this.attendanceTypes=new AttendanceTypeResolver(mapper);
        this.formRoutes=new FormRouteResolver(mapper,forms,employees,approvers,routes);
    }
    public List<Bucket> buckets(TnaPrincipal actor,UUID employeeId) {
        return targetRead(actor,employeeId,()->{employee(actor.tenantId(),employeeId);return mapper.listBuckets(actor.tenantId(),employeeId,today());});
    }
    public List<LedgerEntry> ledger(TnaPrincipal actor,UUID bucketId) {
        return commands.read(actor,()->{var b=bucket(actor.tenantId(),bucketId);
            return targetRead(actor,b.employeeId(),()->mapper.ledger(actor.tenantId(),bucketId));});
    }
    public UUID openBucket(TnaPrincipal actor,BucketForm form) {
        if(form==null||form.employeeId()==null||form.baseDate()==null||form.usableFrom()==null||form.quantityDays()==null
                ||form.quantityDays()<0||form.quantityDays()>100_000||form.baseDate().isAfter(today())
                ||form.usableTo()!=null&&form.usableTo().isBefore(form.usableFrom()))throw OrgException.invalid("기준일·사용 기간과 정수 기초 일수를 확인해 주세요.");
        String source=requiredText(form.sourceReference(),500,"기초 근거"),reason=requiredText(form.reason(),500,"등록 사유");
        var clean=new BucketForm(form.employeeId(),form.baseDate(),form.usableFrom(),form.usableTo(),form.quantityDays(),source,reason,form.commandId());
        String action="LEAVE_BUCKET_OPEN";
        return administration.execute(actor,form.commandId(),action,"LEAVE_BUCKET",fingerprint(action,clean),reason,()->{
            employee(actor.tenantId(),form.employeeId());if(actor.employeeId().equals(form.employeeId()))throw OrgException.forbidden();
            UUID id=UUID.randomUUID();changed(mapper.insertBucket(actor.tenantId(),id,clean,actor.accountId()));
            ledger(actor,id,form.employeeId(),"OPENING",form.quantityDays(),form.baseDate(),null,null,null,source,reason);
            return new Change(id,null,bucket(actor.tenantId(),id));
        });
    }
    public UUID adjust(TnaPrincipal actor,UUID bucketId,AdjustmentForm form) {
        if(form==null||form.quantityDays()==null||form.quantityDays()==0||Math.abs((long)form.quantityDays())>100_000
                ||form.effectiveOn()==null||form.effectiveOn().isAfter(today()))throw OrgException.invalid("조정 기준일과 0이 아닌 정수 증감량을 입력해 주세요.");
        String source=requiredText(form.sourceReference(),500,"조정 근거"),reason=requiredText(form.reason(),500,"조정 사유"),action="LEAVE_BUCKET_ADJUST";
        return administration.execute(actor,form.commandId(),action,"LEAVE_BUCKET",fingerprint(action,bucketId,form.quantityDays(),form.effectiveOn(),form.expectedVersion(),source,reason),reason,()->{
            var before=bucket(actor.tenantId(),bucketId);checkVersion(form.expectedVersion(),before.version());
            if(actor.employeeId().equals(before.employeeId()))throw OrgException.forbidden();
            if(form.effectiveOn().isBefore(before.baseDate())||(long)before.balanceDays()+form.quantityDays()<0)
                throw OrgException.invalid("기초 기준일 이전이거나 승인 사용량을 침해하는 조정은 할 수 없습니다.");
            ledger(actor,bucketId,before.employeeId(),"ADJUSTMENT",form.quantityDays(),form.effectiveOn(),null,null,null,source,reason);
            changed(mapper.advanceBucket(actor.tenantId(),bucketId,before.version()));
            return new Change(bucketId,before,bucket(actor.tenantId(),bucketId));
        });
    }
    public List<RequestView> mine(TnaPrincipal actor) {return commands.read(actor,()->mapper.requests(actor.tenantId(),actor.accountId(),actor.employeeId(),"MINE"));}
    public List<RequestView> inbox(TnaPrincipal actor) {return approvalPage(actor,false,0).requests();}
    public ApprovalPage approvalPage(TnaPrincipal actor,boolean completed,int page) {
        if(page<0||page>100_000)throw OrgException.invalid("올바른 조회 페이지를 선택해 주세요.");
        return commands.read(actor,()->{
            mapper.readLock(actor.tenantId());
            if(!actor.roles().contains("APPROVER")||!approvers.eligible(actor.tenantId(),actor.employeeId(),today()))throw OrgException.forbidden();
            var found=mapper.approvalPage(actor.tenantId(),actor.accountId(),actor.employeeId(),completed,page*50);
            return new ApprovalPage(List.copyOf(found.subList(0,Math.min(50,found.size()))),page,found.size()>50,completed);
        });
    }
    public ApprovalPreview approvalPreview(TnaPrincipal actor) {
        return commands.read(actor,()->{mapper.readLock(actor.tenantId());return routes.resolve(actor,actor.employeeId(),today()).preview();});
    }
    public ApprovalPreview formPreview(TnaPrincipal actor,UUID target,String kind) {
        UUID employeeId=target==null?actor.employeeId():target;
        return targetRead(actor,employeeId,()->{
            mapper.readLock(actor.tenantId()); employee(actor.tenantId(),employeeId);
            return formRoutes.form(actor.tenantId(),actor.employeeId(),employeeId,kind,today()).preview();
        });
    }
    public ApprovalPreview adminRoutePreview(TnaPrincipal actor,UUID target,UUID routeVersionId) {
        return administration.read(actor,()->{
            mapper.readLock(actor.tenantId());
            employee(actor.tenantId(),target);
            return formRoutes.resolve(actor.tenantId(),target,target,today(),null,null,null,routeVersionId).preview();
        });
    }
    public List<RequestView> adminRequests(TnaPrincipal actor, RequestScope scope) {
        return administration.read(actor, () -> mapper.adminRequests(actor.tenantId(), scope == null ? "" : scope.name()));
    }
    public RequestDetail get(TnaPrincipal actor,UUID requestId) {
        return commands.read(actor,()->{mapper.readLock(actor.tenantId());var r=request(actor.tenantId(),requestId);
            if(r.authorAccountId().equals(actor.accountId())||r.targetEmployeeId().equals(actor.employeeId()))return detail(actor.tenantId(),r);
            if(actor.roles().contains("ATTENDANCE_ADMIN"))return administration.read(actor,()->detail(actor.tenantId(),r));
            if((r.currentApproverId().equals(actor.employeeId())||mapper.hasDecided(actor.tenantId(),r.id(),actor.employeeId()))&&actor.roles().contains("APPROVER")
                    &&approvers.eligible(actor.tenantId(),actor.employeeId(),today()))return detail(actor.tenantId(),r);
            throw OrgException.notFound();});
    }
    public UUID submitLeave(TnaPrincipal actor,LeaveForm form) {
        return submitLeave(actor,form,null);
    }
    public UUID submitLeave(TnaPrincipal actor,LeaveForm form,String routeToken) {
        String reason=leaveReason(form),action="REQUEST_LEAVE_SUBMIT";
        return commands.execute(actor,form.commandId(),action,"REQUEST",reviewedFingerprint(routeToken,action,form.bucketId(),form.from(),form.to(),form.predecessorRequestId(),reason),reason,"EMPLOYEE",()->{
            var days=validateLeaveInput(actor,form);
            var r=createRequest(actor,actor.employeeId(),"LEAVE_DAY",form.from(),form.to(),reason,form.predecessorRequestId(),null,form.bucketId(),false,form.commandId(),routeToken);
            for(var d:days)changed(mapper.insertDay(actor.tenantId(),UUID.randomUUID(),r.id(),d));
            history(actor,r,"SUBMITTED",null,"PENDING",reason,null,r.currentApproverId());
            return new Change(r.id(),null,request(actor.tenantId(),r.id()));
        });
    }
    public AnnualRequestData.Preview previewLeave(TnaPrincipal actor,LeaveForm form) {
        leaveReason(form);
        return commands.read(actor,()->{mapper.readLock(actor.tenantId());
            var days=validateLeaveInput(actor,form);var policy=attendanceTypes.forSubmission(actor.tenantId(),"LEAVE_DAY",form.from(),form.to());
            var approval=formRoutes.form(actor.tenantId(),actor.employeeId(),actor.employeeId(),"LEAVE_DAY",today()).preview();var bucket=bucket(actor.tenantId(),form.bucketId());
            int quantity=days.stream().mapToInt(RequestDay::quantityDays).sum();
            return new AnnualRequestData.Preview(quantity,available(actor),bucket.availableDays(),policy,approval,approval.eligible(),
                    !approval.eligible()?approval.message():quantity>bucket.availableDays()?"현재 사용 가능량을 초과합니다. 승인 시 잔여를 다시 확인합니다.":"일정·잔여·결재자는 제출과 승인 때 다시 검증합니다.");
        });
    }
    public AnnualRequestData.Preview previewCorrection(TnaPrincipal actor,CorrectionForm form) {
        if(form==null||form.workDate()==null||form.workDate().isAfter(today())||form.baseInputRevision()==null
                ||form.sourceFingerprint()==null||!form.sourceFingerprint().matches("[0-9a-f]{64}"))throw OrgException.invalid("정정 근무일과 현재 사실 개정을 확인해 주세요.");
        String inAction=action(form.inAction()),outAction=action(form.outAction());requiredText(form.reason(),500,"정정 사유");
        Instant in=proposed(inAction,form.requestedInAt()),out=proposed(outAction,form.requestedOutAt());var breaks=parseBreaks(form.actualBreaks());
        if(!form.breaksConfirmed()&&!breaks.isEmpty())throw OrgException.invalid("실제 휴게 근거의 확인 여부를 표시해 주세요.");
        if(inAction.equals("KEEP")&&outAction.equals("KEEP")&&!form.breaksConfirmed())throw OrgException.invalid("변경할 시각 또는 실제 휴게를 입력해 주세요.");
        return commands.read(actor,()->{mapper.readLock(actor.tenantId());
            if(form.exceptional()||form.targetEmployeeId()!=null&&!form.targetEmployeeId().equals(actor.employeeId()))throw OrgException.forbidden();
            var employee=currentEmployee(actor.tenantId(),actor.employeeId(),false);employmentDate(employee,form.workDate());
            predecessor(actor.tenantId(),form.predecessorRequestId(),actor.employeeId(),"ATTENDANCE_CORRECTION");
            var before=clocks.snapshot(actor.tenantId(),actor.employeeId(),form.workDate());checkVersion(form.baseInputRevision(),before.inputRevision());
            if(!before.sourceFingerprint().equals(form.sourceFingerprint()))throw OrgException.conflict("원천·일정이 변경되었습니다. 최신 내용을 확인해 주세요.");
            var previous=mapper.currentCorrection(actor.tenantId(),actor.employeeId(),form.workDate());
            if(!Objects.equals(previous==null?null:previous.requestId(),form.replacesRequestId()))throw OrgException.conflict("현재 유효 정정을 참조해 다시 신청해 주세요.");
            if(mapper.pendingCorrection(actor.tenantId(),actor.employeeId(),form.workDate()))throw OrgException.conflict("이미 대기 중인 정정이 있습니다.");
            validateCorrection(before,inAction,outAction,in,out,form.breaksConfirmed(),breaks);
            var policy=attendanceTypes.forSubmission(actor.tenantId(),"ATTENDANCE_CORRECTION",form.workDate(),form.workDate());
            var approval=formRoutes.form(actor.tenantId(),actor.employeeId(),actor.employeeId(),"ATTENDANCE_CORRECTION",today()).preview();
            return new AnnualRequestData.Preview(null,available(actor),null,policy,approval,approval.eligible(),approval.eligible()?"정정 근거·정책·결재자는 제출과 승인 때 다시 검증합니다.":approval.message());
        });
    }
    private long available(TnaPrincipal actor){return mapper.listBuckets(actor.tenantId(),actor.employeeId(),today()).stream().mapToLong(Bucket::availableDays).sum();}
    private String leaveReason(LeaveForm form){
        if(form==null||form.bucketId()==null||form.from()==null||form.to()==null||form.from().isAfter(form.to())
                ||form.from().isBefore(today())||ChronoUnit.DAYS.between(form.from(),form.to())>=366)
            throw OrgException.invalid("오늘 이후 같은 묶음 안의 366일 이내 기간을 입력해 주세요.");
        return requiredText(form.reason(),500,"신청 사유");
    }
    private List<RequestDay> validateLeaveInput(TnaPrincipal actor,LeaveForm form){
        currentEmployee(actor.tenantId(),actor.employeeId(),false);var bucket=bucket(actor.tenantId(),form.bucketId());
        if(!bucket.employeeId().equals(actor.employeeId()))throw OrgException.notFound();
        bucketPeriod(bucket,form.from(),form.to());predecessor(actor.tenantId(),form.predecessorRequestId(),actor.employeeId(),"LEAVE_DAY");
        var days=days(actor.tenantId(),actor.employeeId(),form.from(),form.to());
        if(days.stream().mapToInt(RequestDay::quantityDays).sum()==0)throw OrgException.invalid("소정근로일이 없는 기간입니다.");
        for(var day:days)if(day.quantityDays()==1&&mapper.overlappingLeave(actor.tenantId(),actor.employeeId(),day.leaveDate(),null))throw OrgException.conflict("같은 날짜의 대기 또는 유효 연차가 있습니다.");
        return days;
    }
    /** 그 날짜의 현재 승인 정정 신청 ID. 정정 화면이 ID를 입력받지 않고 hidden으로 보내도록 서버가 채운다. */
    public UUID currentCorrectionRequestId(TnaPrincipal actor,UUID employeeId,LocalDate date) {
        return targetRead(actor,employeeId,()->{var effect=mapper.currentCorrection(actor.tenantId(),employeeId,date);return effect==null?null:effect.requestId();});
    }
    record CorrectionPageEvidence(ClockData.DaySnapshot day, UUID currentCorrectionId, UUID pendingCorrectionId) { }

    CorrectionPageEvidence correctionPageEvidence(TnaPrincipal actor, LocalDate date) {
        return commands.read(actor, () -> {
            UUID tenantId = actor.tenantId(), employeeId = actor.employeeId();
            var day = clocks.snapshot(tenantId, employeeId, date);
            var current = mapper.currentCorrection(tenantId, employeeId, date);
            return new CorrectionPageEvidence(day, current == null ? null : current.requestId(),
                    mapper.pendingCorrectionId(tenantId, employeeId, date));
        });
    }
    public UUID submitCorrection(TnaPrincipal actor,CorrectionForm form) {
        return submitCorrection(actor,form,null);
    }
    public UUID submitCorrection(TnaPrincipal actor,CorrectionForm form,String routeToken) {
        if(form==null||form.workDate()==null||form.workDate().isAfter(today())||form.baseInputRevision()==null
                ||form.sourceFingerprint()==null||!form.sourceFingerprint().matches("[0-9a-f]{64}"))throw OrgException.invalid("정정 근무일과 현재 사실 개정을 확인해 주세요.");
        String inAction=action(form.inAction()),outAction=action(form.outAction()),reason=requiredText(form.reason(),500,"정정 사유");
        Instant proposedIn=proposed(inAction,form.requestedInAt()),proposedOut=proposed(outAction,form.requestedOutAt());
        List<ClockData.Interval> breaks=parseBreaks(form.actualBreaks());
        if(!form.breaksConfirmed()&&!breaks.isEmpty())throw OrgException.invalid("실제 휴게 근거의 확인 여부를 표시해 주세요.");
        if(inAction.equals("KEEP")&&outAction.equals("KEEP")&&!form.breaksConfirmed())throw OrgException.invalid("변경할 시각 또는 실제 휴게를 입력해 주세요.");
        String action="REQUEST_CORRECTION_SUBMIT";
        return commands.execute(actor,form.commandId(),action,"REQUEST",reviewedFingerprint(routeToken,action,form,inAction,outAction,proposedIn,proposedOut,breaks),reason,form.exceptional()?"ATTENDANCE_ADMIN":"EMPLOYEE",()->{
            UUID target=form.targetEmployeeId()==null?actor.employeeId():form.targetEmployeeId();
            if(!form.exceptional()&&!target.equals(actor.employeeId()))throw OrgException.forbidden();
            var employee=currentEmployee(actor.tenantId(),target,form.exceptional());employmentDate(employee,form.workDate());
            predecessor(actor.tenantId(),form.predecessorRequestId(),target,"ATTENDANCE_CORRECTION");
            var before=clocks.snapshot(actor.tenantId(),target,form.workDate());
            checkVersion(form.baseInputRevision(),before.inputRevision());
            if(!before.sourceFingerprint().equals(form.sourceFingerprint()))throw OrgException.conflict("원천·일정이 변경되었습니다. 최신 내용을 확인해 주세요.");
            var previous=mapper.currentCorrection(actor.tenantId(),target,form.workDate());
            if(!Objects.equals(previous==null?null:previous.requestId(),form.replacesRequestId()))throw OrgException.conflict("현재 유효 정정을 참조해 다시 신청해 주세요.");
            if(mapper.pendingCorrection(actor.tenantId(),target,form.workDate()))throw OrgException.conflict("이미 대기 중인 정정이 있습니다.");
            validateCorrection(before,inAction,outAction,proposedIn,proposedOut,form.breaksConfirmed(),breaks);
            var r=createRequest(actor,target,"ATTENDANCE_CORRECTION",form.workDate(),form.workDate(),reason,form.predecessorRequestId(),null,null,form.exceptional(),form.commandId(),routeToken);
            var d=new CorrectionDetail(form.workDate(),form.baseInputRevision(),form.sourceFingerprint(),inAction,outAction,proposedIn,proposedOut,
                    form.presenceAsWork(),form.breaksConfirmed(),json.writeValueAsString(breaks),form.replacesRequestId(),json.writeValueAsString(before));
            changed(mapper.insertCorrection(actor.tenantId(),UUID.randomUUID(),r.id(),d));
            changed(mapper.reviseDay(actor.tenantId(),target,form.workDate(),0));
            history(actor,r,"SUBMITTED",null,"PENDING",reason,null,r.currentApproverId());
            return new Change(r.id(),null,request(actor.tenantId(),r.id()));
        });
    }
    public UUID requestCancellation(TnaPrincipal actor,UUID originalId,CancelForm form) {
        return requestCancellation(actor,originalId,form,null);
    }
    public UUID requestCancellation(TnaPrincipal actor,UUID originalId,CancelForm form,String routeToken) {
        if(form==null)throw OrgException.invalid("취소 내용을 입력해 주세요.");
        String reason=requiredText(form.reason(),500,"취소 사유"),action="REQUEST_CANCEL_SUBMIT";
        return commands.execute(actor,form.commandId(),action,"REQUEST",reviewedFingerprint(routeToken,action,originalId,form.exceptional(),form.expectedVersion(),reason),reason,form.exceptional()?"ATTENDANCE_ADMIN":"EMPLOYEE",()->{
            var original=request(actor.tenantId(),originalId);
            if(!form.exceptional()&&!original.targetEmployeeId().equals(actor.employeeId()))throw OrgException.notFound();
            checkVersion(form.expectedVersion(),original.version());
            if(!original.kind().equals("LEAVE_DAY")||!original.status().equals("APPROVED")||!original.effectStatus().equals("EFFECTIVE"))throw OrgException.invalid("현재 유효한 승인 연차만 취소 신청할 수 있습니다.");
            if(!form.exceptional()&&!original.targetEmployeeId().equals(actor.employeeId()))throw OrgException.notFound();
            currentEmployee(actor.tenantId(),original.targetEmployeeId(),form.exceptional());
            if(!form.exceptional()&&started(actor.tenantId(),original))throw OrgException.invalid("이미 시작한 연차는 담당자 예외 경로로 요청해 주세요.");
            if(mapper.pendingCancellation(actor.tenantId(),originalId))throw OrgException.conflict("이미 대기 중인 취소가 있습니다.");
            var r=createRequest(actor,original.targetEmployeeId(),"CANCEL_LEAVE",original.fromDate(),original.toDate(),reason,null,original.id(),original.leaveBucketId(),form.exceptional(),form.commandId(),routeToken);
            history(actor,r,"SUBMITTED",null,"PENDING",reason,null,r.currentApproverId());
            return new Change(r.id(),null,request(actor.tenantId(),r.id()));
        });
    }
    public UUID decide(TnaPrincipal actor,UUID requestId,DecisionForm form) {
        if(form==null||!Set.of("APPROVE","REJECT").contains(form.decision()))throw OrgException.invalid("승인 또는 반려를 선택해 주세요.");
        String reason=requiredText(form.reason(),500,"결정 사유"),action="REQUEST_"+form.decision();
        return commands.execute(actor,form.commandId(),action,"REQUEST",reviewedFingerprint(form.expectedStep(),action,requestId,form.expectedVersion(),reason),reason,"APPROVER",()->{
            var before=request(actor.tenantId(),requestId);
            if(!before.currentApproverId().equals(actor.employeeId()))throw OrgException.forbidden();
            independent(actor.tenantId(),before.targetEmployeeId(),before.authorAccountId(),actor.employeeId());
            checkVersion(form.expectedVersion(),before.version());pending(before);checkStep(before,form.expectedStep());currentEmployee(actor.tenantId(),before.targetEmployeeId(),before.exceptional());
            if(!approvers.eligible(actor.tenantId(),actor.employeeId(),today()))throw OrgException.forbidden();
            String status=form.decision().equals("APPROVE")?"APPROVED":"REJECTED";
            if(!before.legacyForm())changed(mapper.decideStep(actor.tenantId(),requestId,before.currentStep(),actor.employeeId(),status,reason));
            if("APPROVED".equals(status)&&before.currentStep()<before.totalSteps()) {
                changed(mapper.activateStep(actor.tenantId(),requestId,before.currentStep()+1));
                changed(mapper.advanceStep(actor.tenantId(),requestId,before.version()));
                var after=request(actor.tenantId(),requestId);
                history(actor,before,"STEP_APPROVED","PENDING","PENDING",reason,actor.employeeId(),after.currentApproverId());
                return new Change(requestId,before,after);
            }
            if(status.equals("APPROVED")) {
                attendanceTypes.stored(actor.tenantId(),before);
                switch(before.kind()) {
                    case "LEAVE_DAY" -> approveLeave(actor,before,reason);
                    case "CANCEL_LEAVE" -> approveCancellation(actor,before,reason);
                    case "ATTENDANCE_CORRECTION" -> approveCorrection(actor,before);
                    default -> throw OrgException.invalid("지원하지 않는 신청 종류입니다.");
                }
            } else if(before.kind().equals("ATTENDANCE_CORRECTION"))changed(mapper.reviseDay(actor.tenantId(),before.targetEmployeeId(),before.fromDate(),0));
            if("REJECTED".equals(status))mapper.closeSteps(actor.tenantId(),requestId);
            changed(mapper.transition(actor.tenantId(),requestId,before.version(),status));
            history(actor,before,status,"PENDING",status,reason,null,null);
            return new Change(requestId,before,request(actor.tenantId(),requestId));
        });
    }
    public UUID withdraw(TnaPrincipal actor,UUID requestId,DecisionForm form) {
        if(form==null)throw OrgException.invalid("철회 내용을 입력해 주세요.");
        String reason=requiredText(form.reason(),500,"철회 사유"),action="REQUEST_WITHDRAW";
        return commands.execute(actor,form.commandId(),action,"REQUEST",reviewedFingerprint(form.expectedStep(),action,requestId,form.expectedVersion(),reason),reason,"EMPLOYEE",()->{
            var before=request(actor.tenantId(),requestId);if(!before.authorAccountId().equals(actor.accountId()))throw OrgException.forbidden();
            checkVersion(form.expectedVersion(),before.version());pending(before);checkStep(before,form.expectedStep());
            mapper.closeSteps(actor.tenantId(),requestId);
            changed(mapper.transition(actor.tenantId(),requestId,before.version(),"WITHDRAWN"));
            if(before.kind().equals("ATTENDANCE_CORRECTION"))changed(mapper.reviseDay(actor.tenantId(),before.targetEmployeeId(),before.fromDate(),0));
            history(actor,before,"WITHDRAWN","PENDING","WITHDRAWN",reason,null,null);
            return new Change(requestId,before,request(actor.tenantId(),requestId));
        });
    }
    public UUID reassign(TnaPrincipal actor,UUID requestId,ReassignForm form) {
        if(form==null||form.approverEmployeeId()==null)throw OrgException.invalid("새 승인자를 선택해 주세요.");
        String reason=requiredText(form.reason(),500,"재할당 사유"),action="REQUEST_REASSIGN";
        return administration.execute(actor,form.commandId(),action,"REQUEST",reviewedFingerprint(form.expectedStep(),action,requestId,form.approverEmployeeId(),form.expectedVersion(),reason),reason,()->{
            var before=request(actor.tenantId(),requestId);checkVersion(form.expectedVersion(),before.version());pending(before);checkStep(before,form.expectedStep());
            independent(actor.tenantId(),before.targetEmployeeId(),before.authorAccountId(),form.approverEmployeeId());
            employee(actor.tenantId(),form.approverEmployeeId());if(!approvers.eligible(actor.tenantId(),form.approverEmployeeId(),today()))throw OrgException.invalid("현재 재직 중인 적격 승인자가 필요합니다.");
            if(!before.legacyForm()) {
                if(mapper.assignedElsewhere(actor.tenantId(),requestId,before.currentStep(),form.approverEmployeeId()))
                    throw OrgException.invalid("다른 결재 단계의 담당자와 중복됩니다.");
                changed(mapper.reassignStep(actor.tenantId(),requestId,before.currentStep(),form.approverEmployeeId()));
            }
            changed(mapper.reassign(actor.tenantId(),requestId,before.version(),form.approverEmployeeId()));
            history(actor,before,"REASSIGNED","PENDING","PENDING",reason,before.currentApproverId(),form.approverEmployeeId());
            return new Change(requestId,before,request(actor.tenantId(),requestId));
        });
    }
    private static String reviewedFingerprint(Object confirmation,Object... fields) {
        String existingHash=fingerprint(fields);
        return confirmation==null?existingHash:fingerprint(existingHash,confirmation);
    }

    private static void checkStep(RequestView request,Integer expected) {
        if((expected!=null||request.totalSteps()>1)&&!Integer.valueOf(request.currentStep()).equals(expected))
            throw OrgException.conflict("현재 결재 단계가 변경되었습니다. 새로 조회해 주세요.");
    }

    private void approveLeave(TnaPrincipal actor,RequestView request,String reason) {
        var b=bucket(actor.tenantId(),request.leaveBucketId());bucketPeriod(b,request.fromDate(),request.toDate());
        var submitted=mapper.requestDays(actor.tenantId(),request.id());var current=days(actor.tenantId(),request.targetEmployeeId(),request.fromDate(),request.toDate());
        if(!submitted.equals(current))throw OrgException.invalid("일정·휴일 또는 차감량이 변경되었습니다. 철회 후 다시 신청해 주세요.");
        int quantity=current.stream().mapToInt(RequestDay::quantityDays).sum();
        if(quantity<=0||b.balanceDays()<quantity)throw OrgException.invalid("승인할 수 있는 연차 잔여가 부족합니다.");
        for(var d:current)if(d.quantityDays()==1&&mapper.overlappingLeave(actor.tenantId(),request.targetEmployeeId(),d.leaveDate(),request.id()))throw OrgException.conflict("다른 유효 연차와 날짜가 겹칩니다.");
        UUID effect=UUID.randomUUID();changed(mapper.insertEffect(actor.tenantId(),effect,request.id(),request.targetEmployeeId(),null,"LEAVE",json.writeValueAsString(current),null,null,actor.accountId()));
        for(var d:current)if(d.quantityDays()==1) {
            ledger(actor,b.id(),b.employeeId(),"USE",-1,today(),effect,d.leaveDate(),null,null,reason);
            changed(mapper.reviseDay(actor.tenantId(),request.targetEmployeeId(),d.leaveDate(),1));
        }
        changed(mapper.advanceBucket(actor.tenantId(),b.id(),b.version()));
    }
    private void approveCancellation(TnaPrincipal actor,RequestView request,String reason) {
        var original=request(actor.tenantId(),request.originalRequestId());var effect=mapper.requestEffect(actor.tenantId(),original.id());
        if(!original.kind().equals("LEAVE_DAY")||!original.status().equals("APPROVED")||effect==null||!effect.effectStatus().equals("EFFECTIVE"))throw OrgException.conflict("원연차의 현재 효력이 변경되었습니다.");
        if(!request.exceptional()&&started(actor.tenantId(),original))throw OrgException.invalid("이미 시작한 연차는 담당자 예외 경로로 취소해야 합니다.");
        var used=mapper.uses(actor.tenantId(),effect.id());if(used.isEmpty())throw OrgException.conflict("복원할 원사용 원장이 없습니다.");
        UUID restoration=UUID.randomUUID();changed(mapper.endEffect(actor.tenantId(),effect.id(),"CANCELED"));
        changed(mapper.insertEffect(actor.tenantId(),restoration,request.id(),request.targetEmployeeId(),null,"CANCEL",json.writeValueAsString(used),effect.id(),null,actor.accountId()));
        var b=bucket(actor.tenantId(),original.leaveBucketId());
        for(var row:used) {
            ledger(actor,row.bucketId(),row.employeeId(),"RESTORE",-row.signedQuantityDays(),today(),restoration,row.leaveDate(),row.id(),null,reason);
            changed(mapper.reviseDay(actor.tenantId(),row.employeeId(),row.leaveDate(),1));
        }
        changed(mapper.advanceBucket(actor.tenantId(),b.id(),b.version()));
    }
    private void approveCorrection(TnaPrincipal actor,RequestView request) {
        var d=mapper.correction(actor.tenantId(),request.id());var before=clocks.snapshot(actor.tenantId(),request.targetEmployeeId(),d.workDate());
        checkVersion(d.baseInputRevision(),before.inputRevision());if(!d.sourceFingerprint().equals(before.sourceFingerprint()))throw OrgException.conflict("정정 대상 사실·일정이 변경되었습니다. 재검토해 주세요.");
        var previous=mapper.currentCorrection(actor.tenantId(),request.targetEmployeeId(),d.workDate());
        if(!Objects.equals(previous==null?null:previous.requestId(),d.replacesRequestId()))throw OrgException.conflict("현재 유효 정정이 변경되었습니다.");
        var breaks=readBreaks(d.actualBreaks());validateCorrection(before,d.inAction(),d.outAction(),d.requestedInAt(),d.requestedOutAt(),d.breaksConfirmed(),breaks);
        if(previous!=null)changed(mapper.endEffect(actor.tenantId(),previous.id(),"SUPERSEDED"));
        changed(mapper.reviseDay(actor.tenantId(),request.targetEmployeeId(),d.workDate(),1));
        UUID effect=UUID.randomUUID();
        var evidence=new java.util.LinkedHashMap<String,Object>();
        evidence.put("inputRevision",before.inputRevision()+1);evidence.put("reviewRevision",before.reviewRevision()+1);
        evidence.put("requestId",request.id());evidence.put("confirmed",d.presenceAsWork()&&d.breaksConfirmed());evidence.put("actualBreaks",breaks);
        evidence.put("scheduleVersionId",before.scheduleVersionId());evidence.put("calendarVersionId",before.calendarVersionId());
        changed(mapper.insertEffect(actor.tenantId(),effect,request.id(),request.targetEmployeeId(),d.workDate(),"CORRECTION",json.writeValueAsString(List.of(evidence)),null,previous==null?null:previous.id(),actor.accountId()));
    }
    private RequestView createRequest(TnaPrincipal actor,UUID target,String kind,LocalDate from,LocalDate to,String reason,UUID predecessor,UUID original,UUID bucket,boolean exceptional,UUID commandId,String routeToken) {
        // Cancellation carries the original contract even after its type was disabled for new requests.
        var policy=original==null?attendanceTypes.forSubmission(actor.tenantId(),kind,from,to)
                :attendanceTypes.stored(actor.tenantId(),request(actor.tenantId(),original));
        Instant at=clock.instant().truncatedTo(ChronoUnit.MILLIS);
        var result=formRoutes.form(actor.tenantId(),actor.employeeId(),target,kind,at.atZone(SEOUL).toLocalDate());
        var preview=result.preview();
        if(!preview.eligible())throw OrgException.invalid(preview.message());
        if((routeToken!=null||result.routeVersionId()!=null)&&!Objects.equals(routeToken,preview.routeToken()))
            throw OrgException.conflict("결재선이 변경되었습니다. 새 결재 순서를 확인하고 다시 제출해 주세요.");
        var first=preview.steps().getFirst();
        var legacy=result.inherited()==null?null:result.inherited().legacy();
        UUID snapshotId=legacy==null?UUID.randomUUID():null;
        var request=new RequestView(UUID.randomUUID(),target,employee(actor.tenantId(),target).displayName(),actor.accountId(),kind,"PENDING",first.approverId(),
                first.approverName(),legacy==null?null:legacy.assignmentId(),legacy==null?null:legacy.assignmentVersion(),at,
                reason,predecessor,original,bucket,from,to,exceptional,0,"NONE",0,snapshotId,policy.id(),preview.formCode(),preview.formName(),1,preview.steps().size(),false);
        changed(mapper.insertRequest(actor.tenantId(),request,commandId,result.formVersionId(),preview.steps().size()));
        formRoutes.persist(actor.tenantId(),request.id(),snapshotId,at,result);
        return request;
    }
    private List<RequestDay> days(UUID tenant,UUID employeeId,LocalDate from,LocalDate to) {
        var result=new ArrayList<RequestDay>();var employee=employee(tenant,employeeId);
        for(LocalDate date=from;!date.isAfter(to);date=date.plusDays(1)) {
            employmentDate(employee,date);var day=clocks.snapshot(tenant,employeeId,date);
            if(day.schedule()==null||day.scheduleVersionId()==null||day.calendarVersionId()==null||!Set.of("WORKDAY","NON_WORKDAY","HOLIDAY").contains(day.dayKind()))throw OrgException.invalid("선택 기간의 일정·휴일 확인이 필요합니다.");
            int quantity=day.schedule().workday()&&day.dayKind().equals("WORKDAY")?1:0;
            String zero=quantity==1?null:day.dayKind().equals("HOLIDAY")?"CONFIRMED_HOLIDAY":"CONFIRMED_NON_WORKDAY";
            result.add(new RequestDay(date,quantity,day.scheduleVersionId(),day.calendarVersionId(),zero));
        }
        return List.copyOf(result);
    }
    private void validateCorrection(ClockData.DaySnapshot before,String inAction,String outAction,Instant proposedIn,Instant proposedOut,boolean breaksConfirmed,List<ClockData.Interval> breaks) {
        Instant in=effective(before,"IN",inAction,proposedIn),out=effective(before,"OUT",outAction,proposedOut);
        if(in!=null&&in.isAfter(clock.instant())||out!=null&&out.isAfter(clock.instant()))throw OrgException.invalid("미래 출퇴근 시각으로 정정할 수 없습니다.");
        if(in!=null&&out!=null&&!in.isBefore(out))throw OrgException.invalid("출근보다 늦은 퇴근 시각을 입력해 주세요.");
        if(breaksConfirmed) {
            if(in==null||out==null)throw OrgException.invalid("실제 휴게 확인에는 유효한 출퇴근 구간이 필요합니다.");
            for(var pause:breaks)if(pause.startAt().isBefore(in)||pause.endAt().isAfter(out))throw OrgException.invalid("실제 휴게는 출퇴근 구간 안이어야 합니다.");
        }
    }
    private Instant effective(ClockData.DaySnapshot day,String kind,String action,Instant proposed) {
        if(action.equals("SET"))return proposed;if(action.equals("CLEAR"))return null;
        var events=day.effectiveEvents().stream().filter(e->e.kind().equals(kind)).toList();
        if(events.size()>1)throw OrgException.invalid("중복 기록은 KEEP 대신 SET 또는 CLEAR로 명시해 주세요.");
        return events.isEmpty()?null:events.getFirst().occurredAt();
    }
    private List<ClockData.Interval> parseBreaks(String input) {
        if(input==null||input.isBlank())return List.of();if(input.length()>8_000)throw OrgException.invalid("실제 휴게 입력이 너무 깁니다.");
        var values=new ArrayList<ClockData.Interval>();
        for(String part:input.split(",",-1)) {
            var pair=part.strip().split("/",-1);if(pair.length!=2)throw OrgException.invalid("휴게는 시작/종료를 쉼표로 구분해 주세요.");
            Instant from=instant(pair[0]),to=instant(pair[1]);if(!from.isBefore(to))throw OrgException.invalid("휴게 시작보다 늦은 종료를 입력해 주세요.");
            values.add(new ClockData.Interval(from,to));
        }
        return List.copyOf(values);
    }
    private List<ClockData.Interval> readBreaks(String value) {
        var list=new ArrayList<ClockData.Interval>();
        for(var node:json.readTree(value))list.add(new ClockData.Interval(Instant.parse(node.get("startAt").asString()),Instant.parse(node.get("endAt").asString())));
        return List.copyOf(list);
    }
    private Instant proposed(String action,String value) {
        if(action.equals("SET"))return instant(value);if(value!=null&&!value.isBlank())throw OrgException.invalid("SET에서만 정정 시각을 입력해 주세요.");return null;
    }
    private Instant instant(String value) {
        try {
            if(value==null||value.length()>50)throw new IllegalArgumentException();
            Instant result;
            try{result=OffsetDateTime.parse(value.strip()).toInstant();}catch(java.time.format.DateTimeParseException e){result=LocalDateTime.parse(value.strip()).atZone(SEOUL).toInstant();}
            if(result.getNano()%1_000_000!=0)throw new IllegalArgumentException();return result;
        }catch(RuntimeException error){throw OrgException.invalid("서울 날짜·시각을 밀리초 이내 정밀도로 입력해 주세요.");}
    }
    private String action(String value) {if(value==null||!ACTIONS.contains(value))throw OrgException.invalid("KEEP·SET·CLEAR 중 하나를 선택해 주세요.");return value;}
    private boolean started(UUID tenant,RequestView original) {
        LocalDate currentDate=today();
        var days=mapper.requestDays(tenant,original.id());
        for(var d:days)if(d.quantityDays()==1) {
            if(d.leaveDate().isBefore(currentDate))return true;
            if(d.leaveDate().isAfter(currentDate))return false;
            var snapshot=clocks.snapshot(tenant,original.targetEmployeeId(),d.leaveDate());
            return snapshot.schedule()==null||snapshot.schedule().startTime()==null
                    ||!clock.instant().isBefore(d.leaveDate().atTime(snapshot.schedule().startTime()).atZone(SEOUL).toInstant());
        }
        return true;
    }
    private <T>T targetRead(TnaPrincipal actor,UUID employeeId,Supplier<T> action) {
        if(actor==null)throw OrgException.forbidden();return actor.employeeId().equals(employeeId)?commands.read(actor,action):administration.read(actor,action);
    }
    private RequestDetail detail(UUID tenant, RequestView request) {
        var correction = mapper.correction(tenant, request.id());
        var comparison = correction == null ? null : correctionComparison(correction,
                clocks.snapshot(tenant, request.targetEmployeeId(), correction.workDate()));
        return new RequestDetail(request, mapper.requestDays(tenant, request.id()), correction,
                mapper.history(tenant, request.id()), mapper.routeSnapshot(tenant, request.id()),
                request.attendanceTypeVersionId() == null ? null : mapper.attendancePolicy(tenant, request.attendanceTypeVersionId()),
                comparison,mapper.decisionSteps(tenant,request.id()),
                "PENDING".equals(request.status())&&!approvers.eligible(tenant,request.currentApproverId(),today()),
                "LEAVE_DAY".equals(request.kind()) && "EFFECTIVE".equals(request.effectStatus())
                        ? mapper.scheduleChanges(tenant,request.id(),request.targetEmployeeId(),today()) : List.of());
    }

    private CorrectionComparison correctionComparison(CorrectionDetail correction, ClockData.DaySnapshot current) {
        CorrectionTimes submitted = submittedTimes(correction.beforeSnapshot());
        return new CorrectionComparison(correctionTimes(current.events()), correctionTimes(current.effectiveEvents()),
                new CorrectionTimes(proposedTimes(correction.inAction(), correction.requestedInAt(), submitted.in()),
                        proposedTimes(correction.outAction(), correction.requestedOutAt(), submitted.out())));
    }

    private CorrectionTimes submittedTimes(String snapshot) {
        try {
            if (snapshot != null) {
                var before = json.readTree(snapshot);
                var events = before.get("effectiveEvents");
                if (events == null || events.isNull()) events = before.get("events");
                if (events != null && events.isArray()) {
                    return correctionTimes(Arrays.asList(json.readValue(events.toString(), ClockData.Event[].class)));
                }
            }
        } catch (JacksonException invalidLegacySnapshot) {
            // The submitted base is unavailable. Current records and explicitly changed values still display.
        }
        return new CorrectionTimes(null, null);
    }

    private static CorrectionTimes correctionTimes(List<ClockData.Event> events) {
        return new CorrectionTimes(eventTimes(events, "IN"), eventTimes(events, "OUT"));
    }

    private static List<Instant> eventTimes(List<ClockData.Event> events, String kind) {
        return events.stream().filter(event -> kind.equals(event.kind())).map(ClockData.Event::occurredAt).toList();
    }

    private static List<Instant> proposedTimes(String action, Instant requested, List<Instant> submitted) {
        return switch (action) {
            case "SET" -> requested == null ? null : List.of(requested);
            case "CLEAR" -> List.of();
            default -> submitted;
        };
    }
    private RequestView request(UUID tenant,UUID id) {var value=mapper.findRequest(tenant,id);if(value==null)throw OrgException.notFound();return value;}
    private Bucket bucket(UUID tenant,UUID id) {var value=mapper.findBucket(tenant,id,today());if(value==null)throw OrgException.notFound();return value;}
    private Employee employee(UUID tenant,UUID id) {var e=employees.get(tenant,id);if(e==null)throw OrgException.notFound();return e;}
    private Employee currentEmployee(UUID tenant,UUID id,boolean exceptional) {
        var e=employee(tenant,id);if(!exceptional&&(!e.active()||e.joinedOn().isAfter(today())||e.leftOn()!=null&&e.leftOn().isBefore(today())))throw OrgException.invalid("현재 재직 중인 활성 사원만 일반 신청·승인을 진행할 수 있습니다.");return e;
    }
    private void employmentDate(Employee e,LocalDate date) {if(date.isBefore(e.joinedOn())||e.leftOn()!=null&&date.isAfter(e.leftOn()))throw OrgException.invalid("대상 날짜의 재직 기간을 확인해 주세요.");}
    private void bucketPeriod(Bucket b,LocalDate from,LocalDate to) {if(from.isBefore(b.baseDate())||from.isBefore(b.usableFrom())||b.usableTo()!=null&&to.isAfter(b.usableTo()))throw OrgException.invalid("기초 기준일 이후 같은 묶음의 사용 가능 기간 안에서 나누어 신청해 주세요.");}
    private void predecessor(UUID tenant,UUID id,UUID target,String kind) {if(id==null)return;var r=request(tenant,id);if(!r.targetEmployeeId().equals(target)||!r.kind().equals(kind)||r.status().equals("PENDING"))throw OrgException.invalid("같은 대상·종류의 처리된 이전 신청을 참조해 주세요.");}
    private void independent(UUID tenant,UUID target,UUID author,UUID approver) {
        if(target.equals(approver))throw OrgException.invalid("대상 사원은 자기 신청을 승인할 수 없습니다.");
        if(employees.get(tenant,approver)==null)throw OrgException.notFound();
        // Compare account ownership as well as the request target; roles do not permit self approval.
        if(mapperAuthorEmployee(tenant,author).equals(approver))throw OrgException.invalid("신청 작성자는 직접 승인할 수 없습니다.");
    }
    private UUID mapperAuthorEmployee(UUID tenant,UUID accountId) {String id=mapper.authorEmployee(tenant,accountId);if(id==null)throw OrgException.notFound();return UUID.fromString(id);}
    private void history(TnaPrincipal actor,RequestView r,String kind,String from,String to,String reason,UUID previous,UUID next) {
        changed(mapper.insertHistory(actor.tenantId(),UUID.randomUUID(),r.id(),kind,actor.accountId(),from,to,reason,previous,next,r.legacyForm()?null:r.currentStep()));
    }
    private void ledger(TnaPrincipal actor,UUID bucket,UUID employee,String kind,int quantity,LocalDate date,UUID effect,LocalDate leaveDate,UUID reverses,String source,String reason) {
        changed(mapper.insertLedger(actor.tenantId(),UUID.randomUUID(),bucket,employee,kind,quantity,date,effect,leaveDate,reverses,source,reason,actor.accountId()));
    }
    private void pending(RequestView request) {if(!request.status().equals("PENDING"))throw OrgException.conflict("이미 처리된 신청입니다. 최신 내용을 확인해 주세요.");}
    private LocalDate today() {return LocalDate.now(clock.withZone(SEOUL));}
    private static void changed(int count) {if(count!=1)throw OrgException.conflict("다른 변경과 충돌했습니다. 최신 내용을 확인해 주세요.");}
}
