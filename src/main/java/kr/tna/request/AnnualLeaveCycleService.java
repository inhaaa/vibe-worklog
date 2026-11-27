package kr.tna.request;

import static kr.tna.org.OrgCommands.checkVersion;
import static kr.tna.org.OrgCommands.fingerprint;
import static kr.tna.org.OrgCommands.requiredText;
import static kr.tna.request.AnnualLeaveCycleData.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import kr.tna.common.auth.TnaPrincipal;
import kr.tna.common.tenant.BusinessCommands;
import kr.tna.org.OrgCommands;
import kr.tna.org.OrgCommands.Change;
import kr.tna.org.OrgException;
import kr.tna.request.AnnualLeaveCycleMapper.Source;
import kr.tna.request.LeaveAttributionData.Attribution;
import kr.tna.request.RequestData.BucketForm;
import org.springframework.stereotype.Service;

@Service
public final class AnnualLeaveCycleService {
    private static final BigDecimal HALF = new BigDecimal("0.5");
    private static final BigDecimal LIMIT = BigDecimal.valueOf(100_000);
    private static final BigDecimal ZERO = BigDecimal.ZERO;
    private final AnnualLeaveCycleMapper mapper;
    private final LeaveWorkflowMapper workflow;
    private final LeaveAttributionMapper attribution;
    private final OrgCommands administration;
    private final BusinessCommands employees;

    public AnnualLeaveCycleService(AnnualLeaveCycleMapper mapper, LeaveWorkflowMapper workflow,
            LeaveAttributionMapper attribution, OrgCommands administration, BusinessCommands employees) {
        this.mapper = mapper;
        this.workflow = workflow;
        this.attribution = attribution;
        this.administration = administration;
        this.employees = employees;
    }

    public Rule rule(TnaPrincipal actor) {
        return administration.readShared(actor, () -> mapper.currentRule(actor.tenantId()));
    }

    public Rule savedRule(TnaPrincipal actor, UUID id) {
        return administration.readShared(actor, () -> {
            Rule saved = mapper.savedRule(actor.tenantId(), id);
            if (saved == null) throw OrgException.notFound();
            return saved;
        });
    }

    public UUID saveRule(TnaPrincipal actor, RuleForm form) {
        if (form == null || !StatutoryLeave.FISCAL.equals(form.basis()) && !StatutoryLeave.HIRE_DATE.equals(form.basis()))
            throw OrgException.invalid("연차 부여 기준을 회계연도 기준 또는 입사일 기준으로 선택해 주세요.");
        boolean fiscal = StatutoryLeave.FISCAL.equals(form.basis());
        if (fiscal && (form.fiscalStartMonth() == null || form.fiscalStartMonth() < 1 || form.fiscalStartMonth() > 12))
            throw OrgException.invalid("회계 시작월을 1~12월 중에서 선택해 주세요.");
        var cap = quantity(form.carryLimitDays());
        String reason = requiredText(form.reason(), 500, "변경 사유");
        return administration.execute(actor, form.commandId(), "ANNUAL_LEAVE_RULE_SAVE", "ANNUAL_LEAVE_RULE",
                fingerprint(form.expectedVersion(), form.basis(), form.fiscalStartMonth(), cap, reason), reason, () -> {
                    var before = mapper.currentRule(actor.tenantId());
                    long version = before == null ? 0 : before.version();
                    checkVersion(form.expectedVersion(), version);
                    String beforeBasis = before == null ? StatutoryLeave.FISCAL : before.basis();
                    int beforeMonth = before == null ? 1 : before.fiscalStartMonth();
                    // 입사일 기준도 월 연차 귀속연도가 쓰는 회계 시작월을 그대로 보존한다.
                    int month = fiscal ? form.fiscalStartMonth() : beforeMonth;
                    boolean basisChanged = !beforeBasis.equals(form.basis());
                    if ((basisChanged || beforeMonth != month) && mapper.hasCycles(actor.tenantId()))
                        throw OrgException.conflict(basisChanged
                                ? "연간 부여나 월별 연차를 기록한 회사는 연차 기준을 바꿀 수 없습니다. 이월 한도는 변경할 수 있습니다."
                                : "연간 부여나 월별 연차를 기록한 회사는 기존 회계 시작월을 유지해 주세요. 이월 한도는 변경할 수 있습니다.");
                    var rule = new Rule(version + 1, month, StatutoryLeave.BASE_DAYS, cap, form.basis());
                    UUID id = UUID.randomUUID();
                    changed(mapper.insertRule(actor.tenantId(), id, rule, actor.accountId()));
                    return new Change(id, before, rule);
                });
    }

