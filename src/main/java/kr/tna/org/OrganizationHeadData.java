package kr.tna.org;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;

public final class OrganizationHeadData {
    private OrganizationHeadData() { }

    public record HeadForm(UUID organizationId, UUID employeeId,
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate validFrom,
            Long expectedVersion, String reason, UUID commandId) { }

    public enum HeadOperation { ASSIGN, REPLACE, CHANGE_END, RESCHEDULE, CANCEL }

    public record HeadCommand(HeadOperation operation, UUID organizationId, UUID assignmentId, UUID employeeId,
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate validFrom,
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate lastDay,
            Boolean endToday, Long expectedVersion, String reason, UUID commandId) {
        boolean endsToday() { return Boolean.TRUE.equals(endToday); }
    }

    public record HeadPeriod(UUID employeeId, String employeeName, LocalDate validFrom, LocalDate lastDay) { }

    public record Vacancy(LocalDate from, LocalDate through) { }

    public record HeadPreview(HeadPeriod before, HeadPeriod after, LocalDate previousLastDay, HeadPeriod next,
            Vacancy vacancy, Vacancy additionalVacancy) { }

    public record HeadOverview(HeadPeriod current, HeadPeriod next) { }

    public record OrganizationOption(UUID id, String code, String name, boolean active, long version) { }

    public record Candidate(UUID id, String employeeNo, String employeeName, LocalDate joinedOn, LocalDate leftOn) { }

    public record HeadAssignment(UUID id, UUID organizationId, UUID employeeId, String employeeNo,
            String employeeName, LocalDate validFrom, LocalDate validTo, Instant supersededAt,
            String reason, long version, boolean eligible, boolean approverEligible) { }

    public record HeadEndChange(UUID id, String reason, Instant occurredAt, String validFrom,
            String beforeValidTo, String afterValidTo) { }

    public record ManagementView(Instant asOf, LocalDate today, UUID organizationId,
            List<OrganizationOption> organizations, List<Candidate> candidates, List<HeadAssignment> history,
            List<HeadEndChange> endChanges, HeadForm form, String q) { }

    public record OrganizationNode(UUID id, String code, String name, UUID parentId, boolean active,
            String headEmployeeNo, String headEmployeeName, boolean headEligible, boolean headApproverEligible) { }

    public record Member(UUID id, String employeeNo, String employeeName, String organizationName) { }

    public record CompanyView(Instant asOf, LocalDate today, UUID organizationId, String q, int page,
            boolean hasNext, List<OrganizationNode> organizations, OrganizationNode selectedOrganization,
            List<Member> employees) { }

    public record SheetHead(String employeeName, boolean eligible, boolean approverEligible) { }

    public record EmployeeContext(String organizationName, String organizationHeadName,
            boolean organizationHeadEligible, boolean organizationHeadApproverEligible, String scheduleName) { }

    public record EmployeeContextRow(UUID employeeId, String organizationName, String organizationHeadName,
            boolean organizationHeadEligible, boolean organizationHeadApproverEligible, String scheduleName) { }
}
