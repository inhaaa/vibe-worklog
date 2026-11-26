package kr.tna.worktime;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.zip.ZipInputStream;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MonthCloseWorkbookTest {
    @Test
    void countsTransferredPeopleOnceAndWritesDecimalsAsNumbersAndNamesAsText() throws Exception {
        var person = UUID.randomUUID();
        var assigned = new MonthCloseExportMapper.Row(person, "0001", "=예시", UUID.randomUUID(), "A", "부서A",
                12, 2, 7_200_000L, 0.5, 14_400_000L, false, null, null, null, null, null, null, null, null, null);
        var unassigned = new MonthCloseExportMapper.Row(person, "0001", "=예시", null, null, null,
                18, 1, 3_600_000L, 0, 0L, false, null, null, null, null, null, null, null, null, null);
        String sheet = worksheet(List.of(assigned, unassigned), true);
        assertTrue(sheet.contains("회사 전체"));
        assertTrue(sheet.contains("소속 미등록"));
        assertTrue(sheet.contains("2026-11-01 10:00"));
        assertTrue(sheet.contains("=예시"));
        assertFalse(sheet.contains("<f>"));
        assertTrue(sheet.contains("<v>0.5</v>"));
        String summary = summaryRow(sheet);
        assertTrue(summary.contains("<v>30</v>"));
        assertTrue(summary.contains("<v>1</v>"));
        assertTrue(summary.contains("<v>3.0</v>"));
        assertTrue(summary.contains("<v>4.0</v>"));
    }

    @Test
    void unknownWorkRemainsUnknownInTheCompanyTotalEvenWhenOtherPeopleHaveKnownHours() throws Exception {
        var unknown = new MonthCloseExportMapper.Row(UUID.randomUUID(), "1", "가", null, null, null,
                2, null, null, 0.5, null, false, null, null, null, null, null, null, null, null, null);
        var known = new MonthCloseExportMapper.Row(UUID.randomUUID(), "2", "나", null, null, null,
                1, 0, 0L, 0, 0L, false, null, null, null, null, null, null, null, null, null);
        String sheet = worksheet(List.of(unknown, known), false);
        assertTrue(sheet.contains("기존 마감 · 이관 시점 기준"));
        String summary = summaryRow(sheet);
        assertEquals(2, summary.split(">미확정<", -1).length - 1);
        assertTrue(summary.contains("<v>0.5</v>"));
        assertFalse(sheet.contains("유급휴가 인정시간"));
    }

    @Test
    void unknownPaidLeaveDoesNotEraseConfirmedWorkOrTurnTheScopeCreditIntoZero() throws Exception {
        var incomplete = new MonthCloseExportMapper.Row(UUID.randomUUID(), "01", "가", null, null, null, 1, 0, 0L, 1, null, false, null, null, null, null, null, null, null, null, null);
        var confirmed = new MonthCloseExportMapper.Row(UUID.randomUUID(), "02", "나", null, null, null, 1, 1, 3_600_000L, 0.5, 7_200_000L, false, null, null, null, null, null, null, null, null, null);
        String summary = summaryRow(worksheet(List.of(incomplete, confirmed), true));
        assertTrue(summary.contains("<v>1.0</v>"));
        assertTrue(summary.contains("<v>1.5</v>"));
        assertEquals(1, summary.split(">미확정<", -1).length - 1);
    }

    private static MonthCloseExportMapper.Row payroll(String no, Long approved, Long unapproved, int lateCount, long lateMillis,
            double absence) {
        return new MonthCloseExportMapper.Row(UUID.randomUUID(), no, "급여", null, null, null, 1, 1, 3_600_000L, 0, 0L,
                true, approved, unapproved, 3_600_000L, 7_200_000L, lateCount, lateMillis, 1, 1_800_000L,
                java.math.BigDecimal.valueOf(absence));
    }

    @Test
    void payrollColumnsFollowTheLeaveColumnAndSumAcrossRowsUnlessAnyValueIsUnknown() throws Exception {
        var a = payroll("1", 7_200_000L, 3_600_000L, 1, 420_000L, 1.0);
        var b = payroll("2", null, null, 0, 0L, 0.5);
        String sheet = worksheet(List.of(a, b), true);
        int header = sheet.indexOf(">사번<");
        String headerRow = sheet.substring(header, sheet.indexOf("</row>", header));
        assertTrue(headerRow.indexOf("유급휴가 인정시간(시간)") < headerRow.indexOf("인정 연장(시간)"));
        assertTrue(headerRow.indexOf("인정 연장(시간)") < headerRow.indexOf("승인 없는 초과(시간)"));
        assertTrue(headerRow.indexOf("지각(회)") < headerRow.indexOf("지각(분)"));
        assertTrue(headerRow.endsWith("결근(일)</t></is></c>") || headerRow.contains(">결근(일)<"));
        String summary = summaryRow(sheet);
        assertEquals(2, summary.split(">미확정<", -1).length - 1, "approved/unapproved sums are unknown");
        assertTrue(summary.contains("<v>2.0</v>"), "night 1h+1h");
        assertTrue(summary.contains("<v>4.0</v>"), "holiday 2h+2h");
        assertTrue(summary.contains("<v>7</v>"), "late minutes");
        assertTrue(summary.contains("<v>1.5</v>"), "absence days");
        assertTrue(sheet.contains("<v>2.0</v>"));
    }

    @Test
    void rowsFromClosesBeforePayrollCaptureLeaveEveryPayrollCellAndTotalBlank() throws Exception {
        var legacy = new MonthCloseExportMapper.Row(UUID.randomUUID(), "1", "가", null, null, null, 1, 0, 0L, 0, 0L,
                false, null, null, null, null, null, null, null, null, null);
        String sheet = worksheet(List.of(legacy), true);
        assertTrue(sheet.contains(">결근(일)<"));
        assertFalse(summaryRow(sheet).contains("미확정"));
        int row = sheet.indexOf(">1<");
        assertFalse(sheet.substring(row, sheet.indexOf("</row>", row)).contains("미확정"));
    }

    private static String summaryRow(String sheet) {
        int start = sheet.indexOf(">범위 합계<");
        return sheet.substring(start, sheet.indexOf("</row>", start));
    }

    private static String worksheet(List<MonthCloseExportMapper.Row> rows, boolean atClose) throws Exception {
        var event = new MonthCloseExportMapper.Event(UUID.randomUUID(), 1, "CLOSED",
                Instant.parse("2026-11-01T01:00:00Z"), "담당자", atClose, atClose);
        byte[] bytes = MonthCloseWorkbook.write("2026-10", event, "회사 전체", rows);
        try (var zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                if (entry.getName().equals("xl/worksheets/sheet1.xml")) {
                    return new String(zip.readAllBytes(), StandardCharsets.UTF_8);
                }
            }
        }
        throw new AssertionError("worksheet missing");
    }
}
