package kr.tna.schedule;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

public final class WorkTimeCodeData {
    private WorkTimeCodeData() {}

    public interface Policy {
        String workMode();
        LocalTime startTime();
        LocalTime endTime();
        LocalTime beforeStart();
        LocalTime beforeEnd();
        LocalTime afterStart();
        LocalTime afterEnd();
        LocalTime coreStart();
        LocalTime coreEnd();
        Integer standardMinutes();
        String settlementPeriod();
    }

    public record Break(String kind, LocalTime start, LocalTime end) {}
    public record Command(UUID commandId, UUID id, Long expectedVersion, String code, String name,
            LocalDate effectiveFrom, Boolean enabled, LocalTime startTime, LocalTime endTime,
            LocalTime beforeStart, LocalTime beforeEnd, LocalTime afterStart, LocalTime afterEnd,
            List<Break> breaks, String reason, UUID replaceVersionId, String workMode, LocalTime coreStart,
            LocalTime coreEnd, Integer standardMinutes, String settlementPeriod) implements Policy {
        public Command {
            if (workMode == null) workMode = "FIXED";
            if ("SELECTIVE".equals(workMode) && settlementPeriod == null) settlementPeriod = "CALENDAR_MONTH";
        }

        public Command(UUID commandId, UUID id, Long expectedVersion, String code, String name,
                LocalDate effectiveFrom, Boolean enabled, LocalTime startTime, LocalTime endTime,
                LocalTime beforeStart, LocalTime beforeEnd, LocalTime afterStart, LocalTime afterEnd,
                List<Break> breaks, String reason, UUID replaceVersionId) {
            this(commandId,id,expectedVersion,code,name,effectiveFrom,enabled,startTime,endTime,
                    beforeStart,beforeEnd,afterStart,afterEnd,breaks,reason,replaceVersionId,"FIXED",null,null,null,null);
        }
    }
    public record Master(UUID id, String code, long version) {}
    public record VersionRow(UUID id, UUID codeId, long revision, LocalDate effectiveFrom, boolean enabled,
            String name, LocalTime startTime, LocalTime endTime, LocalTime beforeStart, LocalTime beforeEnd,
            LocalTime afterStart, LocalTime afterEnd, String reason, String workMode, LocalTime coreStart,
            LocalTime coreEnd, Integer standardMinutes, String settlementPeriod) implements Policy {}
    public record BreakRow(UUID versionId, String kind, LocalTime startTime, LocalTime endTime) {}
    public record GroupUse(UUID versionId, UUID groupId, String code, String name) {}
    public record Totals(long scheduledMinutes, long overtimeMinutes) {}
    public record Version(UUID id, long revision, LocalDate effectiveFrom, boolean enabled,
            String name, LocalTime startTime, LocalTime endTime, LocalTime beforeStart, LocalTime beforeEnd,
            LocalTime afterStart, LocalTime afterEnd, List<Break> breaks, long scheduledMinutes,
            long overtimeMinutes, String reason, List<GroupUse> groups, String workMode, LocalTime coreStart,
            LocalTime coreEnd, Integer standardMinutes, String settlementPeriod) {}
    public record Code(UUID id, String code, long version, List<Version> versions) {}
    public record Catalog(LocalDate date, List<Code> codes) {}
}
