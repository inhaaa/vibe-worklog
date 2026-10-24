package kr.tna.clock;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import kr.tna.schedule.ScheduleData;
import org.springframework.format.annotation.DateTimeFormat;

public final class ClockData {
    private ClockData() { }
    public record Event(UUID id,String kind,Instant occurredAt,LocalDate workDate) { }
    public record Interval(Instant startAt,Instant endAt) { }
    public record Confirmation(UUID id,long confirmedInputRevision,long reviewRevision,boolean presenceAsWork,
            boolean breaksMatch,String sourceFingerprint,List<Interval> breaks,Instant confirmedAt) { }
    public record DaySnapshot(UUID employeeId,LocalDate workDate,List<Event> events,ScheduleData.Day schedule,
            UUID scheduleVersionId,UUID calendarVersionId,String dayKind,long inputRevision,long reviewRevision,
            Confirmation confirmation,String sourceFingerprint) { }
    public record ConfirmationForm(@DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate workDate,
            Long baseInputRevision,Long expectedReviewRevision,boolean presenceAsWork,boolean breaksMatch,
            String sourceFingerprint,UUID commandId) { }
}
