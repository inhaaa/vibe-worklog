package kr.tna;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;
import kr.tna.clock.ClockData.DaySnapshot;
import kr.tna.clock.ClockData.Event;
import kr.tna.schedule.ScheduleData;
import kr.tna.workspace.WorkspaceData.DayView;
import kr.tna.workspace.WorkspaceData.TimelineEntry;
import org.junit.jupiter.api.Test;

class DayFlowTimelineTest {
    private static final LocalDate DAY = LocalDate.of(2027, 1, 1);

    @Test
    void plannedWorkBreaksAndEffectiveRecordsAreMergedInTimeOrderWithExplicitLabels() {
        var schedule = schedule(true, LocalTime.of(9, 0), LocalTime.of(18, 0),
                List.of(new ScheduleData.Break(LocalTime.NOON, LocalTime.of(13, 0))));
        var raw = List.of(event("IN", "2027-01-01T00:01:00Z"), event("OUT", "2027-01-01T09:00:00Z"));
        var effective = List.of(event("OUT", "2027-01-01T09:03:00Z"), event("IN", "2027-01-01T00:02:00Z"));
        var day = day(schedule, raw, effective);

        var timeline = day.timeline();

        assertEquals(List.of(LocalTime.of(9, 0), LocalTime.of(9, 2), LocalTime.NOON,
                LocalTime.of(13, 0), LocalTime.of(18, 0), LocalTime.of(18, 3)), times(timeline));
        assertEquals(List.of("planned", "recorded", "planned", "planned", "planned", "recorded"),
                timeline.stream().map(TimelineEntry::kind).toList());
        assertEquals(List.of("예정 근무 시작", "현재 반영 출근", "예정 휴게 시작", "예정 휴게 종료",
                "예정 근무 종료", "현재 반영 퇴근"), timeline.stream().map(TimelineEntry::title).toList());
        for (var entry : timeline) {
            String qualifier = entry.kind().equals("planned") ? "예정" : "현재 반영";
            assertTrue(entry.title().contains(qualifier));
            assertTrue(entry.detail().contains(qualifier));
        }
        assertEquals(raw, day.snapshot().events());
        assertEquals(effective, day.snapshot().effectiveEvents());
        assertNull(day.snapshot().confirmation());
    }

    @Test
    void equalTimePlannedItemsAndRepeatedEffectiveRecordsAreAllPreserved() {
        var repeated = List.of(event("IN", "2027-01-01T00:00:00Z"), event("IN", "2027-01-01T00:00:00Z"));
        var day = day(schedule(true, LocalTime.of(9, 0), LocalTime.of(18, 0),
                List.of(new ScheduleData.Break(LocalTime.of(9, 0), LocalTime.of(9, 30)))), repeated, repeated);

        var timeline = day.timeline();

        assertEquals(6, timeline.size());
        assertEquals(4L, timeline.stream().filter(entry -> entry.time().equals(LocalTime.of(9, 0))).count());
        assertEquals(4L, timeline.stream().filter(entry -> entry.kind().equals("planned")).count());
        assertEquals(2L, timeline.stream().filter(entry -> entry.kind().equals("recorded")).count());
        assertEquals(repeated, day.snapshot().events());
        assertNull(day.snapshot().confirmation(), "a timeline must not turn repeated facts into confirmed work");
    }

    @Test
    void effectiveInstantsUseSeoulLocalTimeAcrossTheUtcYearBoundary() {
        var effective = List.of(event("OUT", "2027-01-01T14:59:59.123Z"),
                event("IN", "2026-12-31T15:05:00Z"));
        var raw = List.of(event("IN", "2027-01-01T01:00:00Z"));

        var timeline = day(null, raw, effective).timeline();

        assertEquals(List.of(LocalTime.of(0, 5), LocalTime.of(23, 59, 59, 123_000_000)), times(timeline));
        assertTrue(timeline.stream().allMatch(entry -> entry.kind().equals("recorded")));
    }

    @Test
    void unassignedAndNonWorkdaysKeepRecordsWithoutInventingPlannedWorkOrBreaks() {
        var recorded = List.of(event("IN", "2027-01-01T00:15:00Z"));
        var nonWorkday = schedule(false, LocalTime.of(9, 0), LocalTime.of(18, 0),
                List.of(new ScheduleData.Break(LocalTime.NOON, LocalTime.of(13, 0))));
        for (var day : List.of(day(null, recorded, recorded), day(nonWorkday, recorded, recorded))) {
            var timeline = day.timeline();
            assertEquals(1, timeline.size());
            assertEquals("recorded", timeline.getFirst().kind());
            assertEquals(LocalTime.of(9, 15), timeline.getFirst().time());
        }
    }

    @Test
    void absentPlannedEndpointsAreOmittedInsteadOfBecomingMidnight() {
        var incomplete = schedule(true, null, LocalTime.of(18, 0),
                List.of(new ScheduleData.Break(LocalTime.NOON, null), new ScheduleData.Break(null, null)));

        var timeline = day(incomplete, List.of(), List.of()).timeline();

        assertEquals(List.of(LocalTime.NOON, LocalTime.of(18, 0)), times(timeline));
        assertTrue(timeline.stream().allMatch(entry -> entry.kind().equals("planned")));
    }

    @Test
    void noScheduleAndNoRecordsProduceAnEmptyTimeline() {
        assertTrue(day(null, List.of(), List.of()).timeline().isEmpty());
    }

    private static List<LocalTime> times(List<TimelineEntry> timeline) {
        return timeline.stream().map(TimelineEntry::time).toList();
    }

    private static Event event(String kind, String instant) {
        return new Event(UUID.randomUUID(), kind, Instant.parse(instant), DAY);
    }

    private static ScheduleData.Day schedule(boolean workday, LocalTime start, LocalTime end,
            List<ScheduleData.Break> breaks) {
        return new ScheduleData.Day(DAY.getDayOfWeek().getValue(), workday, start, end, breaks);
    }

    private static DayView day(ScheduleData.Day schedule, List<Event> raw, List<Event> effective) {
        return new DayView(new DaySnapshot(UUID.randomUUID(), DAY, raw, schedule, null, null,
                "UNCONFIRMED", 0, 0, null, "synthetic-timeline", effective), List.of(), false, false);
    }
}
