package kr.tna.worktime;

import static kr.tna.org.OrgCommands.*;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;
import java.util.function.Supplier;
import kr.tna.clock.AttendanceHistory;
import kr.tna.clock.ClockData;
import kr.tna.clock.ClockService;
import kr.tna.common.auth.TnaPrincipal;
import kr.tna.common.tenant.BusinessCommands;
import kr.tna.common.tenant.CompanyReadAccess;
import kr.tna.common.tenant.TenantTransactions;
import kr.tna.org.EmployeeAdminMapper;
import kr.tna.org.OrgCommands;
import kr.tna.org.OrgCommands.Change;
import kr.tna.org.OrgData.Employee;
import kr.tna.org.OrgException;
import kr.tna.worktime.WorktimeData.*;
import kr.tna.worktime.WorktimeCalculator.*;
import kr.tna.worktime.SelectiveSettlement.Action;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

@Service
public final class WorktimeService {
    private static final Logger LOG=LoggerFactory.getLogger(WorktimeService.class);
    private static final ZoneId SEOUL=ZoneId.of("Asia/Seoul");
    private static final DateTimeFormatter EXPORT_INSTANT=DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSSXXX",Locale.ROOT).withZone(SEOUL);
    private final WorktimeMapper mapper;
    private final EmployeeAdminMapper employees;
    private final ClockService clocks;
    private final BusinessCommands commands;
    private final CompanyReadAccess companyAccess;
    private final OrgCommands administration;
    private final Clock clock;
    private final TenantTransactions transactions;
    private final WorktimeCalculator calculator=new WorktimeCalculator();
    private final JsonMapper json=JsonMapper.builder().build();
    public WorktimeService(WorktimeMapper mapper,EmployeeAdminMapper employees,ClockService clocks,
            BusinessCommands commands,CompanyReadAccess companyAccess,OrgCommands administration,Clock clock,TenantTransactions transactions) {
        this.mapper=mapper;this.employees=employees;this.clocks=clocks;this.commands=commands;this.companyAccess=companyAccess;this.administration=administration;this.clock=clock;
        this.transactions=transactions;
    }
    public ResultDetail detail(TnaPrincipal actor,UUID resultId) {
        if(actor==null)throw OrgException.forbidden();if(resultId==null)throw OrgException.notFound();
        boolean administrator=actor.roles().contains("ATTENDANCE_ADMIN");
        return read(actor,actor.employeeId(),()->{
            Supplier<ResultDetail> load=()->{
                var stored=mapper.result(actor.tenantId(),resultId,actor.employeeId(),administrator);
                if(stored==null)throw OrgException.notFound();
                var person=employee(actor.tenantId(),stored.employeeId());
                var current=input(actor.tenantId(),person,stored.workDate());
                var result=storedResult(stored);
                var latest=mapper.latest(actor.tenantId(),stored.employeeId(),stored.workDate(),stored.workDate()).getFirst();
                boolean isLatest = stored.id().equals(latest.id());
                boolean employed = employed(person,stored.workDate());
                boolean currentSource = current(current.clock(),current.source(),stored.inputRevision(),stored.reviewRevision(),stored.sourceFingerprint())
                        || (isLatest && employed && !mapper.closedMonths(actor.tenantId(),stored.workDate(),stored.workDate()).isEmpty());
                var run=mapper.run(actor.tenantId(),stored.runId(),stored.employeeId());
                var frozen=frozenEvidence(mapper.resultInput(actor.tenantId(),resultId),stored.employeeId(),stored.workDate());
                return new ResultDetail(storedDay(person,stored,result,currentSource,
                        employed&&calculator.pending(current.day(),LocalDate.now(clock.withZone(SEOUL)),currentSource,result.actualWorkMillis()),
                        current.day().approvedLeaveDays(),currentSource ? result.paidLeaveMillis() : approvedPaidLeaveMillis(current.day()),employed),isLatest,latest.id(),run,
                        frozen.clock(),frozen.policy(),frozen.assessment(),frozen.employment(),frozen.leave(),frozen.pending(),frozen.complete()&&run!=null);
            };
            return administrator?administration.read(actor,load):load.get();
        });
    }

    /** 확인 전인 날 중 일정과 캘린더가 있고, 승인 휴가나 대기 정정이 없는 재직일만 안내한다. */
    public boolean correctionSuggested(TnaPrincipal actor,LocalDate date) {
        if(actor==null)throw OrgException.forbidden();
        return read(actor,actor.employeeId(),()->{var employee=employee(actor.tenantId(),actor.employeeId());
            if (!mapper.closedMonths(actor.tenantId(),date,date).isEmpty()) return false;
            var in = input(actor.tenantId(),employee,date);
            return correctionEligible(in.clock(), in.day(), employed(employee,date), LocalDate.now(clock.withZone(SEOUL)));});
    }

    private boolean correctionEligible(ClockData.DaySnapshot snapshot, DayInput day, boolean employed, LocalDate today) {
        return employed && snapshot.schedule() != null && snapshot.calendarVersionId() != null
                && day.approvedLeaveDays() < 1 && !day.pendingCorrection()
                && calculator.pending(day, today, false, null);
    }

    public List<AttendanceHistory.Row> attendanceHistory(TnaPrincipal actor, AttendanceHistory.Range range) {
        UUID employeeId = target(actor, null);
        return read(actor, employeeId, () -> {
            UUID tenant = actor.tenantId();
            var employee = employee(tenant, employeeId);
            var stored = latestDays(tenant, employee, range.from(), range.to());
            var closedMonths = Set.copyOf(mapper.closedMonths(tenant, range.from(), range.to()));
            var rows = new ArrayList<AttendanceHistory.Row>();
            for (LocalDate date = range.to(); !date.isBefore(range.from()); date = date.minusDays(1)) {
                var in = input(tenant, employee, date);
                var day = in.clock();
                var saved = stored.get(date);
                boolean closed = closedMonths.contains(date.withDayOfMonth(1));
                var view = dayView(employee, in.clock(), in.day(), in.source(), saved, range.today(), closed);
                boolean past = date.isBefore(range.today()) || day.effectiveEvents().stream().anyMatch(event -> event.kind().equals("OUT"));
                boolean suggest = !closed && past && day.confirmation() == null && correctionEligible(day, in.day(), view.employed(), range.today())
                        && !clocks.canConfirm(day);
                rows.add(AttendanceHistory.row(day, range.today(), view.employed(), view.approvedLeaveDays() == 1,
                        in.day().pendingCorrection(), view.pending(), saved != null && !view.current(),
                        view.current() ? view.result().actualWorkMillis() : null, suggest));
            }
            return List.copyOf(rows);
        });
    }

    public List<EmployeeChoice> employees(TnaPrincipal actor) {
        if(actor==null)throw OrgException.forbidden();
        if(actor.roles().contains("ATTENDANCE_ADMIN"))return administration.read(actor,()->mapper.employees(actor.tenantId()));
        return commands.read(actor,()->{var e=employee(actor.tenantId(),actor.employeeId());return List.of(new EmployeeChoice(e.id(),e.employeeNo(),e.displayName()));});
    }
    public List<DayView> days(TnaPrincipal actor,UUID employeeId,LocalDate from,LocalDate to) {
        range(from,to);UUID target=target(actor,employeeId);
        return read(actor,target,()->readDays(actor.tenantId(),employee(actor.tenantId(),target),from,to));
    }
    /** Company reads reuse the same current-source comparison without granting administration commands. */
    public List<DayView> companyDays(TnaPrincipal actor,UUID employeeId,YearMonth month) {
        if(employeeId==null||month==null||month.getYear()<1||month.getYear()>9999)throw OrgException.invalid("대상 사원과 조회 월을 확인해 주세요.");
        return companyAccess.read(actor,()->readMonthDays(actor.tenantId(),employee(actor.tenantId(),employeeId),month.atDay(1),month.atEndOfMonth()).days());
    }

