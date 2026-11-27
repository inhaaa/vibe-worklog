package kr.tna.request;

import static kr.tna.org.OrgCommands.fingerprint;
import static kr.tna.org.OrgCommands.requiredText;
import static kr.tna.request.EmploymentAccrualData.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import kr.tna.common.auth.TnaPrincipal;
import kr.tna.org.EmployeeAdminMapper;
import kr.tna.org.OrgCommands;
import kr.tna.org.OrgData.Employee;
import kr.tna.org.OrgException;
import kr.tna.request.EmploymentAccrualMapper.Review;
import kr.tna.request.EmploymentAccrualMapper.Source;
import kr.tna.request.LeaveAttributionData.Attribution;
import kr.tna.request.RequestData.BucketForm;
import kr.tna.worktime.WorktimeService;
import org.springframework.stereotype.Service;

@Service
public final class EmploymentAccrualService {
    private final EmploymentAccrualMapper mapper;
    private final EmployeeAdminMapper people;
    private final WorktimeService worktime;
    private final AnnualLeaveCycleMapper cycles;
    private final LeaveWorkflowMapper workflow;
    private final LeaveAttributionMapper attribution;
    private final OrgCommands commands;

    public EmploymentAccrualService(EmploymentAccrualMapper mapper, EmployeeAdminMapper people, WorktimeService worktime,
            AnnualLeaveCycleMapper cycles, LeaveWorkflowMapper workflow, LeaveAttributionMapper attribution, OrgCommands commands) {
        this.mapper = mapper;
        this.people = people;
        this.worktime = worktime;
        this.cycles = cycles;
        this.workflow = workflow;
        this.attribution = attribution;
        this.commands = commands;
    }

    public Preview preview(TnaPrincipal actor, UUID employeeId) {
        return commands.readShared(actor, () -> {
            var basis = basis(actor,employeeId);
            var rows = new TreeMap<LocalDate,Period>();
            basis.reviews().values().forEach(review -> rows.put(review.accrualOn(), review.period()));
            for (var window : basis.windows()) {
                rows.computeIfAbsent(window.accrualOn(), ignored -> period(actor,basis,window));
            }
            var person = basis.employee();
            var sources = basis.sources().stream().map(source -> new OpeningSource(source.bucketId(),source.sourceReference(),
                    source.creditedDays(),source.linkedDays(),basis.windows().stream().map(Window::accrualOn)
                            .filter(date -> source.covers(date,basis.rule())).toList()))
                    .filter(source -> !source.accrualDates().isEmpty() || source.linkedDays().signum() > 0).toList();
            return new Preview(person.id(),person.employeeNo(),person.displayName(),person.joinedOn(),person.leftOn(),
                    commands.today(),basis.usableTo(),basis.settled(),List.copyOf(rows.values()),sources);
        });
    }

