package kr.tna.clock;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.time.Clock;
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
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.servlet.view.RedirectView;

class AttendanceNavigationTest {
    private final ClockService clocks = mock(ClockService.class);
    private final OrgCommands commands = mock(OrgCommands.class);
    private final kr.tna.worktime.WorktimeService worktime = mock(kr.tna.worktime.WorktimeService.class);
    private final AttendanceController controller = new AttendanceController(null, clocks, commands, worktime, new SelectiveSettlementReturn(worktime));
    private final TnaPrincipal actor = new TnaPrincipal(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 1, Set.of("EMPLOYEE"), Instant.EPOCH);
    private final LocalDate today = LocalDate.of(2027, 1, 1);

    @Test void todayExposesIndependentCommandsAndTheSelectedRevision() {
        when(commands.today()).thenReturn(today);
        when(clocks.day(actor, today)).thenReturn(day(today));
        var model = controller.history(actor, null).getModel();
        assertEquals(today, model.get("today"));
        assertEquals(true, model.get("isToday"));
        assertInstanceOf(UUID.class, model.get("inCommand"));
        assertInstanceOf(UUID.class, model.get("outCommand"));
        assertNotEquals(model.get("inCommand"), model.get("outCommand"));
        var form = (ClockData.ConfirmationForm) model.get("confirmationForm");
        assertEquals(today, form.workDate());
        assertEquals(4L, form.baseInputRevision());
        assertEquals("snapshot-fingerprint", form.sourceFingerprint());
    }

    @Test void historicalAndFutureDaysDoNotOfferServerTimeClockCommands() {
        when(commands.today()).thenReturn(today);
        for (LocalDate selected : List.of(today.minusDays(1), today.plusDays(1))) {
            when(clocks.day(actor, selected)).thenReturn(day(selected));
            var model = controller.history(actor, selected).getModel();
            assertEquals(false, model.get("isToday"));
            assertFalse(model.containsKey("inCommand"));
            assertFalse(model.containsKey("outCommand"));
            assertEquals(selected, ((ClockData.ConfirmationForm) model.get("confirmationForm")).workDate());
        }
    }

    @Test void recordingKeepsTheValidatedRemoteAddressAndReturnsToAttendance() {
        var request = new MockHttpServletRequest();
        request.setRemoteAddr("127.0.0.1");
        request.addHeader("X-Forwarded-For", "198.51.100.7");
        UUID command = UUID.randomUUID();
        var redirect = (RedirectView) controller.event(actor, "IN", command, null, request).getView();
        assertEquals("/me/attendance", redirect.getUrl());
        verify(clocks).record(actor, "IN", command, "127.0.0.1");
        assertEquals("/me/attendance", ((RedirectView) controller.event(actor, "IN", command, "https://example.test", request).getView()).getUrl(),
                "home 외의 값은 무시해야 합니다.");
        doThrow(OrgException.forbidden()).when(clocks).record(actor, "OUT", command, "127.0.0.1");
        assertThrows(OrgException.class, () -> controller.event(actor, "OUT", command, null, request),
                "홈에서 보낸 기록이 아니면 기존처럼 오류 화면으로 보내야 합니다.");
    }

    @Test void recordingFromHomeReturnsHomeAndCarriesOnlyTheErrorStatus() {
        var request = new MockHttpServletRequest();
        request.setRemoteAddr("127.0.0.1");
        UUID command = UUID.randomUUID();
        assertEquals("/me/home", ((RedirectView) controller.event(actor, "IN", command, "home", request).getView()).getUrl());
        verify(clocks).record(actor, "IN", command, "127.0.0.1");
        doThrow(OrgException.forbidden()).when(clocks).record(actor, "OUT", command, "127.0.0.1");
        assertEquals("/me/home?clockError=403", ((RedirectView) controller.event(actor, "OUT", command, "home", request).getView()).getUrl());
        doThrow(OrgException.conflict("오늘 출근은 이미 기록했습니다.")).when(clocks).record(actor, "IN", command, "127.0.0.1");
        assertEquals("/me/home?clockError=409", ((RedirectView) controller.event(actor, "IN", command, "home", request).getView()).getUrl());
    }

