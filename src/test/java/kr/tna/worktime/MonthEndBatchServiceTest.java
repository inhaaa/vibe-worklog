package kr.tna.worktime;

import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import kr.tna.common.auth.TnaPrincipal;
import kr.tna.common.tenant.CompanyReadAccess;
import kr.tna.org.OrgException;
import kr.tna.worktime.CheckoutFollowUpService.DayKey;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MonthEndBatchServiceTest {
    private final TnaPrincipal actor = new TnaPrincipal(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 1,
            Set.of("EMPLOYEE", "ATTENDANCE_ADMIN"), Instant.now());
    private final MonthEndReviewQueries queries = mock(MonthEndReviewQueries.class);
    private final CompanyReadAccess company = mock(CompanyReadAccess.class);
    private final WorktimeMapper mapper = mock(WorktimeMapper.class);
    private final MonthEndBatchService batches = new MonthEndBatchService(queries, null, mapper, null, company);
    private final UUID first = UUID.randomUUID(), second = UUID.randomUUID();
    private final LocalDate marchFirst = LocalDate.of(2024, 3, 1);
    private final DayKey firstDay = new DayKey(first, marchFirst);
    private final DayKey nextDay = new DayKey(first, marchFirst.plusDays(1));
    private final DayKey otherPerson = new DayKey(second, marchFirst);
    private final List<DayKey> selection = List.of(firstDay, otherPerson, nextDay);

    @BeforeEach
    void authorizedCompanyRead() {
        when(company.read(eq(actor), any())).thenAnswer(call -> call.<Supplier<?>>getArgument(1).get());
    }

    @Test
    void previewUsesThePageOrderAndOneWeekPerEmployeeAcrossTheMonthBoundary() {
        givenPage("첫 계산 근거");
        var preview = batches.preview(actor, selection(selection));
        assertEquals(2, preview.weekCount());
        assertEquals(List.of(nextDay, firstDay, otherPerson), preview.rows().stream()
                .map(row -> new DayKey(row.employeeId(), row.workDate())).toList());
        assertTrue(preview.rows().stream().allMatch(row -> row.weekStart().equals(LocalDate.of(2024, 2, 26))
                && row.weekEnd().equals(LocalDate.of(2024, 3, 3)) && row.reasons().equals(List.of("UNCONFIRMED"))));
        assertEquals(preview, batches.preview(actor, selection(selection.reversed())));
        givenPage("같은 표시 사유이지만 변경된 계산 근거");
        assertNotEquals(preview.previewToken(), batches.preview(actor, selection(selection)).previewToken());
    }

    @Test
    void rejectsEmptyDuplicateOversizedAndIncompleteSelectionsBeforeQuerying() {
        for (List<DayKey> invalid : List.of(List.<DayKey>of(), List.of(firstDay, firstDay),
                Collections.nCopies(51, firstDay), List.of(new DayKey(first, null)), List.of(new DayKey(null, marchFirst)))) {
            assertEquals(422, assertThrows(OrgException.class, () -> batches.preview(actor, selection(invalid))).status());
        }
        verifyNoInteractions(queries);
    }

    @Test
    void oneRowOutsideTheVisiblePageInvalidatesTheWholePreview() {
        givenPage("현재 근거");
        var keys = List.of(firstDay, new DayKey(UUID.randomUUID(), marchFirst));
        var error = assertThrows(OrgException.class, () -> batches.preview(actor, selection(keys)));
        assertEquals(409, error.status());
    }

    @Test
    void yearEndExpansionStopsAtClosedJanuaryAndAnExplicitDecemberRequestIsRejected() {
        LocalDate first = LocalDate.of(2024, 12, 30), last = first.plusDays(1);
        var week = CalculationWindow.weeks(first, last);
        assertEquals(new CalculationWindow(first, last), week.openDates(first, last, List.of(last.plusDays(1))));
        assertEquals(409, assertThrows(OrgException.class, () ->
                week.openDates(first, last.plusDays(1), List.of(last.plusDays(1)))).status());
    }

    private MonthEndBatchService.Selection selection(List<DayKey> keys) {
        return new MonthEndBatchService.Selection("2024-03", null, "", "ALL", 0, keys);
    }

    private void givenPage(String basis) {
        var month = YearMonth.from(marchFirst);
        var rows = List.of(row(nextDay, "A"), row(firstDay, "A"), row(otherPerson, "B"));
        var page = new MonthEndReviewQueries.Page(month, month, null, List.of(), "", "ALL", 0, false, true, rows, null);
        when(queries.readForBatch(actor, "2024-03", null, "", "ALL", 0)).thenReturn(
                new MonthEndReviewQueries.BatchPage(page, Map.of(nextDay, "이튿날", firstDay, basis, otherPerson, "다른 사원")));
    }

    private MonthEndReviewQueries.Row row(DayKey key, String number) {
        return new MonthEndReviewQueries.Row(key.employeeId(), number, "합성 " + number, "합성 조직", key.workDate(),
                List.of("UNCONFIRMED"), null, null, false, null);
    }
}
