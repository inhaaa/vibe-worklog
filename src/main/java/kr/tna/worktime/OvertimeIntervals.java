package kr.tna.worktime;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import kr.tna.worktime.WorktimeCalculator.Interval;

/** 승인된 연장 구간에서 그 근무시간코드의 연장 휴게를 빼고, 겹치는 구간을 합친다(설계 §2). 자정을 넘는 연장은 없다. */
public final class OvertimeIntervals {
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    public record Pause(LocalTime start, LocalTime end) { }
    public record Approved(LocalTime start, LocalTime end, List<Pause> pauses) { }
    private OvertimeIntervals() { }

    public static List<Interval> net(LocalDate date, List<Approved> approved) {
        var pieces = new ArrayList<Interval>();
        for (var a : approved) {
            var remaining = new ArrayList<Interval>(List.of(interval(date, a.start(), a.end())));
            for (var p : a.pauses()) {
                var cut = interval(date, p.start(), p.end());
                var next = new ArrayList<Interval>();
                for (var r : remaining) {
                    if (!cut.start().isBefore(r.end()) || !cut.end().isAfter(r.start())) { next.add(r); continue; }
                    if (r.start().isBefore(cut.start())) next.add(new Interval(r.start(), cut.start()));
                    if (cut.end().isBefore(r.end())) next.add(new Interval(cut.end(), r.end()));
                }
                remaining = next;
            }
            pieces.addAll(remaining);
        }
        pieces.sort(Comparator.comparing(Interval::start));
        var merged = new ArrayList<Interval>();
        for (var p : pieces) {
            if (!merged.isEmpty() && !p.start().isAfter(merged.getLast().end())) {
                var last = merged.removeLast();
                merged.add(new Interval(last.start(), p.end().isAfter(last.end()) ? p.end() : last.end()));
            } else merged.add(p);
        }
        return List.copyOf(merged);
    }

    private static Interval interval(LocalDate date, LocalTime start, LocalTime end) {
        return new Interval(date.atTime(start).atZone(SEOUL).toInstant(), date.atTime(end).atZone(SEOUL).toInstant());
    }
}
