package kr.tna.approval;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;

public final class RequestFormData {
    private RequestFormData() { }

    public record Master(UUID id,String code,String kind,long version) { }
    public record Version(UUID id,UUID formId,String code,String kind,long revision,LocalDate effectiveFrom,
                          boolean enabled,String name,UUID routeVersionId,String reason,UUID createdBy,Instant createdAt,String changedBy) { }
    public record Row(UUID id,String code,String kind,long version,String name,boolean enabled,UUID routeVersionId,
                      String routeName,Long routeRevision,LocalDate nextFrom) { }
    public record RouteOption(UUID id,String code,String name,long revision,String approverKind,String terminalUnit,boolean active) { }
    public record EmployeeOption(UUID id,String employeeNo,String name) { }
    public record Command(UUID id,Long expectedVersion,String code,String kind,String name,
                          @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate effectiveFrom,Boolean enabled,
                          UUID routeVersionId,UUID replaceFormId,Long replaceExpectedVersion,String reason,UUID commandId) { }
    public record Page(String q,LocalDate today,List<Row> forms,Row selected,List<Version> history,
                       List<RouteOption> routes,List<EmployeeOption> employees,List<Row> replacementOptions) { }
}
