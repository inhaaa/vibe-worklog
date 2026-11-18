package kr.tna.workspace;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;
import kr.tna.clock.ClockData.DaySnapshot;
import kr.tna.clock.ClockService;
import kr.tna.common.auth.TnaPrincipal;
import kr.tna.common.tenant.CompanyReadAccess;
import kr.tna.org.OrgCommands;
import kr.tna.org.OrgException;
import kr.tna.worktime.WorktimeCalculator;
import kr.tna.worktime.WorktimeMapper;
import kr.tna.worktime.WorktimeService;
import kr.tna.workspace.ProfileCompanyData.CheckoutCandidate;
import kr.tna.workspace.ProfileCompanyData.CheckoutOperations;
import kr.tna.workspace.ProfileCompanyData.CheckoutRow;
import org.springframework.stereotype.Service;

@Service
public final class CheckoutOperationsQueries {
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final Pattern DATE = Pattern.compile("[0-9]{4}-[0-9]{2}-[0-9]{2}");
    private static final int PAGE_SIZE = 50;
    private static final List<String> STATUSES = List.of("MISSING", "PENDING", "CORRECTED");
    private final CompanyReadAccess company;
    private final OrgCommands administration;
    private final ProfileCompanyMapper mapper;
    private final ClockService clocks;
    private final WorktimeService worktime;
    private final WorktimeMapper worktimeMapper;
    private final Clock clock;

    public CheckoutOperationsQueries(CompanyReadAccess company, OrgCommands administration, ProfileCompanyMapper mapper,
            ClockService clocks, WorktimeService worktime, WorktimeMapper worktimeMapper, Clock clock) {
        this.company = company;
        this.administration = administration;
        this.mapper = mapper;
        this.clocks = clocks;
        this.worktime = worktime;
        this.worktimeMapper = worktimeMapper;
        this.clock = clock;
    }

    public CheckoutOperations read(TnaPrincipal actor, String requestedDate, UUID organizationId, String q,
            String status, int page) {
        if (actor == null || !actor.roles().contains("ATTENDANCE_ADMIN")) throw OrgException.forbidden();
        String query = q == null ? "" : q.strip();
        return company.read(actor, () -> administration.read(actor,
                () -> checkout(actor, requestedDate, organizationId, query, status, page)));
    }

    private CheckoutOperations checkout(TnaPrincipal actor, String requestedDate, UUID organizationId, String q,
            String status, int page) {
        UUID tenantId = actor.tenantId();
        if (organizationId != null && !mapper.organizationExists(tenantId, organizationId)) throw OrgException.notFound();
        var options = List.copyOf(mapper.organizations(tenantId));
        LocalDate latest = LocalDate.now(clock.withZone(SEOUL)).minusDays(1);
        LocalDate date = latest;
        boolean validStatus = status != null && ("ALL".equals(status) || STATUSES.contains(status));
        boolean validPage = page >= 0 && page <= 100_000;
        try {
            date = date(requestedDate, latest);
            if (!validStatus) throw OrgException.invalid("올바른 정정 상태를 선택해 주세요.");
            if (!validPage) throw OrgException.invalid("올바른 조회 페이지를 선택해 주세요.");
            if (q.length() > 100) throw OrgException.invalid("검색어는 100자 이내로 입력해 주세요.");
        } catch (OrgException invalid) {
            return new CheckoutOperations(date, latest, organizationId, options, q, validStatus ? status : "ALL",
                    validPage ? page : 0, false, false, List.of(), invalid.getMessage());
        }

        LocalDate selected = date;
        var visible = mapper.checkoutCandidates(tenantId, date, organizationId, q).stream()
                .map(candidate -> checkoutCase(tenantId, selected, candidate))
                .filter(Objects::nonNull)
                .filter(row -> status.equals("ALL") || status.equals(row.recordState()))
                .sorted(Comparator.comparingInt(row -> STATUSES.indexOf(row.recordState())))
                .skip((long) page * PAGE_SIZE)
                .limit(PAGE_SIZE + 1L)
                .toList();
        var rows = visible.stream().limit(PAGE_SIZE).map(row -> row(actor, row)).toList();
        return new CheckoutOperations(date, latest, organizationId, options, q, status, page, page > 0,
                visible.size() > PAGE_SIZE, rows, null);
    }

