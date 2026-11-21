package kr.tna.request;

import static kr.tna.org.OrgCommands.fingerprint;
import static kr.tna.org.OrgCommands.requiredText;
import static kr.tna.request.LeaveExitData.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import kr.tna.common.auth.TnaPrincipal;
import kr.tna.org.OrgCommands;
import kr.tna.org.OrgCommands.Change;
import kr.tna.org.OrgException;
import kr.tna.request.LeaveExitMapper.Source;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

@Service
public final class LeaveExitService {
    private final LeaveExitMapper mapper;
    private final EmploymentAccrualMapper monthly;
    private final LeaveWorkflowMapper workflow;
    private final OrgCommands commands;
    private final JsonMapper json;

    public LeaveExitService(LeaveExitMapper mapper, EmploymentAccrualMapper monthly, LeaveWorkflowMapper workflow,
            OrgCommands commands, JsonMapper json) {
        this.mapper = mapper;
        this.monthly = monthly;
        this.workflow = workflow;
        this.commands = commands;
        this.json = json;
    }

    public Preview preview(TnaPrincipal actor, UUID employeeId) {
        return commands.readShared(actor, () -> {
            checkTarget(actor, employeeId);
            String receipt = mapper.receipt(actor.tenantId(), employeeId);
            return receipt == null ? plan(actor, employeeId).preview() : json.readValue(receipt, Preview.class);
        });
    }

    public UUID post(TnaPrincipal actor, PostForm form) {
        if (form == null || form.employeeId() == null) throw OrgException.invalid("정산할 사원을 선택해 주세요.");
        if (form.token() == null || !form.token().matches("[0-9a-f]{64}"))
            throw OrgException.invalid("퇴사 정산 미리보기를 먼저 확인해 주세요.");
        String reason = requiredText(form.reason(), 500, "퇴사 정산 근거");
        boolean history = Boolean.TRUE.equals(form.priorHistoryConfirmed());
        return commands.execute(actor, form.commandId(), "LEAVE_EXIT_SETTLE", "LEAVE_EXIT_SETTLEMENT",
                fingerprint(form.employeeId(), form.token(), history, reason), reason, () -> {
                    checkTarget(actor,form.employeeId());
                    UUID tenant = actor.tenantId();
                    if (mapper.receipt(tenant, form.employeeId()) != null) throw OrgException.conflict("이미 퇴사 정산을 확정했습니다.");
                    var plan = plan(actor, form.employeeId());
                    var p = plan.preview();
                    if (!p.token().equals(form.token())) throw OrgException.conflict("인사정보 또는 연차 근거가 바뀌었습니다. 다시 미리 보세요.");
                    if (!p.canPost()) throw OrgException.conflict("미리보기에 표시된 확인 항목을 처리해 주세요.");
                    if (p.priorHistoryReviewRequired() && !history)
                        throw OrgException.invalid("입사일 기준 월별·연간 발생과 실제 사용, 회사 부여·이관 원장을 대조한 후 확인해 주세요.");
                    UUID id = UUID.randomUUID();
                    var receipt = new Preview(p.employeeId(),p.employeeNo(),p.employeeName(),p.joinedOn(),p.leftOn(),p.rows(),
                            p.balanceDays(),p.settlementDays(),p.missingMonths(),p.priorHistoryReviewRequired(),List.of(),null,false,id,reason);
                    changed(mapper.insert(tenant,p.employeeId(),id,p.leftOn(),p.settlementDays(),reason,
                            p.priorHistoryReviewRequired() && history,actor.accountId(),json.writeValueAsString(receipt)));
                    for (int i=0; i<p.rows().size(); i++) {
                        Row row = p.rows().get(i);
                        if (row.settlementDays().signum()==0) continue;
                        changed(mapper.debit(tenant,row.bucketId(),p.employeeId(),UUID.randomUUID(),row.settlementDays().negate(),
                                p.leftOn(),id,row.sourceReference(),reason,actor.accountId()));
                        changed(workflow.advanceBucket(tenant,row.bucketId(),plan.sources().get(i).version()));
                    }
                    return new Change(id,null,Map.of("employeeId",p.employeeId(),"leftOn",p.leftOn(),"settlementDays",p.settlementDays(),
                            "priorHistoryConfirmed",p.priorHistoryReviewRequired() && history));
                });
    }

    private record Plan(Preview preview,List<Source> sources) { }