    public UUID post(TnaPrincipal actor, PostForm form) {
        if (form == null || form.employeeId() == null || form.accrualOn() == null
                || form.decision() == null || !Set.of("FULL","NOT_FULL","INCLUDED").contains(form.decision()))
            throw OrgException.invalid("사원, 발생일과 담당자가 확인한 개근 여부를 선택해 주세요.");
        if ("INCLUDED".equals(form.decision()) != (form.includedBucketId() != null))
            throw OrgException.invalid("기초에 이미 포함된 개근 월을 확인할 때 해당 기초 원장을 선택해 주세요.");
        if (form.token() == null || !form.token().matches("[0-9a-f]{64}"))
            throw OrgException.invalid("해당 기간의 산정 근거를 다시 미리 보세요.");
        String reason = requiredText(form.reason(),500,"월별 판단 근거");
        String commandHash = fingerprint(form.employeeId(),form.accrualOn(),form.decision(),reason,form.token());
        if (form.includedBucketId() != null) commandHash = fingerprint(commandHash,form.includedBucketId());
        return commands.execute(actor,form.commandId(),"MONTHLY_LEAVE_REVIEW","LEAVE_MONTHLY_REVIEW",
                commandHash,reason,() -> {
                    var basis = basis(actor,form.employeeId());
                    if (basis.settled()) throw OrgException.conflict("퇴사 잔여 정산을 확정한 사원입니다.");
                    if (!basis.consistent()) throw OrgException.conflict("재직기간과 기존 월별 판정이 다릅니다. 기존 근거를 확인해 주세요.");
                    var window = basis.windows().stream().filter(w -> w.accrualOn().equals(form.accrualOn())).findFirst()
                            .orElseThrow(() -> OrgException.invalid("서울 기준 발생일과 마지막 재직일을 확인해 주세요."));
                    if (basis.reviews().containsKey(window.monthNumber())) throw OrgException.conflict("이미 확정한 근무월입니다.");
                    var row = period(actor,basis,window);
                    if (!form.token().equals(row.token())) throw OrgException.conflict("근무 근거, 인사정보 또는 기초 원장이 변경되었습니다. 다시 미리 보세요.");
                    Source included = null;
                    if (form.includedBucketId() != null) {
                        included = basis.sources().stream().filter(s -> s.bucketId().equals(form.includedBucketId())
                                && s.covers(row.accrualOn(),basis.rule())).findFirst()
                                .orElseThrow(() -> OrgException.invalid("같은 사원·귀속연도의 발생일을 포함하는 확정 연차 기초 원장을 선택해 주세요."));
                        if (included.creditedDays().subtract(included.linkedDays()).compareTo(BigDecimal.ONE) < 0)
                            throw OrgException.invalid("이 기초 원장에서 추가로 연결할 수 있는 원부여 일수가 1일 미만입니다.");
                    }
                    String finding = switch (form.decision()) {
                        case "FULL" -> "개근";
                        case "INCLUDED" -> "개근 · 기초에 포함";
                        default -> "미개근";
                    };
                    var saved = record(actor,basis,row,form.decision(),included,reason,finding);
                    Map<String,Object> audit = new HashMap<>(Map.of("employeeId",form.employeeId(),"accrualOn",row.accrualOn(),
                            "decision",form.decision(),"grantedDays",saved.quantity(),"source",saved.source(),"bucketId",saved.bucket()));
                    if (included != null) audit.put("includedBucketId",included.bucketId());
                    return new OrgCommands.Change(saved.id(),null,audit);
                });
    }

    public BulkPreview bulkPreview(TnaPrincipal actor) {
        return commands.readShared(actor, () -> bulk(actor));
    }

    public UUID postBulk(TnaPrincipal actor, BulkForm form) {
        if (form == null || form.token() == null || !form.token().matches("[0-9a-f]{64}"))
            throw OrgException.invalid("월 연차 미리보기를 다시 확인해 주세요.");
        String reason = requiredText(form.reason(),500,"반영 사유");
        return commands.execute(actor,form.commandId(),"MONTHLY_LEAVE_BULK","LEAVE_MONTHLY_REVIEW",
                fingerprint(form.token(),reason),reason,() -> {
                    var preview = bulk(actor);
                    if (!form.token().equals(preview.token()))
                        throw OrgException.conflict("근태 결과나 사원 정보가 바뀌었습니다. 월 연차 미리보기를 다시 확인해 주세요.");
                    if (!preview.canPost()) throw OrgException.invalid("일괄 반영할 개근·미개근 판정이 없습니다.");
                    var ids = new ArrayList<UUID>();
                    for (var row : preview.rows()) {
                        if (StatutoryLeave.NEEDS_REVIEW.equals(row.decision())) continue;
                        // 같은 사원의 다음 달은 방금 기록한 판정의 묶음을 이어 쓰도록 근거를 다시 읽는다.
                        var basis = basis(actor,row.employeeId());
                        String finding = row.finding() + " · " + (StatutoryLeave.FULL.equals(row.decision()) ? "개근" : "미개근");
                        ids.add(record(actor,basis,row.period(),row.decision(),null,reason,finding).id());
                    }
                    return new OrgCommands.Change(ids.getFirst(),null,Map.of("reviewIds",ids,"postedCount",ids.size(),
                            "previewToken",preview.token()));
                });
    }

