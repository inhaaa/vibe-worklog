package kr.tna.request;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import kr.tna.worktime.WorktimeService.LeaveAttendanceDay;

/** 근로기준법 제60조 연차 일수와 출근율·월 개근 판정(설계 2026-11-27 §3·§4). 저장소에 접근하지 않는다. */
public final class StatutoryLeave {
    public static final String FISCAL = "FISCAL", HIRE_DATE = "HIRE_DATE";
    public static final String GRANT = "GRANT", NEEDS_REVIEW = "NEEDS_REVIEW", FULL = "FULL", NOT_FULL = "NOT_FULL";
    public static final BigDecimal BASE_DAYS = new BigDecimal("15.0");
    private static final BigDecimal TWO = BigDecimal.valueOf(2), FOUR = BigDecimal.valueOf(4), FIVE = BigDecimal.valueOf(5),
            HUNDRED = BigDecimal.valueOf(100), YEAR_DAYS = BigDecimal.valueOf(365);

    private StatutoryLeave() { }

    /** 근속 n년차 일수: 15 + floor((n-1)/2), 최대 25. */
    public static BigDecimal serviceDays(int serviceYear) {
        if (serviceYear < 1) throw new IllegalArgumentException("serviceYear");
        return BigDecimal.valueOf(Math.min(15 + (serviceYear - 1) / 2, 25)).setScale(1);
    }

    /** 입사 n주년 기념일. 2월 29일 입사자의 평년 기념일은 3월 1일이다. n=0이면 입사일이다. */
    public static LocalDate anniversary(LocalDate joinedOn, int years) {
        LocalDate date = joinedOn.plusYears(years);
        return date.getDayOfMonth() == joinedOn.getDayOfMonth() ? date : date.plusDays(1);
    }

    /** 날짜가 속한 회계연도의 시작일. */
    public static LocalDate fiscalStart(LocalDate date, int fiscalStartMonth) {
        return LocalDate.of(date.getYear() - (date.getMonthValue() < fiscalStartMonth ? 1 : 0), fiscalStartMonth, 1);
    }

    /** 회계연도 기준 근속년차: 입사한 회계연도의 시작일부터 이번 회계연도 시작일까지의 햇수. */
    public static int fiscalServiceYear(LocalDate joinedOn, LocalDate fiscalYearStart, int fiscalStartMonth) {
        return (int) ChronoUnit.YEARS.between(fiscalStart(joinedOn, fiscalStartMonth), fiscalYearStart);
    }

    /** 입사 다음 회계연도 비례 일수: 15 × (입사일~입사 회계연도 말일 일수) / 365를 0.5일 단위로 올린다. 15일을 넘지 않는다. */
    public static BigDecimal prorated(LocalDate joinedOn, LocalDate fiscalYearStart) {
        long days = ChronoUnit.DAYS.between(joinedOn, fiscalYearStart);
        BigDecimal halves = BigDecimal.valueOf(15 * 2 * days).divide(YEAR_DAYS, 0, RoundingMode.CEILING);
        return halves.divide(TWO, 1, RoundingMode.UNNECESSARY).min(BASE_DAYS);
    }

    public record Evidence(int scheduledDays, BigDecimal absenceDays, int unresolvedDays) {
        public static Evidence of(List<LeaveAttendanceDay> days) {
            int scheduled = 0, unresolved = 0;
            BigDecimal absence = BigDecimal.ZERO;
            for (var day : days) {
                if (day.scheduled()) scheduled++;
                if (day.unresolved()) unresolved++;
                absence = absence.add(BigDecimal.valueOf(day.absenceDays()));
            }
            return new Evidence(scheduled, absence.setScale(1), unresolved);
        }
    }

    public record Judgement(String decision, BigDecimal attendanceRate, String reason) { }

    /** 연간 부여 80% 판정(§4.1). 출근율은 소수 첫째 자리에서 버려 표시하고 비교는 정확히 한다. */
    public static Judgement annual(Evidence evidence) {
        if (evidence.unresolvedDays() > 0) return new Judgement(NEEDS_REVIEW, null, "판정 불가 " + evidence.unresolvedDays() + "일");
        if (evidence.scheduledDays() == 0) return new Judgement(NEEDS_REVIEW, null, "소정근로일 0일");
        BigDecimal scheduled = BigDecimal.valueOf(evidence.scheduledDays());
        BigDecimal attended = scheduled.subtract(evidence.absenceDays());
        BigDecimal rate = attended.multiply(HUNDRED).divide(scheduled, 1, RoundingMode.DOWN);
        if (attended.multiply(FIVE).compareTo(scheduled.multiply(FOUR)) >= 0) return new Judgement(GRANT, rate, null);
        return new Judgement(NEEDS_REVIEW, rate, "출근율 " + rate.toPlainString() + "%");
    }

    /** 월 개근 판정(§4.2): 결근이 있으면 미개근, 판정 불가가 있으면 확인 필요, 아니면 개근. */
    public static String monthly(Evidence evidence) {
        if (evidence.absenceDays().signum() > 0) return NOT_FULL;
        return evidence.unresolvedDays() > 0 ? NEEDS_REVIEW : FULL;
    }
}
