package kr.tna.approval;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import kr.tna.org.BulkData;
import kr.tna.org.OrgData;
import org.springframework.format.annotation.DateTimeFormat;

public final class ApprovalRouteData {
    private ApprovalRouteData() { }
    public record Route(UUID id, String code, String name, boolean active, long version, UUID routeVersionId,
            long revision, String approverKind, UUID employeeId, String employeeName, String terminalUnit) { }
    public record RouteRow(String clientRowId, UUID id, Long expectedVersion, BulkData.Operation operation,
            String code, String name, boolean active, String approverKind, UUID employeeId,
            @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL) String terminalUnit) implements BulkData.Row {
        public RouteRow(String clientRowId, UUID id, Long expectedVersion, BulkData.Operation operation,
                String code, String name, boolean active, String approverKind, UUID employeeId) {
            this(clientRowId,id,expectedVersion,operation,code,name,active,approverKind,employeeId,null);
        }
    }
    public record RouteVersion(UUID id, UUID routeId, String code, String name, boolean active, long revision,
            String approverKind, UUID employeeId, String employeeName, String reason, Instant createdAt, String terminalUnit) { }
    public record ManagementView(String q, int page, boolean hasNext, List<Route> routes, List<RouteVersion> versions,
            List<OrgData.Employee> candidates, BulkData.Sheet sheet) { }
    public record AssignmentForm(String scopeKind, UUID employeeId, UUID organizationId, UUID routeVersionId,
            @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate validFrom, Long expectedVersion, String reason, UUID commandId) { }
    public record RouteAssignment(UUID id, UUID routeVersionId, String routeCode, String routeName, long revision,
            String approverKind, String approverEmployeeName, String scopeKind, UUID employeeId, UUID organizationId,
            LocalDate validFrom, LocalDate validTo, Instant supersededAt, String reason, long version, boolean routeActive) { }
    public record AssignmentView(LocalDate today, String scopeKind, UUID employeeId, UUID organizationId,
            List<OrgData.Employee> employees, List<OrgData.Organization> organizations, List<RouteVersion> versions,
            List<RouteAssignment> history, AssignmentForm form) { }
}