    @Test void todayOffersCommandsOnlyForKindsWithoutARawRecord() {
        when(commands.today()).thenReturn(today);
        var in = new ClockData.Event(UUID.randomUUID(), "IN", Instant.EPOCH, today);
        var d = day(today);
        when(clocks.day(actor, today)).thenReturn(new ClockData.DaySnapshot(d.employeeId(), today, List.of(in), null, null, null, "UNKNOWN",
                4, 2, null, "snapshot-fingerprint", List.of()));
        var model = controller.history(actor, null).getModel();
        assertFalse(model.containsKey("inCommand"), "이미 출근을 기록했으면 출근 명령을 주지 않아야 합니다.");
        assertInstanceOf(UUID.class, model.get("outCommand"));
    }

    @Test void confirmationCalculatesTheDayAndStillReturnsWhenCalculationFails() {
        LocalDate day = today.minusDays(1);UUID command = UUID.randomUUID();
        var form = new ClockData.ConfirmationForm(day, 4L, 2L, true, true, "snapshot-fingerprint", command);
        var binding = new org.springframework.validation.BeanPropertyBindingResult(form, "confirmationForm");
        var redirect = (RedirectView) controller.confirm(actor, form, binding, null).getView();
        assertEquals("/me/attendance?date=" + day, redirect.getUrl());
        var order = inOrder(clocks, worktime);
        order.verify(clocks).confirm(actor, form);
        order.verify(worktime).calculateConfirmedDay(actor, day, command);
        doThrow(new IllegalStateException("합성 오류")).when(worktime).calculateConfirmedDay(actor, day, command);
        assertEquals("/me/attendance?date=" + day, ((RedirectView) controller.confirm(actor, form, binding, null).getView()).getUrl(),
                "계산이 실패해도 저장한 확인은 유지하고 화면을 정상 이동해야 합니다.");
    }
    @Test void rejectedConfirmationNeverCalculates() {
        LocalDate day = today.minusDays(1);UUID command = UUID.randomUUID();
        var form = new ClockData.ConfirmationForm(day, 4L, 2L, true, true, "snapshot-fingerprint", command);
        var binding = new org.springframework.validation.BeanPropertyBindingResult(form, "confirmationForm");
        when(clocks.confirm(actor, form)).thenThrow(new kr.tna.org.OrgException(409, "합성 충돌"));
        when(commands.today()).thenReturn(today);
        when(clocks.day(actor, day)).thenReturn(day(day));
        when(worktime.matchesSettlementDay(actor, actor.employeeId(), day, "CALENDAR_MONTH")).thenReturn(true);
        String origin = "/me/worktime?month=" + java.time.YearMonth.from(day)
                + "&settlementPeriod=CALENDAR_MONTH&settlementFrom=" + day.withDayOfMonth(1);
        var view = controller.confirm(actor, form, binding, origin);
        assertEquals(409, view.getStatus().value());
        assertEquals(origin, view.getModel().get("settlementReturn"));
        verify(worktime, never()).calculateConfirmedDay(any(), any(), any());
    }
    @Test void dayPageCarriesOnlyTheCurrentActualWork() {
        when(commands.today()).thenReturn(today);
        when(clocks.day(actor, today)).thenReturn(confirmed(day(today)));
        when(worktime.days(actor, actor.employeeId(), today, today)).thenReturn(List.of(view(today, true, 8 * 3_600_000L)));
        assertEquals(8 * 3_600_000L, controller.history(actor, today).getModel().get("actualWorkMillis"));
        when(worktime.days(actor, actor.employeeId(), today, today)).thenReturn(List.of(view(today, false, 8 * 3_600_000L)));
        assertNull(controller.history(actor, today).getModel().get("actualWorkMillis"), "오래된 결과의 값은 보이지 않아야 합니다.");
        when(worktime.days(actor, actor.employeeId(), today, today)).thenReturn(List.of(view(today, true, null)));
        assertNull(controller.history(actor, today).getModel().get("actualWorkMillis"), "현재 결과라도 실근로가 없으면 null이어야 합니다.");
    }
    @Test void unconfirmedDayDoesNotReadWorktime() {
        when(commands.today()).thenReturn(today);
        when(clocks.day(actor, today)).thenReturn(day(today));
        var model = controller.history(actor, today).getModel();
        assertTrue(model.containsKey("actualWorkMillis"));
        assertNull(model.get("actualWorkMillis"));
        verify(worktime, never()).days(any(), any(), any(), any());
    }

