package kr.tna.clock;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import kr.tna.org.OrgCommands;
import kr.tna.schedule.ScheduleData;
import kr.tna.workspace.WorkspaceData.DayView;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class SelectiveClockProjectionTest {
    private final UUID employee = UUID.fromString("aaaaaaaa-1111-4000-8000-000000000001");
    private final UUID version = UUID.fromString("aaaaaaaa-1111-4000-8000-000000000002");
    private final UUID calendar = UUID.fromString("aaaaaaaa-1111-4000-8000-000000000003");
    private final LocalDate date = LocalDate.of(2026, 11, 20);
    private final ClockService service = new ClockService(null, null, null,
            Clock.fixed(Instant.parse("2026-11-20T14:00:00Z"), ZoneId.of("Asia/Seoul")));

    @Test void preservesFingerprintUsedByExistingFixedWorkConfirmations() {
        var row = new ClockMapper.ScheduleRow(version, 5, true, LocalTime.of(7, 0), LocalTime.of(21, 0));
        var oldRow = "ScheduleRow[scheduleVersionId=" + version + ", weekday=5, workday=true, startTime=07:00, endTime=21:00]";
        var oldDay = "Day[weekday=5, workday=true, startTime=07:00, endTime=21:00, breaks=[]]";
        assertEquals(OrgCommands.fingerprint(employee, date, List.of(), List.of(oldRow), oldDay, calendar, "WORKDAY"),
                project(row).sourceFingerprint());
    }

    @Test void showsSelectableTimesAndPreservesPolicyInTheIndividualDay() {
        var row = JsonMapper.builder().build().readValue("""
                {"scheduleVersionId":"%s","weekday":5,"workday":true,"startTime":"07:00","endTime":"21:00",
                 "workMode":"SELECTIVE","coreStart":"10:00","coreEnd":"15:00","standardMinutes":480,"settlementPeriod":"ISO_WEEK"}
                """.formatted(version), ClockMapper.ScheduleRow.class);
        var day = project(row);
        var view = new DayView(day, List.of(), true, true, null);
        assertEquals("선택 가능 07:00 ~ 21:00", view.scheduleText());
        assertTrue(view.timeline().stream().anyMatch(entry -> "선택 가능 시작".equals(entry.title())));
        assertTrue(view.timeline().stream().anyMatch(entry -> "코어타임 시작".equals(entry.title())));
        var json = JsonMapper.builder().build().valueToTree(day.schedule());
        assertEquals("ISO_WEEK", json.path("settlementPeriod").asString());
        assertEquals(480, json.path("standardMinutes").asInt());
        var history = AttendanceHistory.row(day,date,true,0,false,false,false,null,false,
                kr.tna.worktime.WorktimeCalculator.Attendance.NONE,java.time.Instant.EPOCH);
        assertEquals("SELECTIVE", history.workMode());
    }

    private ClockData.DaySnapshot project(ClockMapper.ScheduleRow row) {
        return service.projectSnapshot(employee, date, List.of(), List.of(row), List.of(),
                new ClockMapper.CalendarRow(calendar, "WORKDAY"), 0, 0, null, fingerprint -> null);
    }

    @Test void selectableAfternoonDoesNotRequireACorrectionForAMorningBreak() {
        assertEquals(List.of(), service.confirmableBreaks(afternoon("SELECTIVE",13,0)).orElseThrow());
        var expected = new ClockData.Interval(Instant.parse("2026-11-20T03:30:00Z"),Instant.parse("2026-11-20T04:00:00Z"));
        assertEquals(List.of(expected), service.confirmableBreaks(afternoon("SELECTIVE",12,30)).orElseThrow());
        assertFalse(service.canConfirm(afternoon("FIXED",13,0)));
    }

    private ClockData.DaySnapshot afternoon(String mode, int hour, int minute) {
        var row = new ClockMapper.ScheduleRow(version,5,true,LocalTime.of(7,0),LocalTime.of(21,0),
                mode,null,null,"SELECTIVE".equals(mode) ? 480 : null,"SELECTIVE".equals(mode) ? "ISO_WEEK" : null);
        var zone = ZoneId.of("Asia/Seoul");
        var in = date.atTime(hour,minute).atZone(zone).toInstant();
        var out = date.atTime(18,0).atZone(zone).toInstant();
        var events = List.of(new ClockData.Event(UUID.randomUUID(),"IN",in,date),new ClockData.Event(UUID.randomUUID(),"OUT",out,date));
        return service.projectSnapshot(employee,date,events,List.of(row),
                List.of(new ScheduleData.Break(LocalTime.NOON,LocalTime.of(13,0))),
                new ClockMapper.CalendarRow(calendar,"WORKDAY"),2,0,null,fingerprint -> null);
    }
}
