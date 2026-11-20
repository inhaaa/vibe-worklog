package kr.tna.org;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class BulkData {
    private BulkData() { }
    public enum Operation { CREATE, UPDATE, DEACTIVATE }
    public interface Row {
        String clientRowId(); UUID id(); Long expectedVersion(); Operation operation();
    }
    public record OrganizationRow(String clientRowId, UUID id, Long expectedVersion, Operation operation,
            String code, String name, UUID parentId, boolean active,
            @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL) String approvalUnit) implements Row {
        public OrganizationRow(String clientRowId,UUID id,Long expectedVersion,Operation operation,String code,String name,UUID parentId,boolean active) {
            this(clientRowId,id,expectedVersion,operation,code,name,parentId,active,null);
        }
    }
    public record EmployeeRow(String clientRowId, UUID id, Long expectedVersion, Operation operation,
            String employeeNo, String displayName, LocalDate joinedOn, LocalDate leftOn, boolean active) implements Row { }
    public record Form<R extends Row>(UUID commandId, String reason, List<R> rows) { }
    public record RowResult(String clientRowId, UUID id, long version) { }
    public record Result(UUID batchId, List<RowResult> rows) { }
    public record Change(String clientRowId, UUID id, long version, Object before, Object after) { }
    public record FieldError(String clientRowId, String field, String message) { }
    public record Sheet(UUID commandId, String reason, List<? extends Row> rows, List<?> latestRows,
                        List<FieldError> errors) { }

    public static Sheet organizations(UUID commandId, String reason, List<OrgData.Organization> latest,
                                      List<OrganizationRow> dirty, List<FieldError> errors) {
        var base = latest.stream().map(row -> new OrganizationRow(row.id().toString(), row.id(), row.version(),
                Operation.UPDATE, row.code(), row.name(), row.parentId(), row.active(), row.approvalUnit())).toList();
        return new Sheet(commandId, reason, overlay(base, dirty), latest, errors);
    }

    public static Sheet employees(UUID commandId, String reason, List<OrgData.Employee> latest,
                                  List<EmployeeRow> dirty, List<FieldError> errors) {
        var base = latest.stream().map(row -> new EmployeeRow(row.id().toString(), row.id(), row.version(),
                Operation.UPDATE, row.employeeNo(), row.displayName(), row.joinedOn(), row.leftOn(), row.active())).toList();
        return new Sheet(commandId, reason, overlay(base, dirty), latest, errors);
    }

    private static <R extends Row> List<R> overlay(List<R> base, List<R> dirty) {
        var result = new ArrayList<>(base);
        var replaced = new java.util.HashSet<UUID>();
        for (R row : dirty) {
            int index = -1;
            if (row.id() != null && replaced.add(row.id())) {
                for (int position = 0; position < result.size(); position++) {
                    if (row.id().equals(result.get(position).id())) { index = position; break; }
                }
            }
            if (index < 0) result.add(row); else result.set(index, row);
        }
        return List.copyOf(result);
    }
}