    public Preview preview(TnaPrincipal actor, int year) {
        return administration.readShared(actor, () -> plan(actor, validYear(year)).preview());
    }

    public UUID post(TnaPrincipal actor, PostForm form) {
        if (form == null || form.year() == null) throw OrgException.invalid("반영할 연도를 입력해 주세요.");
        int year = validYear(form.year());
        if (form.token() == null || !form.token().matches("[0-9a-f]{64}"))
            throw OrgException.invalid("반영할 연도의 미리보기를 확인해 주세요.");
        String reason = requiredText(form.reason(), 500, "반영 사유");
        return administration.execute(actor, form.commandId(), "ANNUAL_LEAVE_POST", "ANNUAL_LEAVE_CYCLE",
                fingerprint(year, form.token(), reason), reason, () -> {
                    var plan = plan(actor, year);
                    var preview = plan.preview();
                    if (!form.token().equals(preview.token()))
                        throw OrgException.conflict("회사 기준, 대상 사원 또는 잔여가 바뀌었습니다. 미리보기를 다시 확인해 주세요.");
                    if (!preview.canPost()) throw OrgException.invalid("회계연도 시작일 이후에 반영 대상자를 확인해 주세요.");
                    UUID cycleId = mapper.cycleId(actor.tenantId(), year);
                    if (cycleId == null) {
                        cycleId = UUID.randomUUID();
                        changed(mapper.insertCycle(actor.tenantId(), cycleId, year, preview.rule().version(), actor.accountId()));
                    }
                    UUID type = attribution.annualLeaveType(actor.tenantId());
                    if (type == null) throw OrgException.conflict("회사의 시스템 연차 유형을 확인해 주세요.");
                    String source = year + "년 회사 연차 기준 " + preview.rule().version() + "차";
                    var awards = new ArrayList<UUID>();
                    for (var award : plan.awards()) awards.add(postAward(actor, preview, award, type, source, reason));
                    return new Change(cycleId, null, Map.of("year", year, "ruleVersion", preview.rule().version(),
                            "periodStart", preview.periodStart(), "periodEnd", preview.periodEnd(), "awardBuckets", awards,
                            "postedCount", awards.size(), "previewToken", preview.token()));
                });
    }

    public Ledger ledger(TnaPrincipal actor, Integer selectedYear) {
        return employees.read(actor, () -> {
            workflow.readLock(actor.tenantId());
            LocalDate today = administration.today();
            var current = mapper.currentRule(actor.tenantId());
            int year = validYear(selectedYear == null ? currentYear(today, current) : selectedYear);
            var applied = mapper.appliedRule(actor.tenantId(), year);
            var rule = applied == null ? current : applied;
            LocalDate start = LocalDate.of(year, rule == null ? 1 : rule.fiscalStartMonth(), 1);
            LocalDate end = start.plusYears(1);
            return new Ledger(year, today, mapper.available(actor.tenantId(), actor.employeeId(), today),
                    mapper.entries(actor.tenantId(), actor.employeeId(), year, start, end),
                    mapper.exitReceipt(actor.tenantId(), actor.employeeId(), start, end));
        });
    }

    private record Settlement(Source source, BigDecimal carry, BigDecimal expire) { }
    private record Award(Row row, List<Settlement> settlements) { }
    private record Plan(Preview preview, List<Award> awards) { }

