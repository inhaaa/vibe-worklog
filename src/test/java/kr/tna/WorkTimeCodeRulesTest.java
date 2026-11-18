package kr.tna;

import static org.junit.jupiter.api.Assertions.*;

import java.time.LocalTime;
import java.util.List;
import kr.tna.org.OrgException;
import kr.tna.schedule.WorkTimeCodeData.*;
import kr.tna.schedule.WorkTimeCodeRules;
import org.junit.jupiter.api.Test;

class WorkTimeCodeRulesTest {
    private static LocalTime t(String value) { return LocalTime.parse(value); }
    private static Break pause(String kind, String start, String end) { return new Break(kind,t(start),t(end)); }

    @Test void adjacentBreaksAreValidAndOvertimeIsNetOfItsOwnBreaksOnly() {
        var totals=WorkTimeCodeRules.validate(t("09:00"),t("18:00"),null,null,t("18:30"),t("21:00"),
                List.of(pause("REGULAR","12:00","12:30"),pause("REGULAR","12:30","13:00"),pause("AFTER","20:00","20:15")));
        assertEquals(480,totals.scheduledMinutes());
        assertEquals(135,totals.overtimeMinutes());
    }
    @Test void rejectsCrossMidnightOverlapWrongWindowsOrSubminuteInputs() {
        assertThrows(OrgException.class,()->WorkTimeCodeRules.validate(t("23:00"),t("06:00"),null,null,null,null,List.of()));
        assertThrows(OrgException.class,()->WorkTimeCodeRules.validate(t("09:00"),t("18:00"),null,null,t("17:30"),t("19:00"),List.of()));
        assertThrows(OrgException.class,()->WorkTimeCodeRules.validate(t("09:00"),t("18:00"),null,null,null,null,
                List.of(pause("REGULAR","12:00","12:45"),pause("REGULAR","12:30","13:00"))));
        assertThrows(OrgException.class,()->WorkTimeCodeRules.validate(t("09:00"),t("18:00"),null,null,null,null,List.of(pause("AFTER","19:00","19:10"))));
        assertThrows(OrgException.class,()->WorkTimeCodeRules.validate(t("09:00:01"),t("18:00"),null,null,null,null,List.of()));
    }
}
