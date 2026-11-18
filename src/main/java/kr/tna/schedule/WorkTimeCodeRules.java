package kr.tna.schedule;

import java.time.Duration;
import java.time.LocalTime;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import kr.tna.org.OrgException;
import kr.tna.schedule.WorkTimeCodeData.Break;
import kr.tna.schedule.WorkTimeCodeData.Totals;

public final class WorkTimeCodeRules {
    private WorkTimeCodeRules() {}

    public static Totals validate(LocalTime start, LocalTime end, LocalTime beforeStart, LocalTime beforeEnd,
            LocalTime afterStart, LocalTime afterEnd, List<Break> breaks) {
        long regular = minutes(start,end,"정규 근무");
        long before = optionalMinutes(beforeStart,beforeEnd,"출근 전 연장");
        long after = optionalMinutes(afterStart,afterEnd,"퇴근 후 연장");
        if ((beforeEnd != null && beforeEnd.isAfter(start)) || (afterStart != null && afterStart.isBefore(end)))
            throw OrgException.invalid("연장 허용 시간대가 정규 근무와 겹칩니다.");
        if (breaks == null || breaks.stream().anyMatch(b -> b == null || b.kind() == null
                || !Set.of("REGULAR","BEFORE","AFTER").contains(b.kind()) || b.start() == null || b.end() == null))
            throw OrgException.invalid("예정 휴게의 구분과 시작·종료 시각을 확인해 주세요.");
        long overtime = before + after;
        Map<String,LocalTime> previousEnd = new HashMap<>();
        for (Break pause : breaks.stream().sorted(Comparator.comparing(Break::start)).toList()) {
            LocalTime windowStart = switch (pause.kind()) { case "BEFORE" -> beforeStart; case "AFTER" -> afterStart; default -> start; };
            LocalTime windowEnd = switch (pause.kind()) { case "BEFORE" -> beforeEnd; case "AFTER" -> afterEnd; default -> end; };
            long length = minutes(pause.start(),pause.end(),"예정 휴게");
            if (windowStart == null || pause.start().isBefore(windowStart) || pause.end().isAfter(windowEnd))
                throw OrgException.invalid("예정 휴게는 해당 정규 근무 또는 연장 허용 시간 안에 입력해 주세요.");
            LocalTime last = previousEnd.put(pause.kind(),pause.end());
            if (last != null && pause.start().isBefore(last)) throw OrgException.invalid("예정 휴게 시간이 겹칩니다.");
            if ("REGULAR".equals(pause.kind())) regular -= length;
            else overtime -= length;
        }
        if (regular == 0) throw OrgException.invalid("휴게를 제외한 소정시간이 있어야 합니다.");
        return new Totals(regular,overtime);
    }

    private static long optionalMinutes(LocalTime start, LocalTime end, String label) {
        return start == null && end == null ? 0 : minutes(start,end,label);
    }

    private static long minutes(LocalTime start, LocalTime end, String label) {
        if (start == null || end == null || !end.isAfter(start))
            throw OrgException.invalid(label + " 종료 시각은 같은 날의 시작 시각보다 늦어야 합니다.");
        if (start.getSecond()!=0 || end.getSecond()!=0 || start.getNano()!=0 || end.getNano()!=0)
            throw OrgException.invalid(label + " 시각은 분 단위로 입력해 주세요.");
        return Duration.between(start,end).toMinutes();
    }
}
