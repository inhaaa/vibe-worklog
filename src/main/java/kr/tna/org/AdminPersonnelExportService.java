package kr.tna.org;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;
import kr.tna.common.auth.TnaPrincipal;
import kr.tna.org.EmployeePersonalDetailsData.Entry;
import kr.tna.org.EmployeePersonalDetailsData.Form;
import kr.tna.org.EmployeePersonnelData.Header;
import kr.tna.worktime.ReviewWorkbook;
import org.springframework.stereotype.Service;

@Service
final class AdminPersonnelExportService {
    private static final String ACTION = "PERSONNEL_TAB_EXPORT";
    private static final Set<String> FAMILY_RELATIONSHIPS = Set.of("배우자", "자녀", "부모", "기타");
    private static final Pattern FAMILY_COUNT = Pattern.compile("([1-9][0-9]{0,9})명");
    private static final Pattern EMAIL_DOMAIN = Pattern.compile("[\\p{L}\\p{N}-]+(?:\\.[\\p{L}\\p{N}-]+)+");
    private static final Pattern INVALID_XML = Pattern.compile("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F\\uFFFE\\uFFFF]");
    private static final Map<String, String> EMPLOYMENT_STATUS = Map.of("WORKING", "재직", "ON_LEAVE", "휴직",
            "SUSPENDED", "정직", "PRE_JOINING", "입사 예정", "LEFT", "퇴사", "INACTIVE", "비활성");
    private static final Map<String, String> ADMISSION_TYPE = Map.of("NEW", "신입", "EXPERIENCED", "경력", "TRANSFER", "전입");

    private final OrgCommands commands;
    private final EmployeeService employees;
    private final EmployeePersonnelService personnel;
    private final EmployeePersonalDetailsMapper details;
    private final PublicDemoIdentityGuard demo;

    AdminPersonnelExportService(OrgCommands commands, EmployeeService employees, EmployeePersonnelService personnel,
            EmployeePersonalDetailsMapper details, PublicDemoIdentityGuard demo) {
        this.commands = commands;
        this.employees = employees;
        this.personnel = personnel;
        this.details = details;
        this.demo = demo;
    }

    byte[] download(TnaPrincipal actor, UUID employeeId, PersonnelTab tab) {
        if (demo.publicDemo()) throw OrgException.forbidden();
        UUID commandId = UUID.randomUUID();
        var result = new AtomicReference<byte[]>();
        commands.execute(actor, commandId, ACTION, "EMPLOYEE", OrgCommands.fingerprint(ACTION, employeeId, tab.id(), commandId),
                "인사정보 탭 Excel 다운로드", () -> commands.read(actor, () -> {
                    var header = personnel.header(actor, employeeId);
                    var rows = workbookRows(actor.tenantId(), header, tab);
                    result.set(ReviewWorkbook.create(rows));
                    return new OrgCommands.Change(employeeId, null, Map.of("tab", tab.id(), "rowCount", rows.size() - 1));
                }));
        return result.get();
    }

