package kr.tna.worktime;

import java.time.DateTimeException;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import kr.tna.common.auth.TnaPrincipal;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.ModelAndView;

/** Restores a settlement only when it contains the day acted on. */
@Component
public final class SelectiveSettlementReturn {
    private static final String ID = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";
    private static final Pattern ORIGIN = Pattern.compile(
            "(?:/me/worktime[?]|/admin/attendance[?]employeeId=(" + ID + ")&)"
            + "month=([0-9]{4}-[0-9]{2})&settlementPeriod=(ISO_WEEK|CALENDAR_MONTH)"
            + "&settlementFrom=([0-9]{4}-[0-9]{2}-[0-9]{2})");

    private final WorktimeService worktime;
    public SelectiveSettlementReturn(WorktimeService worktime) { this.worktime = worktime; }

    public String employee(TnaPrincipal actor, String raw, LocalDate date) {
        return validate(actor, raw, date, id -> id == null);
    }

    public String admin(TnaPrincipal actor, String raw, UUID employee, LocalDate date) {
        if (employee == null || !actor.roles().contains("ATTENDANCE_ADMIN")) return null;
        return validate(actor, raw, date, employee::equals);
    }

    private String validate(TnaPrincipal actor, String raw, LocalDate date, Predicate<UUID> eligible) {
        if (raw == null || date == null) return null;
        var match = ORIGIN.matcher(raw);
        if (!match.matches()) return null;
        try {
            YearMonth month = YearMonth.parse(match.group(2));
            LocalDate start = LocalDate.parse(match.group(4));
            LocalDate end;
            if ("ISO_WEEK".equals(match.group(3))) {
                if (start.getDayOfWeek() != DayOfWeek.MONDAY) return null;
                end = start.plusDays(6);
            } else {
                if (!start.equals(month.atDay(1))) return null;
                end = month.atEndOfMonth();
            }
            if (date.isBefore(start) || date.isAfter(end) || start.isAfter(month.atEndOfMonth())
                    || end.isBefore(month.atDay(1))) return null;
        } catch (DateTimeException invalid) {
            return null;
        }
        UUID employee = match.group(1) == null ? null : UUID.fromString(match.group(1));
        if (!eligible.test(employee)) return null;
        UUID target = employee != null ? employee : actor.employeeId();
        return worktime.matchesSettlementDay(actor, target, date, match.group(3)) ? raw : null;
    }

    public static ModelAndView attach(ModelAndView page, String validatedOrigin) {
        if (validatedOrigin != null) page.addObject("settlementReturn", validatedOrigin);
        return page;
    }
}
