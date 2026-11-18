package kr.tna;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.time.*;
import java.util.List;
import java.util.UUID;
import kr.tna.clock.ClockData.DaySnapshot;
import kr.tna.clock.ClockService;
import kr.tna.org.EmployeeAdminMapper;
import kr.tna.org.OrgData.Employee;
import kr.tna.org.OrgException;
import kr.tna.overtime.OvertimePolicyService;
import kr.tna.schedule.ScheduleData;
import kr.tna.schedule.WorkTimeCodeData.Break;
import kr.tna.workspace.PersonalScheduleMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class OvertimePolicyTest {
    private final UUID tenant = UUID.randomUUID(), employee = UUID.randomUUID(), schedule = UUID.randomUUID(),
            code = UUID.randomUUID(), calendar = UUID.randomUUID();
    private final LocalDate date = LocalDate.of(2026, 11, 25);
    private final ClockService clocks = mock(ClockService.class);
    private final PersonalScheduleMapper schedules = mock(PersonalScheduleMapper.class);
    private final EmployeeAdminMapper employees = mock(EmployeeAdminMapper.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-11-25T00:00:00Z"), ZoneOffset.UTC);
    private final OvertimePolicyService service = new OvertimePolicyService(clocks, schedules, employees, clock);

    private static LocalTime t(String text) { return LocalTime.parse(text); }

    @BeforeEach void normalWorkDay() {
        when(employees.get(tenant, employee)).thenReturn(new Employee(employee, "01", "합성 사원", date.minusYears(1), null, true, 1));
        snapshot(true, "WORKDAY", calendar);
        policy(true, t("18:30"), t("21:00"));
        when(schedules.breaks(tenant, code)).thenReturn(List.of(new Break("REGULAR",t("12:00"),t("13:00")),
                new Break("BEFORE",t("07:15"),t("07:30")), new Break("AFTER",t("20:00"),t("20:15"))));
    }

    private void policy(boolean group, LocalTime afterStart, LocalTime afterEnd) {
        when(schedules.policy(tenant, schedule, date.getDayOfWeek().getValue())).thenReturn(
                new PersonalScheduleMapper.Policy(group, "주간조", "G01", "W01", "주간", code,
                        t("09:00"),t("18:00"),t("07:00"),t("08:30"),afterStart,afterEnd));
    }

    private void snapshot(boolean workday,String kind,UUID cal) {
        var day = new ScheduleData.Day(date.getDayOfWeek().getValue(),workday,workday?t("09:00"):null,
                workday?t("18:00"):null,List.of());
        when(clocks.snapshot(tenant,employee,date)).thenReturn(new DaySnapshot(employee,date,List.of(),day,
                schedule,cal,kind,0,0,null,"test",List.of()));
    }

    @Test void deductsOnlyIntersectionInsideSelectedWindow() {
        var windows = service.day(tenant,employee,date).windows();
        assertEquals(75,windows.getFirst().minutes());
        assertEquals(135,windows.getLast().minutes());
        assertEquals(135,evaluate("18:30","21:00",true).requestedMinutes());
        assertEquals(10,evaluate("19:50","20:10",true).requestedMinutes());
        assertEquals(90,evaluate("18:30","20:00",true).requestedMinutes());
        assertEquals(45,evaluate("20:15","21:00",true).requestedMinutes());
    }

    @Test void rejectsEmptyCrossMidnightSubminuteAndOutsideIntervals() {
        for (String[] pair : List.of(new String[]{"18:15","19:00"}, new String[]{"20:00","20:15"},
                new String[]{"21:00","21:00"}, new String[]{"20:00","01:00"},new String[]{"18:30:01","19:00"},
                new String[]{"07:00","19:00"})) {
            assertThrows(OrgException.class,()->evaluate(pair[0],pair[1],true));
        }
    }

    @Test void submissionClosesAtStartButFinalApprovalRechecksTheOriginalDayWithoutDeadline() {
        assertThrows(OrgException.class,()->service.requireAdvance(date,t("09:00")));
        assertThrows(OrgException.class,()->evaluate("07:00","08:30",true));
        assertEquals(75,evaluate("07:00","08:30",false).requestedMinutes());
        assertDoesNotThrow(()->service.requireAdvance(date,t("09:01")));
    }

    @Test void evidenceTokenIncludesCalendarVersionAndSubmittedWindow() {
        var first = evaluate("18:30","21:00",true);
        assertEquals(first,evaluate("18:30","21:00",false));
        assertNotEquals(first.basisToken(),evaluate("18:31","21:00",false).basisToken());
        snapshot(true,"WORKDAY",UUID.randomUUID());
        assertNotEquals(first.basisToken(),evaluate("18:30","21:00",false).basisToken());
    }

    @Test void explainsHolidayUnknownCalendarAndNonWorkdayAndRejectsTheirIntervals() {
        snapshot(true,"HOLIDAY",calendar);
        unavailable("휴일");
        snapshot(true,"UNCONFIRMED",null);
        unavailable("캘린더");
        snapshot(false,"WORKDAY",calendar);
        unavailable("비근로일");
    }

    @Test void legacyUnassignedAndOutsideEmploymentAreUnavailable() {
        policy(false,null,null);
        unavailable("고정배정");
        when(clocks.snapshot(tenant,employee,date)).thenReturn(new DaySnapshot(employee,date,List.of(),null,
                null,calendar,"WORKDAY",0,0,null,"test",List.of()));
        unavailable("미배정");
        when(employees.get(tenant,employee)).thenReturn(new Employee(employee,"01","합성 사원",date.minusYears(1),
                date.minusDays(1),true,1));
        unavailable("재직");
    }

    private OvertimePolicyService.Basis evaluate(String start,String end,boolean ahead) {
        return service.evaluate(tenant,employee,date,t(start),t(end),ahead);
    }
    private void unavailable(String explanation) {
        assertTrue(service.day(tenant,employee,date).unavailableReason().contains(explanation));
        assertTrue(service.day(tenant,employee,date).windows().isEmpty());
        assertThrows(OrgException.class,()->evaluate("18:30","19:30",false));
    }
}