    @Test void correctionSuggestionComesFromTheServerOnlyForUnconfirmedPastDays() {
        when(commands.today()).thenReturn(today);
        var past = today.minusDays(1);
        when(clocks.day(actor, past)).thenReturn(day(past));
        when(worktime.correctionSuggested(actor, past)).thenReturn(true);
        assertEquals(true, controller.history(actor, past).getModel().get("correctionSuggested"));
        verify(worktime).correctionSuggested(actor, past);
        when(clocks.day(actor, today)).thenReturn(day(today));
        assertEquals(false, controller.history(actor, today).getModel().get("correctionSuggested"));
        when(clocks.day(actor, past)).thenReturn(confirmed(day(past)));
        assertEquals(false, controller.history(actor, past).getModel().get("correctionSuggested"));
        verify(worktime, times(1)).correctionSuggested(any(), any());
    }

    @Test void scheduledBreakOutsidePresenceMakesConfirmationUnavailableOnFirstLoad() {
        ZoneId seoul = ZoneId.of("Asia/Seoul");
        LocalDate past = today.minusDays(1);
        var realValidation = spy(new ClockService(null, null, null, Clock.fixed(today.atStartOfDay(seoul).toInstant(), seoul)));
        var attendance = new AttendanceController(null, realValidation, commands, worktime, new SelectiveSettlementReturn(worktime));
        when(commands.today()).thenReturn(today);
        when(worktime.correctionSuggested(actor, past)).thenReturn(true);

        var schedule = new ScheduleData.Day(past.getDayOfWeek().getValue(), true, LocalTime.of(9, 0), LocalTime.of(18, 0),
                List.of(new ScheduleData.Break(LocalTime.NOON, LocalTime.of(13, 0))));
        var in = new ClockData.Event(UUID.randomUUID(), "IN", past.atTime(9, 0).atZone(seoul).toInstant(), past);
        var earlyOut = new ClockData.Event(UUID.randomUUID(), "OUT", past.atTime(11, 30).atZone(seoul).toInstant(), past);
        var events = List.of(in, earlyOut);
        var early = new ClockData.DaySnapshot(actor.employeeId(), past, events, schedule, UUID.randomUUID(), UUID.randomUUID(),
                "WORKDAY", 2, 0, null, "snapshot-fingerprint", events);
        doReturn(early).when(realValidation).day(actor, past);

        var model = attendance.history(actor, past).getModel();
        assertFalse(model.containsKey("canConfirm"));
        assertEquals(true, model.get("correctionSuggested"));

        var out = new ClockData.Event(UUID.randomUUID(), "OUT", past.atTime(13, 0).atZone(seoul).toInstant(), past);
        var coveredEvents = List.of(in, out);
        var covered = new ClockData.DaySnapshot(actor.employeeId(), past, coveredEvents, schedule, early.scheduleVersionId(), early.calendarVersionId(),
                "WORKDAY", 2, 0, null, "snapshot-fingerprint", coveredEvents);
        doReturn(covered).when(realValidation).day(actor, past);
        var coveredModel = attendance.history(actor, past).getModel();
        assertFalse(coveredModel.containsKey("confirmationBreaks"));
        assertEquals(false, coveredModel.get("correctionSuggested"));
    }

    private ClockData.DaySnapshot day(LocalDate selected) {
        return new ClockData.DaySnapshot(actor.employeeId(), selected, List.of(), null, null, null, "UNKNOWN", 4, 2, null, "snapshot-fingerprint", List.of());
    }
    private ClockData.DaySnapshot confirmed(ClockData.DaySnapshot d) {
        var c = new ClockData.Confirmation(UUID.randomUUID(), d.inputRevision(), d.reviewRevision(), true, true, d.sourceFingerprint(), List.of(), Instant.EPOCH);
        return new ClockData.DaySnapshot(d.employeeId(), d.workDate(), d.events(), d.schedule(), d.scheduleVersionId(), d.calendarVersionId(), d.dayKind(),
                d.inputRevision(), d.reviewRevision(), c, d.sourceFingerprint(), d.effectiveEvents());
    }
    private kr.tna.worktime.WorktimeData.DayView view(LocalDate date, boolean current, Long actual) {
        var result = new kr.tna.worktime.WorktimeCalculator.DayResult(date, kr.tna.worktime.WorktimeCalculator.Status.CALCULATED,
                null, null, actual, null, null, null, null, null, null, 0, 0L, 4, 2, Set.of());
        return new kr.tna.worktime.WorktimeData.DayView(UUID.randomUUID(), UUID.randomUUID(), actor.employeeId(), "E001", "직원",
                date, 1, "CALC-1", Instant.EPOCH, "a".repeat(64), current, result, false, 0, 0L, true);
    }
}
