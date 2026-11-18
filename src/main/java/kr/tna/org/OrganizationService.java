package kr.tna.org;

import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import kr.tna.common.auth.TnaPrincipal;
import org.springframework.stereotype.Service;

@Service
public final class OrganizationService {
    private final OrganizationAdminMapper organizations;
    private final OrgCommands commands;

    public OrganizationService(OrganizationAdminMapper organizations, OrgCommands commands) {
        this.organizations = organizations;
        this.commands = commands;
    }

    public List<OrgData.Organization> list(TnaPrincipal actor, String query, int page) {
        String normalized = query == null || query.isBlank() ? "" : OrgCommands.requiredText(query, 120, "검색어");
        int offset = OrgCommands.offset(page);
        return commands.read(actor, () -> organizations.list(actor.tenantId(), normalized, offset));
    }

    public List<OrgData.Organization> options(TnaPrincipal actor) {
        return commands.read(actor, () -> organizations.options(actor.tenantId()));
    }

    public OrgData.Organization get(TnaPrincipal actor, UUID id) {
        return commands.read(actor, () -> requireOrganization(actor.tenantId(), id));
    }

    public List<OrgData.CreatedOrganization> creationResults(TnaPrincipal actor, UUID commandId) {
        return commands.read(actor, () -> organizations.creationResults(actor.tenantId(), actor.accountId(), commandId));
    }

    public UUID create(TnaPrincipal actor, OrgData.OrganizationForm form) {
        var normalized = normalize(form);
        String action = "ORGANIZATION_CREATE";
        return commands.execute(actor, normalized.commandId(), action, "ORGANIZATION",
                fingerprint(action, null, normalized), normalized.reason(), () -> {
                    validateParent(actor.tenantId(), null, normalized.parentId(), false);
                    var createdAt = commands.creationTime();
                    String code = new OrganizationCodes(createdAt, organizations.codes(actor.tenantId())).issue(normalized.code());
                    UUID id = UUID.randomUUID();
                    var created = new OrgData.Organization(id, code, normalized.name(),
                            normalized.parentId(), normalized.active(), 0, OrgData.approvalUnit(normalized.approvalUnit(), null));
                    organizations.insert(actor.tenantId(), created, actor.accountId(), createdAt);
                    return new OrgCommands.Change(id, null, requireOrganization(actor.tenantId(), id));
                });
    }

    public UUID update(TnaPrincipal actor, UUID id, OrgData.OrganizationForm form) {
        var normalized = normalize(form);
        String action = "ORGANIZATION_UPDATE";
        return commands.execute(actor, normalized.commandId(), action, "ORGANIZATION",
                fingerprint(action, id, normalized), normalized.reason(), () -> {
                    var before = requireOrganization(actor.tenantId(), id);
                    OrgCommands.checkVersion(normalized.expectedVersion(), before.version());
                    if (normalized.code() != null && !before.code().equals(normalized.code())) {
                        throw OrgException.invalid("조직 코드는 변경할 수 없습니다.");
                    }
                    boolean preserveArchivedParent = !before.active() && !normalized.active()
                            && java.util.Objects.equals(before.parentId(), normalized.parentId());
                    validateParent(actor.tenantId(), id, normalized.parentId(), preserveArchivedParent);
                    if (before.active() && !normalized.active()) {
                        if (organizations.hasActiveChildren(actor.tenantId(), id)) {
                            throw OrgException.invalid("활성 하위 조직이 있어 비활성화할 수 없습니다.");
                        }
                        if (organizations.hasCurrentOrFutureAssignments(actor.tenantId(), id, commands.today())) {
                            throw OrgException.invalid("오늘 또는 미래 소속 배정이 있어 비활성화할 수 없습니다.");
                        }
                    }
                    var updated = new OrgData.Organization(id, before.code(), normalized.name(),
                            normalized.parentId(), normalized.active(), before.version(), OrgData.approvalUnit(normalized.approvalUnit(), before.approvalUnit()));
                    if (organizations.update(actor.tenantId(), updated, actor.accountId()) != 1) {
                        throw OrgException.conflict("다른 변경이 반영되었습니다. 최신 내용을 확인해 주세요.");
                    }
                    return new OrgCommands.Change(id, before, requireOrganization(actor.tenantId(), id));
                });
    }

    private OrgData.Organization requireOrganization(UUID tenantId, UUID id) {
        var organization = organizations.find(tenantId, id);
        if (organization == null) throw OrgException.notFound();
        return organization;
    }

    private void validateParent(UUID tenantId, UUID id, UUID parentId, boolean preserveArchivedParent) {
        var visited = new HashSet<UUID>();
        UUID current = parentId;
        while (current != null) {
            if (current.equals(id) || !visited.add(current)) {
                throw OrgException.invalid("조직은 자신이나 하위 조직을 상위 조직으로 지정할 수 없습니다.");
            }
            var parent = requireOrganization(tenantId, current);
            if (!parent.active() && !preserveArchivedParent) {
                throw OrgException.invalid("활성 조직만 상위 조직으로 지정할 수 있습니다.");
            }
            current = parent.parentId();
        }
    }

    private static OrgData.OrganizationForm normalize(OrgData.OrganizationForm form) {
        if (form == null) throw OrgException.invalid("조직 정보를 입력해 주세요.");
        return new OrgData.OrganizationForm(OrganizationCodes.normalize(form.code()),
                OrgCommands.requiredText(form.name(), 120, "조직 이름"), form.parentId(), form.active(),
                form.expectedVersion(), OrgCommands.requiredText(form.reason(), 500, "변경 사유"), form.commandId(), form.approvalUnit());
    }

    private static String fingerprint(String action, UUID id, OrgData.OrganizationForm form) {
        String original = OrgCommands.fingerprint(action, id, form.code(), form.name(), form.parentId(), form.active(),
                form.expectedVersion(), form.reason());
        return form.approvalUnit() == null ? original : OrgCommands.fingerprint(original, form.approvalUnit());
    }
}