    public record LeaveAttendanceDay(LocalDate date, boolean scheduled, boolean attended, boolean leave,
            boolean unresolved, String source, UUID resultId, long resultRevision) { }

    /** A completed service month uses the same effective schedule, approved leave and result freshness as company results. */
    public List<LeaveAttendanceDay> leaveAttendanceDays(TnaPrincipal actor, UUID employeeId, LocalDate from, LocalDate through) {
        if (from == null || through == null || through.isBefore(from) || ChronoUnit.DAYS.between(from, through) > 33)
            throw OrgException.invalid("월별 발생의 산정 기간을 확인해 주세요.");
        return administration.readShared(actor, () -> {
            var attendance = new ArrayList<LeaveAttendanceDay>();
            readMonthDays(actor.tenantId(), employee(actor.tenantId(), employeeId), from, through, attendance);
            return List.copyOf(attendance);
        });
    }

    public SelectiveSettlement.Overview ownSettlements(TnaPrincipal actor, String rawMonth) {
        var employeeId = target(actor, null);
        return read(actor, employeeId, () -> settlements(actor.tenantId(), employeeId, AttendanceMonthCloseService.month(rawMonth)));
    }

    public SelectiveSettlement.Overview adminSettlements(TnaPrincipal actor, UUID employeeId, String rawMonth) {
        return commands.read(actor, () -> administration.readShared(actor, () -> {
            if (employeeId == null) throw OrgException.invalid("정산을 조회할 사원을 선택해 주세요.");
            return settlements(actor.tenantId(), employeeId, AttendanceMonthCloseService.month(rawMonth));
        }));
    }

    public boolean matchesSettlementDay(TnaPrincipal actor, UUID employeeId, LocalDate date, String period) {
        if (actor == null || employeeId == null || date == null || date.getYear() < 1 || date.getYear() > 9999
                || !("ISO_WEEK".equals(period) || "CALENDAR_MONTH".equals(period))) return false;
        boolean own = employeeId.equals(actor.employeeId());
        if (!own && !actor.roles().contains("ATTENDANCE_ADMIN")) return false;
        Supplier<Boolean> matches = () -> {
            var employee = employees.get(actor.tenantId(), employeeId);
            if (employee == null) return false;
            var today = LocalDate.now(clock.withZone(SEOUL));
            return mapper.settlementDays(actor.tenantId(), employeeId, date, date).stream()
                    .filter(row -> !row.unknownMembership())
                    .map(row -> appliedSettlementDay(employee, row, YearMonth.from(date), today))
                    .anyMatch(day -> day != null && period.equals(day.settlementPeriod()));
        };
        return own ? read(actor, employeeId, matches) : commands.read(actor, () -> administration.readShared(actor, matches));
    }

    private SelectiveSettlement.Overview settlements(UUID tenant, UUID employeeId, YearMonth month) {
        var employee = employee(tenant, employeeId);
        var from = WorktimeCalculator.weekStart(month.atDay(1));
        var through = WorktimeCalculator.weekStart(month.atEndOfMonth()).plusDays(6);
        var today = LocalDate.now(clock.withZone(SEOUL));
        var days = new ArrayList<SelectiveSettlement.Day>();
        for (var row : mapper.settlementDays(tenant, employeeId, from, through)) {
            var day = appliedSettlementDay(employee, row, month, today);
            if (day != null) days.add(day);
        }
        return new SelectiveSettlement.Overview(month, employeeId, employee.employeeNo(), employee.displayName(),
                SelectiveSettlement.periods(month, today, days));
    }

    private SelectiveSettlement.Day appliedSettlementDay(Employee employee, WorktimeMapper.SettlementRow row,
            YearMonth month, LocalDate today) {
        return row.closed() ? closedSettlementDay(row, month) : openSettlementDay(employee, row, today);
    }

    private SelectiveSettlement.Day openSettlementDay(Employee employee, WorktimeMapper.SettlementRow row, LocalDate today) {
        if (!"SELECTIVE".equals(row.workMode()) || !employed(employee, row.workDate())) return null;
        var snapshot = MonthEndReviewQueries.snapshot(clocks, row);
        var leave = MonthEndReviewQueries.list(row.leaveEffects(), WorktimeMapper.LeaveEffect[].class);
        var pending = MonthEndReviewQueries.list(row.pendingCorrections(), String[].class);
        String source = source(snapshot, employee.joinedOn(), employee.leftOn(), row.policyId(), row.assessmentId(),
                leave.stream().map(WorktimeMapper.LeaveEffect::id).toList(), pending);
        boolean matches = row.storedSource() != null && current(snapshot, source, row.storedInput(), row.storedReview(), row.storedSource());
        double leaveDays = WorktimeMapper.LeaveEffect.totalDays(leave);
        double paidLeaveDays = WorktimeMapper.LeaveEffect.paidDays(leave);
        var facts = dayInput(snapshot, false, !pending.isEmpty(), leaveDays, paidLeaveDays);
        var reasons = new HashSet<String>();
        if (matches) reasons.addAll(MonthEndReviewQueries.list(row.reasonCodes(), String[].class));
        else {
            reasons.add(row.storedSource() == null ? "NOT_CALCULATED" : "STALE_RESULT");
            if (!row.workDate().isAfter(today)) reasons.addAll(calculator.day(facts).reasons());
        }
        boolean awaitingClockOut = row.workDate().equals(today) && pending.isEmpty() && leaveDays < 1
                && snapshot.schedule().workday() && "WORKDAY".equals(snapshot.dayKind())
                && snapshot.effectiveEvents().size() <= 1 && snapshot.effectiveEvents().stream().noneMatch(e -> "OUT".equals(e.kind()));
        Action employeeAction = Action.NONE;
        if (awaitingClockOut) {
            reasons.remove("MISSING_CLOCK_IN");
            reasons.remove("MISSING_CLOCK_OUT");
            reasons.add("DAY_IN_PROGRESS");
        } else if (pending.isEmpty() && leaveDays < 1 && snapshot.confirmation() == null && !row.workDate().isAfter(today)) {
            if (clocks.canConfirm(snapshot)) employeeAction = Action.CONFIRM;
            else if (correctionEligible(snapshot, facts, true, today)) employeeAction = Action.CORRECT;
        }
        return settlementDay(snapshot, leaveDays, paidLeaveDays, pending, matches ? row.actualWorkMillis() : null, reasons, employeeAction, false);
    }

