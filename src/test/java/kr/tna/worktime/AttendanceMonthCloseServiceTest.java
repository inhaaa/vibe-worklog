package kr.tna.worktime;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AttendanceMonthCloseServiceTest {
    @Test
    void closingRequiresAnOpenPastMonthWithEmployeesAndNoOutstandingWork() {
        assertTrue(check("OPEN", true, 1, 0, 0, 0).canClose());
        for (var blocked : java.util.List.of(
                check("CLOSED", true, 1, 0, 0, 0),
                check("OPEN", false, 1, 0, 0, 0),
                check("OPEN", true, 0, 0, 0, 0),
                check("OPEN", true, 1, 1, 0, 0),
                check("OPEN", true, 1, 0, 1, 0),
                check("OPEN", true, 1, 0, 0, 1))) {
            assertFalse(blocked.canClose());
        }
    }

    private static AttendanceMonthCloseService.Check check(String status, boolean past, long employees,
            long days, long requests, long followUps) {
        return new AttendanceMonthCloseService.Check("2024-03", status, 0, past, employees, days, days,
                0, 0, 0, requests, followUps, null);
    }
}
