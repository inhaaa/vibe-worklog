package kr.tna.request;

import static java.time.LocalDate.parse;
import static org.junit.jupiter.api.Assertions.*;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

class EmploymentAccrualCalendarTest {
    @Test void earlierUseMustKeepALaterReservationFundedUntilThatReservationIsRestored() {
        var first = parse("2026-04-15");
        var grant = new EmploymentAccrualData.BalanceMovement(first,BigDecimal.ONE);
        var use = new EmploymentAccrualData.BalanceMovement(first.plusDays(4),BigDecimal.ONE.negate());
        var restore = new EmploymentAccrualData.BalanceMovement(first.plusDays(4),BigDecimal.ONE);
        var next = new EmploymentAccrualData.BalanceMovement(first.plusMonths(1),BigDecimal.ONE);
        var request = List.of(new RequestData.RequestDay(first.plusDays(2),1,null,null,null,"FULL"));
        assertFalse(EmploymentAccrualData.covers(List.of(grant,use,next),request));
        assertTrue(EmploymentAccrualData.covers(List.of(grant,use,restore,next),request));
    }

    @Test void ordinaryMonthsUseEmploymentAnniversariesAndRequireEmploymentOnAccrualDay() {
        var joined = parse("2025-10-15");
        assertTrue(EmploymentAccrualData.periods(joined, parse("2025-11-14")).isEmpty());
        var dates = EmploymentAccrualData.periods(joined, parse("2026-01-15"));
        assertEquals(3, dates.size());
        assertEquals(new EmploymentAccrualData.Window(2, parse("2025-11-15"), parse("2025-12-14"), parse("2025-12-15")), dates.get(1));
        assertEquals(11, EmploymentAccrualData.periods(joined, parse("2027-01-01")).size());
    }

    @Test void missingMonthDayCompletesAtEndOfShortMonthAndLeapDayUsesFullFirstYear() {
        var joined = parse("2024-01-31");
        var dates = EmploymentAccrualData.periods(joined, parse("2024-04-01"));
        assertEquals(new EmploymentAccrualData.Window(1, joined, parse("2024-02-29"), parse("2024-03-01")), dates.getFirst());
        assertEquals(new EmploymentAccrualData.Window(2, parse("2024-03-01"), parse("2024-03-30"), parse("2024-03-31")), dates.getLast());
        assertEquals(parse("2025-03-01"), EmploymentAccrualData.accrualOn(parse("2024-02-29"), 12));
    }
}
