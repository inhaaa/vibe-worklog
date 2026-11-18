package kr.tna.workspace;

import kr.tna.request.RequestScope;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.YearMonth;
import java.util.List;
import java.util.UUID;

/** Explicit projections for personal identity and company reads; no source evidence or free text. */
public final class ProfileCompanyData {
    private ProfileCompanyData() { }

    public record ProfileView(String employeeNo, String employeeName, String companyName, String email,
            LocalDate joinedOn, LocalDate leftOn, String organizationName, String scheduleName,
            String approverName, boolean approverEligible, List<String> roles, Instant asOf,
            String organizationHeadName, boolean organizationHeadEligible, boolean organizationHeadApproverEligible,
            kr.tna.request.RequestData.ApprovalPreview approvalPreview) { }

    public record ProfileRow(String employeeNo, String employeeName, String companyName, String email,
            LocalDate joinedOn, LocalDate leftOn, String organizationName, String scheduleName,
            String approverName, boolean approverEligible, String organizationHeadName,
            boolean organizationHeadEligible, boolean organizationHeadApproverEligible) { }

    public record OrganizationItem(UUID id, String code, String name) { }

    public record EmployeeItem(UUID id, String employeeNo, String employeeName, String organizationName) { }

    public record AttendanceDay(LocalDate workDate, Long scheduledMillis, Long actualWorkMillis,
            Double leaveDays, String status, boolean current) { }

    public record CompanyAttendance(YearMonth month, Instant asOf, UUID organizationId, UUID employeeId,
            String q, int page, boolean hasNext, List<OrganizationItem> organizations, List<EmployeeItem> employees,
            EmployeeItem selectedEmployee, List<AttendanceDay> days) { }

    record CompanyAttendancePage(CompanyAttendance attendance, boolean canViewCheckout) { }

    record CheckoutCandidate(UUID employeeId, String employeeNo, String employeeName, String organizationName,
            UUID recentRequestId, String recentRequestState, UUID effectiveRequestId) { }

    public record CheckoutOperations(LocalDate date, LocalDate latestDate, UUID organizationId,
            List<OrganizationItem> options, String q, String status, int page, boolean hasPrevious, boolean hasNext,
            List<CheckoutRow> rows, String error) { }

    public record CheckoutRow(UUID employeeId, String employeeNo, String employeeName, String organizationName,
            LocalDate workDate, LocalTime scheduledEnd, Instant sourceIn, Instant sourceOut, Instant effectiveIn,
            Instant effectiveOut, UUID requestId, String requestState, String recordState, String resultState,
            Long actualWorkMillis, Instant calculatedAt, String resultReason) { }

    public record CompanyRequest(UUID id, String employeeNo, String employeeName, String organizationName,
            String kind, String status, LocalDate fromDate, LocalDate toDate, double quantityDays,
            String effectStatus, Instant submittedAt, String leavePart) { }

    public record CompanyRequests(int year, Instant asOf, UUID organizationId, String status, int page,
            boolean hasNext, RequestScope scope, List<OrganizationItem> organizations, List<CompanyRequest> requests) { }
}
