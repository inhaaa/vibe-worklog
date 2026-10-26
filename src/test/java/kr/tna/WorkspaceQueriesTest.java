package kr.tna;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import kr.tna.clock.ClockData.DaySnapshot;
import kr.tna.clock.ClockService;
import kr.tna.common.auth.TnaPrincipal;
import kr.tna.common.tenant.BusinessCommands;
import kr.tna.org.EmployeeMapper;
import kr.tna.org.EmployeeOverview;
import kr.tna.org.OrgException;
import kr.tna.request.LeaveWorkflowService;
import kr.tna.request.RequestData.*;
import kr.tna.workspace.WorkspaceQueries;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class WorkspaceQueriesTest {
    private final TnaPrincipal actor = new TnaPrincipal(UUID.randomUUID(), UUID.randomUUID(),
            UUID.randomUUID(), 1, Set.of("EMPLOYEE"), Instant.parse("2026-10-26T00:00:00Z"));
    private final BusinessCommands commands = mock(BusinessCommands.class);
    private final ClockService clocks = mock(ClockService.class);
    private final EmployeeMapper employees = mock(EmployeeMapper.class);
    private final LeaveWorkflowService leave = mock(LeaveWorkflowService.class);
    private final AtomicBoolean reading = new AtomicBoolean();
    private WorkspaceQueries queries;

    @BeforeEach
    void existingReadBoundary() {
        queries = queryAt("2026-10-26T00:00:00Z");
        when(commands.read(eq(actor), any())).thenAnswer(call -> {
            reading.set(true);
            try { return ((Supplier<?>) call.getArgument(1)).get(); }
            finally { reading.set(false); }
        });
        when(clocks.snapshot(eq(actor.tenantId()), eq(actor.employeeId()), any())).thenAnswer(call -> {
            assertTrue(reading.get(), "snapshot must be inside the existing authorized tenant read");
            return snapshot(call.getArgument(2));
        });
        when(employees.findSelf(actor.tenantId(), actor.employeeId()))
                .thenReturn(new EmployeeOverview("합성 직원", "합성 회사"));
        when(leave.mine(actor)).thenReturn(List.of());
        when(leave.buckets(actor, actor.employeeId())).thenReturn(List.of());
    }

    @Test
    void monthReadsOnlyItsThirtyOneDaysInsideTheAuthorizedScope() {
        var month = queries.calendar(actor, YearMonth.of(2026, 12), LocalDate.of(2026, 12, 31));

        assertEquals(31, month.days().size());
        assertEquals(YearMonth.of(2027, 1), month.nextMonth());
        assertEquals(YearMonth.of(2026, 11), month.previousMonth());
        assertEquals(LocalDate.of(2026, 12, 31), month.selectedDay().snapshot().workDate());
        assertTrue(month.days().stream().allMatch(day -> day.snapshot().employeeId().equals(actor.employeeId())));
        verify(clocks, times(31)).snapshot(eq(actor.tenantId()), eq(actor.employeeId()), any());
        verify(commands).read(eq(actor), any());
    }

    @Test
    void invalidSelectionAndYearAreRejectedBeforeSnapshotQueries() {
        assertEquals(422, assertThrows(OrgException.class, () -> queries.calendar(actor,
                YearMonth.of(2026, 2), LocalDate.of(2026, 3, 1))).status());
        assertThrows(OrgException.class, () -> queries.calendar(actor, YearMonth.of(0, 1), null));
        assertThrows(OrgException.class, () -> queries.calendar(actor, YearMonth.of(10000, 1), null));
        verifyNoInteractions(clocks);
    }

    @Test
    void defaultMonthAndHomeDateUseSeoulAcrossUtcYearBoundary() {
        var boundary = queryAt("2026-12-31T15:05:00Z");
        var month = boundary.calendar(actor, null, null);
        var home = boundary.home(actor);

        assertEquals(YearMonth.of(2027, 1), month.month());
        assertEquals(LocalDate.of(2027, 1, 1), month.selectedDate());
        assertEquals(LocalDate.of(2027, 1, 1), home.today().snapshot().workDate());
        assertNull(home.availableLeaveDays(), "no opening record must not become a zero-day balance");
        assertEquals("근무 일정 미배정", home.today().scheduleText());
        assertEquals("실제 근로 미확인", home.today().confirmationText());
    }

    @Test
    void approvedLeaveAndPendingCancellationKeepTheOriginalEffectAndZeroDaysDistinct() {
        LocalDate date = LocalDate.of(2026, 10, 26);
        var original = request("LEAVE_DAY", "APPROVED", "EFFECTIVE", actor.employeeId(), date, date.plusDays(1), null);
        var cancel = request("CANCEL_LEAVE", "PENDING", "NONE", actor.employeeId(), date, date.plusDays(1), original.id());
        var canceled = request("LEAVE_DAY", "APPROVED", "CANCELED", actor.employeeId(), date, date, null);
        var foreign = request("LEAVE_DAY", "APPROVED", "EFFECTIVE", UUID.randomUUID(), date, date, null);
        when(leave.mine(actor)).thenReturn(List.of(cancel, original, canceled, foreign));
        when(leave.get(actor, original.id())).thenReturn(new RequestDetail(original, List.of(
                new RequestDay(date, 1, UUID.randomUUID(), UUID.randomUUID(), null),
                new RequestDay(date.plusDays(1), 0, UUID.randomUUID(), UUID.randomUUID(), "CONFIRMED_HOLIDAY")), null, List.of()));
        when(leave.get(actor, canceled.id())).thenReturn(new RequestDetail(canceled,
                List.of(new RequestDay(date, 1, null, null, null)), null, List.of()));

        var month = queries.calendar(actor, YearMonth.of(2026, 10), date);
        var selected = month.selectedDay().requests();
        assertEquals(3, selected.size(), "authored requests targeting another employee are not personal leave");
        assertTrue(selected.stream().anyMatch(item -> item.effectiveLeave() && item.statusLabel().equals("승인 유효")));
        assertTrue(selected.stream().anyMatch(item -> item.statusLabel().equals("취소 검토 중 · 원승인 유효")));
        assertTrue(selected.stream().anyMatch(item -> item.statusLabel().equals("승인 후 취소")));
        var holiday = month.days().stream().filter(day -> day.snapshot().workDate().equals(date.plusDays(1))).findFirst().orElseThrow();
        assertTrue(holiday.requests().stream().noneMatch(item -> item.effectiveLeave()));
        assertTrue(holiday.requests().stream().allMatch(item -> item.kindLabel().contains("차감 0일")));
        verify(leave, never()).get(actor, foreign.id());
    }

    @Test
    void homeUsesCurrentAvailableDaysAndCountsOnlyTheAuthenticatedEmployeesPendingRequests() {
        LocalDate date = LocalDate.of(2026, 10, 26);
        when(leave.buckets(actor, actor.employeeId())).thenReturn(List.of(bucket(9), bucket(4)));
        var pending = request("ATTENDANCE_CORRECTION", "PENDING", "NONE", actor.employeeId(), date, date, null);
        var foreign = request("ATTENDANCE_CORRECTION", "PENDING", "NONE", UUID.randomUUID(), date, date, null);
        when(leave.mine(actor)).thenReturn(List.of(pending, foreign));

        var home = queries.home(actor);

        assertEquals(13L, home.availableLeaveDays());
        assertEquals(1L, home.pendingRequests());
        assertEquals(1, home.recentRequests().size());
        assertEquals("정정 신청", home.recentRequests().getFirst().kindLabel());
    }

    private WorkspaceQueries queryAt(String instant) {
        return new WorkspaceQueries(commands, clocks, employees, leave,
                Clock.fixed(Instant.parse(instant), ZoneOffset.UTC));
    }

    private DaySnapshot snapshot(LocalDate date) {
        return new DaySnapshot(actor.employeeId(), date, List.of(), null, null, null,
                "UNCONFIRMED", 0, 0, null, "synthetic-source", List.of());
    }

    private RequestView request(String kind, String status, String effect, UUID target,
            LocalDate from, LocalDate to, UUID original) {
        return new RequestView(UUID.randomUUID(), target, "합성 직원", actor.accountId(), kind, status,
                UUID.randomUUID(), "합성 승인자", UUID.randomUUID(), 0, Instant.parse("2026-10-26T00:00:00Z"),
                "합성 근거", null, original, null, from, to, false, 0, effect, 1);
    }

    private Bucket bucket(int available) {
        LocalDate date = LocalDate.of(2026, 10, 26);
        return new Bucket(UUID.randomUUID(), actor.employeeId(), "합성 직원", date, date, date.plusYears(1),
                "합성 기초 근거", 0, 20, available, 0, 0);
    }
}
