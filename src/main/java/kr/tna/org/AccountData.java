package kr.tna.org;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Set;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;

public final class AccountData {
    private AccountData() { }
    public record Account(UUID id, UUID employeeId, String email, boolean active,
                          boolean credentialSet, long authVersion, Set<String> roles,
                          Instant activationExpiresAt, int activationFailures) { }
    public record InvitationForm(String email, Long expectedVersion, String reason, UUID commandId) { }
    public record RolesForm(Set<String> roles, Long expectedVersion, String reason, UUID commandId) { }
    public record ApproverAssignment(UUID id, UUID employeeId, UUID approverEmployeeId, String approverName,
            LocalDate validFrom, LocalDate validTo, Instant supersededAt, String reason, long version,
            boolean eligible) { }
    public record ApproverForm(UUID employeeId, UUID approverEmployeeId,
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate validFrom,
            Long expectedVersion, String reason, UUID commandId) { }
}
