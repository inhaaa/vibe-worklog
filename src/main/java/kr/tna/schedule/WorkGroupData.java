package kr.tna.schedule;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;
import kr.tna.schedule.ScheduleData.Break;

public final class WorkGroupData {
    private WorkGroupData() {}

    public record WeekInput(int weekday, UUID codeVersionId) {}
    public record GroupCommand(UUID id, String code, String name, LocalDate validFrom, List<WeekInput> days,
            String reason, Long expectedVersion, UUID replaceRevisionId, String token, UUID commandId) {
        public GroupCommand confirm(String token) {
            return new GroupCommand(id,code,name,validFrom,days,reason,expectedVersion,replaceRevisionId,token,commandId);
        }
    }
    public record AssignmentCommand(UUID groupId, LocalDate validFrom, List<UUID> employeeIds, String reason,
            List<UUID> replaceAssignmentIds, String token, UUID commandId) {
        public AssignmentCommand confirm(String token) {
            return new AssignmentCommand(groupId,validFrom,employeeIds,reason,replaceAssignmentIds,token,commandId);
        }
    }
    public record WeekDay(int weekday, UUID codeVersionId, String code, LocalTime startTime, LocalTime endTime,
            List<Break> breaks, int minutes, String workMode, LocalTime coreStart, LocalTime coreEnd,
            Integer standardMinutes, String settlementPeriod) {
        static WeekDay off(int weekday) {
            return new WeekDay(weekday,null,null,null,null,List.of(),0,null,null,null,null,null);
        }
    }
    public record Revision(UUID id, int revision, LocalDate validFrom, String reason) {}
    public record Group(UUID id, String code, String name, long version, UUID revisionId, Integer revision,
            LocalDate validFrom, List<WeekDay> days, List<Revision> revisions) {}
    public record Employee(UUID id, String number, String name, String organization, String currentGroup, long version) {}
    public record GroupPreview(String token, List<WeekDay> beforeDays, List<WeekDay> days, int weeklyMinutes,
            List<Employee> affected, List<Employee> departing, List<Employee> incoming, List<String> conflicts) {}
    public record AssignmentRow(UUID employeeId, String number, String name, String currentGroup, String newGroup,
            LocalDate lastDay, LocalDate firstDay, LocalDate nextStart, UUID assignmentId, String conflict) {}
    public record AssignmentPreview(String token, List<AssignmentRow> rows, List<String> conflicts) {}
}