    /** 발생일이 지났지만 판정하지 않은 월(설계 §5). 월 연차 사용기한이 끝난 사원은 사원별 화면에서 처리한다. */
    private BulkPreview bulk(TnaPrincipal actor) {
        LocalDate today = commands.today();
        var rows = new ArrayList<BulkRow>();
        var signature = new ArrayList<String>();
        signature.add(fingerprint(actor.tenantId(),actor.accountId(),today));
        for (UUID id : mapper.monthlyCandidates(actor.tenantId(),actor.employeeId(),today.minusMonths(13),today)) {
            var basis = basis(actor,id);
            if (basis.settled() || !basis.consistent() || basis.usableTo().isBefore(today)) continue;
            var person = basis.employee();
            for (var window : basis.windows()) {
                if (basis.reviews().containsKey(window.monthNumber())) continue;
                var period = period(actor,basis,window);
                // 기초 원장에 이미 포함됐을 수 있는 월은 사원별 화면에서 '기초에 포함' 여부를 정한다(설계 §5).
                boolean opening = basis.sources().stream().anyMatch(source -> source.covers(window.accrualOn(),basis.rule())
                        && source.creditedDays().subtract(source.linkedDays()).compareTo(BigDecimal.ONE) >= 0);
                String decision = opening ? StatutoryLeave.NEEDS_REVIEW
                        : StatutoryLeave.monthly(new StatutoryLeave.Evidence(period.scheduledDays(),period.absenceDays(),period.unresolvedDays()));
                String finding = opening ? "기초 잔여 확인 필요"
                        : StatutoryLeave.NEEDS_REVIEW.equals(decision) ? "판정 불가 %d일".formatted(period.unresolvedDays())
                        : "자동 판정: 소정 %d일, 결근 %s일".formatted(period.scheduledDays(),period.absenceDays().stripTrailingZeros().toPlainString());
                rows.add(new BulkRow(person.id(),person.employeeNo(),person.displayName(),person.joinedOn(),period,decision,finding));
                signature.add(fingerprint(person.id(),window.accrualOn(),period.token(),decision));
            }
        }
        int full = (int) rows.stream().filter(row -> StatutoryLeave.FULL.equals(row.decision())).count();
        int notFull = (int) rows.stream().filter(row -> StatutoryLeave.NOT_FULL.equals(row.decision())).count();
        return new BulkPreview(today,List.copyOf(rows),full,notFull,rows.size()-full-notFull,fingerprint(signature.toArray()),full+notFull>0);
    }

    private record Recorded(UUID id, UUID bucket, String source, int quantity) { }

    /** 한 근무월의 판정과 MONTHLY_ACCRUAL 원장을 같은 묶음에 기록한다. 사원별 확정과 일괄 반영이 같이 쓴다. */
    private Recorded record(TnaPrincipal actor, Basis basis, Period row, String decision, Source included, String reason, String finding) {
        String source = "%d개월차 %s~%s · 소정 %d일 · 출근 확인 %d일 · 승인 휴가 %d일 · 미확인 %d일 · %s".formatted(
                row.monthNumber(),row.periodStart(),row.periodEnd(),row.scheduledDays(),row.attendedDays(),
                row.leaveDays(),row.unresolvedDays(),finding);
        UUID tenant = actor.tenantId(), employee = basis.employee().id(), id = UUID.randomUUID();
        int year = AnnualLeaveCycleData.currentYear(row.accrualOn(),basis.rule());
        UUID bucket = bucket(actor,basis,year);
        int quantity = decision.equals("FULL") ? 1 : 0;
        changed(mapper.insert(tenant,id,employee,basis.employee().joinedOn(),row,basis.usableTo(),year,
                decision,quantity,bucket,reason,actor.accountId(),included == null ? null : included.bucketId(),
                included == null ? null : included.sourceReference()));
        changed(mapper.movement(tenant,UUID.randomUUID(),bucket,employee,quantity,row.accrualOn(),id,source,reason,actor.accountId()));
        return new Recorded(id,bucket,source,quantity);
    }

