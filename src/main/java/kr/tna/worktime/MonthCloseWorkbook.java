package kr.tna.worktime;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
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
        List<List<Object>> sheet = new ArrayList<>();
        sheet.add(List.of("월 마감 결과", month, scope));
        sheet.add(List.of("마감 버전", event.version(), "마감자", event.actorName(), "마감 일시(서울)", SEOUL.format(event.changedAt())));
        sheet.add(List.of("자료 기준", event.capturedAtClose() ? "마감 시점 기준" : "기존 마감 · 이관 시점 기준", "근무일 당시 소속"));
        sheet.add(List.of("집계 범위", "부서코드", "사원 수(명)", "재직일수(일)", "실근로 발생 일수(일)", "실근로시간(시간)", "사용 휴가(일)"));
        var total = new Totals();
        var departments = new LinkedHashMap<UUID, Totals>();
        for (Row row : records) {
            total.add(row);
            departments.computeIfAbsent(row.organizationId(), ignored -> new Totals()).add(row);
        }
        sheet.add(total.summary("범위 합계", ""));
        for (Totals department : departments.values()) {
            sheet.add(department.summary(name(department.first), code(department.first)));
        }
        sheet.add(List.of());
        sheet.add(List.of("사번", "성명", "부서코드", "부서명", "재직일수(일)", "실근로 발생 일수(일)", "실근로시간(시간)", "사용 휴가(일)"));
        for (Row row : records) {
            sheet.add(Arrays.asList(row.employeeNo(), row.employeeName(), code(row), name(row), row.employedDays(),
                    row.workedDays(), hours(row.actualMillis()), row.leaveDays()));
        }
        return ReviewWorkbook.create("월 마감 결과", sheet);
    }

    static String name(Row row) { return row.organizationId() == null ? "소속 미등록" : row.organizationName(); }
    private static String code(Row row) { return row.organizationCode() == null ? "" : row.organizationCode(); }
    private static Double hours(Long millis) { return millis == null ? null : millis / 3_600_000.0; }

    private static final class Totals {
        private final Set<UUID> people = new HashSet<>();
        private Row first;
        private long days, workedDays, actualMillis;
        private double leaveDays;
        private boolean unknownWork;

        void add(Row row) {
            if (first == null) first = row;
            people.add(row.employeeId());
            days += row.employedDays();
            leaveDays += row.leaveDays();
            if (row.actualMillis() == null || row.workedDays() == null) {
                unknownWork = true;
            } else {
                workedDays += row.workedDays();
                actualMillis += row.actualMillis();
            }
        }

        List<Object> summary(String name, String code) {
            return Arrays.asList(name, code, people.size(), days, unknownWork ? null : workedDays,
                    unknownWork ? null : hours(actualMillis), leaveDays);
        }
    }
}
