package kr.tna.org;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;

/** Private details are projected only to the employee and the current attendance administrator. */
public final class EmployeePersonalDetailsData {
    private EmployeePersonalDetailsData() { }

    public record Form(String admissionType, Integer recognizedMonths,
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate groupJoinedOn,
            String englishName, String maritalStatus,
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate marriedOn,
            String veteranStatus, String disabilityStatus, String militaryStatus, String militaryBranch,
            String mobilePhone, String postalCode, String address, String addressDetail, String contactEmail,
            Long expectedVersion, String reason, UUID commandId) { }

    public record EntryForm(String kind, String title, String detail,
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate effectiveFrom,
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate effectiveTo,
            Long expectedVersion, String reason, UUID commandId) { }

    public record Entry(UUID id, String kind, String title, String detail, LocalDate effectiveFrom,
            LocalDate effectiveTo, long revision) { }

    public record Appointment(UUID id, String organizationName, LocalDate validFrom, LocalDate validTo, boolean initial) { }

    public record View(UUID employeeId, long version, Form form, Form currentForm,
            List<Entry> entries, List<Appointment> appointments, EntryForm entryForm) { }
}
