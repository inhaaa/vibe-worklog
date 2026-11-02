package kr.tna.clock;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import kr.tna.common.auth.TnaPrincipal;
import kr.tna.org.OrgCommands;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.servlet.view.RedirectView;

class AttendanceNavigationTest {
    private final ClockService clocks = mock(ClockService.class);
    private final OrgCommands commands = mock(OrgCommands.class);
    private final AttendanceController controller = new AttendanceController(null, clocks, commands);
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
        var redirect = (RedirectView) controller.event(actor, "IN", command, request).getView();
        assertEquals("/me/attendance", redirect.getUrl());
        verify(clocks).record(actor, "IN", command, "127.0.0.1");
    }

    private ClockData.DaySnapshot day(LocalDate selected) {
        return new ClockData.DaySnapshot(actor.employeeId(), selected, List.of(), null, null, null, "UNKNOWN", 4, 2, null, "snapshot-fingerprint", List.of());
    }
}
