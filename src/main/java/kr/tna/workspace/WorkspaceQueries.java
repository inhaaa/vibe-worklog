package kr.tna.workspace;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.IntStream;
import kr.tna.clock.ClockService;
import kr.tna.common.auth.TnaPrincipal;
import kr.tna.common.tenant.BusinessCommands;
import kr.tna.org.EmployeeMapper;
import kr.tna.org.OrgException;
import kr.tna.request.LeaveWorkflowService;
import kr.tna.request.RequestData.RequestDay;
import kr.tna.request.RequestData.RequestView;
import kr.tna.workspace.WorkspaceData.*;
import org.springframework.stereotype.Service;

/** Presentation-only composition of existing personal reads; no schedule or leave rules are recalculated. */
@Service
public final class WorkspaceQueries {
    private final BusinessCommands commands;
    private final ClockService clocks;
    private final EmployeeMapper employees;
    private final LeaveWorkflowService leave;
    private final Clock clock;
    private final PersonalScheduleMapper schedules;

    public WorkspaceQueries(BusinessCommands commands, ClockService clocks, EmployeeMapper employees,
            LeaveWorkflowService leave, Clock clock, PersonalScheduleMapper schedules) {
        this.schedules = schedules;
        this.commands = commands;
        this.clocks = clocks;
        this.employees = employees;
        this.leave = leave;
        this.clock = clock;
    }

    public HomeView home(TnaPrincipal actor) {
        LocalDate today = today();
        LocalDate monday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        return commands.read(actor, () -> {
            var data = readDays(actor, monday.datesUntil(monday.plusDays(7)).toList(), today, today);
            var employee = employees.findSelf(actor.tenantId(), actor.employeeId());
            if (employee == null) throw OrgException.notFound();
            var buckets = leave.buckets(actor, actor.employeeId());
            Double available = buckets.isEmpty() ? null : buckets.stream().mapToDouble(bucket -> bucket.availableDays()).sum();
            return new HomeView(employee, data.days().stream().filter(day -> day.today()).findFirst().orElseThrow(),
                    data.days(), available, data.requests().stream().filter(row -> "PENDING".equals(row.status())).count(),
                    data.requests().stream().limit(3).map(row -> item(row, null, false)).toList());
        });
    }

    public MonthView calendar(TnaPrincipal actor, YearMonth requestedMonth, LocalDate requestedDate) {
        LocalDate today = today();
        YearMonth month = requestedMonth == null ? YearMonth.from(requestedDate == null ? today : requestedDate) : requestedMonth;
        if (month.getYear() < 1 || month.getYear() > 9999) throw OrgException.invalid("1~9999년의 월을 선택해 주세요.");
        LocalDate selected = requestedDate == null ? (YearMonth.from(today).equals(month) ? today : month.atDay(1)) : requestedDate;
        if (!YearMonth.from(selected).equals(month)) throw OrgException.invalid("선택 날짜는 조회 월 안에 있어야 합니다.");
        return commands.read(actor, () -> {
            var data = readDays(actor, month.atDay(1).datesUntil(month.atEndOfMonth().plusDays(1)).toList(), today, selected);
            var selectedDay = data.days().stream().filter(DayView::selected).findFirst().orElseThrow();
            return new MonthView(month, selected, data.days(),
                    IntStream.range(0, month.atDay(1).getDayOfWeek().getValue() - 1).boxed().toList(),
                    month.equals(YearMonth.of(1, 1)) ? null : month.minusMonths(1),
                    month.equals(YearMonth.of(9999, 12)) ? null : month.plusMonths(1),
                    selectedDay, schedules.day(actor.tenantId(), selectedDay.snapshot().scheduleVersionId(), selected.getDayOfWeek().getValue()));
        });
    }

