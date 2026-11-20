package kr.tna.request;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import kr.tna.workspace.ProfileCompanyData.OrganizationItem;

public final class LeaveOperationsData {
    private LeaveOperationsData() { }

    public record Page(String status, String leaveTypeCode, String requestKind, String from, String to,
            UUID organizationId, String q, int page, boolean hasNext, LocalDate asOf, List<LeaveType> leaveTypes,
            List<OrganizationItem> organizations, Map<String, Long> counts, List<Row> rows, String error) { }

    public record LeaveType(String code, String name, String systemKind) { }

    public record Row(UUID id, String employeeNo, String employeeName, String organizationName, String leaveTypeName,
            String requestKind, LocalDate from, LocalDate to, double quantityDays, String status, String effectStatus,
            String approverName, String decidedByName, Instant submittedAt, Instant decidedAt, UUID originalRequestId, int currentStep, int totalSteps, String leavePart) { }

    public record Search(String status, String leaveTypeCode, String requestKind, LocalDate from, LocalDate to,
            UUID organizationId, String q, int offset) { }

    public record Counts(long pending, long approved, long rejected, long total) {
        Map<String, Long> byStatus() {
            return Map.of("PENDING", pending, "APPROVED", approved, "REJECTED", rejected, "ALL", total);
        }
    }
}
