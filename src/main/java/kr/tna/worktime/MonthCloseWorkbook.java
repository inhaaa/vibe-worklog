package kr.tna.worktime;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import kr.tna.worktime.MonthCloseExportMapper.Event;
import kr.tna.worktime.MonthCloseExportMapper.Row;

final class MonthCloseWorkbook {
    private static final DateTimeFormatter SEOUL = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss")
            .withZone(ZoneId.of("Asia/Seoul"));
    private MonthCloseWorkbook() {}

    static byte[] write(String month, Event event, String scope, List<Row> records) {
        boolean includePaid = event.includesPaidLeave();
        List<List<Object>> sheet = new ArrayList<>();
        sheet.add(List.of("월 마감 결과", month, scope));
        sheet.add(List.of("마감 버전", event.version(), "마감자", event.actorName(), "마감 일시(서울)", SEOUL.format(event.changedAt())));
        sheet.add(List.of("자료 기준", event.capturedAtClose() ? "마감 시점 기준" : "기존 마감 · 이관 시점 기준", "근무일 당시 소속"));
        sheet.add(withPaidColumn(List.of("집계 범위", "부서코드", "사원 수(명)", "재직일수(일)", "실근로 발생 일수(일)", "실근로시간(시간)", "사용 휴가(일)"),
                includePaid, "유급휴가 인정시간(시간)", PAYROLL));
        var total = new Totals();
        var departments = new LinkedHashMap<UUID, Totals>();
        for (Row row : records) {
            total.add(row);
            departments.computeIfAbsent(row.organizationId(), ignored -> new Totals()).add(row);
        }
        sheet.add(total.summary("범위 합계", "", includePaid));
        for (Totals department : departments.values()) {
            sheet.add(department.summary(name(department.first), code(department.first), includePaid));
        }
        sheet.add(List.of());
        sheet.add(withPaidColumn(List.of("사번", "성명", "부서코드", "부서명", "재직일수(일)", "실근로 발생 일수(일)", "실근로시간(시간)", "사용 휴가(일)"),
                includePaid, "유급휴가 인정시간(시간)", PAYROLL));
        for (Row row : records) {
            sheet.add(withPaidColumn(Arrays.asList(row.employeeNo(), row.employeeName(), code(row), name(row), row.employedDays(),
                    row.workedDays(), hours(row.actualMillis()), row.leaveDays()), includePaid, hours(row.paidLeaveMillis()),
                    payrollCells(row)));
        }
        return ReviewWorkbook.create("월 마감 결과", sheet);
    }

    static String name(Row row) { return row.organizationId() == null ? "소속 미등록" : row.organizationName(); }
    private static String code(Row row) { return row.organizationCode() == null ? "" : row.organizationCode(); }
    private static Double hours(Long millis) { return millis == null ? null : millis / 3_600_000.0; }

    private static final List<Object> PAYROLL = List.of("인정 연장(시간)", "승인 없는 초과(시간)", "야간근로(시간)", "휴일근로(시간)",
            "지각(회)", "지각(분)", "조퇴(회)", "조퇴(분)", "결근(일)");
    private static final List<Object> BLANK = Collections.nCopies(PAYROLL.size(), "");

    /** Raw payroll values (millis, counts, minutes, days), or null when the close predates payroll capture. */
    private static Number[] payrollRaw(Row r) {
        if (!r.payrollCaptured()) return null;
        return new Number[] {r.approvedOvertimeMillis(), r.unapprovedExtraMillis(), r.nightWorkMillis(), r.holidayWorkMillis(),
                r.lateCount(), minutes(r.lateMillis()), r.earlyLeaveCount(), minutes(r.earlyLeaveMillis()),
                r.absenceDays() == null ? null : r.absenceDays().doubleValue()};
    }

    private static List<Object> payrollCells(Row r) {
        Number[] raw = payrollRaw(r);
        if (raw == null) return BLANK;
        var cells = new ArrayList<Object>();
        for (int i = 0; i < raw.length; i++) cells.add(i < 4 ? hours((Long) raw[i]) : raw[i]);
        return cells;
    }

    private static Long minutes(Long millis) { return millis == null ? null : millis / 60_000; }

    private static List<Object> withPaidColumn(List<Object> existing, boolean includePaid, Object value, List<Object> payroll) {
        var cells = new ArrayList<>(existing);
        if (includePaid) cells.add(value);
        cells.addAll(payroll);
        return cells;
    }

    private static final class Totals {
        private final Set<UUID> people = new HashSet<>();
        private Row first;
        private long days, workedDays, actualMillis, paidLeaveMillis;
        private double leaveDays;
        private boolean unknownWork, unknownPaidLeave, notCaptured;
        private final double[] payroll = new double[PAYROLL.size()];
        private final boolean[] payrollUnknown = new boolean[PAYROLL.size()];

        void add(Row row) {
            if (first == null) first = row;
            people.add(row.employeeId());
            days += row.employedDays();
            leaveDays += row.leaveDays();
            Number[] raw = payrollRaw(row);
            if (raw == null) notCaptured = true;
            for (int i = 0; i < payroll.length; i++) {
                if (raw == null || raw[i] == null) payrollUnknown[i] = true;
                else payroll[i] += raw[i].doubleValue();
            }
            if (row.paidLeaveMillis() == null) unknownPaidLeave = true;
            else paidLeaveMillis += row.paidLeaveMillis();
            if (row.actualMillis() == null || row.workedDays() == null) {
                unknownWork = true;
            } else {
                workedDays += row.workedDays();
                actualMillis += row.actualMillis();
            }
        }

        List<Object> summary(String name, String code, boolean includePaid) {
            return withPaidColumn(Arrays.asList(name, code, people.size(), days, unknownWork ? null : workedDays,
                    unknownWork ? null : hours(actualMillis), leaveDays), includePaid, unknownPaidLeave ? null : hours(paidLeaveMillis), payrollSummary());
        }

        private List<Object> payrollSummary() {
            if (notCaptured) return BLANK;
            var cells = new ArrayList<Object>();
            for (int i = 0; i < payroll.length; i++) {
                cells.add(payrollUnknown[i] ? null : i < 4 ? payroll[i] / 3_600_000.0 : i < 8 ? (Object) (long) payroll[i] : payroll[i]);
            }
            return cells;
        }
    }
}
