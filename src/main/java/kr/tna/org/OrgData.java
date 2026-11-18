package kr.tna.org;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;

public final class OrgData {
    private OrgData() { }
    public record Organization(UUID id, String code, String name, UUID parentId, boolean active, long version, String approvalUnit) {
        @org.apache.ibatis.annotations.AutomapConstructor public Organization { }
        public Organization(UUID id,String code,String name,UUID parentId,boolean active,long version) {
            this(id,code,name,parentId,active,version,null);
        }
    }
    static String approvalUnit(String requested, String current) {
        if (requested == null) return current;
        return switch (requested) {
            case "", "UNASSIGNED" -> null;
            case "TEAM", "OFFICE", "PASS_THROUGH" -> requested;
            default -> throw OrgException.invalid("결재 단위는 팀, 실, 경유 조직 중에서 선택해 주세요.");
        };
    }
    public record CreatedOrganization(String clientRowId, UUID id, String code) { }
    public record Employee(UUID id, String employeeNo, String displayName, LocalDate joinedOn,
                           LocalDate leftOn, boolean active, long version) { }
    public record EmployeeOrganization(UUID employeeId, String organizationName) { }
    public record EmployeeDirectory(List<Employee> employees, Map<UUID, String> organizationNames,
                                    Employee selectedEmployee, boolean hasNext) { }
    public record Assignment(UUID id, UUID employeeId, UUID organizationId, String organizationName,
                             LocalDate validFrom, LocalDate validTo, String reason, long version) { }
    public record OrganizationForm(String code, String name, UUID parentId, boolean active,
                                   Long expectedVersion, String reason, UUID commandId, String approvalUnit) {
        public OrganizationForm(String code,String name,UUID parentId,boolean active,Long expectedVersion,String reason,UUID commandId) {
            this(code,name,parentId,active,expectedVersion,reason,commandId,null);
        }
    }
    public record EmployeeForm(String employeeNo, String displayName,
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate joinedOn,
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate leftOn,
            boolean active, Long expectedVersion, String reason, UUID commandId) { }
    public record AssignmentForm(UUID organizationId,
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate validFrom,
            Long expectedVersion, String reason, UUID commandId) { }
}