    private SelectiveSettlement.Day closedSettlementDay(WorktimeMapper.SettlementRow row, YearMonth month) {
        if (row.savedSelectiveInputs() == null) return null;
        var frozen = frozenEvidence(row.savedSelectiveInputs(), row.employeeId(), row.workDate());
        if (!frozen.complete()) throw new OrgException(503, "마감 시점의 정산 근거를 확인할 수 없습니다.");
        if (row.unknownMembership()) {
            if (month.equals(YearMonth.from(row.workDate())) || "ISO_WEEK".equals(frozen.clock().schedule().settlementPeriod())) {
                throw new OrgException(503, YearMonth.from(row.workDate()) + " 마감 당시 재직일을 확인할 수 없습니다. 근태 담당자가 해당 월을 재개해 다시 마감해 주세요.");
            }
            return null;
        }
        var employment = frozen.employment();
        if (row.workDate().isBefore(employment.joinedOn()) ||
                employment.leftOn() != null && row.workDate().isAfter(employment.leftOn())) return null;
        return settlementDay(frozen.clock(), row.storedLeaveDays(), row.storedPaidLeaveDays(), frozen.pending(), row.actualWorkMillis(),
                Set.copyOf(MonthEndReviewQueries.list(row.reasonCodes(), String[].class)), Action.NONE, true);
    }

