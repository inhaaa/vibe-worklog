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
import kr.tna.schedule.WorkTimeCodeData.Policy;
import kr.tna.schedule.WorkTimeCodeData.Totals;

public final class WorkTimeCodeRules {
    private WorkTimeCodeRules() {}

    public static Totals validate(Policy policy, List<Break> breaks) {
        if ("FIXED".equals(policy.workMode())) {
            if (policy.coreStart()!=null || policy.coreEnd()!=null || policy.standardMinutes()!=null || policy.settlementPeriod()!=null)
                throw OrgException.invalid("고정근무에는 선택근무의 코어시간·표준분·정산기간을 지정할 수 없습니다.");
            return validate(policy.startTime(),policy.endTime(),policy.beforeStart(),policy.beforeEnd(),
                    policy.afterStart(),policy.afterEnd(),breaks);
        }
        if (!"SELECTIVE".equals(policy.workMode())) throw OrgException.invalid("근무유형을 확인해 주세요.");
        if (policy.beforeStart()!=null || policy.beforeEnd()!=null || policy.afterStart()!=null || policy.afterEnd()!=null)
            throw OrgException.invalid("선택근무에는 고정근무의 출근 전·퇴근 후 연장시간을 지정할 수 없습니다.");
        var window=validate(policy.startTime(),policy.endTime(),null,null,null,null,breaks);
        if (policy.standardMinutes()==null || policy.standardMinutes()<1 || policy.standardMinutes()>window.scheduledMinutes())
            throw OrgException.invalid("표준 근로시간은 1분부터 선택 가능 시간에서 예정 휴게를 뺀 분까지 입력해 주세요.");
        if (!"CALENDAR_MONTH".equals(policy.settlementPeriod()) && !"ISO_WEEK".equals(policy.settlementPeriod()))
            throw OrgException.invalid("정산기간은 역월 또는 월요일부터 일요일까지의 주를 선택해 주세요.");
        if (policy.coreStart()!=null || policy.coreEnd()!=null) {
            minutes(policy.coreStart(),policy.coreEnd(),"코어시간");
            if (policy.coreStart().isBefore(policy.startTime()) || policy.coreEnd().isAfter(policy.endTime()))
                throw OrgException.invalid("코어시간은 선택 가능 시간 안에 입력해 주세요.");
        }
        return new Totals(policy.standardMinutes(),0);
    }

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
