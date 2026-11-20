package kr.tna.org;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import kr.tna.common.auth.TnaPrincipal;
import kr.tna.worktime.ReviewWorkbook;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Service;

@Service
public final class PersonnelExportService {
    private static final int LIMIT = 1_000;
    private static final String ACTION = "PERSONNEL_EXPORT";
    private final OrgCommands commands;
    private final EmployeeAdminMapper mapper;
    private final boolean publicDemo;

    PersonnelExportService(OrgCommands commands, EmployeeAdminMapper mapper, Environment environment) {
        this.commands = commands;
        this.mapper = mapper;
        this.publicDemo = environment.acceptsProfiles(Profiles.of("public-demo"));
    }

    byte[] download(TnaPrincipal actor, String scope, List<UUID> employeeIds, String q, UUID organizationId,
                    String status) {
        if (publicDemo) throw OrgException.forbidden();
        boolean selected = "SELECTED".equals(scope);
        if (selected) {
            if (q != null || organizationId != null || status != null || employeeIds == null || employeeIds.isEmpty()
                    || employeeIds.size() > LIMIT || employeeIds.stream().anyMatch(Objects::isNull)
                    || new HashSet<>(employeeIds).size() != employeeIds.size()) throw invalidScope();
        } else if (!"FILTERED".equals(scope) || employeeIds != null
                || status != null && !List.of("all", "active", "inactive").contains(status)) {
            throw invalidScope();
        }
        String filter = status == null ? "all" : status;
        String normalized = q == null || q.isBlank() ? "" : OrgCommands.requiredText(q, 120, "검색어");
        String search = "%" + normalized.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
        UUID commandId = UUID.randomUUID();
        var result = new AtomicReference<byte[]>();
        commands.execute(actor, commandId, ACTION, "PERSONNEL", OrgCommands.fingerprint(ACTION, commandId),
                "인사기본 Excel 다운로드", () -> commands.read(actor, () -> {
                    if (organizationId != null && mapper.organizationActive(actor.tenantId(), organizationId) == null) {
                        throw OrgException.notFound();
                    }
                    LocalDate asOf = commands.today();
                    var rows = mapper.personnelExport(actor.tenantId(), employeeIds, search, organizationId, filter, asOf);
                    if (selected && rows.size() != employeeIds.size()) throw OrgException.notFound();
                    if (rows.size() > LIMIT) throw OrgException.invalid("조회 조건을 좁혀 1,000행 이하로 다운로드해 주세요.");
                    result.set(workbook(asOf, rows));
                    return new OrgCommands.Change(actor.tenantId(), null, Map.of("scope", scope, "rowCount", rows.size()));
                }));
        return result.get();
    }

    private static OrgException invalidScope() {
        return new OrgException(400, "선택한 사원 또는 현재 조회 조건 중 한 범위를 지정해 주세요.");
    }

    record Row(UUID employeeId, String employeeNo, String displayName, LocalDate joinedOn, LocalDate leftOn,
               boolean active, String organizationCode, String organizationName, String scheduleName,
               String gradeName, String positionName, String jobName) { }

    static byte[] workbook(LocalDate asOf, List<Row> employees) {
        List<List<Object>> rows = new ArrayList<>();
        rows.add(List.of("조회 기준일 (서울)", asOf.toString()));
        rows.add(List.of("사번", "이름", "현재 소속 코드", "현재 소속 이름", "현재 근무 템플릿", "입사일",
                "마지막 재직일", "활성 여부", "직급", "직책", "직무"));
        for (Row row : employees) {
            rows.add(List.of(row.employeeNo(), row.displayName(), text(row.organizationCode()),
                    text(row.organizationName()), text(row.scheduleName()), row.joinedOn().toString(),
                    row.leftOn() == null ? "" : row.leftOn().toString(), row.active() ? "활성" : "비활성",
                    text(row.gradeName()), text(row.positionName()), text(row.jobName())));
        }
        return ReviewWorkbook.create(rows);
    }

    private static String text(String value) {
        return value == null ? "" : value;
    }
}