    private static SelectiveSettlement.Day settlementDay(ClockData.DaySnapshot snapshot, double leave, double paidLeave, List<String> pending,
            Long actual, Set<String> reasons, Action employeeAction, boolean closed) {
        var schedule = snapshot.schedule();
        Integer target = null;
        if (snapshot.calendarVersionId() != null && !"UNCONFIRMED".equals(snapshot.dayKind())) {
            target = schedule.workday() && "WORKDAY".equals(snapshot.dayKind()) ? schedule.standardMinutes() : Integer.valueOf(0);
        }
        if (Integer.valueOf(0).equals(target) && snapshot.effectiveEvents().isEmpty() && pending.isEmpty() && leave == 0) return null;
        UUID requestId = pending.isEmpty() ? null : UUID.fromString(pending.getFirst());
        return new SelectiveSettlement.Day(snapshot.workDate(), schedule.settlementPeriod(), target, paidLeave, actual,
                requestId, reasons, employeeAction, closed);
    }
    public MonthView month(TnaPrincipal actor,UUID employeeId,YearMonth month) {
        if(month==null)throw OrgException.invalid("조회 월을 입력해 주세요.");UUID target=target(actor,employeeId);
        return read(actor,target,()->{
            Employee employee=employee(actor.tenantId(),target);LocalDate from=month.atDay(1),to=month.atEndOfMonth();
            LocalDate expandedFrom=WorktimeCalculator.weekStart(from),expandedTo=WorktimeCalculator.weekStart(to).plusDays(6);
            var monthDays=readMonthDays(actor.tenantId(),employee,expandedFrom,expandedTo);
            var selected=monthDays.days().stream().filter(d->!d.workDate().isBefore(from)&&!d.workDate().isAfter(to)).toList();
            var weeks=weeklyPeriods(expandedFrom,expandedTo,monthDays.days(),monthDays.reviewedWeekDates()::contains);
            var result=calculator.month(month,selected.stream().map(this::currentResult).toList());
            return new MonthView(month,List.copyOf(selected),List.copyOf(weeks),new PeriodView(from,to.plusDays(1),
                    selected.stream().allMatch(DayView::current),result,ids(selected),pendingDays(selected)));
        });
    }
    public UUID recalculate(TnaPrincipal actor,UUID employeeId,LocalDate from,LocalDate to,UUID commandId,String reason) {
        range(from,to);UUID target=target(actor,employeeId);String checked=requiredText(reason,500,"재집계 사유"),action="WORKTIME_RECALCULATE";
        return administration.execute(actor,commandId,action,"CALCULATION_RUN",fingerprint(action,target,from,to,checked),checked,()->
                calculate(actor,employee(actor.tenantId(),target),from,to));
    }
    /**
     * 퇴근 기록(자동 확인) 또는 본인 근무 확인 직후 그날이 속한 주(월~일)를 계산한다. 주 40시간 초과는 7일이 모두 현재 결과여야 나오므로 기록 없는 휴무일도 함께 계산한다.
     * 같은 퇴근·확인 명령을 다시 보내도 계산은 한 번이다. 퇴근·확인 트랜잭션이 커밋된 뒤 별도로 호출한다.
     */
    public UUID calculateConfirmedDay(TnaPrincipal actor,LocalDate workDate,UUID confirmationCommandId) {
        if(actor==null)throw OrgException.forbidden();
        if(workDate==null||confirmationCommandId==null)throw OrgException.invalid("계산할 근무일을 확인해 주세요.");
        String action="WORKTIME_AUTO_CALCULATE";
        UUID commandId=UUID.nameUUIDFromBytes((action+":"+confirmationCommandId).getBytes(StandardCharsets.UTF_8));
        return commands.execute(actor,commandId,action,"CALCULATION_RUN",fingerprint(action,actor.employeeId(),workDate),"퇴근·근무 확인 후 자동 계산","EMPLOYEE",
                ()->calculate(actor,employee(actor.tenantId(),actor.employeeId()),workDate,workDate));
    }
    private Change calculate(TnaPrincipal actor,Employee employee,LocalDate from,LocalDate to) {
        var weeks=CalculationWindow.weeks(from,to);
        var window=weeks.openDates(from,to,mapper.closedMonths(actor.tenantId(),weeks.start(),weeks.end()));
        LocalDate start=window.start(),end=window.end();
        UUID target=employee.id(),runId=UUID.randomUUID();
        changed(mapper.insertRun(actor.tenantId(),runId,target,actor.accountId(),from,to,start,end));int count=0;
        for(LocalDate date=start;!date.isAfter(end);date=date.plusDays(1)) {
            storeResult(actor.tenantId(),runId,employee,date);
            count++;
        }
        changed(mapper.completeRun(actor.tenantId(),runId));
        return new Change(runId,null,Map.of("employeeId",target,"from",from,"to",to,"expandedFrom",start,"expandedTo",end,"dayCount",count,"ruleVersion","CALC-2"));
    }
    private void storeResult(UUID tenant,UUID runId,Employee employee,LocalDate date) {
        UUID target=employee.id();
        Input input=input(tenant,employee,date);DayResult result=calculator.day(input.day(),input.context());
        UUID resultId=UUID.randomUUID();long revision=mapper.nextResult(tenant,target,date);
        var refs=new LinkedHashMap<String,Object>();refs.put("clock",input.clock());refs.put("policy",input.policy());
        refs.put("assessment",input.assessment());refs.put("leaveEffects",input.leave());refs.put("pendingCorrections",input.pending());
        refs.put("paidLeaveDays",input.day().paidLeaveDays());
        var employment=new LinkedHashMap<String,Object>();employment.put("joinedOn",employee.joinedOn().toString());
        employment.put("leftOn",employee.leftOn()==null?null:employee.leftOn().toString());refs.put("employment",employment);
        changed(mapper.insertResult(tenant,resultId,runId,target,revision,result,input.source(),
                input.policy()==null?null:input.policy().id(),input.assessment()==null?null:input.assessment().id(),
                json.writeValueAsString(result.reasons().stream().sorted().toList()),json.writeValueAsString(List.of(refs))));
        changed(mapper.calculated(tenant,target,date,result.inputRevision(),result.reviewRevision()));
        var day = input.clock();
        // 결근 판정의 실근로 0은 퇴근 보완을 해소하지 않는다(무근로 해소는 휴일·종일 휴가 근거가 있을 때만).
        if (result.actualWorkMillis() != null && result.attendance().absence() == null && !input.day().pendingCorrection()
                && !day.events().equals(day.effectiveEvents())) {
            mapper.settleCheckoutFollowUp(tenant,target,date,resultId);
        }
    }
    /**
     * 사람 감사 없이 지정 날짜만 계산한다(주 확장 없음, 마감 월 제외). 호출하는 쪽이 회사 트랜잭션을 연다.
     * requestedBy가 null이면 시스템 실행. 이미 현재이고 그날이 끝난 뒤 계산된 CALC-2 결과는 건너뛴다.
     */
    public int calculateDatesAsSystem(UUID tenant,UUID requestedBy,UUID employeeId,java.util.Collection<LocalDate> dates) {
        // 결과 개정 번호를 같은 회사의 다른 쓰기와 겹치지 않게 배타 잠금으로 직렬화한다.
        mapper.lockForWrite(tenant);
        Employee employee=employee(tenant,employeeId);
        LocalDate today=LocalDate.now(clock.withZone(SEOUL));
        var targets=new java.util.TreeSet<LocalDate>();
        for(LocalDate d:dates) if(d!=null&&!d.isAfter(today)&&employed(employee,d)) targets.add(d);
        if(targets.isEmpty())return 0;
        var closed=Set.copyOf(mapper.closedMonths(tenant,targets.first(),targets.last()));
        targets.removeIf(d->closed.contains(d.withDayOfMonth(1)));
        if(targets.isEmpty())return 0;
        var stored=latestDays(tenant,employee,targets.first(),targets.last());
        var todo=new ArrayList<LocalDate>();
        for(LocalDate d:targets) {
            var in=input(tenant,employee,d);var saved=stored.get(d);
            Instant dayEnd=d.plusDays(1).atStartOfDay(SEOUL).toInstant();
            // CALC-1 결과나 날이 끝나기 전에 계산된 결과는 결근 판정이 빠져 있으므로 다시 계산한다.
            boolean fresh=saved!=null&&"CALC-2".equals(saved.ruleVersion())
                    &&current(in.clock(),in.source(),saved.inputRevision(),saved.reviewRevision(),saved.sourceFingerprint())
                    &&(d.equals(today)||!saved.calculatedAt().isBefore(dayEnd));
            if(!fresh)todo.add(d);
        }
        if(todo.isEmpty())return 0;
        UUID runId=UUID.randomUUID();
        changed(mapper.insertRun(tenant,runId,employee.id(),requestedBy,todo.getFirst(),todo.getLast(),todo.getFirst(),todo.getLast()));
        for(LocalDate d:todo) storeResult(tenant,runId,employee,d);
        changed(mapper.completeRun(tenant,runId));
        return todo.size();
    }
    /** 결재 처리 직후 대상 사원의 재집계 필요 날짜를 계산한다. 실패해도 결재는 유지되고 그날은 '재집계 필요'로 남는다. */
    public void recalculateAfterDecision(TnaPrincipal actor,UUID requestId) {
        if(actor==null||requestId==null)return;
        try {
            transactions.execute(actor.tenantId(),()->{
                UUID target=mapper.requestTarget(actor.tenantId(),requestId);
                if(target==null)return 0;
                return calculateDatesAsSystem(actor.tenantId(),actor.accountId(),target,
                        mapper.recalculationDates(actor.tenantId(),target,LocalDate.now(clock.withZone(SEOUL))));
            });
        } catch(RuntimeException failed) {
            LOG.warn("event=worktime_decision_recalculate outcome=failed cause={}",failed.getClass().getSimpleName());
        }
    }
    public UUID reviewPolicy(TnaPrincipal actor,PolicyForm form) {
        if(form==null)throw OrgException.invalid("회사 검토 정보를 입력해 주세요.");period(form.from(),form.to());
        String basis=requiredText(form.basis(),500,"주 기산점 검토 근거"),reason=requiredText(form.reason(),500,"등록 사유"),action="WORK_POLICY_REVIEW";
        return administration.execute(actor,form.commandId(),action,"WORK_POLICY",fingerprint(action,form.from(),form.to(),form.mondayWeekReviewed(),basis,reason),reason,()->{
            mapper.requireOpen(actor.tenantId(),form.from(),form.to());
            UUID id=UUID.randomUUID();int revision=mapper.nextPolicy(actor.tenantId());
            changed(mapper.insertPolicy(actor.tenantId(),id,revision,form.from(),exclusive(form.to()),form.mondayWeekReviewed(),basis,actor.accountId()));
            return new Change(id,null,Map.of("revision",revision,"mondayWeekReviewed",form.mondayWeekReviewed(),"basis",basis));
        });
    }
    public UUID assess(TnaPrincipal actor,AssessmentForm form) {
        if(form==null||form.employeeId()==null)throw OrgException.invalid("대상 사원을 선택해 주세요.");period(form.from(),form.to());
        String basis=requiredText(form.basis(),500,"일반 규정 검토 근거"),reason=requiredText(form.reason(),500,"등록 사유"),action="EMPLOYMENT_RULE_ASSESS";
        return administration.execute(actor,form.commandId(),action,"RULE_ASSESSMENT",fingerprint(action,form.employeeId(),form.from(),form.to(),form.generalRuleReviewed(),basis,reason),reason,()->{
            employee(actor.tenantId(),form.employeeId());
            mapper.requireOpen(actor.tenantId(),form.from(),form.to());
            UUID id=UUID.randomUUID();int revision=mapper.nextAssessment(actor.tenantId(),form.employeeId());
            changed(mapper.insertAssessment(actor.tenantId(),id,form.employeeId(),revision,form.from(),exclusive(form.to()),form.generalRuleReviewed(),basis,actor.accountId()));
            return new Change(id,null,Map.of("employeeId",form.employeeId(),"revision",revision,"generalRuleReviewed",form.generalRuleReviewed(),"basis",basis));
        });
    }
    public List<PolicyReview> policies(TnaPrincipal actor) {return administration.read(actor,()->mapper.policies(actor.tenantId()));}
    public List<AssessmentReview> assessments(TnaPrincipal actor,UUID employeeId) {return administration.read(actor,()->{employee(actor.tenantId(),employeeId);return mapper.assessments(actor.tenantId(),employeeId);});}
    public UUID prepareExport(TnaPrincipal actor,UUID employeeId,LocalDate from,LocalDate to,UUID commandId,String reason) {
        range(from,to);UUID target=target(actor,employeeId);String checked=requiredText(reason,500,"출력 사유"),action="REVIEW_EXPORT_PREPARE";
        return administration.execute(actor,commandId,action,"REPORT_SNAPSHOT",fingerprint(action,target,from,to,checked),checked,()->{
            var employee=employee(actor.tenantId(),target);
            LocalDate weeklyFrom=WorktimeCalculator.weekStart(from),weeklyTo=WorktimeCalculator.weekStart(to).plusDays(6);
            var all=readDays(actor.tenantId(),employee,weeklyFrom,weeklyTo);
            var days=all.stream().filter(d->!d.workDate().isBefore(from)&&!d.workDate().isAfter(to)).toList();
            var weeks=weeklyPeriods(weeklyFrom,weeklyTo,all,date -> {
                var policy=mapper.policy(actor.tenantId(),date);
                return policy!=null && policy.mondayWeekReviewed();
            });
            String company=mapper.companyName(actor.tenantId());if(company==null)throw OrgException.notFound();
            Instant at=clock.instant().truncatedTo(ChronoUnit.MILLIS);UUID id=UUID.randomUUID();
            var rows=new ArrayList<List<Object>>();
            rows.add(row("근태 검토용 REVIEW / 급여·마감·법률 확정 자료 아님"));
            rows.add(row("고객사",company,"고객사 ID",actor.tenantId().toString()));
            rows.add(row("조회 시작일",from.toString(),"조회 종료일",to.toString(),"사원 ID",target.toString()));
            rows.add(row("스냅샷 ID",id.toString()));
            rows.add(row("생성 시각",EXPORT_INSTANT.format(at),"시간대","Asia/Seoul","규칙","CALC-2"));
            rows.add(row("생성 당시 스냅샷이며 이후 재집계·원천 변경을 포함하지 않습니다."));
            rows.add(row("사번","이름","근무일","결과 상태","생성 당시 최신성","소정(ms)","체류(ms)","확인 실근로(ms)","확인 휴게(ms)",
                    "일정 밖(ms)","일 8시간 초과 참고(ms)","야간(ms)","휴일(ms)","법정 최종 연장(ms)","승인 휴가(일)","사유 코드","결과 ID","실행 ID","결과 개정","사실 개정","검토 개정","계산 시각",
                    "소속","일정 버전 ID","휴일 버전 ID","정책 버전 ID","적용 검토 ID","유효 출근","유효 퇴근","확인 휴게 구간",
                    "소정(HH:mm:ss.SSS)","체류(HH:mm:ss.SSS)","확인 실근로(HH:mm:ss.SSS)","확인 휴게(HH:mm:ss.SSS)",
                    "일정 밖(HH:mm:ss.SSS)","일 8시간 초과 참고(HH:mm:ss.SSS)","야간(HH:mm:ss.SSS)","휴일(HH:mm:ss.SSS)","법정 최종 연장(HH:mm:ss.SSS)","유급휴가 인정(ms)"));
            rows.add(row("일자별 입력 근거는 저장된 결과 당시 값입니다. 소속은 출력 시점에 확인한 근무일별 적용 소속입니다."));
            for(var day:days) {
                var evidence=day.id()==null?null:mapper.exportEvidence(actor.tenantId(),day.id());
                var saved=evidence==null||evidence.clockJson()==null?null:json.readValue(evidence.clockJson(),ClockData.DaySnapshot.class);
                String freshness = day.current() ? "작성 시점 현재" : "오래됨 · 재집계 필요";
                if (day.id() == null) freshness = day.current() ? "월 마감 근거로 확인" : "미산정";
                var r=day.result();rows.add(row(day.employeeNo(),day.employeeName(),day.workDate().toString(),r.status().name(),
                        freshness,r.scheduledMillis(),r.presenceMillis(),r.actualWorkMillis(),
                        r.confirmedBreakMillis(),r.outsideScheduleMillis(),r.over8hMillis(),r.nightWorkMillis(),r.holidayWorkMillis(),r.legalOvertimeMillis(),r.leaveDays(),
                        String.join(";",r.reasons().stream().sorted().toList()),day.id()==null?null:day.id().toString(),day.runId()==null?null:day.runId().toString(),
                        day.resultRevision(),r.inputRevision(),r.reviewRevision(),day.calculatedAt()==null?null:EXPORT_INSTANT.format(day.calculatedAt()),
                        mapper.organizationAt(actor.tenantId(),target,day.workDate()),saved==null||saved.scheduleVersionId()==null?null:saved.scheduleVersionId().toString(),
                        saved==null||saved.calendarVersionId()==null?null:saved.calendarVersionId().toString(),evidence==null?null:evidence.policyVersionId(),evidence==null?null:evidence.assessmentId(),
                        exportedEvents(saved,"IN"),exportedEvents(saved,"OUT"),exportedBreaks(saved),
                        humanDuration(r.scheduledMillis()),humanDuration(r.presenceMillis()),humanDuration(r.actualWorkMillis()),humanDuration(r.confirmedBreakMillis()),
                        humanDuration(r.outsideScheduleMillis()),humanDuration(r.over8hMillis()),humanDuration(r.nightWorkMillis()),humanDuration(r.holidayWorkMillis()),humanDuration(r.legalOvertimeMillis()),r.paidLeaveMillis()));
            }
            long sum=days.stream().filter(DayView::current).map(DayView::result).filter(r->r.actualWorkMillis()!=null).mapToLong(DayResult::actualWorkMillis).sum();
            long unresolved=days.stream().filter(d->!d.current()||d.result().actualWorkMillis()==null).count();
            rows.add(row("작성 시점 현재 확인 부분합(ms)",sum,"미확정·오래됨 일수",unresolved,"작성 시점 현재 확인 부분합(HH:mm:ss.SSS)",humanDuration(sum)));
            rows.add(row("주간 참고량은 선택 기간 밖의 같은 주 전체 일자를 포함합니다. 월요일 주 기산점·일반 적용과 일 결과가 모두 확인되어야 주40시간 참고값을 표시합니다."));
            rows.add(row("주 시작일","주 종료일 (포함)","주 확인 실근로 부분합(ms)","주 미확정 일수","주 40시간 초과 참고(ms)","주 상태","생성 당시 주 최신성","주 사유 코드","주 확인 실근로 부분합(HH:mm:ss.SSS)","주 40시간 초과 참고(HH:mm:ss.SSS)"));
            for(var week:weeks) {
                var result=week.result();rows.add(row(week.from().toString(),week.to().minusDays(1).toString(),result.confirmedWorkMillis(),
                        result.unconfirmedDays(),result.over40hMillis(),result.status().name(),week.current()?"작성 시점 현재":"미산정·재집계 필요",
                        String.join(";",result.reasons().stream().sorted().toList()),humanDuration(result.confirmedWorkMillis()),humanDuration(result.over40hMillis())));
            }
            var refs=all.stream().map(d->{var ref=new LinkedHashMap<String,Object>();ref.put("workDate",d.workDate());ref.put("resultId",d.id());
                    ref.put("inputRevision",d.result().inputRevision());ref.put("reviewRevision",d.result().reviewRevision());ref.put("current",d.current());ref.put("sourceFingerprint",d.sourceFingerprint());return ref;}).toList();
            changed(mapper.insertSnapshot(actor.tenantId(),id,actor.accountId(),target,from,to,at,
                    json.writeValueAsString(Map.of("employeeId",target,"from",from,"to",to,"weeklyFrom",weeklyFrom,"weeklyTo",weeklyTo,"purpose","REVIEW")),json.writeValueAsString(refs),ReviewWorkbook.create(rows)));
            return new Change(id,null,Map.of("employeeId",target,"from",from,"to",to,"purpose","REVIEW","resultCount",days.size()));
        });
    }
    public byte[] download(TnaPrincipal actor,UUID snapshotId) {
        var workbook=new AtomicReference<byte[]>();String action="REVIEW_EXPORT_DOWNLOAD";
        administration.execute(actor,UUID.randomUUID(),action,"REPORT_SNAPSHOT",fingerprint(action,snapshotId),"검토용 Excel 다운로드",()->{
            var snapshot=mapper.snapshot(actor.tenantId(),snapshotId);
            if(snapshot==null||!snapshot.requestedBy().equals(actor.accountId()))throw OrgException.notFound();
            workbook.set(snapshot.workbook().clone());return new Change(snapshotId,null,Map.of("purpose","REVIEW","asOf",snapshot.asOf()));
        });return workbook.get();
    }
    public AuditPage audits(TnaPrincipal actor,UUID before,UUID after) {
        if(before!=null&&after!=null)throw new OrgException(400,"이전·다음 커서를 동시에 지정할 수 없습니다.");
        var result=new AtomicReference<AuditPage>();String action="AUDIT_VIEW";
        administration.execute(actor,UUID.randomUUID(),action,"TENANT",fingerprint(action,before,after),"감사 목록 조회",()->{
            UUID cursorId=before!=null?before:after;
            AuditRow cursor=cursorId==null?null:mapper.auditCursor(actor.tenantId(),cursorId);
            if(cursorId!=null&&cursor==null)throw OrgException.notFound();
            var found=after==null?mapper.auditOlder(actor.tenantId(),cursor==null?null:cursor.at(),cursorId)
                    :mapper.auditNewer(actor.tenantId(),cursor.at(),cursorId);
            var rows=new ArrayList<>(found.subList(0,Math.min(50,found.size())));
            if(after!=null)java.util.Collections.reverse(rows);
            UUID previous=null,next=null;
            if(!rows.isEmpty()){
                var first=rows.getFirst();var last=rows.getLast();
                boolean newer=after!=null?found.size()>50:mapper.auditHasNewer(actor.tenantId(),first.at(),first.id());
                boolean older=after==null?found.size()>50:mapper.auditHasOlder(actor.tenantId(),last.at(),last.id());
                if(newer)previous=first.id();if(older)next=last.id();
            }
            result.set(new AuditPage(List.copyOf(rows),previous,next));
            return new Change(actor.tenantId(),null,Map.of("returnedCount",rows.size()));
        });return result.get();
    }
    public AuditDetail auditDetail(TnaPrincipal actor,UUID id) {
        if(id==null)throw OrgException.notFound();
        var result=new AtomicReference<AuditDetail>();String action="AUDIT_DETAIL_VIEW";
        administration.execute(actor,UUID.randomUUID(),action,"AUDIT_EVENT",fingerprint(action,id),"감사 상세 조회",()->{
            var row=mapper.auditDetailRow(actor.tenantId(),id);if(row==null)throw OrgException.notFound();
            result.set(new AuditDetail(row.id(),row.commandId(),row.requestTraceId(),row.actorAccountId(),row.action(),
                    row.targetKind(),row.targetId(),row.reason(),row.at(),AuditSummary.project(row.beforeJson()),AuditSummary.project(row.afterJson())));
            return new Change(id,null,Map.of("auditedEventId",id));
        });return result.get();
    }
    public List<AuditRow> audits(TnaPrincipal actor) {
        var rows=new AtomicReference<List<AuditRow>>();String action="AUDIT_VIEW";
        administration.execute(actor,UUID.randomUUID(),action,"TENANT",fingerprint(action,actor==null?null:actor.tenantId()),"감사 목록 조회",()->{
            rows.set(List.copyOf(mapper.audits(actor.tenantId())));return new Change(actor.tenantId(),null,Map.of("returnedCount",rows.get().size()));
        });return rows.get();
    }
    private static String humanDuration(Long millis) {
        if(millis==null)return null;
        return String.format(Locale.ROOT,"%02d:%02d:%02d.%03d",millis/3_600_000,millis/60_000%60,millis/1_000%60,millis%1_000);
    }
    private static String exportedEvents(ClockData.DaySnapshot saved,String kind) {
        if(saved==null)return null;
        var events=saved.effectiveEvents()==null?saved.events():saved.effectiveEvents();
        var selected=events.stream().filter(e->e.kind().equals(kind)).map(e->EXPORT_INSTANT.format(e.occurredAt())).toList();
        return selected.isEmpty()?null:String.join("; ",selected);
    }
    private static String exportedBreaks(ClockData.DaySnapshot saved) {
        if(saved==null||saved.confirmation()==null)return null;
        var breaks=saved.confirmation().breaks();
        if(breaks.isEmpty())return "확인된 휴게 없음";
        return String.join("; ",breaks.stream().map(b->EXPORT_INSTANT.format(b.startAt())+" / "+EXPORT_INSTANT.format(b.endAt())).toList());
    }
    private Map<LocalDate, WorktimeMapper.ResultRow> latestDays(UUID tenant, Employee employee, LocalDate from, LocalDate to) {
        var stored = new HashMap<LocalDate, WorktimeMapper.ResultRow>();
        for (var row : mapper.latest(tenant, employee.id(), from, to)) stored.put(row.workDate(), row);
        return stored;
    }
    private List<DayView> readDays(UUID tenant,Employee employee,LocalDate from,LocalDate to) {
        var stored = latestDays(tenant, employee, from, to);
        var closedMonths = Set.copyOf(mapper.closedMonths(tenant, from, to));
        LocalDate today=LocalDate.now(clock.withZone(SEOUL));
        var days=new ArrayList<DayView>();
        for(LocalDate date=from;!date.isAfter(to);date=date.plusDays(1)) {
            var current = input(tenant, employee, date);
            days.add(dayView(employee, current.clock(), current.day(), current.source(), stored.get(date), today,
                    closedMonths.contains(date.withDayOfMonth(1))));
        }return List.copyOf(days);
    }
    private record MonthDays(List<DayView> days, Set<LocalDate> reviewedWeekDates) { }
    private MonthDays readMonthDays(UUID tenant, Employee employee, LocalDate from, LocalDate through) {
        return readMonthDays(tenant, employee, from, through, null);
    }
    private MonthDays readMonthDays(UUID tenant, Employee employee, LocalDate from, LocalDate through,
            List<LeaveAttendanceDay> attendance) {
        var stored = latestDays(tenant, employee, from, through);
        var closed = Set.copyOf(mapper.closedMonths(tenant, from, through));
        var days = new ArrayList<DayView>();
        var reviewedWeekDates = new HashSet<LocalDate>();
        var today = LocalDate.now(clock.withZone(SEOUL));
        for (var row : mapper.monthDays(tenant, employee.id(), from, through)) {
            var snapshot = MonthEndReviewQueries.snapshot(clocks, row);
            var effects = MonthEndReviewQueries.list(row.leaveEffects(), WorktimeMapper.LeaveEffect[].class);
            var pending = MonthEndReviewQueries.list(row.pendingCorrections(), String[].class);
            var date = row.workDate();
            var facts = dayInput(snapshot, row.generalRuleReviewed() && employed(employee, date), !pending.isEmpty(),
                    WorktimeMapper.LeaveEffect.totalDays(effects), WorktimeMapper.LeaveEffect.paidDays(effects));
            var source = source(snapshot, employee.joinedOn(), employee.leftOn(), row.policyId(), row.assessmentId(),
                    effects.stream().map(WorktimeMapper.LeaveEffect::id).toList(), pending);
            var day = dayView(employee, snapshot, facts, source, stored.get(date), today, closed.contains(date.withDayOfMonth(1)));
            days.add(day);
            if (attendance != null) {
                boolean known = snapshot.schedule() != null && snapshot.calendarVersionId() != null
                        && !"UNCONFIRMED".equals(snapshot.dayKind());
                boolean scheduled = known && snapshot.schedule().workday() && "WORKDAY".equals(snapshot.dayKind());
                boolean attended = scheduled && day.current() && pending.isEmpty()
                        && day.result().actualWorkMillis() != null && day.result().actualWorkMillis() > 0;
                boolean unresolved = !known || scheduled && (!pending.isEmpty() || !attended && day.approvedLeaveDays() < 1);
                attendance.add(new LeaveAttendanceDay(date, scheduled, attended, scheduled && day.approvedLeaveDays() > 0,
                        unresolved, source, day.id(), day.resultRevision()));
            }
            if (row.mondayWeekReviewed()) reviewedWeekDates.add(date);
        }
        return new MonthDays(List.copyOf(days), Set.copyOf(reviewedWeekDates));
    }
    private DayView dayView(Employee employee, ClockData.DaySnapshot snapshot, DayInput facts, String source,
            WorktimeMapper.ResultRow stored, LocalDate today, boolean closed) {
        LocalDate date = snapshot.workDate();
        double leave = facts.approvedLeaveDays();
        boolean employed = employed(employee, date);
        if (stored == null) {
            if (closed && employed && !facts.pendingCorrection()) {
                var confirmed = calculator.day(facts);
                if (Long.valueOf(0).equals(confirmed.actualWorkMillis())) {
                    return new DayView(null, null, employee.id(), employee.employeeNo(), employee.displayName(), date, 0, "CALC-2", null,
                            source, true, confirmed, false, leave, confirmed.paidLeaveMillis(), true);
                }
            }
            Long paid = approvedPaidLeaveMillis(facts);
            var missing = new DayResult(date, Status.REVIEW_REQUIRED, null, null, null, null, null, null, null, null, null, leave, paid,
                    snapshot.inputRevision(), snapshot.reviewRevision(), Set.of("NOT_CALCULATED", "LEGAL_OVERTIME_UNDETERMINED"));
            return new DayView(null, null, employee.id(), employee.employeeNo(), employee.displayName(), date, 0, "CALC-2", null, source, false, missing,
                    employed && calculator.pending(facts, today, false, null), leave, paid, employed);
        }
        var result = storedResult(stored);
        boolean matches = (closed && employed) || current(snapshot, source, stored.inputRevision(), stored.reviewRevision(), stored.sourceFingerprint());
        return storedDay(employee, stored, result, matches, employed && calculator.pending(facts, today, matches, result.actualWorkMillis()),
                leave, matches ? result.paidLeaveMillis() : approvedPaidLeaveMillis(facts), employed);
    }
    private Long approvedPaidLeaveMillis(DayInput day) {
        if (day.paidLeaveDays() == 0) return 0L;
        return calculator.day(day).paidLeaveMillis();
    }
    private static boolean employed(Employee employee,LocalDate date) {return !date.isBefore(employee.joinedOn())&&(employee.leftOn()==null||!date.isAfter(employee.leftOn()));}
    private DayResult storedResult(WorktimeMapper.ResultRow r) {
        return new DayResult(r.workDate(),Status.valueOf(r.status()),r.scheduledMillis(),r.presenceMillis(),r.actualWorkMillis(),r.confirmedBreakMillis(),
                r.outsideScheduleMillis(),r.over8hMillis(),r.nightWorkMillis(),r.holidayWorkMillis(),r.legalOvertimeMillis(),r.leaveDays(),
                WorktimeCalculator.paidLeaveMillis(r.savedDayKind()==null?null:DayKind.valueOf(r.savedDayKind()),r.scheduledMillis(),r.paidLeaveDays()),r.inputRevision(),r.reviewRevision(),
                Set.copyOf(Arrays.asList(json.readValue(r.reasonCodes(),String[].class))),
                new Attendance(r.lateMillis(),r.earlyLeaveMillis(),r.absence()));
    }
    private static DayView storedDay(Employee employee,WorktimeMapper.ResultRow r,DayResult result,boolean current,boolean pending,double approvedLeaveDays,Long approvedPaidLeaveMillis,boolean employed) {
        return new DayView(r.id(),r.runId(),employee.id(),employee.employeeNo(),employee.displayName(),r.workDate(),r.resultRevision(),r.ruleVersion(),
                r.calculatedAt(),r.sourceFingerprint(),current,result,pending,approvedLeaveDays,approvedPaidLeaveMillis,employed);
    }
    private FrozenEvidence frozenEvidence(String value,UUID employeeId,LocalDate date) {
        try {
            var refs=json.readTree(value);
            if(!refs.isArray()||refs.size()!=1||!refs.get(0).isObject())return FrozenEvidence.missing();
            var body=refs.get(0);
            if(!body.has("policy")||!body.has("assessment")||body.get("leaveEffects")==null||!body.get("leaveEffects").isArray()
                    ||body.get("pendingCorrections")==null||!body.get("pendingCorrections").isArray())return FrozenEvidence.missing();
            var clockNode=body.get("clock");var employmentNode=body.get("employment");
            if(clockNode==null||clockNode.isNull()||employmentNode==null||employmentNode.isNull())return FrozenEvidence.missing();
            var saved=json.readValue(clockNode.toString(),ClockData.DaySnapshot.class);
            var employment=json.readValue(employmentNode.toString(),EmploymentEvidence.class);
            if(!employeeId.equals(saved.employeeId())||!date.equals(saved.workDate())||employment.joinedOn()==null
                    ||saved.events()==null||saved.effectiveEvents()==null
                    ||saved.confirmation()!=null&&saved.confirmation().breaks()==null
                    ||saved.schedule()!=null&&saved.schedule().breaks()==null)return FrozenEvidence.missing();
            PolicyEvidence policy=null;AssessmentEvidence assessment=null;
            if(body.get("policy")!=null&&!body.get("policy").isNull()) {
                var p=json.readValue(body.get("policy").toString(),PolicyReview.class);
                if(p.id()==null||p.revision()<1||p.validFrom()==null||date.isBefore(p.validFrom())
                        ||p.validTo()!=null&&!date.isBefore(p.validTo()))return FrozenEvidence.missing();
                policy=new PolicyEvidence(p.id(),p.revision(),p.validFrom(),p.validTo(),p.mondayWeekReviewed());
            }
            if(body.get("assessment")!=null&&!body.get("assessment").isNull()) {
                var a=json.readValue(body.get("assessment").toString(),AssessmentReview.class);
                if(a.id()==null||a.revision()<1||a.validFrom()==null||!employeeId.equals(a.employeeId())||date.isBefore(a.validFrom())
                        ||a.validTo()!=null&&!date.isBefore(a.validTo()))return FrozenEvidence.missing();
                assessment=new AssessmentEvidence(a.id(),a.revision(),a.validFrom(),a.validTo(),a.generalRuleReviewed());
            }
            var leave=referenceIds(body.get("leaveEffects"));var pending=referenceIds(body.get("pendingCorrections"));
            return new FrozenEvidence(saved,policy,assessment,employment,leave,pending,true);
        }catch(tools.jackson.core.JacksonException|IllegalArgumentException e){return FrozenEvidence.missing();}
    }
    private List<String> referenceIds(tools.jackson.databind.JsonNode node) {
        if(node==null||node.isNull())return List.of();
        return Arrays.stream(json.readValue(node.toString(),String[].class)).map(UUID::fromString).map(UUID::toString).toList();
    }
    private record FrozenEvidence(ClockData.DaySnapshot clock,PolicyEvidence policy,AssessmentEvidence assessment,
            EmploymentEvidence employment,List<String> leave,List<String> pending,boolean complete) {
        static FrozenEvidence missing(){return new FrozenEvidence(null,null,null,null,List.of(),List.of(),false);}
    }
    private Input input(UUID tenant,Employee employee,LocalDate date) {
        var snapshot=clocks.snapshot(tenant,employee.id(),date);var policy=mapper.policy(tenant,date);var assessment=mapper.assessment(tenant,employee.id(),date);
        var effects=mapper.leaveEffects(tenant,employee.id(),date);
        var leave=effects.stream().map(WorktimeMapper.LeaveEffect::id).toList();
        var pending=List.copyOf(mapper.pendingCorrections(tenant,employee.id(),date));
        var day=dayInput(snapshot,assessment!=null&&assessment.generalRuleReviewed()&&employed(employee,date),
                !pending.isEmpty(),WorktimeMapper.LeaveEffect.totalDays(effects),WorktimeMapper.LeaveEffect.paidDays(effects));
        String source=source(snapshot,employee.joinedOn(),employee.leftOn(),policy==null?null:policy.id(),
                assessment==null?null:assessment.id(),leave,pending);
        var context=new AttendanceContext(date.isBefore(LocalDate.now(clock.withZone(SEOUL))),WorktimeMapper.LeaveEffect.part(effects));
        return new Input(snapshot,policy,assessment,leave,pending,day,source,context);
    }

