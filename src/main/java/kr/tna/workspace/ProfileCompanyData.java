package kr.tna.workspace;

import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.UUID;

/** Explicit projections for personal identity and company reads; no source evidence or free text. */
public final class ProfileCompanyData {
    private ProfileCompanyData() { }

    public record ProfileView(String employeeNo, String employeeName, String companyName, String email,
            LocalDate joinedOn, LocalDate leftOn, String organizationName, String scheduleName,
            String approverName, boolean approverEligible, List<String> roles, Instant asOf) { }

    public record ProfileRow(String employeeNo, String employeeName, String companyName, String email,
            LocalDate joinedOn, LocalDate leftOn, String organizationName, String scheduleName,
            String approverName, boolean approverEligible) { }

    public record OrganizationItem(UUID id, String code, String name) { }

    public record EmployeeItem(UUID id, String employeeNo, String employeeName, String organizationName) { }

    public record AttendanceDay(LocalDate workDate, Long scheduledMillis, Long actualWorkMillis,
            Integer leaveDays, String status, boolean current) { }

    public record CompanyAttendance(YearMonth month, Instant asOf, UUID organizationId, UUID employeeId,
            String q, int page, boolean hasNext, List<OrganizationItem> organizations, List<EmployeeItem> employees,
            EmployeeItem selectedEmployee, List<AttendanceDay> days) { }

    public record CompanyRequest(UUID id, String employeeNo, String employeeName, String organizationName,
            String kind, String status, LocalDate fromDate, LocalDate toDate, int quantityDays,
            String effectStatus, Instant submittedAt) { }

    public record CompanyRequests(int year, Instant asOf, UUID organizationId, String status, int page,
            boolean hasNext, List<OrganizationItem> organizations, List<CompanyRequest> requests) { }
}