    private Plan plan(TnaPrincipal actor, UUID employeeId) {
        UUID tenant=actor.tenantId();
        var employee=mapper.employee(tenant,employeeId);
        if (employee==null) throw OrgException.notFound();
        LocalDate left=employee.leftOn(), today=commands.today();
        var blockers=new ArrayList<String>();
        if (left==null) blockers.add("인사정보에 마지막 재직일을 등록해 주세요.");
        else if (left.isAfter(today)) blockers.add("마지막 재직일이 도래하면 정산을 확정할 수 있습니다.");

        var sources=mapper.sources(tenant,employeeId,left);
        var rows=sources.stream().map(s -> row(s,left)).toList();
        if (rows.stream().anyMatch(r -> !r.status().equals("EMPTY") && !r.status().equals("READY")))
            blockers.add("묶음별 귀속·사용기간·잔여와 퇴사 후 변동을 확인해 주세요. 정리한 뒤 다시 미리 보세요.");

        LocalDate through=left!=null && left.isBefore(today) ? left : today;
        var expected=EmploymentAccrualData.periods(employee.joinedOn(),through);
        var reviews=monthly.reviews(tenant,employeeId);
        var confirmed=new HashSet<LocalDate>();
        boolean consistent=true;
        for (var review : reviews) {
            var recorded=new EmploymentAccrualData.Window(review.monthNumber(),review.periodStart(),review.periodEnd(),review.accrualOn());
            if (!review.joinedOn().equals(employee.joinedOn()) || !expected.contains(recorded)) consistent=false;
            else confirmed.add(review.accrualOn());
        }
        if (!consistent) blockers.add("확정된 월별 발생 근거와 현재 재직 기간이 다릅니다. 입사일과 마지막 재직일을 확인해 주세요.");
        List<LocalDate> missing=left==null ? List.of() : expected.stream().map(EmploymentAccrualData.Window::accrualOn)
                .filter(date -> !confirmed.contains(date)).toList();
        boolean priorHistory=left!=null && !through.isBefore(EmploymentAccrualData.accrualOn(employee.joinedOn(),12));
        if (!missing.isEmpty() && !priorHistory) blockers.add("월별 발생에서 완료된 각 기간의 개근 여부를 먼저 확인해 주세요.");
        if (mapper.pendingLeave(tenant,employeeId)) blockers.add("대기 중인 연차 차감형 휴가 또는 취소 결재를 먼저 처리해 주세요.");
        if (left!=null && mapper.futureLeave(tenant,employeeId,left)) blockers.add("마지막 재직일 이후에 유효한 차감형 휴가의 사용일과 취소를 확인해 주세요.");
        BigDecimal balance=rows.stream().map(Row::balanceDays).reduce(BigDecimal.ZERO,BigDecimal::add);
        BigDecimal settlement=rows.stream().map(Row::settlementDays).reduce(BigDecimal.ZERO,BigDecimal::add);
        String token=fingerprint(tenant,actor.accountId(),employee,sources,reviews,missing,priorHistory,blockers);
        return new Plan(new Preview(employeeId,employee.employeeNo(),employee.employeeName(),employee.joinedOn(),left,
                rows,balance,settlement,missing,priorHistory,List.copyOf(blockers),token,blockers.isEmpty(),null,null), sources);
    }

    private static Row row(Source s,LocalDate left) {
        String status;
        if (s.balanceDays().signum()<0) status="NEGATIVE";
        else if (s.afterExitDays().signum()!=0) status="AFTER_EXIT";
        else if (s.balanceDays().signum()==0) status="EMPTY";
        else if (!s.confirmed()) status="UNKNOWN";
        else if (left==null || s.baseDate().isAfter(left) || s.usableFrom().isAfter(left)) status="FUTURE";
        else if (s.usableTo()!=null && s.usableTo().isBefore(left)) status="EXPIRED";
        else status="READY";
        return new Row(s.bucketId(),s.sourceReference(),s.usableFrom(),s.usableTo(),s.entitlementYear(),s.openingDays(),s.grantDays(),
                s.carryDays(),s.usedDays(),s.restoredDays(),s.adjustmentDays(),s.expireDays(),
                s.balanceDays(),status.equals("READY") ? s.balanceDays() : BigDecimal.ZERO,status);
    }

    private static void checkTarget(TnaPrincipal actor,UUID employeeId) {
        if (employeeId==null) throw OrgException.invalid("정산할 사원을 선택해 주세요.");
        if (employeeId.equals(actor.employeeId())) throw OrgException.forbidden();
    }

    private static void changed(int count) {
        if (count!=1) throw OrgException.conflict("원장 상태가 변경되었습니다. 정산 미리보기를 다시 확인해 주세요.");
    }
}
