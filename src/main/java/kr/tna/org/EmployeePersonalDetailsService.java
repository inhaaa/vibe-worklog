package kr.tna.org;

import java.time.LocalDate;
import java.util.Set;
import java.util.UUID;
import kr.tna.common.auth.TnaPrincipal;
import kr.tna.common.tenant.BusinessCommands;
import kr.tna.org.EmployeePersonalDetailsData.*;
import org.springframework.stereotype.Service;

import static kr.tna.org.OrgCommands.*;

@Service
public final class EmployeePersonalDetailsService {
    private final EmployeePersonalDetailsMapper mapper;
    private final EmployeePersonnelMapper personnel;
    private final OrgCommands commands;
    private final BusinessCommands personal;

    public EmployeePersonalDetailsService(EmployeePersonalDetailsMapper mapper, EmployeePersonnelMapper personnel,
            OrgCommands commands, BusinessCommands personal) {
        this.mapper = mapper; this.personnel = personnel; this.commands = commands; this.personal = personal;
    }

    public View self(TnaPrincipal actor) {
        return personal.read(actor, () -> {
            personnel.lock(actor.tenantId());
            return load(actor, actor.employeeId());
        });
    }

    public View get(TnaPrincipal actor, UUID employeeId) {
        return commands.read(actor, () -> {
            personnel.lock(actor.tenantId());
            return load(actor, employeeId);
        });
    }

    private View load(TnaPrincipal actor, UUID employeeId) {
        if (personnel.header(actor.tenantId(), employeeId, commands.today(), actor.authenticatedAt(), "") == null) {
            throw OrgException.notFound();
        }
        Form stored = mapper.stored(actor.tenantId(), employeeId);
        Form form = copy(stored, stored == null ? 0 : stored.expectedVersion(), "", UUID.randomUUID());
        return new View(employeeId, form.expectedVersion(), form, form,
                mapper.entries(actor.tenantId(), employeeId), mapper.appointments(actor.tenantId(), employeeId),
                new EntryForm("FAMILY", "", "", commands.today(), null, form.expectedVersion(), "", UUID.randomUUID()));
    }

    public UUID update(TnaPrincipal actor, UUID employeeId, Form form) {
        requireAdminRole(actor);
        var checked = new Form(choice(form.admissionType(), Set.of("UNKNOWN", "NEW", "EXPERIENCED", "TRANSFER")),
                form.recognizedMonths(), date(form.groupJoinedOn()), text(form.englishName(), 120),
                choice(form.maritalStatus(), Set.of("UNKNOWN", "YES", "NO")), date(form.marriedOn()),
                choice(form.veteranStatus(), Set.of("UNKNOWN", "YES", "NO")),
                choice(form.disabilityStatus(), Set.of("UNKNOWN", "YES", "NO")),
                choice(form.militaryStatus(), Set.of("UNKNOWN", "COMPLETED", "EXEMPT", "PENDING", "NOT_APPLICABLE")),
                text(form.militaryBranch(), 60), text(form.mobilePhone(), 40), text(form.postalCode(), 20),
                text(form.address(), 200), text(form.addressDetail(), 200), text(form.contactEmail(), 254),
                form.expectedVersion(), requiredText(form.reason(), 500, "변경 사유"), form.commandId());
        if (checked.recognizedMonths() != null && (checked.recognizedMonths() < 0 || checked.recognizedMonths() > 1200)) {
            throw OrgException.invalid("인정경력은 0~1200개월로 입력해 주세요.");
        }
        if (checked.marriedOn() != null && (!"YES".equals(checked.maritalStatus()) || checked.marriedOn().isAfter(commands.today()))) {
            throw OrgException.invalid("결혼일자는 기혼인 경우 오늘 이전의 날짜로 입력해 주세요.");
        }
        return commands.execute(actor, checked.commandId(), "EMPLOYEE_PERSONAL_DETAILS_UPDATE", "EMPLOYEE",
                fingerprint(employeeId, checked), checked.reason(), () -> {
                    View current = load(actor, employeeId);
                    checkVersion(checked.expectedVersion(), current.version());
                    save(actor, employeeId, checked, current.version());
                    return new Change(employeeId, new Audit(current.version(), "BASIC"), new Audit(current.version() + 1, "BASIC"));
                });
    }

    public UUID append(TnaPrincipal actor, UUID employeeId, EntryForm form) {
        requireAdminRole(actor);
        String kind = choice(form.kind(), Set.of("FAMILY", "EDUCATION", "QUALIFICATION", "LANGUAGE"));
        var checked = new EntryForm(kind, requiredText(form.title(), 120, "항목명"), text(form.detail(), 200),
                date(form.effectiveFrom()), date(form.effectiveTo()), form.expectedVersion(),
                requiredText(form.reason(), 500, "변경 사유"), form.commandId());
        if (checked.effectiveFrom() == null || checked.effectiveTo() != null && checked.effectiveTo().isBefore(checked.effectiveFrom())) {
            throw OrgException.invalid("적용 시작일과 종료일을 확인해 주세요.");
        }
        return commands.execute(actor, checked.commandId(), "EMPLOYEE_PERSONAL_ENTRY_APPEND", "EMPLOYEE",
                fingerprint(employeeId, checked), checked.reason(), () -> {
                    View current = load(actor, employeeId);
                    checkVersion(checked.expectedVersion(), current.version());
                    save(actor, employeeId, current.form(), current.version());
                    mapper.append(actor.tenantId(), employeeId, UUID.randomUUID(), checked, current.version() + 1, actor.accountId());
                    return new Change(employeeId, new Audit(current.version(), kind), new Audit(current.version() + 1, kind));
                });
    }

    private void save(TnaPrincipal actor, UUID employeeId, Form form, long version) {
        if (mapper.save(actor.tenantId(), employeeId, form, version + 1, version, actor.accountId()) != 1) {
            throw OrgException.conflict("다른 인사정보가 저장되었습니다. 최신 정보를 확인해 주세요.");
        }
    }
    private static void requireAdminRole(TnaPrincipal actor) {
        if (actor == null || !actor.roles().contains("ATTENDANCE_ADMIN")) throw OrgException.forbidden();
    }
    private static Form copy(Form f, long version, String reason, UUID commandId) {
        if (f == null) return new Form("UNKNOWN", null, null, null, "UNKNOWN", null, "UNKNOWN", "UNKNOWN",
                "UNKNOWN", null, null, null, null, null, null, version, reason, commandId);
        return new Form(f.admissionType(), f.recognizedMonths(), f.groupJoinedOn(), f.englishName(), f.maritalStatus(),
                f.marriedOn(), f.veteranStatus(), f.disabilityStatus(), f.militaryStatus(), f.militaryBranch(),
                f.mobilePhone(), f.postalCode(), f.address(), f.addressDetail(), f.contactEmail(), version, reason, commandId);
    }
    private static String choice(String value, Set<String> allowed) {
        String selected = value == null || value.isBlank() ? "UNKNOWN" : value;
        if (!allowed.contains(selected)) throw OrgException.invalid("선택한 인사 항목을 확인해 주세요.");
        return selected;
    }
    private static String text(String value, int limit) { return value == null || value.isBlank() ? null : requiredText(value, limit, "입력값"); }
    private static LocalDate date(LocalDate value) {
        if (value != null && (value.getYear() < 1 || value.getYear() > 9999)) throw OrgException.invalid("올바른 날짜를 입력해 주세요.");
        return value;
    }
    private record Audit(long version, String section) { }
}
