package kr.tna.clock;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import kr.tna.common.auth.TnaPrincipal;
import kr.tna.worktime.SelectiveSettlementReturn;
import kr.tna.org.OrgCommands;
import kr.tna.org.OrgException;
import kr.tna.schedule.ScheduleData;
import kr.tna.worktime.WorktimeService;
import org.junit.jupiter.api.Test;

class AttendanceHistoryTest {
    private static final LocalDate TODAY = LocalDate.of(2026, 11, 17);
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    @Test void inclusiveRangeUsesTheClosingDateToHandleLeapYearsAndMonthEnds() {
        var defaults = AttendanceHistory.Range.of(TODAY, null, null);
        assertEquals(LocalDate.of(2026, 8, 18), defaults.from());
        assertEquals(TODAY, defaults.to());
        var end = LocalDate.of(2024, 5, 30);
        assertEquals(LocalDate.of(2024, 2, 29), AttendanceHistory.Range.of(TODAY, LocalDate.of(2024, 2, 29), end).from());
        assertThrows(OrgException.class, () -> AttendanceHistory.Range.of(TODAY, LocalDate.of(2024, 2, 28), end));
        assertThrows(OrgException.class, () -> AttendanceHistory.Range.of(TODAY, TODAY, TODAY.plusDays(1)));
        assertThrows(OrgException.class, () -> AttendanceHistory.Range.of(TODAY, TODAY, TODAY.minusDays(1)));
        assertEquals(TODAY, AttendanceHistory.Range.of(TODAY, TODAY, TODAY).from());
    }

    @Test void recordsDistinguishMissingFromOngoingInvalidAndExcludedDates() {
        var past = TODAY.minusDays(1);
        assertEquals(AttendanceHistory.RecordStatus.MISSING, row(past, "WORKDAY", false, false).recordStatus());
        assertEquals(AttendanceHistory.RecordStatus.IN_PROGRESS, row(TODAY, "WORKDAY", false, false, "IN").recordStatus());
        assertEquals(AttendanceHistory.RecordStatus.MISSING, row(TODAY, "WORKDAY", false, false, "OUT").recordStatus());
        assertEquals(AttendanceHistory.RecordStatus.INVALID, row(TODAY, "WORKDAY", false, false, "IN", "IN").recordStatus());
        assertEquals(AttendanceHistory.RecordStatus.INVALID, row(past, "WORKDAY", false, false, "OUT", "IN").recordStatus());
        assertEquals(AttendanceHistory.RecordStatus.NONE, row(TODAY, "WORKDAY", false, false).recordStatus());
        assertEquals(AttendanceHistory.RecordStatus.NONE, row(past, "HOLIDAY", false, false).recordStatus());
        assertEquals(AttendanceHistory.RecordStatus.NONE, row(past, "NON_WORKDAY", false, false).recordStatus());
        assertEquals(AttendanceHistory.RecordStatus.NONE, row(past, "UNCONFIRMED", false, false).recordStatus());
        assertEquals(AttendanceHistory.RecordStatus.NONE, row(past, "WORKDAY", true, false).recordStatus());
        assertEquals(AttendanceHistory.RecordStatus.NONE, row(past, "WORKDAY", false, true).recordStatus());
    }

    @Test void outsideEmploymentKeepsOnlyTheCalendarRow() {
        var time = TODAY.atTime(9, 0).atZone(SEOUL).toInstant();
        var events = List.of(new ClockData.Event(UUID.randomUUID(), "IN", time, TODAY));
        var schedule = new ScheduleData.Day(TODAY.getDayOfWeek().getValue(), true, LocalTime.of(9, 0), LocalTime.of(18, 0), List.of());
        var day = new ClockData.DaySnapshot(UUID.randomUUID(), TODAY, events, schedule, UUID.randomUUID(), UUID.randomUUID(),
                "WORKDAY", 0, 0, null, "fingerprint", events);
        var row = AttendanceHistory.row(day, TODAY, false, false, true, true, false, 5L, true);
        assertEquals(AttendanceHistory.ScheduleKind.OUTSIDE_EMPLOYMENT, row.scheduleKind());
        assertEquals(AttendanceHistory.RecordStatus.NONE, row.recordStatus());
        assertNull(row.inAt());
        assertNull(row.actualWorkMillis());
        assertFalse(row.pendingCorrection());
        assertFalse(row.correctionSuggested());
    }

    @Test void controllerFiltersOnTheServerAndDoesNotReadForAnInvalidRange() {
        var commands = mock(OrgCommands.class);
        var worktime = mock(WorktimeService.class);
        var actor = new TnaPrincipal(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 1, Set.of("EMPLOYEE"), Instant.EPOCH);
        var controller = new AttendanceController(null, null, commands, worktime, new SelectiveSettlementReturn(worktime));
        when(commands.today()).thenReturn(TODAY);
        var past = TODAY.minusDays(1);
        var range = AttendanceHistory.Range.of(TODAY, past, TODAY);
        var missing = row(past, "WORKDAY", false, false);
        when(worktime.attendanceHistory(actor, range)).thenReturn(List.of(row(TODAY, "WORKDAY", false, false, "IN"), missing));
        var view = controller.attendance(actor, null, "period", past, TODAY, "true", null);
        assertEquals("clock/period", view.getViewName());
        var model = assertInstanceOf(AttendanceHistory.Period.class, view.getModel().get("attendancePeriod"));
        assertEquals(List.of(missing), model.rows());
        var rejected = controller.attendance(actor, null, "period", TODAY, past, null, null);
        assertEquals(422, rejected.getStatus().value());
        assertEquals(400, assertThrows(OrgException.class, () -> controller.attendance(actor, null, "period", past, null, null, null)).status());
        assertEquals(400, assertThrows(OrgException.class, () -> controller.attendance(actor, past, "period", past, TODAY, null, null)).status());
        verify(worktime).attendanceHistory(actor, range);
        verifyNoMoreInteractions(worktime);
    }

    private static AttendanceHistory.Row row(LocalDate date, String dayKind, boolean leave, boolean pending, String... kinds) {
        var time = date.atTime(9, 0).atZone(SEOUL).toInstant();
        var events = new java.util.ArrayList<ClockData.Event>();
        for (int i = 0; i < kinds.length; i++) events.add(new ClockData.Event(UUID.randomUUID(), kinds[i], time.plusSeconds(i), date));
        var schedule = new ScheduleData.Day(date.getDayOfWeek().getValue(), true, LocalTime.of(9, 0), LocalTime.of(18, 0), List.of());
        var day = new ClockData.DaySnapshot(UUID.randomUUID(), date, events, schedule, UUID.randomUUID(), UUID.randomUUID(), dayKind,
                0, 0, null, "fingerprint", events);
        return AttendanceHistory.row(day, TODAY, true, leave, pending, false, false, null, false);
    }
}
