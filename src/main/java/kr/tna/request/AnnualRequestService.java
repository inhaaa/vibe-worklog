package kr.tna.request;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import kr.tna.clock.ClockService;
import kr.tna.common.auth.TnaPrincipal;
import kr.tna.common.tenant.BusinessCommands;
import kr.tna.org.OrgException;
import kr.tna.request.AnnualRequestData.*;
import kr.tna.request.RequestData.*;
import org.springframework.stereotype.Service;

@Service
public final class AnnualRequestService {
    public record Draft(String mode,String kind,LocalDate date,UUID predecessorRequestId,LeaveForm leaveForm,
            CorrectionForm correctionForm,boolean preview,boolean conflict) {
        Draft withoutPreview(boolean conflicted){return new Draft(mode,kind,date,predecessorRequestId,leaveForm,correctionForm,false,conflicted);}
    }
    private final AnnualRequestMapper mapper;
    private final LeaveWorkflowMapper workflowMapper;
    private final LeaveWorkflowService flow;
    private final ClockService clocks;
    private final BusinessCommands commands;
    private final Clock clock;
    public AnnualRequestService(AnnualRequestMapper mapper,LeaveWorkflowMapper workflowMapper,LeaveWorkflowService flow,
            ClockService clocks,BusinessCommands commands,Clock clock){
        this.mapper=mapper;this.workflowMapper=workflowMapper;this.flow=flow;this.clocks=clocks;this.commands=commands;this.clock=clock;
    }
    public View view(TnaPrincipal actor,Filter filter,Draft draft,UUID createdId){
        return read(actor,()->{
            var at=clock.instant();LocalDate today=at.atZone(ZoneId.of("Asia/Seoul")).toLocalDate();
            int year=filter.year()==null?today.getYear():filter.year();
            if(year<1900||year>9998)throw OrgException.invalid("조회 연도는1900~9998 범위로 입력해 주세요.");
            if(filter.page()<0||filter.page()>100000||filter.authoredPage()<0||filter.authoredPage()>100000)throw OrgException.invalid("조회 페이지를 확인해 주세요.");
            String status=filter.status()==null?"":filter.status();
            if(!Set.of("","PENDING","APPROVED","REJECTED","WITHDRAWN").contains(status))throw OrgException.invalid("조회 상태를 확인해 주세요.");
            if(filter.typeId()!=null&&mapper.type(actor.tenantId(),filter.typeId())==null)throw OrgException.notFound();
            LocalDate from=LocalDate.of(year,1,1),to=from.plusYears(1);
            var found=mapper.requests(actor.tenantId(),actor.employeeId(),from,to,filter.typeId(),status,filter.page()*50);
            var authored=mapper.authored(actor.tenantId(),actor.accountId(),actor.employeeId(),from,to,filter.authoredPage()*50);
            var summaries=mapper.summaries(actor.tenantId(),actor.employeeId(),from,to,today);
            var buckets=mapper.buckets(actor.tenantId(),actor.employeeId(),today);
            var confirmed=buckets.stream().filter(b->"CONFIRMED".equals(b.classificationStatus())&&Integer.valueOf(year).equals(b.entitlementYear())).toList();
            int unknown=(int)buckets.stream().filter(b->!"CONFIRMED".equals(b.classificationStatus())).count();
            var visible=buckets.stream().filter(b->!"CONFIRMED".equals(b.classificationStatus())||Integer.valueOf(year).equals(b.entitlementYear())).toList();
            Integer outside=null;
            if(createdId!=null){var created=workflowMapper.findRequest(actor.tenantId(),createdId);if(created==null||!created.targetEmployeeId().equals(actor.employeeId()))throw OrgException.notFound();
                if(created.fromDate().getYear()!=year)outside=created.fromDate().getYear();else if(created.toDate().getYear()!=year)outside=created.toDate().getYear();}
            var annual=new Annual(year,today,at,filter.typeId(),status,filter.page(),found.size()>50,filter.authoredPage(),authored.size()>50,
                    summaries.isEmpty()&&confirmed.isEmpty()?"NO_REGISTERED_DATA":"REGISTERED_DATA_ONLY",
                    summaries.stream().mapToLong(TypeSummary::usedDays).sum(),summaries.stream().mapToLong(TypeSummary::plannedDays).sum(),summaries.stream().mapToLong(TypeSummary::pendingDays).sum(),
                    buckets.stream().mapToLong(BucketYear::availableDays).sum(),confirmed.isEmpty()?null:confirmed.stream().mapToLong(BucketYear::openingAdjustmentDays).sum(),
                    confirmed.isEmpty()?null:confirmed.stream().mapToLong(BucketYear::balanceDays).sum(),unknown,unknown>0,
                    mapper.types(actor.tenantId()),summaries,page(found),page(authored),visible,createdId,outside,
                    page(found).stream().filter(r->r.targetEmployeeId().equals(actor.employeeId())&&r.authorAccountId().equals(actor.accountId())
                            &&Set.of("REJECTED","WITHDRAWN").contains(r.status())&&Set.of("LEAVE_DAY","ATTENDANCE_CORRECTION").contains(r.kind())).map(RequestView::id).toList());
            Compose compose=draft==null?null:compose(actor,year,today,draft);
            var legacyForm=compose==null?new LeaveForm(null,today.plusDays(1),today.plusDays(1),null,"",UUID.randomUUID()):compose.leaveForm();
            return new View(annual,compose,flow.buckets(actor,actor.employeeId()),legacyForm);
        });
    }
    public void validateNewContext(Draft draft){
        kind(draft.kind());mode(draft.mode());
        if(draft.date()!=null&&(draft.date().getYear()<1900||draft.date().getYear()>9998))throw OrgException.invalid("작성 대상일을 확인해 주세요.");
        if("ATTENDANCE_CORRECTION".equals(draft.kind())&&draft.date()!=null&&draft.date().isAfter(LocalDate.now(clock.withZone(ZoneId.of("Asia/Seoul")))))throw OrgException.invalid("오늘 이후의 근무일은 정정할 수 없습니다.");
    }
    public void requireResubmission(TnaPrincipal actor,UUID id,String kind){
        if(id==null)return;
        var previous=flow.get(actor,id).request();
        if(!previous.targetEmployeeId().equals(actor.employeeId())||!previous.authorAccountId().equals(actor.accountId())
                ||!previous.kind().equals(kind)||!Set.of("REJECTED","WITHDRAWN").contains(previous.status()))
            throw OrgException.invalid("본인이 작성한 반려·철회 신청만 다시 신청할 수 있습니다.");
    }
    private Compose compose(TnaPrincipal actor,int year,LocalDate today,Draft draft){
        String kind=kind(draft.kind()),mode=mode(draft.mode());
        var previous=draft.predecessorRequestId()==null?null:flow.get(actor,draft.predecessorRequestId());
        if(previous!=null)requireResubmission(actor,previous.request().id(),kind);
        LocalDate selected=draft.date()!=null?draft.date():previous!=null?previous.request().fromDate():"ATTENDANCE_CORRECTION".equals(kind)?(year<today.getYear()?LocalDate.of(year,12,31):today):today.plusDays(1);
        var leave=draft.leaveForm()!=null?draft.leaveForm():previous!=null?new LeaveForm(previous.request().leaveBucketId(),previous.request().fromDate(),previous.request().toDate(),previous.request().id(),previous.request().reason(),UUID.randomUUID()):new LeaveForm(null,selected,selected,null,"",UUID.randomUUID());
        LocalDate factDate=draft.correctionForm()!=null&&draft.correctionForm().workDate()!=null?draft.correctionForm().workDate():selected;
        var day="ATTENDANCE_CORRECTION".equals(kind)?clocks.snapshot(actor.tenantId(),actor.employeeId(),factDate):null;
        var effective=day==null?null:workflowMapper.currentCorrection(actor.tenantId(),actor.employeeId(),day.workDate());
        UUID current=effective==null?null:effective.requestId();
        var correction=draft.correctionForm();
        if(correction==null){
            var old=previous==null?null:previous.correction();
            correction=new CorrectionForm(null,day==null?selected:day.workDate(),day==null?0:day.inputRevision(),day==null?"":day.sourceFingerprint(),
                    old==null?"KEEP":old.inAction(),old==null||old.requestedInAt()==null?"":old.requestedInAt().atZone(ZoneId.of("Asia/Seoul")).toLocalDateTime().toString(),
                    old==null?"KEEP":old.outAction(),old==null||old.requestedOutAt()==null?"":old.requestedOutAt().atZone(ZoneId.of("Asia/Seoul")).toLocalDateTime().toString(),
                    old!=null&&old.presenceAsWork(),old!=null&&old.breaksConfirmed(),old==null?"":breakInput(old.actualBreaks()),current,previous==null?null:previous.request().id(),false,previous==null?"":previous.request().reason(),UUID.randomUUID());
        }
        var approval=flow.approvalPreview(actor);
        var preview=draft.preview()?(kind.equals("LEAVE_DAY")?flow.previewLeave(actor,leave):flow.previewCorrection(actor,correction)):null;
        return new Compose(mode,kind,leave,correction,day,current,preview,approval,draft.conflict());
    }
    private static String breakInput(String raw){
        if(raw==null||raw.isBlank())return "";
        var result=new java.util.ArrayList<String>();
        for(var pause:tools.jackson.databind.json.JsonMapper.builder().build().readTree(raw)){
            var from=java.time.Instant.parse(pause.get("startAt").asString()).atZone(ZoneId.of("Asia/Seoul")).toLocalDateTime();
            var to=java.time.Instant.parse(pause.get("endAt").asString()).atZone(ZoneId.of("Asia/Seoul")).toLocalDateTime();result.add(from+"/"+to);
        }return String.join(",",result);
    }
    private <T>T read(TnaPrincipal actor,Supplier<T> work){return commands.read(actor,()->{mapper.lock(actor.tenantId());return commands.read(actor,work);});}
    private static String kind(String value){String kind=value==null||value.isBlank()?"LEAVE_DAY":value;if(!Set.of("LEAVE_DAY","ATTENDANCE_CORRECTION").contains(kind))throw OrgException.invalid("지원하는 연차 또는 정정 유형을 선택해 주세요.");return kind;}
    private static String mode(String value){String mode=value==null||value.isBlank()?"MODAL":value;if(!Set.of("MODAL","PAGE").contains(mode))throw OrgException.invalid("작성 화면 형식을 확인해 주세요.");return mode;}
    private static <T>List<T> page(List<T> values){return List.copyOf(values.subList(0,Math.min(50,values.size())));}
}
