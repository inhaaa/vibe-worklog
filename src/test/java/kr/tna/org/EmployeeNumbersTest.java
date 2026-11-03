package kr.tna.org;

import static org.junit.jupiter.api.Assertions.*;

import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Test;

class EmployeeNumbersTest {
    @Test
    void sequenceUsesOnlyMatchingAsciiFiveDigitNumbers() {
        var reserved = List.of("26000", "26001", "26017", "25999", "260999", "26ABC", "２６９９９", "99999");
        assertEquals("26018", EmployeeNumbers.next(LocalDate.of(2026, 1, 1), reserved));
        assertEquals("24001", EmployeeNumbers.next(LocalDate.of(2024, 1, 1), reserved));
    }

    @Test
    void keepsTheExistingYearRangeAndUsesAsciiDigitsIndependentlyOfLocale() {
        Locale previous = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("ar-EG"));
            assertEquals("01001", EmployeeNumbers.next(LocalDate.of(1, 1, 1), List.of()));
            assertEquals("00001", EmployeeNumbers.next(LocalDate.of(100, 1, 1), List.of()));
            assertEquals("99001", EmployeeNumbers.next(LocalDate.of(9999, 12, 31), List.of("99999")));
        } finally {
            Locale.setDefault(previous);
        }
    }

    @Test
    void stopsAtTheYearLimitAndSkipsTheReservedAdministratorNumber() {
        assertEquals("26999", EmployeeNumbers.next(LocalDate.of(2026, 1, 1), List.of("26998")));
        assertEquals("99998", EmployeeNumbers.next(LocalDate.of(2099, 1, 1), List.of("99997", "99999")));
        assertEquals(422, assertThrows(OrgException.class,
                () -> EmployeeNumbers.next(LocalDate.of(2026, 1, 1), List.of("26999"))).status());
        assertEquals(422, assertThrows(OrgException.class,
                () -> EmployeeNumbers.next(LocalDate.of(2099, 1, 1), List.of("99998", "99999"))).status());
    }
}
