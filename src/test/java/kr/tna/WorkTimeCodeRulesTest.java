package kr.tna;

import static org.junit.jupiter.api.Assertions.*;

import java.time.LocalTime;
import java.util.List;
import kr.tna.org.OrgException;
import kr.tna.schedule.WorkTimeCodeData.*;
import kr.tna.schedule.WorkTimeCodeRules;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class WorkTimeCodeRulesTest {
    private static LocalTime t(String value) { return LocalTime.parse(value); }
    private static Break pause(String kind, String start, String end) { return new Break(kind,t(start),t(end)); }

    @Test void legacyJsonDefaultsToFixedAndSelectiveJsonDefaultsToCalendarMonth() {
        var json=JsonMapper.builder().build();
        var legacy=json.readValue("{\"startTime\":\"09:00\",\"endTime\":\"18:00\"}",Command.class);
        assertEquals("FIXED",json.valueToTree(legacy).path("workMode").asString());
        var selective=json.readValue("""
                {"workMode":"SELECTIVE","standardMinutes":420,"coreStart":"10:00","coreEnd":"15:00",
                 "startTime":"07:00","endTime":"20:00"}
                """,Command.class);
        var policy=json.valueToTree(selective);
        assertEquals("SELECTIVE",policy.path("workMode").asString());
        assertEquals("CALENDAR_MONTH",policy.path("settlementPeriod").asString());
        assertEquals(420,policy.path("standardMinutes").asInt());
    }

    private static Command policy(String mode, Integer standard, LocalTime coreStart, LocalTime coreEnd,
            String period, LocalTime afterStart, LocalTime afterEnd) {
        return new Command(null,null,null,null,null,null,null,t("07:00"),t("20:00"),null,null,afterStart,afterEnd,
                List.of(pause("REGULAR","12:00","13:00")),null,null,mode,coreStart,coreEnd,standard,period);
    }

    @Test void selectiveTargetUsesStandardMinutesAndAcceptsOptionalCoreAndWeeklySettlement() {
        var calendar=policy("SELECTIVE",420,t("10:00"),t("15:00"),null,null,null);
        assertEquals(new Totals(420,0),WorkTimeCodeRules.validate(calendar,calendar.breaks()));
        var weekly=policy("SELECTIVE",720,null,null,"ISO_WEEK",null,null);
        assertEquals(new Totals(720,0),WorkTimeCodeRules.validate(weekly,weekly.breaks()));
    }

    @Test void rejectsInvalidSelectiveWindowCoreStandardPeriodAndFixedOvertimeFields() {
        for (var invalid : List.of(
                policy("SELECTIVE",null,null,null,null,null,null),
                policy("SELECTIVE",0,null,null,null,null,null),
                policy("SELECTIVE",721,null,null,null,null,null),
                policy("SELECTIVE",420,null,t("15:00"),null,null,null),
                policy("SELECTIVE",420,t("06:59"),t("15:00"),null,null,null),
                policy("SELECTIVE",420,t("10:00"),t("20:01"),null,null,null),
                policy("SELECTIVE",420,t("10:00:01"),t("15:00"),null,null,null),
                policy("SELECTIVE",420,t("15:00"),t("10:00"),null,null,null),
                policy("SELECTIVE",420,null,null,"ROLLING_MONTH",null,null),
                policy("SELECTIVE",420,null,null,null,t("20:00"),t("21:00")),
                policy("FIXED",420,null,null,null,null,null),
                policy("OTHER",null,null,null,null,null,null))) {
            assertEquals(422,assertThrows(OrgException.class,()->WorkTimeCodeRules.validate(invalid,invalid.breaks())).status());
        }
        var selective=policy("SELECTIVE",420,null,null,null,null,null);
        assertThrows(OrgException.class,()->WorkTimeCodeRules.validate(selective,List.of(pause("AFTER","20:00","21:00"))));
    }

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
