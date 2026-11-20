package kr.tna.request;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.*;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import kr.tna.clock.ClockData;
import kr.tna.clock.ClockService;
import kr.tna.common.auth.TnaPrincipal;
import kr.tna.worktime.SelectiveSettlementReturn;
import kr.tna.worktime.WorktimeService;
import kr.tna.org.OrgCommands;
import kr.tna.request.RequestData.CorrectionForm;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.validation.BindingResult;
import org.springframework.web.servlet.view.RedirectView;

class CorrectionPageTest {
    private final LeaveWorkflowService flow = mock(LeaveWorkflowService.class);
    private final ClockService clocks = mock(ClockService.class);
    private final OrgCommands commands = mock(OrgCommands.class);
    private final WorktimeService worktime = mock(WorktimeService.class);
    private final RequestController controller = new RequestController(flow, clocks, null, null, commands, null, null, null, new SelectiveSettlementReturn(worktime));
    private final TnaPrincipal actor = new TnaPrincipal(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 1, Set.of("EMPLOYEE"), Instant.EPOCH);

    @Test void correctionPageFillsTheCurrentApprovedCorrectionInsteadOfAskingForAnId() {
        LocalDate date = LocalDate.of(2026, 11, 4);UUID current = UUID.randomUUID();
        var day = new ClockData.DaySnapshot(actor.employeeId(), date, List.of(), null, null, null, "UNCONFIRMED", 3, 1, null, "a".repeat(64), List.of());
        when(flow.correctionPageEvidence(actor, date)).thenReturn(new LeaveWorkflowService.CorrectionPageEvidence(day, current, null));
        when(commands.today()).thenReturn(date.plusDays(1));
        var form = (CorrectionForm) controller.correction(actor, date, null, null).getModel().get("form");
        assertEquals(current, form.replacesRequestId());
    }

    @Test void singlePastArrivalSuggestsCheckoutAndPreservesPendingAndGeneralModes() {
        LocalDate date = LocalDate.of(2026, 11, 4);
        UUID pending = UUID.randomUUID();
        var arrival = new ClockData.Event(UUID.randomUUID(), "IN", Instant.parse("2026-11-04T00:00:00Z"), date);
        var day = new ClockData.DaySnapshot(actor.employeeId(), date, List.of(arrival), null, null, null,
                "UNCONFIRMED", 3, 1, null, "a".repeat(64), List.of(arrival));
        when(flow.correctionPageEvidence(actor, date)).thenReturn(new LeaveWorkflowService.CorrectionPageEvidence(day, null, pending));
        when(commands.today()).thenReturn(date.plusDays(1));
        when(worktime.matchesSettlementDay(actor, actor.employeeId(), date, "ISO_WEEK")).thenReturn(true);
        String origin = "/me/worktime?month=2026-11&settlementPeriod=ISO_WEEK&settlementFrom=2026-11-02";
        var checkout = controller.correction(actor, date, null, origin).getModel();
        assertEquals(origin, checkout.get("settlementReturn"));
        assertEquals(true, checkout.get("checkoutOnly"));
        assertEquals(pending, checkout.get("pendingCorrectionId"));
        assertEquals("SET", ((CorrectionForm) checkout.get("form")).outAction());
        assertEquals(false, controller.correction(actor, date, "full", null).getModel().get("checkoutOnly"));

        when(commands.today()).thenReturn(date);
        assertEquals(false, controller.correction(actor, date, null, null).getModel().get("checkoutOnly"));
    }

    @Test void checkoutSubmissionKeepsArrivalAndUsesOnlyTheEmployeesOwnScope() {
        LocalDate date = LocalDate.of(2026, 11, 4);
        UUID request = UUID.randomUUID(), command = UUID.randomUUID();
        var submitted = new CorrectionForm(UUID.randomUUID(), date, 3L, "a".repeat(64),
                "SET", "2026-11-04T10:00", "CLEAR", "2026-11-04T18:00", true, true,
                "2026-11-04T12:00/2026-11-04T13:00", null, null, true, "합성 사유", command);
        when(flow.submitCorrection(eq(actor), any(CorrectionForm.class),eq("confirmed"))).thenReturn(request);
        var redirect = (RedirectView) controller.correct(actor, "checkout", submitted, mock(BindingResult.class),"confirmed", null).getView();
        assertEquals("/requests/" + request, redirect.getUrl());

        var accepted = ArgumentCaptor.forClass(CorrectionForm.class);
        verify(flow).submitCorrection(eq(actor), accepted.capture(),eq("confirmed"));
        var form = accepted.getValue();
        assertNull(form.targetEmployeeId());
        assertFalse(form.exceptional());
        assertEquals("KEEP", form.inAction());
        assertEquals("", form.requestedInAt());
        assertEquals("SET", form.outAction());
        assertEquals(submitted.requestedOutAt(), form.requestedOutAt());
        assertEquals(submitted.actualBreaks(), form.actualBreaks());
        assertEquals(submitted.commandId(), form.commandId());
        when(worktime.matchesSettlementDay(actor, actor.employeeId(), date, "ISO_WEEK")).thenReturn(true);
        String origin = "/me/worktime?month=2026-11&settlementPeriod=ISO_WEEK&settlementFrom=2026-11-02";
        var settlement = (RedirectView) controller.correct(actor, "full", submitted, mock(BindingResult.class), "confirmed", origin).getView();
        assertEquals(origin, settlement.getUrl());
    }
}
