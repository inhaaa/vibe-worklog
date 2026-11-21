package kr.tna.worktime;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import kr.tna.common.auth.TnaPrincipal;
import kr.tna.common.tenant.BusinessCommands;
import kr.tna.org.OrgCommands;
import kr.tna.org.OrgException;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

@Service
public final class AttendanceMonthCloseService {
    public record CheckForm(String month) { }
    public record Command(String month, String action, Long expectedVersion, String reason, UUID commandId) { }
    public record Status(String month, String status, long version, boolean pastMonth,
            List<AttendanceMonthCloseMapper.Event> history, boolean hasMoreHistory) { }
    public record Blockers(String month, String kind, int page, long total, boolean hasNext,
            List<AttendanceMonthCloseMapper.BlockerRow> rows) { }
    public record Receipt(String month, String status, long version, Instant changedAt, String actorName, String reason) { }
    public record Check(String month, String status, long version, boolean pastMonth, long employeeCount,
            long reviewDays, long uncalculatedDays, long staleDays, long unconfirmedDays, long pendingCorrectionDays,
            long pendingRequests, long openFollowUps,
            @JsonInclude(JsonInclude.Include.NON_NULL) AttendanceMonthCloseMapper.FollowUpDay outOfEmploymentFollowUp) {
        @JsonProperty("canClose")
        public boolean canClose() { return blockingReason() == null; }

        private String blockingReason() {
            if (!pastMonth) return "서울 기준 지난달까지 마감할 수 있습니다.";
            if ("CLOSED".equals(status)) return "이미 마감된 월입니다.";
            if (employeeCount == 0) return "해당 월에 재직한 사원이 없어 마감할 수 없습니다.";
            if (reviewDays > 0 || pendingRequests > 0 || openFollowUps > 0) {
                return "월말 확인 대상, 결재 대기 신청과 미해소 보완 요청을 모두 처리한 뒤 다시 점검해 주세요.";
            }
            return null;
        }
    }

    private static final int BLOCKER_PAGE_SIZE = 50;
    private static final int HISTORY_LIMIT = 20;
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final BusinessCommands commands;
    private final OrgCommands administration;
    private final AttendanceMonthCloseMapper mapper;
    private final MonthEndReviewQueries reviews;

    public AttendanceMonthCloseService(BusinessCommands commands, OrgCommands administration,
            AttendanceMonthCloseMapper mapper, MonthEndReviewQueries reviews) {
        this.commands = commands;
        this.administration = administration;
        this.mapper = mapper;
        this.reviews = reviews;
    }

    public Status status(TnaPrincipal actor, String rawMonth) {
        return read(actor, () -> {
            var month = month(rawMonth);
            var history = mapper.history(actor.tenantId(), month.atDay(1), HISTORY_LIMIT + 1);
            return new Status(month.toString(), status(history), version(history), month.isBefore(YearMonth.from(administration.today())),
                    List.copyOf(history.subList(0, Math.min(HISTORY_LIMIT, history.size()))), history.size() > HISTORY_LIMIT);
        });
    }

    public Blockers blockers(TnaPrincipal actor, String rawMonth, String kind, int page) {
        return read(actor, () -> {
            var month = month(rawMonth);
            if (!"PENDING_REQUEST".equals(kind) && !"OPEN_FOLLOW_UP".equals(kind)) {
                throw OrgException.invalid("조회할 마감 점검 항목을 선택해 주세요.");
            }
            if (page < 0 || page > 100_000) throw OrgException.invalid("조회 페이지를 확인해 주세요.");
            UUID tenant = actor.tenantId();
            LocalDate from = month.atDay(1), through = month.atEndOfMonth();
            int offset = page * BLOCKER_PAGE_SIZE;
            boolean pending = "PENDING_REQUEST".equals(kind);
            var totals = mapper.totals(tenant, from, through);
            long total = pending ? totals.pendingRequests() : totals.openFollowUps();
            List<AttendanceMonthCloseMapper.BlockerRow> rows = List.of();
            if (offset < total) {
                rows = pending ? mapper.pendingRequests(tenant, from, through, BLOCKER_PAGE_SIZE, offset)
                        : mapper.openFollowUps(tenant, from, through, BLOCKER_PAGE_SIZE, offset);
            }
            return new Blockers(month.toString(), kind, page, total, offset + BLOCKER_PAGE_SIZE < total, rows);
        });
    }

    public Check check(TnaPrincipal actor, CheckForm form) {
        return read(actor, () -> {
            var month = month(form == null ? null : form.month());
            return checkLocked(actor.tenantId(), month, mapper.history(actor.tenantId(), month.atDay(1), 1), null);
        });
    }