    static String source(ClockData.DaySnapshot snapshot,LocalDate joinedOn,LocalDate leftOn,UUID policyId,
            UUID assessmentId,List<String> leave,List<String> pending) {
        return fingerprint(snapshot.sourceFingerprint(),snapshot.inputRevision(),snapshot.reviewRevision(),
                joinedOn,leftOn,policyId,assessmentId,leave,pending);
    }

    static boolean current(ClockData.DaySnapshot snapshot,String source,long storedInput,long storedReview,String storedSource) {
        return WorktimeCalculator.current(storedInput,storedReview,snapshot.inputRevision(),snapshot.reviewRevision())
                && source.equals(storedSource);
    }

    static DayInput dayInput(ClockData.DaySnapshot snapshot,boolean reviewed,boolean pending,double leaveDays) {
        return dayInput(snapshot,reviewed,pending,leaveDays,leaveDays);
    }

    static DayInput dayInput(ClockData.DaySnapshot snapshot,boolean reviewed,boolean pending,double leaveDays,double paidLeaveDays) {
        LocalDate date=snapshot.workDate();
        var events=snapshot.effectiveEvents().stream().map(e->new ClockEvent(e.id(),EventKind.valueOf(e.kind()),e.occurredAt())).toList();
        List<Schedule> schedules=List.of();
        if(snapshot.schedule()!=null) {
            var s=snapshot.schedule();schedules=List.of(new Schedule(s.workday(),s.startTime()==null?null:date.atTime(s.startTime()).atZone(SEOUL).toInstant(),
                    s.endTime()==null?null:date.atTime(s.endTime()).atZone(SEOUL).toInstant(),s.breaks().stream().map(b->new Interval(
                    date.atTime(b.startTime()).atZone(SEOUL).toInstant(),date.atTime(b.endTime()).atZone(SEOUL).toInstant())).toList(),
                    "SELECTIVE".equals(s.workMode()), s.standardMinutes(), s.coreStart()==null?null:new Interval(
                    date.atTime(s.coreStart()).atZone(SEOUL).toInstant(),date.atTime(s.coreEnd()).atZone(SEOUL).toInstant())));
        }
        Confirmation confirmation=null;
        if(snapshot.confirmation()!=null) {
            var c=snapshot.confirmation();confirmation=new Confirmation(c.confirmedInputRevision(),c.reviewRevision(),c.presenceAsWork(),
                    c.breaks().stream().map(b->new Interval(b.startAt(),b.endAt())).toList());
        }
        return new DayInput(date,events,schedules,DayKind.valueOf(snapshot.dayKind()),reviewed,snapshot.inputRevision(),
                snapshot.reviewRevision(),confirmation,pending,leaveDays,paidLeaveDays);
    }
    private List<PeriodView> weeklyPeriods(LocalDate expandedFrom,LocalDate expandedTo,List<DayView> all,Predicate<LocalDate> mondayReviewed) {
        var weeks=new ArrayList<PeriodView>();
        for(LocalDate start=expandedFrom;!start.isAfter(expandedTo);start=start.plusDays(7)) {
            LocalDate begin=start,end=start.plusDays(7);
            var week=all.stream().filter(d->!d.workDate().isBefore(begin)&&d.workDate().isBefore(end)).toList();
            boolean basis=week.size()==7 && week.stream().allMatch(day -> mondayReviewed.test(day.workDate()));
            var result=calculator.week(begin,week.stream().map(this::currentResult).toList(),basis);
            weeks.add(new PeriodView(begin,end,week.stream().allMatch(DayView::current),result,ids(week),pendingDays(week)));
        }
        return List.copyOf(weeks);
    }
    private DayResult currentResult(DayView day) {
        if(day.current())return day.result();var r=day.result();var reasons=new java.util.HashSet<>(r.reasons());reasons.add(day.id()==null?"NOT_CALCULATED":"STALE_RESULT");
        return new DayResult(r.workDate(),Status.REVIEW_REQUIRED,r.scheduledMillis(),r.presenceMillis(),null,null,null,null,null,null,null,
                day.approvedLeaveDays(),day.approvedPaidLeaveMillis(),r.inputRevision(),r.reviewRevision(),Set.copyOf(reasons));
    }
    private <T>T read(TnaPrincipal actor,UUID target,Supplier<T> action) {
        boolean own=target.equals(actor.employeeId());
        Supplier<T> locked=()->{
            mapper.lock(actor.tenantId());
            // Authorization may have changed while waiting for the same tenant's administration lock.
            return own?commands.read(actor,action):administration.read(actor,action);
        };
        return own?commands.read(actor,locked):administration.read(actor,locked);
    }
    private UUID target(TnaPrincipal actor,UUID id) {if(actor==null)throw OrgException.forbidden();return id==null?actor.employeeId():id;}
    private Employee employee(UUID tenant,UUID id) {var employee=employees.get(tenant,id);if(employee==null)throw OrgException.notFound();return employee;}
    private static List<UUID> ids(List<DayView> days) {return days.stream().map(DayView::id).filter(java.util.Objects::nonNull).toList();}
    private static int pendingDays(List<DayView> days) {return (int)days.stream().filter(DayView::pending).count();}
    private static List<Object> row(Object... values) {return Arrays.asList(values);}
    private static void range(LocalDate from,LocalDate to) {if(from==null||to==null||from.isAfter(to)||ChronoUnit.DAYS.between(from,to)>=31||to.equals(LocalDate.MAX))throw OrgException.invalid("31일 이내의 올바른 조회 기간을 입력해 주세요.");}
    private static void period(LocalDate from,LocalDate to) {if(from==null||to!=null&&(to.isBefore(from)||to.equals(LocalDate.MAX)))throw OrgException.invalid("검토 유효기간을 확인해 주세요.");}
    private static LocalDate exclusive(LocalDate to) {return to==null?null:to.plusDays(1);}
    private static void changed(int count) {if(count!=1)throw OrgException.conflict("입력 또는 결과가 변경되었습니다. 다시 집계해 주세요.");}
    private record Input(ClockData.DaySnapshot clock,PolicyReview policy,AssessmentReview assessment,List<String> leave,
                         List<String> pending,DayInput day,String source,AttendanceContext context) { }
}
