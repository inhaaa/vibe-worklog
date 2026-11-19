package kr.tna.schedule;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;

public final class ScheduleData {
    private ScheduleData() { }
    public record Template(UUID id, String code, String name, boolean active, long version) { }
    public record TemplateVersion(UUID id, UUID templateId, int revision, LocalDate validFrom, String reason) { }
    public record Day(int weekday, boolean workday, LocalTime startTime, LocalTime endTime, List<Break> breaks,
                      String workMode, LocalTime coreStart, LocalTime coreEnd, Integer standardMinutes, String settlementPeriod) {
        public Day {
            if (workMode == null) workMode = "FIXED";
        }
        public Day(int weekday, boolean workday, LocalTime startTime, LocalTime endTime, List<Break> breaks) {
            this(weekday, workday, startTime, endTime, breaks, "FIXED", null, null, null, null);
        }
        public boolean selective() { return "SELECTIVE".equals(workMode); }
    }
    public record Break(LocalTime startTime, LocalTime endTime) { }
    public record DayForm(int weekday, boolean workday, String startTime, String endTime, String breaks) { }
    public record TemplateForm(String code, String name,
            @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate validFrom, List<DayForm> days,
            Long expectedVersion, String reason, UUID commandId) { }
    public record Assignment(UUID id, UUID employeeId, UUID scheduleVersionId, String templateName, int revision,
            LocalDate validFrom, LocalDate validTo, String reason, long version) { }
    public record AssignmentForm(UUID scheduleVersionId,
            @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate validFrom, Long expectedVersion,
            String reason, UUID commandId) { }
}
