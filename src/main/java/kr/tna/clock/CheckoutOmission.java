package kr.tna.clock;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import kr.tna.clock.ClockData.DaySnapshot;
import kr.tna.clock.ClockData.Event;

public final class CheckoutOmission {
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    private CheckoutOmission() { }

    public static boolean hasSingleRecordedArrival(DaySnapshot day) {
        return day.schedule() != null && day.schedule().workday() && day.calendarVersionId() != null
                && "WORKDAY".equals(day.dayKind()) && onlyArrivalOn(day.events(), day.workDate());
    }

    public static boolean eligible(DaySnapshot day, double approvedLeaveDays, boolean pendingCorrection) {
        return !pendingCorrection && approvedLeaveDays < 1 && hasSingleRecordedArrival(day)
                && onlyArrivalOn(day.effectiveEvents(), day.workDate());
    }

    private static boolean onlyArrivalOn(List<Event> events, LocalDate date) {
        if (events.size() != 1) return false;
        Event event = events.getFirst();
        return "IN".equals(event.kind()) && date.equals(event.occurredAt().atZone(SEOUL).toLocalDate());
    }
}