    public Receipt change(TnaPrincipal actor, Command form) {
        if (actor == null || !actor.roles().containsAll(List.of("EMPLOYEE", "ATTENDANCE_ADMIN"))) throw OrgException.forbidden();
        if (form == null || (!"CLOSE".equals(form.action()) && !"REOPEN".equals(form.action()))) {
            throw OrgException.invalid("마감 또는 재개를 선택해 주세요.");
        }
        var month = month(form.month());
        if (form.expectedVersion() == null || form.expectedVersion() < 0) throw OrgException.invalid("현재 버전을 확인해 주세요.");
        String reason = OrgCommands.requiredText(form.reason(), 500, "처리 사유");
        boolean closing = "CLOSE".equals(form.action());
        String action = "ATTENDANCE_MONTH_" + form.action();
        UUID eventId = commands.execute(actor, form.commandId(), action, "ATTENDANCE_MONTH",
                OrgCommands.fingerprint(action, month, form.expectedVersion(), reason), reason, "ATTENDANCE_ADMIN", () -> {
            var history = mapper.history(actor.tenantId(), month.atDay(1), 1);
            long previousVersion = version(history);
            OrgCommands.checkVersion(form.expectedVersion(), previousVersion);
            String previousStatus = status(history);
            String paidLeaveSnapshot = null;
            if (closing) {
                if ("CLOSED".equals(previousStatus)) throw OrgException.conflict("이미 마감된 월입니다.");
                var paidDays = new ArrayList<MonthEndReviewQueries.PaidLeaveDay>();
                String blocker = checkLocked(actor.tenantId(), month, history, paidDays).blockingReason();
                if (blocker != null) throw OrgException.conflict(blocker);
                paidLeaveSnapshot = JSON.writeValueAsString(paidDays);
            } else if (!"CLOSED".equals(previousStatus)) {
                throw OrgException.conflict("마감된 월만 재개할 수 있습니다.");
            }
            UUID id = UUID.randomUUID();
            String next = closing ? "CLOSED" : "OPEN";
            if (mapper.append(actor.tenantId(), id, month.atDay(1), previousVersion + 1, next,
                    reason, actor.accountId(), actor.employeeId(), paidLeaveSnapshot) != 1) throw OrgException.forbidden();
            return new OrgCommands.Change(id, Map.of("month", month, "status", previousStatus, "version", previousVersion),
                    Map.of("month", month, "status", next, "version", previousVersion + 1));
        });
        return read(actor, () -> {
            var event = mapper.receipt(actor.tenantId(), eventId, month.atDay(1));
            if (event == null) throw new OrgException(503, "처리 이력을 조회할 수 없습니다. 같은 명령으로 다시 확인해 주세요.");
            return new Receipt(month.toString(), event.status(), event.version(), event.changedAt(), event.actorName(), event.reason());
        });
    }

    private Check checkLocked(UUID tenantId, YearMonth month, List<AttendanceMonthCloseMapper.Event> history,
            List<MonthEndReviewQueries.PaidLeaveDay> paidDays) {
        LocalDate today = administration.today();
        boolean past = month.isBefore(YearMonth.from(today));
        LocalDate from = month.atDay(1), end = month.atEndOfMonth();
        var totals = mapper.totals(tenantId, from, end);
        String status = status(history);
        var days = "CLOSED".equals(status) ? MonthEndReviewQueries.Counts.NONE
                : reviews.countAll(tenantId, from, past ? end : today.minusDays(1), paidDays);
        var outOfEmployment = totals.openFollowUps() > 0 ? mapper.outOfEmploymentFollowUp(tenantId, from, end) : null;
        return new Check(month.toString(), status, version(history), past, totals.employeeCount(), days.days(),
                days.uncalculated(), days.stale(), days.unconfirmed(), days.pendingCorrection(), totals.pendingRequests(),
                totals.openFollowUps(), outOfEmployment);
    }

    private <T> T read(TnaPrincipal actor, Supplier<T> action) {
        return commands.read(actor, () -> administration.readShared(actor, action));
    }

    private static String status(List<AttendanceMonthCloseMapper.Event> history) {
        return history.isEmpty() ? "OPEN" : history.getFirst().status();
    }

    private static long version(List<AttendanceMonthCloseMapper.Event> history) {
        return history.isEmpty() ? 0 : history.getFirst().version();
    }

    static YearMonth month(String value) {
        if (value == null || !value.matches("[0-9]{4}-(0[1-9]|1[0-2])") || value.startsWith("0000")) {
            throw OrgException.invalid("조회 월을 YYYY-MM 형식으로 입력해 주세요.");
        }
        return YearMonth.parse(value);
    }
}