    private ReadResult readDays(TnaPrincipal actor, List<LocalDate> dates, LocalDate today, LocalDate selected) {
        // snapshot acquires the existing shared tenant lock; this whole composition stays in that transaction.
        var snapshots = dates.stream().map(date -> clocks.snapshot(actor.tenantId(), actor.employeeId(), date)).toList();
        var requests = leave.mine(actor).stream().filter(row -> actor.employeeId().equals(row.targetEmployeeId())).toList();
        Map<UUID, List<RequestDay>> evidence = new HashMap<>();
        var days = snapshots.stream().map(snapshot -> {
            var items = requests.stream().filter(row -> !snapshot.workDate().isBefore(row.fromDate())
                    && !snapshot.workDate().isAfter(row.toDate())).map(row -> {
                if (!"LEAVE_DAY".equals(row.kind()) && !"CANCEL_LEAVE".equals(row.kind())) return item(row, null, false);
                UUID basis = "CANCEL_LEAVE".equals(row.kind()) ? row.originalRequestId() : row.id();
                var rows = basis == null ? List.<RequestDay>of() : evidence.computeIfAbsent(basis,
                        id -> leave.get(actor, id).days());
                var quantity = rows.stream().filter(day -> day.leaveDate().equals(snapshot.workDate()))
                        .findFirst().map(RequestDay::quantityDays).orElse(null);
                return item(row, quantity, true);
            }).toList();
            return new DayView(snapshot, items, snapshot.workDate().equals(today), snapshot.workDate().equals(selected));
        }).toList();
        return new ReadResult(days, requests);
    }

    private static RequestItem item(RequestView row, Double quantity, boolean dated) {
        String kind = switch (row.kind()) {
            case "LEAVE_DAY", "CANCEL_LEAVE" -> leaveName(row);
            case "ATTENDANCE_CORRECTION" -> "정정 신청";
            case "OVERTIME" -> "연장근무 신청";
            case "CANCEL_OVERTIME" -> "연장근무 취소";
            default -> row.kind();
        };
        if (dated && quantity == null) kind += " · 일별 근거 미확인";
        if (dated && quantity != null && quantity == 0) kind += row.companyLeaveName() == null ? " 범위 · 차감 0일" : " 범위 · 사용 0일";
        boolean cancel = "CANCEL_LEAVE".equals(row.kind()) || "CANCEL_OVERTIME".equals(row.kind());
        String status = switch (row.status()) {
            case "PENDING" -> cancel ? "취소 검토 중 · 원승인 유효" : "검토 중";
            case "REJECTED" -> "반려";
            case "WITHDRAWN" -> "철회";
            case "APPROVED" -> cancel ? "취소 승인" : switch (row.effectStatus()) {
                case "EFFECTIVE" -> "ATTENDANCE_CORRECTION".equals(row.kind()) ? "정정 반영" : "승인 유효";
                case "CANCELED" -> "승인 후 취소";
                case "SUPERSEDED" -> "이전 정정 이력";
                default -> "승인 · 효력 확인 필요";
            };
            default -> row.status();
        };
        boolean effective = "LEAVE_DAY".equals(row.kind()) && "APPROVED".equals(row.status())
                && "EFFECTIVE".equals(row.effectStatus()) && (!dated || quantity != null && quantity > 0);
        String tone = "PENDING".equals(row.status()) ? "amber" : "APPROVED".equals(row.status())
                && "EFFECTIVE".equals(row.effectStatus()) ? "green" : "gray";
        return new RequestItem(row, kind, status, tone, effective);
    }

    private static String leaveName(RequestView row) {
        boolean cancel = "CANCEL_LEAVE".equals(row.kind());
        if (row.companyLeaveName() != null) {
            String part = switch (row.leavePart()) {
                case "AM" -> " · 오전";
                case "PM" -> " · 오후";
                default -> "";
            };
            return row.companyLeaveName() + part + (cancel ? " 취소" : "");
        }
        String label = switch (row.leavePart()) {
            case "AM" -> "오전 반차";
            case "PM" -> "오후 반차";
            default -> cancel ? "연차" : "종일 연차";
        };
        return cancel ? label + " 취소" : label;
    }

    private LocalDate today() { return LocalDate.now(clock.withZone(ZoneId.of("Asia/Seoul"))); }
    private record ReadResult(List<DayView> days, List<RequestView> requests) { }
}
