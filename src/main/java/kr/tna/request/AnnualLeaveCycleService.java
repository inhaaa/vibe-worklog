package kr.tna.request;

import static kr.tna.org.OrgCommands.checkVersion;
import static kr.tna.org.OrgCommands.fingerprint;
import static kr.tna.org.OrgCommands.requiredText;
import static kr.tna.request.AnnualLeaveCycleData.*;
import static kr.tna.request.StatutoryLeave.HIRE_DATE;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.stream.Collectors;
import kr.tna.common.auth.TnaPrincipal;
import kr.tna.common.tenant.BusinessCommands;
import kr.tna.org.OrgCommands;
import kr.tna.org.OrgCommands.Change;
import kr.tna.org.OrgException;
import kr.tna.request.AnnualLeaveCycleMapper.Candidate;
import kr.tna.request.AnnualLeaveCycleMapper.Source;
import kr.tna.request.LeaveAttributionData.Attribution;
import kr.tna.request.RequestData.BucketForm;
import kr.tna.worktime.WorktimeService;
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
    private final WorktimeService worktime;

    public AnnualLeaveCycleService(AnnualLeaveCycleMapper mapper, LeaveWorkflowMapper workflow,
            LeaveAttributionMapper attribution, OrgCommands administration, BusinessCommands employees, WorktimeService worktime) {
        this.mapper = mapper;
        this.workflow = workflow;
        this.attribution = attribution;
        this.administration = administration;
        this.employees = employees;
        this.worktime = worktime;
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
        return administration.readShared(actor, () -> plan(actor, validYear(year), null).preview());
    }

    /** 입사일 기준 회사의 월별 미리보기: 그 달 1일부터 오늘(지난달이면 말일)까지 기념일이 지난 사원(설계 §5). */
    public Preview previewMonth(TnaPrincipal actor, YearMonth month) {
        return administration.readShared(actor, () -> plan(actor, validYear(month.getYear()), month).preview());
    }

    public static YearMonth month(String value) {
        try {
            var month = YearMonth.parse(value);
            validYear(month.getYear());
            return month;
        } catch (DateTimeParseException invalid) {
            throw OrgException.invalid("반영할 월을 2026-03 형식으로 입력해 주세요.");
        }
    }

    public UUID post(TnaPrincipal actor, PostForm form) {
        if (form == null || form.year() == null && form.month() == null) throw OrgException.invalid("반영할 연도나 월을 입력해 주세요.");
        YearMonth month = form.month() == null ? null : month(form.month());
        int year = month == null ? validYear(form.year()) : month.getYear();
        if (form.token() == null || !form.token().matches("[0-9a-f]{64}"))
            throw OrgException.invalid("반영할 기간의 미리보기를 확인해 주세요.");
        String reason = requiredText(form.reason(), 500, "반영 사유");
        var reviewed = reviewed(form.reviewedEmployeeIds());
        return administration.execute(actor, form.commandId(), "ANNUAL_LEAVE_POST", "ANNUAL_LEAVE_CYCLE",
                fingerprint(year, form.month(), form.token(), reason, reviewed), reason, () -> {
                    var plan = plan(actor, year, month);
                    var preview = plan.preview();
                    if (!form.token().equals(preview.token()))
                        throw OrgException.conflict("회사 기준, 대상 사원, 근태 또는 잔여가 바뀌었습니다. 미리보기를 다시 확인해 주세요.");
                    var selected = selected(plan, reviewed);
                    if (!preview.canPost()) throw OrgException.invalid("부여일 이후에 반영 대상자를 확인해 주세요.");
                    if (selected.isEmpty()) throw OrgException.invalid("부여할 사원이 없습니다. 확인 필요 사원을 고르거나 근태를 확정한 뒤 다시 미리 보세요.");
                    UUID cycleId = mapper.cycleId(actor.tenantId(), year);
                    if (cycleId == null) {
                        cycleId = UUID.randomUUID();
                        changed(mapper.insertCycle(actor.tenantId(), cycleId, year, preview.rule().version(), actor.accountId()));
                    }
                    UUID type = attribution.annualLeaveType(actor.tenantId());
                    if (type == null) throw OrgException.conflict("회사의 시스템 연차 유형을 확인해 주세요.");
                    var awards = new ArrayList<UUID>();
                    for (var award : selected) {
                        String source = month != null
                                ? "%s 입사 %d년차 연차 · 회사 연차 기준 %d차".formatted(award.row().joinedOn(), award.row().serviceYear(), preview.rule().version())
                                : year + "년 회사 연차 기준 " + preview.rule().version() + "차";
                        awards.add(postAward(actor, preview, award, type, source, reason));
                    }
                    var audit = new HashMap<String, Object>(Map.of("year", year, "ruleVersion", preview.rule().version(),
                            "periodStart", preview.periodStart(), "periodEnd", preview.periodEnd(), "awardBuckets", awards,
                            "postedCount", awards.size(), "reviewedEmployeeIds", List.copyOf(reviewed), "previewToken", preview.token()));
                    if (month != null) audit.put("month", month.toString());
                    return new Change(cycleId, null, audit);
                });
    }

    private static Set<UUID> reviewed(List<UUID> ids) {
        if (ids == null) return Set.of();
        if (ids.stream().anyMatch(Objects::isNull)) throw OrgException.invalid("확인한 사원을 다시 선택해 주세요.");
        return new TreeSet<>(ids);
    }

    /** 부여 대상 행 전부와 담당자가 고른 확인 필요 행(설계 §4.1·§5). 고른 사원이 확인 필요 행이 아니면 다시 미리 보게 한다. */
    private static List<Award> selected(Plan plan, Set<UUID> reviewed) {
        var review = plan.awards().stream().filter(award -> StatutoryLeave.NEEDS_REVIEW.equals(award.row().decision()))
                .map(award -> award.row().employeeId()).collect(Collectors.toSet());
        if (!review.containsAll(reviewed))
            throw OrgException.conflict("확인한 사원이 미리보기의 확인 필요 사원과 다릅니다. 미리보기를 다시 확인해 주세요.");
        return plan.awards().stream().filter(award -> StatutoryLeave.GRANT.equals(award.row().decision())
                || reviewed.contains(award.row().employeeId())).toList();
    }

    public Ledger ledger(TnaPrincipal actor, Integer selectedYear) {
        return employees.read(actor, () -> {
            workflow.readLock(actor.tenantId());
            LocalDate today = administration.today();
            var current = mapper.currentRule(actor.tenantId());
            boolean hire = current != null && HIRE_DATE.equals(current.basis());
            LocalDate joined = hire ? mapper.joinedOn(actor.tenantId(), actor.employeeId()) : null;
            int year = validYear(selectedYear != null ? selectedYear : hire ? anniversaryYear(joined, today) : currentYear(today, current));
            LocalDate start, end;
            if (hire) {
                // 입사일 기준 회사의 연도는 그 해 기념일부터 다음 기념일 전날까지다(설계 §5 개인 원장).
                int years = Math.max(0, year - joined.getYear());
                start = StatutoryLeave.anniversary(joined, years);
                end = StatutoryLeave.anniversary(joined, years + 1);
            } else {
                var applied = mapper.appliedRule(actor.tenantId(), year);
                var rule = applied == null ? current : applied;
                start = LocalDate.of(year, rule == null ? 1 : rule.fiscalStartMonth(), 1);
                end = start.plusYears(1);
            }
            return new Ledger(year, today, mapper.available(actor.tenantId(), actor.employeeId(), today),
                    mapper.entries(actor.tenantId(), actor.employeeId(), year, start, end),
                    mapper.exitReceipt(actor.tenantId(), actor.employeeId(), start, end),
                    hire ? HIRE_DATE : StatutoryLeave.FISCAL, start, end.minusDays(1));
        });
    }

    private static int anniversaryYear(LocalDate joined, LocalDate today) {
        int years = Math.max(0, today.getYear() - joined.getYear());
        if (years > 0 && StatutoryLeave.anniversary(joined, years).isAfter(today)) years--;
        return joined.getYear() + years;
    }

    private record Settlement(Source source, BigDecimal carry, BigDecimal expire) { }
    private record Award(Row row, List<Settlement> settlements, LocalDate usableTo) { }
    private record Plan(Preview preview, List<Award> awards) { }

    /** month가 없으면 회계연도 기준 연간 부여, 있으면 입사일 기준 그 달의 기념일 부여다. 회사 기준과 다르면 거절한다. */
    private Plan plan(TnaPrincipal actor, int year, YearMonth month) {
        UUID tenant = actor.tenantId();
        // 입사일 기준은 부여마다 현재 규칙(이월 한도)을 쓴다. 회계연도 기준은 그 연도에 고정한 규칙을 쓴다.
        var applied = month == null ? mapper.appliedRule(tenant, year) : null;
        var rule = applied == null ? mapper.currentRule(tenant) : applied;
        String label = month == null ? null : month.toString();
        if (rule == null) return new Plan(new Preview(year, label, null, null, null, List.of(), 0, 0, 0, 0, 0, null, false), List.of());
        boolean hire = HIRE_DATE.equals(rule.basis());
        if (hire != (month != null))
            throw OrgException.invalid(hire ? "입사일 기준 회사는 월을 선택해 미리 보세요." : "회계연도 기준 회사는 연도를 선택해 미리 보세요.");
        LocalDate today = administration.today();
        LocalDate start = hire ? month.atDay(1) : LocalDate.of(year, rule.fiscalStartMonth(), 1);
        LocalDate end = !hire ? start.plusYears(1).minusDays(1) : month.atEndOfMonth().isAfter(today) ? today : month.atEndOfMonth();
        boolean started = !start.isAfter(today);
        var sources = new HashMap<LocalDate, Map<UUID, List<Source>>>();
        var signatures = new ArrayList<String>();
        signatures.add(fingerprint(tenant, actor.accountId(), actor.employeeId(), year, label, end, rule.version(), rule.basis(),
                rule.fiscalStartMonth(), rule.carryLimitDays(), applied != null, started));
        int posted = 0, existing = 0, later = 0, pending = 0;
        var awards = new ArrayList<Award>();
        for (var employee : mapper.candidates(tenant, actor.employeeId(), year, start)) {
            LocalDate joined = employee.joinedOn();
            int serviceYear = hire ? year - joined.getYear() : StatutoryLeave.fiscalServiceYear(joined, start, rule.fiscalStartMonth());
            LocalDate grantOn = hire ? StatutoryLeave.anniversary(joined, serviceYear) : start;
            if (hire && (serviceYear < 1 || grantOn.isBefore(start) || grantOn.isAfter(end)
                    || employee.leftOn() != null && employee.leftOn().isBefore(grantOn))) continue;
            signatures.add(fingerprint(employee.employeeId(), employee.employeeNo(), employee.employeeName(),
                    employee.version(), employee.joinedOn(), employee.leftOn(), employee.posted(), employee.confirmed(), employee.later()));
            if (employee.posted()) { posted++; continue; }
            if (employee.confirmed()) { existing++; continue; }
            if (employee.later()) { later++; continue; }
            var previousBuckets = sources.computeIfAbsent(grantOn,
                    day -> byEmployee(mapper.sources(tenant, actor.employeeId(), year - 1, day.minusDays(1))))
                    .getOrDefault(employee.employeeId(), List.of());
            for (var source : previousBuckets) signatures.add(fingerprint(source.bucketId(), source.version(),
                    source.revision(), source.balance(), source.closingBalance(), source.pending()));
            if (previousBuckets.stream().anyMatch(Source::pending)) { pending++; continue; }
            var days = !hire && serviceYear == 1 ? StatutoryLeave.prorated(joined, start) : StatutoryLeave.serviceDays(serviceYear);
            var periodStart = hire ? StatutoryLeave.anniversary(joined, serviceYear - 1) : serviceYear == 1 ? joined : start.minusYears(1);
            var usableTo = hire ? StatutoryLeave.anniversary(joined, serviceYear + 1).minusDays(1) : end;
            awards.add(award(actor, rule, employee, grantOn, usableTo, serviceYear, periodStart, days, previousBuckets, signatures));
        }
        String token = fingerprint(signatures.toArray());
        var rows = awards.stream().map(Award::row).toList();
        int review = (int) rows.stream().filter(row -> StatutoryLeave.NEEDS_REVIEW.equals(row.decision())).count();
        var preview = new Preview(year, label, start, end, rule, rows, posted, existing, later, pending, review, token,
                started && !rows.isEmpty());
        return new Plan(preview, awards);
    }

    /** 한 사원의 전기 잔여 이월·소멸과 직전 산정기간 출근율 판정을 반영 행으로 만든다(설계 §4.1). */
    private Award award(TnaPrincipal actor, Rule rule, Candidate employee, LocalDate grantOn, LocalDate usableTo, int serviceYear,
            LocalDate periodStart, BigDecimal days, List<Source> previousBuckets, List<String> signatures) {
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
        var periodEnd = grantOn.minusDays(1);
        var attendance = worktime.leaveAttendanceDays(actor, employee.employeeId(), periodStart, periodEnd);
        signatures.add(fingerprint(attendance.stream().map(day -> fingerprint(day)).toArray()));
        var evidence = StatutoryLeave.Evidence.of(attendance);
        var judgement = StatutoryLeave.annual(evidence);
        var row = new Row(employee.employeeId(), employee.employeeNo(), employee.employeeName(), employee.joinedOn(), grantOn,
                serviceYear, periodStart, periodEnd, evidence.scheduledDays(), evidence.absenceDays(), evidence.unresolvedDays(),
                judgement.attendanceRate(), judgement.decision(), judgement.reason(), previous, days, carried, previous.subtract(carried));
        return new Award(row, settlements, usableTo);
    }

    private static Map<UUID, List<Source>> byEmployee(List<Source> sources) {
        var grouped = new HashMap<UUID, List<Source>>();
        for (var source : sources) grouped.computeIfAbsent(source.employeeId(), key -> new ArrayList<>()).add(source);
        return grouped;
    }

    private UUID postAward(TnaPrincipal actor, Preview preview, Award award, UUID type, String basisSource, String reason) {
        UUID tenant = actor.tenantId(), employee = award.row().employeeId(), bucket = UUID.randomUUID();
        LocalDate start = award.row().grantOn();
        boolean reviewed = StatutoryLeave.NEEDS_REVIEW.equals(award.row().decision());
        String source = reviewed ? basisSource + " · 담당자 확인(" + award.row().reason() + ")" : basisSource;
        changed(workflow.insertBucket(tenant, bucket, new BucketForm(employee, start, start, award.usableTo(),
                award.row().grantDays(), source, reason, null), actor.accountId()));
        changed(mapper.insertAward(tenant, preview.year(), award.row(), bucket, reviewed, actor.accountId()));
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
