package kr.tna.console;

import java.io.Serializable;
import java.security.Principal;
import java.time.Instant;
import java.time.LocalDate;
import org.springframework.format.annotation.DateTimeFormat;
import java.util.UUID;

public final class OperatorData {
    private OperatorData() { }
    public record OperatorPrincipal(UUID id,String loginId,long authVersion,Instant authenticatedAt) implements Principal,Serializable {
        @Override public String getName(){return id.toString();}
    }
    public record Tenant(UUID id,String companyCode,String displayName,boolean active,long version) { }
    public record TenantForm(String companyCode,String companyName,String employeeNo,String employeeName,@DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate joinedOn,String email,
                             String relationshipBasis,String reason,UUID commandId) { }
    public record StateForm(boolean active,Long expectedVersion,String reason,UUID commandId) { }
    public record ReissueForm(Long expectedVersion,String reason,UUID commandId) { }
    public record Reissued(UUID accountId,String companyCode,String email,boolean sent) { }
    public record Audit(UUID id,UUID tenantId,String action,String reason,String relationshipBasis,String dbActor,Instant createdAt) { }
    public record Onboarded(UUID tenantId,UUID accountId,boolean created) { }
}
