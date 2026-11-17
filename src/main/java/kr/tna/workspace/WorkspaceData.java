package kr.tna.workspace;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import kr.tna.clock.ClockData.DaySnapshot;
import kr.tna.org.EmployeeOverview;
import kr.tna.request.RequestData.RequestView;

public final class WorkspaceData {
    private WorkspaceData() { }

    public record RequestItem(RequestView request, String kindLabel, String statusLabel,
            String tone, boolean effectiveLeave) { }

    public record TimelineEntry(LocalTime time, String title, String detail, String kind) { }

    public record DayView(DaySnapshot snapshot, List<RequestItem> requests, boolean today, boolean selected) {
        public List<TimelineEntry> timeline() {
            var entries = new ArrayList<TimelineEntry>();
            var schedule = snapshot.schedule();
            if (schedule != null && schedule.workday()) {
                addPlanned(entries, schedule.startTime(), "예정 근무 시작");
                addPlanned(entries, schedule.endTime(), "예정 근무 종료");
                for (var pause : schedule.breaks()) {
                    addPlanned(entries, pause.startTime(), "예정 휴게 시작");
                    addPlanned(entries, pause.endTime(), "예정 휴게 종료");
                }
            }
            for (var event : snapshot.effectiveEvents()) {
                String title = switch (event.kind()) {
                    case "IN" -> "현재 반영 출근";
                    case "OUT" -> "현재 반영 퇴근";
                    default -> "현재 반영 기록";
                };
                entries.add(new TimelineEntry(event.occurredAt().atZone(ZoneId.of("Asia/Seoul")).toLocalTime(),
                        title, "원천 또는 승인 정정의 현재 반영 기록", "recorded"));
            }
            entries.sort(Comparator.comparing(TimelineEntry::time));
            return List.copyOf(entries);
        }

        private static void addPlanned(List<TimelineEntry> entries, LocalTime time, String title) {
            if (time != null) entries.add(new TimelineEntry(time, title,
                    "예정 일정 · 실제 근로·휴게 확인과 구분", "planned"));
        }

        public String scheduleText() {
            var schedule = snapshot.schedule();
            if (schedule == null) return "근무 일정 미배정";
            if (!schedule.workday()) return "소정 비근로일";
            var format = DateTimeFormatter.ofPattern("HH:mm");
            return schedule.startTime() == null || schedule.endTime() == null ? "근무 시각 미확인"
                    : format.format(schedule.startTime()) + " ~ " + format.format(schedule.endTime());
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
