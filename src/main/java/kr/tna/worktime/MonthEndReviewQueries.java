package kr.tna.worktime;

import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import kr.tna.clock.CheckoutOmission;
import kr.tna.clock.ClockData;
import kr.tna.clock.ClockMapper;
import kr.tna.clock.ClockService;
import kr.tna.common.auth.TnaPrincipal;
import kr.tna.common.tenant.CompanyReadAccess;
import kr.tna.org.OrgCommands;
import kr.tna.org.OrgException;
import kr.tna.schedule.ScheduleData;
import kr.tna.workspace.ProfileCompanyData.OrganizationItem;
import kr.tna.workspace.ProfileCompanyMapper;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

@Service
public final class MonthEndReviewQueries {
    public record Page(YearMonth month, YearMonth latestMonth, UUID organizationId, List<OrganizationItem> options,
            String q, String reason, int page, boolean hasPrevious, boolean hasNext, List<Row> rows, String error) { }
    public record Row(UUID employeeId, String employeeNo, String employeeName, String organizationName,
            LocalDate workDate, List<String> reasons, UUID resultId, UUID pendingRequestId,
            boolean checkoutFollowUpEligible, CheckoutFollowUpService.Summary checkoutFollowUp) {
        CheckoutFollowUpService.DayKey dayKey() {
            return new CheckoutFollowUpService.DayKey(employeeId, workDate);
        }

        Row withFollowUp(CheckoutFollowUpService.Summary followUp) {
            return followUp == null ? this : new Row(employeeId, employeeNo, employeeName, organizationName,
                    workDate, reasons, resultId, pendingRequestId, false, followUp);
        }
    }

    private record Review(List<String> reasons, boolean checkoutFollowUpEligible) { }

    private static final int PAGE_SIZE = 50;
    private static final Set<String> REASONS = Set.of("ALL", "UNCALCULATED", "STALE", "UNCONFIRMED", "PENDING_CORRECTION");
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private final CompanyReadAccess company;
    private final OrgCommands administration;
    private final ProfileCompanyMapper organizations;
    private final MonthEndReviewMapper mapper;
    private final ClockService clocks;
    private final Clock clock;
    private final WorktimeCalculator calculator = new WorktimeCalculator();
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final CheckoutFollowUpService followUps;

    public MonthEndReviewQueries(CompanyReadAccess company, OrgCommands administration, ProfileCompanyMapper organizations,
            MonthEndReviewMapper mapper, ClockService clocks, Clock clock, CheckoutFollowUpService followUps) {
        this.company = company;
        this.administration = administration;
        this.organizations = organizations;
        this.mapper = mapper;
        this.clocks = clocks;
        this.clock = clock;
        this.followUps = followUps;
    }

    public Page read(TnaPrincipal actor, String requestedMonth, UUID organizationId, String q, String reason, int page) {
        if (actor == null || !actor.roles().contains("ATTENDANCE_ADMIN")) throw OrgException.forbidden();
        return company.read(actor, () -> administration.read(actor,
                () -> readMonth(actor.tenantId(), requestedMonth, organizationId, Objects.requireNonNullElse(q, "").strip(),
                        Objects.requireNonNullElse(reason, "ALL"), page)));
    }

