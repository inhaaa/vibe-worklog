package kr.tna.clock;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import kr.tna.clock.ClockData.DaySnapshot;
import kr.tna.clock.ClockData.Event;
import kr.tna.org.OrgException;
import kr.tna.worktime.AttendanceLabel;
import kr.tna.worktime.WorktimeCalculator.Attendance;

public final class AttendanceHistory {
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    private AttendanceHistory() { }

    public record Range(LocalDate today, LocalDate from, LocalDate to) {
        public Range {
            if (today == null || from == null || to == null) throw new OrgException(400, "시작일과 종료일을 입력해 주세요.");
            if (from.isAfter(to)) throw OrgException.invalid("시작일은 종료일보다 늦을 수 없습니다.");
            if (to.isAfter(today)) throw OrgException.invalid("종료일은 오늘보다 늦을 수 없습니다.");
            if (from.isBefore(earliest(to))) throw OrgException.invalid("조회 기간은 최대 3개월입니다.");
        }

        public static Range of(LocalDate today, LocalDate from, LocalDate to) {
            return from == null && to == null ? new Range(today, earliest(today), today) : new Range(today, from, to);
        }

        private static LocalDate earliest(LocalDate end) {
            return end.plusDays(1).minusMonths(3);
        }
    }

    public enum ScheduleKind { WORKDAY, HOLIDAY, NON_WORKDAY, NO_SCHEDULE, CALENDAR_UNKNOWN, APPROVED_LEAVE, OUTSIDE_EMPLOYMENT }
    public enum RecordStatus { NONE, PRESENT, IN_PROGRESS, MISSING, INVALID }
    public enum WorkStatus { AVAILABLE, PENDING, STALE, ABSENT }

    public record Period(LocalDate today, LocalDate from, LocalDate to, boolean missingOnly, List<Row> rows) { }
    public record Row(LocalDate workDate, ScheduleKind scheduleKind, LocalTime startTime, LocalTime endTime,
            Instant inAt, Instant outAt, RecordStatus recordStatus, boolean corrected, boolean pendingCorrection,
            WorkStatus workStatus, Long actualWorkMillis, boolean correctionSuggested, String workMode,
            String attendanceLabel) { }

    public static Row row(DaySnapshot day, LocalDate today, boolean employed, double approvedLeaveDays,
            boolean pendingCorrection, boolean pendingWork, boolean staleWork, Long actualWorkMillis, boolean correctionSuggested,
            Attendance attendance, Instant now) {
        LocalDate date = day.workDate();
        if (!employed) {
            return new Row(date, ScheduleKind.OUTSIDE_EMPLOYMENT, null, null, null, null,
                    RecordStatus.NONE, false, false, WorkStatus.ABSENT, null, false, null, null);
        }
        var schedule = day.schedule();
        var scheduleKind = scheduleKind(day, approvedLeaveDays >= 1);
        var events = day.effectiveEvents();
        Instant in = uniqueTime(events.stream().filter(event -> event.kind().equals("IN")).toList());
        Instant out = uniqueTime(events.stream().filter(event -> event.kind().equals("OUT")).toList());
        boolean invalid = invalid(day);
        RecordStatus recordStatus = recordStatus(day, today, scheduleKind, pendingCorrection);
        var workStatus = actualWorkMillis != null ? WorkStatus.AVAILABLE : pendingWork ? WorkStatus.PENDING
                : staleWork ? WorkStatus.STALE : WorkStatus.ABSENT;
        String label = label(day, today, approvedLeaveDays, pendingCorrection, attendance, now);
        return new Row(date, scheduleKind, schedule == null ? null : schedule.startTime(), schedule == null ? null : schedule.endTime(),
                invalid ? null : in, invalid ? null : out, recordStatus, !events.equals(day.events()), pendingCorrection,
                workStatus, actualWorkMillis, correctionSuggested, schedule == null ? null : schedule.workMode(), label);
    }

