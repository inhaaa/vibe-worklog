package kr.tna.org;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.util.MultiValueMap;

record AdminPersonnelTabCommand(PersonnelTab tab, Map<String, String> draft) {
    private static final Set<String> COMMON = Set.of("commandId", "reason");
    private static final Set<String> BASIC = Set.of("employeeVersion", "personnelVersion", "detailsVersion",
            "displayName", "joinedOn", "leftOn", "birthDate", "gradeName", "positionName", "jobName",
            "employmentStatus", "effectiveFrom", "photoAction", "photoData", "admissionType", "recognizedMonths",
            "groupJoinedOn", "englishName", "maritalStatus", "marriedOn", "veteranStatus", "disabilityStatus",
            "militaryStatus", "militaryBranch");
    private static final Set<String> APPOINTMENTS = Set.of("employeeVersion", "organizationId", "validFrom");
    private static final Set<String> FAMILY = Set.of("detailsVersion", "relationship", "count", "effectiveFrom", "effectiveTo");
    private static final Set<String> HISTORY = Set.of("detailsVersion", "title", "detail", "effectiveFrom", "effectiveTo");
    private static final Set<String> CONTACT = Set.of("detailsVersion", "postalCode", "address", "addressDetail",
            "mobilePhone", "contactEmail");

    static AdminPersonnelTabCommand read(PersonnelTab tab, MultiValueMap<String, String> input) {
        var allowed = switch (tab) {
            case BASIC -> BASIC;
            case APPOINTMENTS -> APPOINTMENTS;
            case FAMILY -> FAMILY;
            case EDUCATION, QUALIFICATION, LANGUAGE -> HISTORY;
            case CONTACT -> CONTACT;
        };
        var draft = new TreeMap<String, String>();
        input.forEach((key, values) -> {
            if ("_csrf".equals(key)) return;
            if ((!COMMON.contains(key) && !allowed.contains(key)) || values.size() != 1) {
                throw new OrgException(400, "선택한 탭에서 수정할 수 있는 항목을 확인해 주세요.");
            }
            draft.put(key, values.getFirst());
        });
        return new AdminPersonnelTabCommand(tab, Map.copyOf(draft));
    }

    String text(String key) { return draft.get(key); }

    String reason() { return OrgCommands.requiredText(text("reason"), 500, "변경 사유"); }

    UUID uuid(String key) {
        String value = text(key);
        if (value == null || value.isBlank()) return null;
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException invalid) {
            throw OrgException.invalid("식별자 입력 형식을 확인해 주세요.");
        }
    }

    LocalDate date(String key) {
        String value = text(key);
        if (value == null || value.isBlank()) return null;
        try {
            LocalDate date = LocalDate.parse(value);
            if (date.getYear() >= 1 && date.getYear() <= 9999) return date;
        } catch (DateTimeParseException invalid) {
            throw OrgException.invalid("날짜 입력 형식을 확인해 주세요.");
        }
        throw OrgException.invalid("날짜 입력 형식을 확인해 주세요.");
    }

    Long version(String key) {
        String value = text(key);
        if (value == null || value.isBlank()) return null;
        try {
            return Long.valueOf(value);
        } catch (NumberFormatException invalid) {
            throw OrgException.invalid("저장 버전 형식을 확인해 주세요.");
        }
    }

    Integer number(String key, String label) {
        String value = text(key);
        if (value == null || value.isBlank()) return null;
        try {
            return Integer.valueOf(value);
        } catch (NumberFormatException invalid) {
            throw OrgException.invalid(label + "은(는) 정수로 입력해 주세요.");
        }
    }

    String fingerprint(UUID target, String action, String reason) {
        var parts = new java.util.ArrayList<Object>();
        parts.add(action);
        parts.add(target);
        parts.add(reason);
        new TreeMap<>(draft).forEach((key, value) -> {
            if (!COMMON.contains(key)) {
                parts.add(key);
                parts.add(value);
            }
        });
        return OrgCommands.fingerprint(parts.toArray());
    }
}
