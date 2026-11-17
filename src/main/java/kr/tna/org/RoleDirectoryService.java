package kr.tna.org;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import kr.tna.common.auth.TnaPrincipal;
import kr.tna.org.RoleDirectoryMapper.EmployeeRow;
import org.springframework.stereotype.Service;

@Service
public final class RoleDirectoryService {
    private static final int PAGE_SIZE = 50;
    private final RoleDirectoryMapper mapper;
    private final OrgCommands commands;
    private final RoleManagementProtection protection;

    public RoleDirectoryService(RoleDirectoryMapper mapper, OrgCommands commands, RoleManagementProtection protection) {
        this.mapper = mapper;
        this.commands = commands;
        this.protection = protection;
    }

    public Directory directory(TnaPrincipal actor, String roleCode, String q, int page) {
        var role = parseRole(roleCode);
        String query = query(q);
        String pattern = searchPattern(query);
        int offset = OrgCommands.offset(page);
        return commands.readShared(actor, () -> {
            var tenant = actor.tenantId();
            var today = commands.today();
            var protectedIds = protection.protectedEmployeeIds(tenant);
            Map<String, Long> counts = mapper.roleCounts(tenant).stream()
                    .collect(Collectors.toMap(RoleDirectoryMapper.RoleCount::role, RoleDirectoryMapper.RoleCount::memberCount));
            var roles = Arrays.stream(Role.values())
                    .map(item -> new RoleEntry(item.name(), item.label, item.editable, counts.getOrDefault(item.name(), 0L)))
                    .toList();
            long total = query.isEmpty() ? counts.getOrDefault(role.name(), 0L)
                    : mapper.searchCount(tenant, role.name(), pattern);
            var members = mapper.members(tenant, role.name(), pattern, today, offset).stream()
                    .map(row -> member(row, role.editable, today, protectedIds)).toList();
            return new Directory(roles, role.name(), members, query, page, offset + PAGE_SIZE < total, total,
                    actor.employeeId());
        });
    }

    public Candidates candidates(TnaPrincipal actor, String roleCode, String q, int page) {
        var role = parseRole(roleCode);
        if (!role.editable) throw OrgException.invalid("공통 권한은 직원의 기본 권한입니다.");
        String pattern = searchPattern(query(q));
        int offset = OrgCommands.offset(page);
        return commands.readShared(actor, () -> {
            var tenant = actor.tenantId();
            var today = commands.today();
            var protectedIds = protection.protectedEmployeeIds(tenant);
            var rows = mapper.candidates(tenant, role.name(), pattern, today, offset, protectedIds);
            return new Candidates(rows.stream().limit(PAGE_SIZE).map(row -> member(row, true, today, protectedIds)).toList(),
                    page, rows.size() > PAGE_SIZE);
        });
    }

    private Member member(EmployeeRow row, boolean roleEditable, LocalDate today, Set<UUID> protectedIds) {
        String restriction = unavailableReason(row, today);
        boolean available = restriction == null;
        if (protectedIds.contains(row.employeeId())) restriction = RoleManagementProtection.SHARED_DEMO_RESTRICTION;
        return new Member(row.employeeId(), row.employeeNo(), row.employeeName(), row.organizationName(),
                row.version(), available, roleEditable && restriction == null, restriction);
    }

    private String unavailableReason(EmployeeRow row, LocalDate today) {
        if (row.joinedOn().isAfter(today)) return "입사 전";
        if (row.leftOn() != null && row.leftOn().isBefore(today)) return "퇴직";
        if (!row.employeeActive()) return "사원 비활성";
        if (!row.credentialSet()) return "초대 수락 대기";
        return row.accountActive() ? null : "계정 비활성";
    }

    private Role parseRole(String code) {
        for (Role role : Role.values()) {
            if (role.name().equals(code)) return role;
        }
        throw OrgException.invalid("지원하는 권한을 선택해 주세요.");
    }

    private String query(String q) {
        return q == null || q.isBlank() ? "" : OrgCommands.requiredText(q, 120, "검색어");
    }

    private String searchPattern(String q) {
        return "%" + q.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
    }

    private enum Role {
        EMPLOYEE("공통", false), ATTENDANCE_ADMIN("담당자", true),
        COMPANY_REPRESENTATIVE("대표", true), APPROVER("결재 담당", true);

        final String label;
        final boolean editable;
        Role(String label, boolean editable) { this.label = label; this.editable = editable; }
    }

    public record RoleEntry(String code, String label, boolean editable, long memberCount) { }
    public record Member(UUID employeeId, String employeeNo, String employeeName, String organizationName, long version,
            boolean available, boolean editable, String restriction) { }
    public record Directory(List<RoleEntry> roles, String role, List<Member> members, String q, int page, boolean hasNext,
            long total, UUID actorEmployeeId) { }
    public record Candidates(List<Member> rows, int page, boolean hasNext) { }
}
