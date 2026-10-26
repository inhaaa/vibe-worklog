package kr.tna.workspace;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import kr.tna.clock.ClockData.DaySnapshot;
import kr.tna.org.EmployeeOverview;
import kr.tna.request.RequestData.RequestView;

public final class WorkspaceData {
    private WorkspaceData() { }

    public record RequestItem(RequestView request, String kindLabel, String statusLabel,
            String tone, boolean effectiveLeave) { }

    public record DayView(DaySnapshot snapshot, List<RequestItem> requests, boolean today, boolean selected) {
        public String scheduleText() {
            var schedule = snapshot.schedule();
            if (schedule == null) return "근무 일정 미배정";
            if (!schedule.workday()) return "소정 비근로일";
            var format = DateTimeFormatter.ofPattern("HH:mm");
            return schedule.startTime() == null || schedule.endTime() == null ? "근무 시각 미확인"
                    : format.format(schedule.startTime()) + "–" + format.format(schedule.endTime());
        }
        public String calendarText() {
            return switch (snapshot.dayKind()) {
                case "WORKDAY" -> "확인된 근로일";
                case "NON_WORKDAY" -> "확인된 비근로일";
                case "HOLIDAY" -> "확인된 휴일";
                default -> "캘린더 미확인";
            };
        }
        public String confirmationText() {
            return snapshot.confirmation() == null ? "실제 근로 미확인" : "현재 사실의 근로·휴게 확인됨";
        }
        public String inTime() { return eventTime("IN"); }
        public String outTime() { return eventTime("OUT"); }
        private String eventTime(String kind) {
            var matching = snapshot.effectiveEvents().stream().filter(event -> kind.equals(event.kind())).toList();
            if (matching.isEmpty()) return "미기록";
            if (matching.size() != 1) return "여러 기록 · 확인 필요";
            return DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.of("Asia/Seoul"))
                    .format(matching.getFirst().occurredAt());
        }
    }

    public record HomeView(EmployeeOverview employee, DayView today, List<DayView> week,
            Long availableLeaveDays, long pendingRequests, List<RequestItem> recentRequests) { }

    public record MonthView(YearMonth month, LocalDate selectedDate, List<DayView> days,
            List<Integer> leadingBlanks, YearMonth previousMonth, YearMonth nextMonth, DayView selectedDay) { }
}
