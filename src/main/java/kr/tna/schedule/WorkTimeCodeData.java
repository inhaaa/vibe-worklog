package kr.tna.schedule;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

public final class WorkTimeCodeData {
    private WorkTimeCodeData() {}

    public record Break(String kind, LocalTime start, LocalTime end) {}
    public record Command(UUID commandId, UUID id, Long expectedVersion, String code, String name,
            LocalDate effectiveFrom, Boolean enabled, LocalTime startTime, LocalTime endTime,
            LocalTime beforeStart, LocalTime beforeEnd, LocalTime afterStart, LocalTime afterEnd,
            List<Break> breaks, String reason, UUID replaceVersionId) {}
    public record Master(UUID id, String code, long version) {}
    public record VersionRow(UUID id, UUID codeId, long revision, LocalDate effectiveFrom, boolean enabled,
            String name, LocalTime startTime, LocalTime endTime, LocalTime beforeStart, LocalTime beforeEnd,
            LocalTime afterStart, LocalTime afterEnd, String reason) {}
    public record BreakRow(UUID versionId, String kind, LocalTime startTime, LocalTime endTime) {}
    public record GroupUse(UUID versionId, UUID groupId, String code, String name) {}
    public record Totals(long scheduledMinutes, long overtimeMinutes) {}
    public record Version(UUID id, long revision, LocalDate effectiveFrom, boolean enabled,
            String name, LocalTime startTime, LocalTime endTime, LocalTime beforeStart, LocalTime beforeEnd,
            LocalTime afterStart, LocalTime afterEnd, List<Break> breaks, long scheduledMinutes,
            long overtimeMinutes, String reason, List<GroupUse> groups) {}
    public record Code(UUID id, String code, long version, List<Version> versions) {}
    public record Catalog(LocalDate date, List<Code> codes) {}
}