    private CheckoutCase checkoutCase(UUID tenantId, LocalDate date, CheckoutCandidate employee) {
        var day = clocks.snapshot(tenantId, employee.employeeId(), date);
        if (day.schedule() == null || !day.schedule().workday() || day.calendarVersionId() == null
                || !"WORKDAY".equals(day.dayKind())) return null;

        var source = day.events();
        if (source.size() != 1 || !"IN".equals(source.getFirst().kind())
                || !date.equals(source.getFirst().occurredAt().atZone(SEOUL).toLocalDate())) return null;
        var in = day.effectiveEvents().stream().filter(event -> "IN".equals(event.kind())).toList();
        var out = day.effectiveEvents().stream().filter(event -> "OUT".equals(event.kind())).toList();
        if (in.size() != 1 || out.size() > 1) return null;
        Instant effectiveIn = in.getFirst().occurredAt();
        Instant effectiveOut = out.isEmpty() ? null : out.getFirst().occurredAt();
        if (!date.equals(effectiveIn.atZone(SEOUL).toLocalDate())
                || effectiveOut != null && (!effectiveIn.isBefore(effectiveOut)
                        || !date.equals(effectiveOut.atZone(SEOUL).toLocalDate()))) return null;
        if (WorktimeMapper.LeaveEffect.totalDays(worktimeMapper.leaveEffects(tenantId, employee.employeeId(), date)) == 1) return null;

        String requestState = employee.recentRequestState();
        String recordState = "PENDING".equals(requestState) ? "PENDING" : effectiveOut != null ? "CORRECTED" : "MISSING";
        UUID requestId = employee.recentRequestId();
        if ("CORRECTED".equals(recordState)) {
            requestId = employee.effectiveRequestId();
            requestState = "APPROVED";
        }
        return new CheckoutCase(employee, day, source.getFirst().occurredAt(), effectiveIn, effectiveOut,
                requestId, requestState, recordState);
    }

    private CheckoutRow row(TnaPrincipal actor, CheckoutCase entry) {
        var employee = entry.employee();
        var day = entry.day();
        Result result = result(actor, entry);
        return new CheckoutRow(employee.employeeId(), employee.employeeNo(), employee.employeeName(),
                Objects.requireNonNullElse(employee.organizationName(), "소속 미등록"), day.workDate(),
                day.schedule().endTime(), entry.sourceIn(), null, entry.effectiveIn(), entry.effectiveOut(),
                entry.requestId(), entry.requestState(), entry.recordState(), result.state(), result.actualWorkMillis(),
                result.calculatedAt(), result.reason());
    }

    private Result result(TnaPrincipal actor, CheckoutCase entry) {
        var day = entry.day();
        if (day.confirmation() == null) {
            String reason = entry.effectiveOut() == null ? "유효한 퇴근 기록이 필요합니다."
                    : clocks.canConfirm(day) ? "직원의 실제 근로·휴게 확인이 필요합니다."
                    : "유효 기록과 일정·휴게 조건을 확인해 주세요.";
            return new Result("WORK_CONFIRMATION_REQUIRED", null, null, reason);
        }
        var work = worktime.days(actor, entry.employee().employeeId(), day.workDate(), day.workDate()).getFirst();
        if (!work.current()) return new Result("RECALCULATION_REQUIRED", null, null, null);
        Long actual = work.result().actualWorkMillis();
        if (work.result().status() == WorktimeCalculator.Status.REVIEW_REQUIRED || actual == null) {
            String reason = work.result().reasons().contains("PENDING_CORRECTION")
                    ? "대기 중인 정정이 있습니다." : "집계 상세에서 확인 사유를 확인해 주세요.";
            return new Result("REVIEW_REQUIRED", actual, work.calculatedAt(), reason);
        }
        return new Result("CURRENT", actual, work.calculatedAt(), null);
    }

    private static LocalDate date(String requested, LocalDate latest) {
        if (requested == null) return latest;
        if (!DATE.matcher(requested).matches()) throw OrgException.invalid("올바른 근무일을 선택해 주세요.");
        try {
            LocalDate parsed = LocalDate.parse(requested);
            if (parsed.getYear() < 1) throw OrgException.invalid("올바른 근무일을 선택해 주세요.");
            if (parsed.isAfter(latest)) throw OrgException.invalid("어제까지의 근무일을 선택해 주세요.");
            return parsed;
        } catch (DateTimeParseException invalid) {
            throw OrgException.invalid("올바른 근무일을 선택해 주세요.");
        }
    }

    private record CheckoutCase(CheckoutCandidate employee, DaySnapshot day, Instant sourceIn, Instant effectiveIn,
            Instant effectiveOut, UUID requestId, String requestState, String recordState) { }

    private record Result(String state, Long actualWorkMillis, Instant calculatedAt, String reason) { }
}