    private record Basis(Employee employee, AnnualLeaveCycleData.Rule rule, List<Window> windows, Map<Integer,Review> reviews,
            LocalDate usableTo, boolean settled, boolean consistent, List<Source> sources) { }

    private Basis basis(TnaPrincipal actor, UUID employeeId) {
        if (employeeId == null) throw OrgException.invalid("사원을 선택해 주세요.");
        if (employeeId.equals(actor.employeeId())) throw OrgException.forbidden();
        UUID tenant = actor.tenantId();
        var person = people.get(tenant,employeeId);
        if (person == null) throw OrgException.notFound();
        LocalDate today = commands.today();
        LocalDate through = person.leftOn() != null && person.leftOn().isBefore(today) ? person.leftOn() : today;
        var windows = periods(person.joinedOn(),through);
        var reviews = new HashMap<Integer,Review>();
        boolean consistent = person.active() || person.leftOn() != null;
        for (var review : mapper.reviews(tenant,employeeId)) {
            reviews.put(review.monthNumber(),review);
            consistent &= review.joinedOn().equals(person.joinedOn()) && !review.accrualOn().isAfter(through);
        }
        return new Basis(person,cycles.currentRule(tenant),windows,reviews,accrualOn(person.joinedOn(),12).minusDays(1),
                mapper.settled(tenant,employeeId),consistent,mapper.openingSources(tenant,employeeId,today,through,accrualOn(person.joinedOn(),1)));
    }

    private Period period(TnaPrincipal actor, Basis basis, Window window) {
        var person = basis.employee();
        var dates = worktime.leaveAttendanceDays(actor,person.id(),window.periodStart(),window.periodEnd());
        int scheduled = 0, attended = 0, leave = 0, unresolved = 0;
        BigDecimal absence = BigDecimal.ZERO;
        var signature = new ArrayList<String>();
        signature.add(fingerprint(actor.tenantId(),actor.accountId(),person,window,basis.rule(),basis.sources()));
        for (var day : dates) {
            if (day.scheduled()) scheduled++;
            if (day.attended()) attended++;
            if (day.leave()) leave++;
            if (day.unresolved()) unresolved++;
            absence = absence.add(BigDecimal.valueOf(day.absenceDays()));
            signature.add(fingerprint(day));
        }
        String token = basis.settled() || !basis.consistent() ? null : fingerprint(signature.toArray());
        return new Period(window.monthNumber(),window.periodStart(),window.periodEnd(),window.accrualOn(),scheduled,attended,
                leave,unresolved,null,null,0,token,null,absence.setScale(1));
    }

    private UUID bucket(TnaPrincipal actor, Basis basis, int year) {
        UUID tenant = actor.tenantId(), employee = basis.employee().id();
        for (var review : basis.reviews().values()) {
            if (review.entitlementYear() != year) continue;
            var bucket = workflow.findBucket(tenant,review.bucketId(),commands.today());
            changed(workflow.advanceBucket(tenant,bucket.id(),bucket.version()));
            return bucket.id();
        }
        UUID type = attribution.annualLeaveType(tenant);
        if (type == null) throw OrgException.conflict("회사의 시스템 연차 유형을 확인해 주세요.");
        UUID id = UUID.randomUUID();
        var yearStart = LocalDate.of(year,basis.rule() == null ? 1 : basis.rule().fiscalStartMonth(),1);
        var first = accrualOn(basis.employee().joinedOn(),1);
        var start = first.isAfter(yearStart) ? first : yearStart;
        String source = "%s 입사 · %d년 귀속 월별 연차".formatted(basis.employee().joinedOn(),year);
        changed(workflow.insertBucket(tenant,id,new BucketForm(employee,start,start,basis.usableTo(),BigDecimal.ZERO,
                source,source,null),actor.accountId()));
        changed(attribution.insert(tenant,new Attribution(UUID.randomUUID(),id,1,type,"CONFIRMED",year,source,source,
                actor.accountId(),null,null)));
        return id;
    }

    private static void changed(int count) {
        if (count != 1) throw OrgException.conflict("월별 연차 근거가 변경되었습니다. 최신 미리보기를 확인해 주세요.");
    }
}
