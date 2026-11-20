package kr.tna.worktime;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Set;
import java.util.UUID;
import kr.tna.common.auth.TnaPrincipal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SelectiveSettlementReturnTest {
    private final UUID employee = UUID.fromString("11223344-1234-4444-8888-123456789012");
    private final String week = "month=2026-10&settlementPeriod=ISO_WEEK&settlementFrom=2026-09-28";
    private final String self = "/me/worktime?" + week;
    private final String admin = "/admin/attendance?employeeId=" + employee + "&" + week;
    private final LocalDate day = LocalDate.of(2026, 9, 30);
    private final TnaPrincipal actor = new TnaPrincipal(UUID.randomUUID(), UUID.randomUUID(), employee, 1,
            Set.of("EMPLOYEE", "ATTENDANCE_ADMIN"), Instant.EPOCH);
    private final WorktimeService worktime = mock(WorktimeService.class);
    private final SelectiveSettlementReturn returns = new SelectiveSettlementReturn(worktime);

    @BeforeEach void assigned() {
        when(worktime.matchesSettlementDay(eq(actor),eq(employee),any(),anyString())).thenReturn(true);
    }

    @Test void rejectsAWellFormedWeekWhenTheEmployeeWorksUnderADifferentPolicy() {
        when(worktime.matchesSettlementDay(actor,employee,day,"ISO_WEEK")).thenReturn(false);
        assertNull(returns.employee(actor,self,day));
        assertNull(returns.admin(actor,admin,employee,day));
        assertNull(returns.calendar(actor,admin,day));
    }

    @Test void keepsTheOriginalMonthForAWeekSpanningTwoMonths() {
        assertEquals(self, returns.employee(actor, self, day));
        assertEquals(admin, returns.admin(actor, admin, employee, day));
        assertEquals(admin, returns.calendar(actor, admin, day));
        var staff = new TnaPrincipal(actor.tenantId(), actor.accountId(), employee, 1, Set.of("EMPLOYEE"), Instant.EPOCH);
        assertNull(returns.calendar(staff, admin, day));
        assertNull(returns.admin(actor, admin, UUID.randomUUID(), day));
        assertNull(returns.employee(actor, admin, day));
        assertNull(returns.calendar(actor, self, day));
    }

    @Test void checksBothTheOriginalLookupMonthAndTheActionDate() {
        assertNull(returns.employee(actor, self, day.plusWeeks(1)));
        assertNull(returns.employee(actor, self.replace("month=2026-10", "month=2026-11"), day));
        assertNull(returns.employee(actor, self.replace("09-28", "09-29"), day));
        String month = "/me/worktime?month=2026-10&settlementPeriod=CALENDAR_MONTH&settlementFrom=2026-10-01";
        assertEquals(month, returns.employee(actor, month, LocalDate.of(2026, 10, 31)));
        assertNull(returns.employee(actor, month, day));
        assertNull(returns.employee(actor, month.replace("10-01", "10-02"), day.plusDays(3)));
    }

    @Test void rejectsNonCanonicalTargetsAndMissingContext() {
        for (String invalid : java.util.List.of("//example.org" + self, self + "#details",
                self + "&month=2026-10", self.replace("09-28", "09-31"), self.replace("/me", "/other"))) {
            assertNull(returns.employee(actor, invalid, day));
        }
        assertNull(returns.employee(actor, null, day));
        assertNull(returns.employee(actor, self, null));
        assertNull(returns.admin(actor, admin, null, day));
    }
}