    private List<List<Object>> workbookRows(UUID tenantId, Header header, PersonnelTab tab) {
        var columns = switch (tab) {
            case BASIC -> List.of("입사일", "마지막 재직일", "입사구분", "인정경력", "그룹입사일", "소속",
                    "재직 표시 상태", "직급", "직책", "직무");
            case APPOINTMENTS -> List.of("발령 구분", "조직명", "적용 시작일", "적용 종료일(미포함)");
            case FAMILY -> List.of("관계", "인원", "시작일", "종료일");
            case EDUCATION -> List.of("학교명", "전공·졸업 구분", "시작일", "종료일");
            case QUALIFICATION -> List.of("자격명", "발급기관", "취득일", "만료일");
            case LANGUAGE -> List.of("언어·시험명", "등급 또는 점수", "취득일", "만료일");
            case CONTACT -> List.of("우편번호", "기본주소", "휴대전화", "연락 이메일");
        };
        var employee = List.of(header.employeeNo(), header.employeeName());
        UUID employeeId = header.employeeId();
        List<List<Object>> rows = new ArrayList<>();
        rows.add(row(List.of("사번", "한글 이름"), columns.toArray()));
        rows.addAll(switch (tab) {
            case BASIC -> List.of(basic(employee, header, details.stored(tenantId, employeeId),
                    employees.requireEmployee(tenantId, employeeId).active()));
            case APPOINTMENTS -> details.appointments(tenantId, employeeId).stream()
                    .map(appointment -> row(employee, appointment.initial() ? "입사" : "소속 배정",
                            appointment.organizationName(), appointment.validFrom(), appointment.validTo())).toList();
            case FAMILY, EDUCATION, QUALIFICATION, LANGUAGE -> details.entries(tenantId, employeeId).stream()
                    .filter(entry -> tab.name().equals(entry.kind())).map(entry -> history(employee, entry, tab)).toList();
            case CONTACT -> contact(employee, details.stored(tenantId, employeeId));
        });
        return rows;
    }

    private static List<Object> basic(List<String> employee, Header header, Form details, boolean active) {
        String status = !active && !"LEFT".equals(header.employmentStatus()) ? "INACTIVE" : header.employmentStatus();
        return row(employee, header.joinedOn(), header.leftOn(),
                details == null ? "" : ADMISSION_TYPE.getOrDefault(details.admissionType(), "미등록"),
                details == null ? null : details.recognizedMonths(), details == null ? null : details.groupJoinedOn(),
                header.organizationName(), EMPLOYMENT_STATUS.getOrDefault(status, "상태 미등록"),
                header.gradeName(), header.positionName(), header.jobName());
    }

    private static List<Object> history(List<String> employee, Entry entry, PersonnelTab tab) {
        String detail = text(entry.detail());
        String prefix = AdminPersonnelTabService.ENTRY_PREFIX;
        boolean structured = detail.startsWith(prefix);
        String normalizedDetail = structured ? detail.substring(prefix.length()) : detail;
        if (tab == PersonnelTab.FAMILY) {
            var count = FAMILY_COUNT.matcher(normalizedDetail);
            boolean known = FAMILY_RELATIONSHIPS.contains(entry.title()) && count.matches();
            return row(employee, known ? entry.title() : "", known ? count.group(1) : "", entry.effectiveFrom(), entry.effectiveTo());
        }
        return row(employee, entry.title(), structured ? normalizedDetail : "", entry.effectiveFrom(), entry.effectiveTo());
    }

    private static List<List<Object>> contact(List<String> employee, Form details) {
        if (details == null) return List.of();
        var values = List.of(text(details.postalCode()), text(details.address()),
                maskedPhone(details.mobilePhone()), maskedEmail(details.contactEmail()));
        return values.stream().allMatch(String::isBlank) ? List.of() : List.of(row(employee, values.toArray()));
    }

    private static String maskedPhone(String phone) {
        String digits = text(phone).replaceAll("[^0-9]", "");
        return digits.length() > 4 ? "****" + digits.substring(digits.length() - 4) : "";
    }

    private static String maskedEmail(String email) {
        if (email == null) return "";
        int at = email.indexOf('@');
        if (at <= 0) return "";
        String domain = email.substring(at + 1);
        return EMAIL_DOMAIN.matcher(domain).matches() ? "***@" + domain : "";
    }

    private static List<Object> row(List<String> prefix, Object... values) {
        List<Object> row = new ArrayList<>();
        prefix.forEach(value -> row.add(cell(value)));
        for (Object value : values) row.add(cell(value));
        return row;
    }

    private static String cell(Object value) {
        return value == null ? "" : INVALID_XML.matcher(value.toString()).replaceAll("");
    }

    private static String text(String value) { return value == null ? "" : value; }
}
