package kr.tna.org;

import java.time.Clock;
import java.time.LocalDate;
import java.util.Set;
import java.util.UUID;
import kr.tna.common.auth.TnaPrincipal;
import kr.tna.common.tenant.BusinessCommands;
import kr.tna.org.EmployeePersonnelData.*;
import org.springframework.stereotype.Service;

import static kr.tna.org.OrgCommands.*;

@Service
public final class EmployeePersonnelService {
    private final EmployeePersonnelMapper mapper;
    private final OrgCommands commands;
    private final BusinessCommands business;
    private final Clock clock;
    private final PublicDemoIdentityGuard identityGuard;

    public EmployeePersonnelService(EmployeePersonnelMapper mapper, OrgCommands commands,
            BusinessCommands business, Clock clock, PublicDemoIdentityGuard identityGuard) {
        this.mapper = mapper;
        this.commands = commands;
        this.business = business;
        this.clock = clock;
        this.identityGuard = identityGuard;
    }

    public LocalDate today() { return commands.today(); }

    public Header selfHeader(TnaPrincipal actor) {
        return business.read(actor, () -> {
            mapper.lock(actor.tenantId());
            return requireHeader(actor, actor.employeeId(), "/me/profile/photo");
        });
    }

    public Header header(TnaPrincipal actor, UUID employeeId) {
        return commands.read(actor, () -> {
            mapper.lock(actor.tenantId());
            return requireHeader(actor, employeeId, adminPhotoUrl(employeeId));
        });
    }

    public Personnel get(TnaPrincipal actor, UUID employeeId) {
        return commands.read(actor, () -> {
            mapper.lock(actor.tenantId());
            requireHeader(actor, employeeId, adminPhotoUrl(employeeId));
            Stored stored = mapper.stored(actor.tenantId(), employeeId);
            var history = mapper.history(actor.tenantId(), employeeId);
            long version = stored == null ? 0 : stored.version();
            var latest = history.isEmpty() ? null : history.getFirst();
            var form = new Form(stored == null ? null : stored.birthDate(), stored == null ? null : stored.gradeName(),
                    stored == null ? null : stored.positionName(), stored == null ? null : stored.jobName(),
                    latest == null ? "UNKNOWN" : latest.employmentStatus(),
                    latest == null || latest.effectiveFrom().isBefore(today()) ? today() : latest.effectiveFrom(),
                    version, "", UUID.randomUUID(), "KEEP", "");
            return new Personnel(employeeId, form, history, version, form);
        });
    }

    public EmployeePhoto.Photo selfPhoto(TnaPrincipal actor) {
        return business.read(actor, () -> {
            mapper.lock(actor.tenantId());
            return requirePhoto(actor, actor.employeeId());
        });
    }

    public EmployeePhoto.Photo photo(TnaPrincipal actor, UUID employeeId) {
        return commands.read(actor, () -> {
            mapper.lock(actor.tenantId());
            return requirePhoto(actor, employeeId);
        });
    }