    /**
     * 판정 문구. 판정이 없으면 근로일의 누락·이상 기록은 '확인 필요', 오늘 예정 출근 시각이 지났으면 '미출근'이다.
     * 근태 내역과 홈·근무 일정이 같이 쓴다.
     */
    public static String label(DaySnapshot day, LocalDate today, double approvedLeaveDays, boolean pendingCorrection,
            Attendance attendance, Instant now) {
        var scheduleKind = scheduleKind(day, approvedLeaveDays >= 1);
        var recordStatus = recordStatus(day, today, scheduleKind, pendingCorrection);
        String label = AttendanceLabel.of(attendance);
        if (label == null && scheduleKind == ScheduleKind.WORKDAY
                && (recordStatus == RecordStatus.MISSING || recordStatus == RecordStatus.INVALID)) label = "확인 필요";
        // 오늘 퇴근 전에는 기록이 아직 끝나지 않았으므로 '확인 필요' 대신 미출근/표시 없음으로 둔다.
        if ("확인 필요".equals(label) && day.workDate().equals(today)
                && day.effectiveEvents().stream().noneMatch(event -> event.kind().equals("OUT"))) label = null;
        // ponytail: 반차일(오전·오후 모두)은 미출근을 숨긴다. 반차 기준 출근 시각을 쓰려면 이 경로에 leavePart가 필요하다.
        if (label == null) label = missingClockIn(day, today, approvedLeaveDays > 0, now);
        return label;
    }

    private static boolean invalid(DaySnapshot day) {
        var events = day.effectiveEvents();
        var ins = events.stream().filter(event -> event.kind().equals("IN")).toList();
        var outs = events.stream().filter(event -> event.kind().equals("OUT")).toList();
        Instant in = uniqueTime(ins), out = uniqueTime(outs);
        return ins.size() > 1 || outs.size() > 1 || (in != null && out != null && !in.isBefore(out))
                || events.stream().anyMatch(event -> !event.occurredAt().atZone(SEOUL).toLocalDate().equals(day.workDate()));
    }

    private static RecordStatus recordStatus(DaySnapshot day, LocalDate today, ScheduleKind scheduleKind, boolean pendingCorrection) {
        LocalDate date = day.workDate();
        Instant in = uniqueTime(day.effectiveEvents().stream().filter(event -> event.kind().equals("IN")).toList());
        Instant out = uniqueTime(day.effectiveEvents().stream().filter(event -> event.kind().equals("OUT")).toList());
        if (invalid(day)) return RecordStatus.INVALID;
        if (in != null && out != null) return RecordStatus.PRESENT;
        if (date.equals(today) && in != null) return RecordStatus.IN_PROGRESS;
        if (scheduleKind == ScheduleKind.WORKDAY && !pendingCorrection && (date.isBefore(today) || out != null)) return RecordStatus.MISSING;
        if (out != null) return RecordStatus.INVALID;
        return RecordStatus.NONE;
    }

    /** 오늘 근로일에 예정 출근 시각이 지났는데 출근이 없으면 '미출근'이다. 선택근무는 출근 시각이 정해져 있지 않아 제외한다. */
    private static String missingClockIn(DaySnapshot day, LocalDate today, boolean approvedLeave, Instant now) {
        var schedule = day.schedule();
        boolean missing = day.workDate().equals(today) && scheduleKind(day, approvedLeave) == ScheduleKind.WORKDAY
                && !schedule.selective() && schedule.startTime() != null
                && day.effectiveEvents().stream().noneMatch(event -> event.kind().equals("IN"))
                && now.isAfter(today.atTime(schedule.startTime()).atZone(SEOUL).toInstant());
        return missing ? "미출근" : null;
    }

    private static Instant uniqueTime(List<Event> events) {
        return events.size() == 1 ? events.getFirst().occurredAt() : null;
    }

    private static ScheduleKind scheduleKind(DaySnapshot day, boolean approvedLeave) {
        if (approvedLeave) return ScheduleKind.APPROVED_LEAVE;
        if (day.schedule() == null) return ScheduleKind.NO_SCHEDULE;
        if (day.calendarVersionId() == null || day.dayKind().equals("UNCONFIRMED")) return ScheduleKind.CALENDAR_UNKNOWN;
        if (day.dayKind().equals("HOLIDAY")) return ScheduleKind.HOLIDAY;
        if (day.dayKind().equals("NON_WORKDAY")) return ScheduleKind.NON_WORKDAY;
        return day.schedule().workday() ? ScheduleKind.WORKDAY : ScheduleKind.NON_WORKDAY;
    }
}
