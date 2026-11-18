package kr.tna.workspace;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;
import kr.tna.clock.ClockData.Event;
import kr.tna.workspace.WorkspaceData.DayView;
import kr.tna.workspace.WorkspaceData.HomeView;
import kr.tna.workspace.WorkspaceData.RequestItem;

/** Home needs display summaries. Detailed evidence and reasons stay on their own pages. */
public record WorkspaceHomeData(Employee employee, Today today, List<WeekDay> week,
        Double availableLeaveDays, long pendingRequests, List<RecentRequest> recentRequests) {
    public record Employee(String employeeName) { }
    public record DateLabel(LocalDate workDate) { }
    public record LeaveStatus(boolean effectiveLeave, String leavePart) { }
    public record TimelineEntry(LocalTime time, String title, String kind) { }
    public record Today(DateLabel snapshot, String scheduleText, String calendarText, String confirmationText,
            Instant inRecordedAt, Instant outRecordedAt, List<TimelineEntry> timeline, List<LeaveStatus> requests) { }
    public record WeekDay(DateLabel snapshot, boolean today, String scheduleText, String calendarText,
            List<LeaveStatus> requests) { }
    public record RequestPeriod(UUID id, LocalDate fromDate, LocalDate toDate) { }
    public record RecentRequest(RequestPeriod request, String kindLabel, String statusLabel, String tone) { }

    static WorkspaceHomeData from(HomeView view) {
        var day = view.today();
        var timeline = day.timeline().stream().map(entry -> new TimelineEntry(entry.time(), entry.title(), entry.kind())).toList();
        var today = new Today(new DateLabel(day.snapshot().workDate()), day.scheduleText(), day.calendarText(),
                day.confirmationText(), recordedAt(day, "IN"), recordedAt(day, "OUT"), timeline, effectiveLeave(day));
        var week = view.week().stream().map(item -> new WeekDay(new DateLabel(item.snapshot().workDate()), item.today(),
                item.scheduleText(), item.calendarText(), effectiveLeave(item))).toList();
        var recent = view.recentRequests().stream().map(item -> {
            var request = item.request();
            return new RecentRequest(new RequestPeriod(request.id(), request.fromDate(), request.toDate()),
                    item.kindLabel(), item.statusLabel(), item.tone());
        }).toList();
        return new WorkspaceHomeData(new Employee(view.employee().employeeName()), today, week,
                view.availableLeaveDays(), view.pendingRequests(), recent);
    }

    /** Raw clock record before corrections: the home clock buttons follow what the employee pressed. */
    private static Instant recordedAt(DayView day, String kind) {
        return day.snapshot().events().stream().filter(event -> kind.equals(event.kind()))
                .map(Event::occurredAt).findFirst().orElse(null);
    }

    private static List<LeaveStatus> effectiveLeave(DayView day) {
        return day.requests().stream().filter(RequestItem::effectiveLeave)
                .map(item -> new LeaveStatus(true, item.request().leavePart())).toList();
    }
}