    private Plan plan(TnaPrincipal actor, int year) {
        UUID tenant = actor.tenantId();
        var applied = mapper.appliedRule(tenant, year);
        var rule = applied == null ? mapper.currentRule(tenant) : applied;
        if (rule == null) return new Plan(new Preview(year, null, null, null, List.of(), 0, 0, 0, 0, null, false), List.of());
        var start = LocalDate.of(year, rule.fiscalStartMonth(), 1);
        var end = start.plusYears(1).minusDays(1);
        var sources = new HashMap<UUID,List<Source>>();
        for (var source : mapper.sources(tenant, actor.employeeId(), year-1, start.minusDays(1))) {
            sources.computeIfAbsent(source.employeeId(), key -> new ArrayList<>()).add(source);
        }

        boolean started = !start.isAfter(administration.today());
        var signatures = new ArrayList<String>();
        signatures.add(fingerprint(tenant, actor.accountId(), actor.employeeId(), year, rule.version(),
                rule.fiscalStartMonth(), rule.basis(), rule.carryLimitDays(), applied != null, started));
        int posted = 0, existing = 0, later = 0, pending = 0;
        var awards = new ArrayList<Award>();
        for (var employee : mapper.candidates(tenant, actor.employeeId(), year, start)) {
            signatures.add(fingerprint(employee.employeeId(), employee.employeeNo(), employee.employeeName(),
                    employee.version(), employee.joinedOn(), employee.leftOn(), employee.posted(), employee.confirmed(), employee.later()));
            if (employee.posted()) { posted++; continue; }
            if (employee.confirmed()) { existing++; continue; }
            if (employee.later()) { later++; continue; }

            var previousBuckets = sources.getOrDefault(employee.employeeId(), List.of());
            for (var source : previousBuckets) signatures.add(fingerprint(source.bucketId(), source.version(),
                    source.revision(), source.balance(), source.closingBalance(), source.pending()));
            if (previousBuckets.stream().anyMatch(Source::pending)) { pending++; continue; }
            BigDecimal previous = ZERO, carried = ZERO;
            var settlements = new ArrayList<Settlement>();
            for (var source : previousBuckets) {
                if (source.balance().signum() < 0) throw OrgException.conflict("이월 대상 묶음에 음수 잔여가 있습니다. 해당 원장을 확인해 주세요.");
                // Credits effective after expiry stay in the original bucket for an administrator to review.
                BigDecimal eligible = source.balance().min(source.closingBalance()).max(ZERO);
                if (eligible.signum() == 0) continue;
                BigDecimal carry = eligible.min(rule.carryLimitDays().subtract(carried));
                settlements.add(new Settlement(source, carry, eligible.subtract(carry)));
                previous = previous.add(eligible);
                carried = carried.add(carry);
            }
            var row = new Row(employee.employeeId(), employee.employeeNo(), employee.employeeName(),
                    previous, rule.grantDays(), carried, previous.subtract(carried));
            awards.add(new Award(row, settlements));
        }
        String token = fingerprint(signatures.toArray());
        var preview = new Preview(year, start, end, rule, awards.stream().map(Award::row).toList(),
                posted, existing, later, pending, token, started && !awards.isEmpty());
        return new Plan(preview, awards);
    }

    private UUID postAward(TnaPrincipal actor, Preview preview, Award award, UUID type, String source, String reason) {
        UUID tenant = actor.tenantId(), employee = award.row().employeeId(), bucket = UUID.randomUUID();
        LocalDate start = preview.periodStart();
        changed(workflow.insertBucket(tenant, bucket, new BucketForm(employee, start, start, preview.periodEnd(),
                award.row().grantDays(), source, reason, null), actor.accountId()));
        changed(mapper.insertAward(tenant, preview.year(), employee, bucket, actor.accountId()));
        changed(attribution.insert(tenant, new Attribution(UUID.randomUUID(), bucket, 1, type, "CONFIRMED", preview.year(),
                source, reason, actor.accountId(), null, null)));
        movement(actor, employee, bucket, bucket, "GRANT", award.row().grantDays(), start, source, reason);
        if (award.row().carryDays().signum() > 0)
            movement(actor, employee, bucket, bucket, "CARRY_IN", award.row().carryDays(), start, source, reason);
        for (var settlement : award.settlements()) {
            UUID previous = settlement.source().bucketId();
            if (settlement.carry().signum() > 0)
                movement(actor, employee, bucket, previous, "CARRY_OUT", settlement.carry().negate(), start, source, reason);
            if (settlement.expire().signum() > 0)
                movement(actor, employee, bucket, previous, "EXPIRE", settlement.expire().negate(), start, source, reason);
            changed(workflow.advanceBucket(tenant, previous, settlement.source().version()));
        }
        return bucket;
    }

    private void movement(TnaPrincipal actor, UUID employee, UUID award, UUID bucket, String kind, BigDecimal days,
            LocalDate date, String source, String reason) {
        changed(mapper.movement(actor.tenantId(), UUID.randomUUID(), bucket, employee, kind, days, date, award, source, reason, actor.accountId()));
    }

    private static BigDecimal quantity(BigDecimal value) {
        BigDecimal normalized = value == null ? null : value.stripTrailingZeros();
        if (normalized == null || normalized.signum() < 0 || normalized.compareTo(LIMIT) > 0 || normalized.scale() > 1
                || normalized.remainder(HALF).signum() != 0)
            throw OrgException.invalid("이월 한도는 0~100000일 사이의 0.5일 단위로 입력해 주세요.");
        return normalized.setScale(1);
    }

    private static int validYear(int year) {
        if (year < MIN_YEAR || year > MAX_YEAR) throw OrgException.invalid("조회 연도는 1900~9998 범위로 입력해 주세요.");
        return year;
    }

    private static void changed(int rows) {
        if (rows != 1) throw OrgException.conflict("원장 상태가 변경되었습니다. 최신 미리보기를 확인해 주세요.");
    }
}