    public UUID update(TnaPrincipal actor, UUID employeeId, Form form) {
        identityGuard.requireMutable(actor, employeeId);
        String grade = optionalName(form.gradeName(), "직급"), position = optionalName(form.positionName(), "직책"),
                job = optionalName(form.jobName(), "직무");
        if (form.birthDate() != null && (form.birthDate().isAfter(today()) || !validDate(form.birthDate()))) {
            throw OrgException.invalid("생년월일은 오늘 이전의 올바른 날짜로 입력해 주세요.");
        }
        if (form.effectiveFrom() == null || form.effectiveFrom().isBefore(today()) || !validDate(form.effectiveFrom())) {
            throw OrgException.invalid("상태 적용일은 오늘 이후의 날짜로 입력해 주세요.");
        }
        if (form.employmentStatus() == null || !Set.of("WORKING", "ON_LEAVE", "SUSPENDED", "UNKNOWN").contains(form.employmentStatus())) {
            throw OrgException.invalid("재직·휴직·정직·상태 미등록 중 하나를 선택해 주세요.");
        }
        if (form.photoAction() == null || !Set.of("KEEP", "REPLACE", "CLEAR").contains(form.photoAction())) {
            throw OrgException.invalid("사진 변경 방법을 확인해 주세요.");
        }
        if (form.photoData() != null && form.photoData().length() > EmployeePhoto.MAX_BASE64_LENGTH + 32) {
            throw OrgException.invalid("사진은 128KiB 이하 PNG/JPEG만 등록할 수 있습니다.");
        }
        if (!"REPLACE".equals(form.photoAction()) && form.photoData() != null && !form.photoData().isEmpty()) {
            throw OrgException.invalid("사진 교체를 선택한 경우에만 새 사진을 입력해 주세요.");
        }
        String reason = requiredText(form.reason(), 500, "변경 사유");
        String action = "EMPLOYEE_PERSONNEL_UPDATE";
        return commands.execute(actor, form.commandId(), action, "EMPLOYEE",
                fingerprint(action, employeeId, form.birthDate(), grade, position, job, form.employmentStatus(),
                        form.effectiveFrom(), form.expectedVersion(), reason, form.photoAction(), form.photoData()), reason, () -> {
                    requireHeader(actor, employeeId, adminPhotoUrl(employeeId));
                    Stored before = mapper.stored(actor.tenantId(), employeeId);
                    long version = before == null ? 0 : before.version();
                    checkVersion(form.expectedVersion(), version);
                    var photo = switch (form.photoAction()) {
                        case "REPLACE" -> EmployeePhoto.decode(form.photoData());
                        case "CLEAR" -> null;
                        default -> before == null || before.photoBytes() == null ? null
                                : new EmployeePhoto.Photo(before.photoContentType(), before.photoBytes());
                    };
                    var after = new Stored(form.birthDate(), grade, position, job,
                            photo == null ? null : photo.contentType(), photo == null ? null : photo.bytes(), version + 1);
                    if (mapper.save(actor.tenantId(), employeeId, after, version, actor.accountId()) != 1) {
                        throw OrgException.conflict("다른 변경이 반영되었습니다. 최신 인사정보를 확인해 주세요.");
                    }
                    mapper.appendStatus(actor.tenantId(), UUID.randomUUID(), employeeId, form.employmentStatus(),
                            form.effectiveFrom(), after.version(), reason, actor.accountId());
                    // Private values never become generic audit JSON; only presence and the changed revision do.
                    return new Change(employeeId, audit(before, null, null), audit(after, form.employmentStatus(), form.effectiveFrom()));
                });
    }

    private Header requireHeader(TnaPrincipal actor, UUID employeeId, String photoUrl) {
        Header header = mapper.header(actor.tenantId(), employeeId, today(), clock.instant(), photoUrl);
        if (header == null) throw OrgException.notFound();
        return header;
    }

    private EmployeePhoto.Photo requirePhoto(TnaPrincipal actor, UUID employeeId) {
        requireHeader(actor, employeeId, adminPhotoUrl(employeeId));
        var stored = mapper.stored(actor.tenantId(), employeeId);
        if (stored == null || stored.photoBytes() == null) throw OrgException.notFound();
        return new EmployeePhoto.Photo(stored.photoContentType(), stored.photoBytes());
    }

    private static String adminPhotoUrl(UUID id) { return "/admin/employees/" + id + "/photo"; }
    private static boolean validDate(LocalDate date) { return date.getYear() >= 1 && date.getYear() <= 9999; }
    private static String optionalName(String value, String label) {
        return value == null || value.isBlank() ? null : requiredText(value, 120, label);
    }
    private static AuditSummary audit(Stored stored, String status, LocalDate effectiveFrom) {
        return stored == null ? null : new AuditSummary(stored.version(), stored.birthDate() != null,
                stored.photoBytes() != null, status, effectiveFrom);
    }
    private record AuditSummary(long version, boolean birthDateRegistered, boolean photoRegistered,
            String employmentStatus, LocalDate effectiveFrom) { }
}
