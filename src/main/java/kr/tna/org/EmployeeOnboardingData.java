package kr.tna.org;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;

public final class EmployeeOnboardingData {
    private EmployeeOnboardingData() { }

    public enum State { PLANNED, CONFIRMED, WITHDRAWN }
    public enum Action { CREATE, EDIT, CONFIRM, WITHDRAW }

    public record Form(String displayName, @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate joinedOn,
                       @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate leftOn,
                       UUID organizationId, UUID scheduleVersionId, String employeeNo, Long expectedVersion,
                       Long expectedOrganizationVersion, Long expectedTemplateVersion, UUID commandId, String reason) { }

    public record Row(UUID id, State state, long version, String displayName, LocalDate joinedOn, LocalDate leftOn,
                      UUID organizationId, UUID scheduleVersionId, UUID employeeId, String employeeNo,
                      UUID organizationAssignmentId, UUID scheduleAssignmentId, UUID decidedBy, Instant decidedAt,
                      String organizationCode, String organizationName, String scheduleTemplateName,
                      Integer scheduleRevision, String decidedByName, String decisionReason) { }

    public record OrganizationOption(UUID id, String code, String name, long version) { }

    public record ScheduleOption(UUID id, UUID templateId, String templateName, int revision, LocalDate validFrom,
                                 long templateVersion, boolean group, LocalDate validTo) { }
    public record ScheduleDayOption(UUID versionId, int weekday, boolean group, String templateCode,
                                    String workCode, boolean workday, LocalTime startTime, LocalTime endTime, String workMode) { }

    public record View(Instant asOf, LocalDate today, String q, State state, LocalDate from, LocalDate to, int page,
                       boolean hasNext, List<Row> rows, Row detail, List<OrganizationOption> organizations,
                       List<ScheduleOption> scheduleVersions, List<ScheduleDayOption> scheduleDays, Form form, boolean publicDemo, Action action,
                       boolean possibleDuplicate, boolean inviteEligible, boolean retrySameCommand) { }
}