    private Page readMonth(UUID tenantId, String requestedMonth, UUID organizationId, String q, String reason, int page) {
        if (organizationId != null && !organizations.organizationExists(tenantId, organizationId)) throw OrgException.notFound();
        var options = organizations.organizations(tenantId);
        var today = LocalDate.now(clock.withZone(SEOUL));
        var latest = YearMonth.from(today);
        YearMonth month = latest;
        boolean validPage = page >= 0 && page <= 100_000;
        try {
            if (requestedMonth != null) {
                if (!requestedMonth.matches("[0-9]{4}-(0[1-9]|1[0-2])")) throw OrgException.invalid("올바른 조회 월을 선택해 주세요.");
                var parsed = YearMonth.parse(requestedMonth);
                if (parsed.getYear() == 0 || parsed.isAfter(latest)) throw OrgException.invalid("이번 달까지의 조회 월을 선택해 주세요.");
                month = parsed;
            }
            if (!REASONS.contains(reason)) throw OrgException.invalid("올바른 확인 사유를 선택해 주세요.");
            if (!validPage) throw OrgException.invalid("올바른 조회 페이지를 선택해 주세요.");
            if (q.length() > 100) throw OrgException.invalid("검색어는 100자 이내로 입력해 주세요.");
        } catch (OrgException invalid) {
            return new Page(month, latest, organizationId, options, q, REASONS.contains(reason) ? reason : "ALL",
                    validPage ? page : 0, false, false, List.of(), invalid.getMessage());
        }
        var from = month.atDay(1);
        var through = month.equals(latest) ? today.minusDays(1) : month.atEndOfMonth();
        var rows = new ArrayList<Row>(PAGE_SIZE);
        int skip = page * PAGE_SIZE;
        boolean next = false;
        if (!through.isBefore(from)) {
            try (var candidates = mapper.candidates(tenantId, from, through, organizationId, q, reason)) {
                for (var candidate : candidates) {
                    var review = assess(candidate);
                    var causes = review.reasons();
                    if (causes.isEmpty() || !reason.equals("ALL") && !causes.contains(reason)) continue;
                    if (skip > 0) {
                        skip--;
                    } else if (rows.size() < PAGE_SIZE) {
                        rows.add(new Row(candidate.employeeId(), candidate.employeeNo(), candidate.employeeName(),
                                Objects.requireNonNullElse(candidate.organizationName(), "소속 미등록"),
                                candidate.workDate(), causes, candidate.resultId(), candidate.pendingRequestId(),
                                review.checkoutFollowUpEligible(), null));
                    } else {
                        next = true;
                        break;
                    }
                }
            }
        }
        var active = followUps.openForDays(tenantId, rows.stream().map(Row::dayKey).toList());
        var pageRows = rows.stream().map(row -> row.withFollowUp(active.get(row.dayKey()))).toList();
        return new Page(month, latest, organizationId, options, q, reason, page, page > 0, next, pageRows, null);
    }

    private Review assess(MonthEndReviewMapper.Candidate candidate) {
        var snapshot = snapshot(clocks, candidate);
        var leave = list(candidate.leaveEffects(), WorktimeMapper.LeaveEffect[].class);
        boolean pending = candidate.pendingRequestId() != null;
        double leaveDays = WorktimeMapper.LeaveEffect.totalDays(leave);
        var input = WorktimeService.dayInput(snapshot, candidate.reviewed(), pending, leaveDays);
        boolean unconfirmed = calculator.day(input).actualWorkMillis() == null;
        var reasons = new ArrayList<String>(3);
        if (candidate.resultId() == null) {
            if (unconfirmed || !snapshot.effectiveEvents().isEmpty() || candidate.openCheckoutFollowUp()) reasons.add("UNCALCULATED");
        } else {
            String source = WorktimeService.source(snapshot, candidate.joinedOn(), candidate.leftOn(), candidate.policyId(),
                    candidate.assessmentId(), leave.stream().map(WorktimeMapper.LeaveEffect::id).toList(),
                    pending ? List.of(candidate.pendingRequestId().toString()) : List.of());
            if (!WorktimeService.current(snapshot, source, candidate.storedInput(), candidate.storedReview(), candidate.storedSource())) {
                reasons.add("STALE");
            }
        }
        if (unconfirmed) reasons.add("UNCONFIRMED");
        if (pending) reasons.add("PENDING_CORRECTION");
        return new Review(List.copyOf(reasons), CheckoutOmission.eligible(snapshot, leaveDays, pending));
    }

    static ClockData.DaySnapshot snapshot(ClockService clocks, MonthEndReviewMapper.DayEvidence row) {
        var schedules = row.scheduleVersionId() == null ? List.<ClockMapper.ScheduleRow>of()
                : List.of(new ClockMapper.ScheduleRow(row.scheduleVersionId(), row.workDate().getDayOfWeek().getValue(),
                        row.workday(), row.startTime(), row.endTime()));
        var calendar = row.calendarVersionId() == null ? null : new ClockMapper.CalendarRow(row.calendarVersionId(), row.dayKind());
        var correction = row.correction() == null ? null : JSON.readValue(row.correction(), ClockMapper.CorrectionRow.class);
        var confirmations = list(row.confirmations(), ClockData.Confirmation[].class);
        return clocks.projectSnapshot(row.employeeId(), row.workDate(), list(row.events(), ClockData.Event[].class),
                schedules, list(row.breaks(), ScheduleData.Break[].class), calendar, row.inputRevision(), row.reviewRevision(),
                correction, source -> confirmations.stream().filter(c -> c.sourceFingerprint().equals(source)).findFirst().orElse(null));
    }

    private static <T> List<T> list(String value, Class<T[]> type) {
        return value == null ? List.of() : List.of(JSON.readValue(value, type));
    }
}
