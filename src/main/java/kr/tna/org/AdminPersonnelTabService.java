package kr.tna.org;

import java.util.Objects;
import java.util.UUID;
import kr.tna.common.auth.TnaPrincipal;
import kr.tna.org.EmployeePersonalDetailsData.EntryForm;
import org.springframework.stereotype.Service;

import static kr.tna.org.OrgCommands.checkVersion;
import static kr.tna.org.OrgCommands.requiredText;

@Service
final class AdminPersonnelTabService {
    static final String ENTRY_PREFIX = "[worklog:personnel-v1] ";

    private final EmployeeService employees;
    private final EmployeePersonnelService personnel;
    private final EmployeePersonalDetailsService details;
    private final OrgCommands commands;
    private final PublicDemoIdentityGuard identityGuard;

    AdminPersonnelTabService(EmployeeService employees, EmployeePersonnelService personnel,
            EmployeePersonalDetailsService details, OrgCommands commands, PublicDemoIdentityGuard identityGuard) {
        this.employees = employees;
        this.personnel = personnel;
        this.details = details;
        this.commands = commands;
        this.identityGuard = identityGuard;
    }

    void save(TnaPrincipal actor, UUID id, AdminPersonnelTabCommand input) {
        identityGuard.requireMutable(actor, id);
        String reason = input.reason();
        UUID commandId = input.uuid("commandId");
        switch (input.tab()) {
            case BASIC -> saveBasic(actor, id, input, reason, commandId);
            case CONTACT -> saveContact(actor, id, input, reason, commandId);
            case APPOINTMENTS -> employees.assign(actor, id, new OrgData.AssignmentForm(input.uuid("organizationId"),
                    input.date("validFrom"), input.version("employeeVersion"), reason, commandId));
            case FAMILY, EDUCATION, QUALIFICATION, LANGUAGE -> append(actor, id, input, reason, commandId);
        }
    }

    private void saveBasic(TnaPrincipal actor, UUID id, AdminPersonnelTabCommand input, String reason, UUID commandId) {
        String action = "EMPLOYEE_PERSONNEL_BASIC_UPDATE";
        commands.execute(actor, commandId, action, "EMPLOYEE", input.fingerprint(id, action, reason), reason, () -> {
            var employeeBefore = employees.requireEmployee(actor.tenantId(), id);
            var personnelBefore = personnel.current(actor, id);
            var detailsBefore = details.current(actor, id);
            long personnelVersion = EmployeePersonnelService.version(personnelBefore);
            checkVersion(input.version("employeeVersion"), employeeBefore.version());
            checkVersion(input.version("personnelVersion"), personnelVersion);
            checkVersion(input.version("detailsVersion"), detailsBefore.expectedVersion());

            String status = input.text("employmentStatus");
            var personnelForm = new EmployeePersonnelData.Form(input.date("birthDate"), input.text("gradeName"),
                    input.text("positionName"), input.text("jobName"), status,
                    status == null || status.isBlank() ? null : input.date("effectiveFrom"),
                    personnelVersion, reason, commandId, Objects.requireNonNullElse(input.text("photoAction"), "KEEP"),
                    input.text("photoData"));
            var detailForm = new EmployeePersonalDetailsData.Form(input.text("admissionType"),
                    input.number("recognizedMonths", "인정경력"), input.date("groupJoinedOn"), input.text("englishName"),
                    input.text("maritalStatus"), input.date("marriedOn"), input.text("veteranStatus"),
                    input.text("disabilityStatus"), input.text("militaryStatus"), input.text("militaryBranch"),
                    null, null, null, null, null, detailsBefore.expectedVersion(), reason, commandId);

            var before = new BasicAudit(employeeBefore.version(), personnelVersion, detailsBefore.expectedVersion());
            long employeeAfter = employees.saveBasic(actor, employeeBefore, input.text("displayName"),
                    input.date("joinedOn"), input.date("leftOn"));
            long personnelAfter = personnel.saveBasic(actor, id, personnelBefore, personnelForm);
            long detailsAfter = details.saveBasic(actor, id, detailsBefore, detailForm);
            return new OrgCommands.Change(id, before, new BasicAudit(employeeAfter, personnelAfter, detailsAfter));
        });
    }

    private void saveContact(TnaPrincipal actor, UUID id, AdminPersonnelTabCommand input, String reason, UUID commandId) {
        String action = "EMPLOYEE_PERSONNEL_CONTACT_UPDATE";
        commands.execute(actor, commandId, action, "EMPLOYEE", input.fingerprint(id, action, reason), reason, () -> {
            employees.requireEmployee(actor.tenantId(), id);
            var before = details.current(actor, id);
            checkVersion(input.version("detailsVersion"), before.expectedVersion());
            long after = details.saveContact(actor, id, before, input.draft());
            return new OrgCommands.Change(id, new ContactAudit(before.expectedVersion()), new ContactAudit(after));
        });
    }

    private void append(TnaPrincipal actor, UUID id, AdminPersonnelTabCommand input, String reason, UUID commandId) {
        String title;
        String detail;
        if (input.tab() == PersonnelTab.FAMILY) {
            title = switch (Objects.requireNonNullElse(input.text("relationship"), "")) {
                case "SPOUSE" -> "배우자";
                case "CHILD" -> "자녀";
                case "PARENT" -> "부모";
                case "OTHER" -> "기타";
                default -> throw OrgException.invalid("가족관계를 선택해 주세요.");
            };
            Integer count = input.number("count", "인원");
            if (count == null || count < 1) throw OrgException.invalid("인원은 1명 이상으로 입력해 주세요.");
            detail = count + "명";
        } else {
            title = input.text("title");
            String text = input.text("detail");
            detail = text == null || text.isBlank() ? null
                    : ENTRY_PREFIX + requiredText(text, 200 - ENTRY_PREFIX.length(), "상세");
        }
        details.append(actor, id, new EntryForm(input.tab().name(), title, detail, input.date("effectiveFrom"),
                input.date("effectiveTo"), input.version("detailsVersion"), reason, commandId));
    }

    private record BasicAudit(long employeeVersion, long personnelVersion, long detailsVersion) { }
    private record ContactAudit(long detailsVersion) { }
}
