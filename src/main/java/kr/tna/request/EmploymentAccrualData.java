package kr.tna.request;

import java.time.LocalDate;
import java.time.YearMonth;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;
import java.util.UUID;

public final class EmploymentAccrualData {
    private EmploymentAccrualData() { }

    public record Window(int monthNumber, LocalDate periodStart, LocalDate periodEnd, LocalDate accrualOn) { }
    public record OpeningSource(UUID bucketId, String sourceReference, BigDecimal creditedDays, BigDecimal linkedDays, List<LocalDate> accrualDates) { }
    public record Period(int monthNumber, LocalDate periodStart, LocalDate periodEnd, LocalDate accrualOn,
            int scheduledDays, int attendedDays, int leaveDays, int unresolvedDays, String decision, String reason, int grantedDays, String token,
            UUID includedBucketId) { }
    public record Preview(UUID employeeId, String employeeNo, String employeeName, LocalDate joinedOn, LocalDate leftOn,
            LocalDate today, LocalDate usableTo, boolean settled, List<Period> periods, List<OpeningSource> openingSources) { }
    public record PostForm(UUID employeeId, LocalDate accrualOn, String decision, String reason, String token, UUID commandId, UUID includedBucketId) {
        public PostForm(UUID employeeId, LocalDate accrualOn, String decision, String reason, String token, UUID commandId) {
            this(employeeId,accrualOn,decision,reason,token,commandId,null);
        }
    }
    public record BalanceMovement(LocalDate date, BigDecimal days) { }

    /** Each use must be covered on its own date, including reservations already approved for later days. */
    public static boolean covers(List<BalanceMovement> movements, List<RequestData.RequestDay> requested) {
        if (movements.isEmpty()) return true;
        var dates = new TreeMap<LocalDate, BigDecimal>();
        for (var movement : movements) dates.merge(movement.date(), movement.days(), BigDecimal::add);
        for (var day : requested) dates.merge(day.leaveDate(), BigDecimal.valueOf(-day.quantityDays()), BigDecimal::add);
        var balance = BigDecimal.ZERO;
        for (var quantity : dates.values()) {
            balance = balance.add(quantity);
            if (balance.signum() < 0) return false;
        }
        return true;
    }

    public static LocalDate accrualOn(LocalDate joinedOn, int month) {
        var target = YearMonth.from(joinedOn).plusMonths(month);
        return target.isValidDay(joinedOn.getDayOfMonth())
                ? target.atDay(joinedOn.getDayOfMonth()) : target.atEndOfMonth().plusDays(1);
    }

    public static List<Window> periods(LocalDate joinedOn, LocalDate through) {
        var completed = new ArrayList<Window>();
        LocalDate start = joinedOn;
        for (int month = 1; month <= 11; month++) {
            LocalDate next = accrualOn(joinedOn, month);
            if (next.isAfter(through)) break;
            completed.add(new Window(month, start, next.minusDays(1), next));
            start = next;
        }
        return List.copyOf(completed);
    }
}
