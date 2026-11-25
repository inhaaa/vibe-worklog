package kr.tna.worktime;

import kr.tna.worktime.WorktimeCalculator.Attendance;

/** 판정값을 화면 문구로 바꾼다. 분은 올림(08:00:01 출근 = 지각 1분). 판정하지 않은 날은 null. */
public final class AttendanceLabel {
    private AttendanceLabel() { }

    public static String of(Attendance a) {
        if (a == null || a.equals(Attendance.NONE)) return null;
        if ("FULL".equals(a.absence())) return "결근";
        if ("HALF".equals(a.absence())) return "결근(반일)";
        var parts = new java.util.ArrayList<String>();
        if (a.lateMillis() != null && a.lateMillis() > 0) parts.add("지각 " + minutes(a.lateMillis()) + "분");
        if (a.earlyLeaveMillis() != null && a.earlyLeaveMillis() > 0) parts.add("조퇴 " + minutes(a.earlyLeaveMillis()) + "분");
        if (!parts.isEmpty()) return String.join(" · ", parts);
        // 한쪽 기록만 판정된 날은 정상으로 단정하지 않는다.
        return a.lateMillis() != null && a.earlyLeaveMillis() != null ? "정상" : "확인 필요";
    }

    public static String of(Long lateMillis, Long earlyLeaveMillis, String absence) {
        return of(new Attendance(lateMillis, earlyLeaveMillis, absence));
    }

    static long minutes(long millis) { return (millis + 59_999L) / 60_000L; }
}
