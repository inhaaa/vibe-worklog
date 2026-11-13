package kr.tna.request;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import kr.tna.clock.ClockData;
import kr.tna.clock.ClockService;
import kr.tna.common.auth.TnaPrincipal;
import kr.tna.request.RequestData.CorrectionForm;
import org.junit.jupiter.api.Test;

class CorrectionPageTest {
    private final LeaveWorkflowService flow = mock(LeaveWorkflowService.class);
    private final ClockService clocks = mock(ClockService.class);
    private final RequestController controller = new RequestController(flow, clocks, null, null, null, null, null);
    private final TnaPrincipal actor = new TnaPrincipal(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 1, Set.of("EMPLOYEE"), Instant.EPOCH);

    @Test void correctionPageFillsTheCurrentApprovedCorrectionInsteadOfAskingForAnId() {
        LocalDate date = LocalDate.of(2026, 11, 4);UUID current = UUID.randomUUID();
        when(clocks.day(actor, date)).thenReturn(new ClockData.DaySnapshot(actor.employeeId(), date, List.of(), null, null, null, "UNCONFIRMED", 3, 1, null, "a".repeat(64), List.of()));
        when(flow.currentCorrectionRequestId(actor, actor.employeeId(), date)).thenReturn(current);
        var form = (CorrectionForm) controller.correction(actor, date).getModel().get("form");
        assertEquals(current, form.replacesRequestId());
    }
}
