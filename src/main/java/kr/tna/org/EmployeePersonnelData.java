package kr.tna.org;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;

public final class EmployeePersonnelData {
    private EmployeePersonnelData() { }

    public record Header(UUID employeeId, String employeeNo, String employeeName, String companyName,
            String organizationName, LocalDate joinedOn, LocalDate leftOn, LocalDate birthDate,
            String gradeName, String positionName, String jobName, String employmentStatus,
            String photoUrl, Instant asOf) { }

    public record Form(@DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate birthDate,
            String gradeName, String positionName, String jobName, String employmentStatus,
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate effectiveFrom,
            Long expectedVersion, String reason, UUID commandId, String photoAction, String photoData) { }

    public record Personnel(UUID employeeId, Form form, List<StatusHistory> statusHistory, long version,
            Form currentForm) { }

    public record StatusHistory(UUID id, String employmentStatus, LocalDate effectiveFrom,
            long revision, String reason, Instant createdAt) { }

    public record Stored(LocalDate birthDate, String gradeName, String positionName, String jobName,
            String photoContentType, byte[] photoBytes, long version) { }
}
