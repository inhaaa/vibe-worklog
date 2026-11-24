package kr.tna.org;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/** 엑셀에서 붙여넣은 재직자 행을 값으로 바꾸고 행 안의 규칙만 검사한다. DB 조건은 서비스가 더한다. */
public final class EmployeeImportRows {
    public static final int MAX_ROWS = 500;
    private static final LocalDate EARLIEST_BIRTH = LocalDate.of(1900, 1, 1);
    private static final Pattern SERIAL = Pattern.compile("\\d{5}");
    private static final Pattern DATE = Pattern.compile("(\\d{4})([-./]?)(\\d{2})\\2(\\d{2})");

    public record Row(String clientRowId, String employeeNo, String displayName, String joinedOn,
                      String birthDate, String organizationCode, String gradeName, String positionName) { }
    public record FieldError(String field, String message) { }
    public record Parsed(String clientRowId, String employeeNo, String displayName, LocalDate joinedOn,
                         LocalDate birthDate, String organizationCode, String gradeName, String positionName,
                         List<FieldError> errors) { }

    private EmployeeImportRows() { }

    public static void checkCount(int rows) {
        if (rows < 1 || rows > MAX_ROWS) throw OrgException.invalid("1~500행을 붙여넣어 주세요.");
    }

    public static Parsed check(Row row, LocalDate today) {
        var errors = new ArrayList<FieldError>();
        String number = text(row.employeeNo(), 40, "사번", "employeeNo", errors, null);
        if (number != null && number.contains("@")) {
            errors.add(new FieldError("employeeNo", "사번은 1~40자이며 @를 쓸 수 없습니다."));
            number = null;
        } else if ("99999".equals(number)) {
            errors.add(new FieldError("employeeNo", "예약 사번 99999는 사용할 수 없습니다."));
            number = null;
        }
        String name = text(row.displayName(), 120, "이름", "displayName", errors, "이름을 입력해 주세요.");
        LocalDate joined = date(row.joinedOn(), "입사일", "joinedOn", errors);
        if (joined != null && joined.isAfter(today)) {
            errors.add(new FieldError("joinedOn", "입사일이 오늘 이후면 입사 예정 관리에서 등록해 주세요."));
        }
        LocalDate birth = date(row.birthDate(), "생년월일", "birthDate", errors);
        if (birth != null && birth.isBefore(EARLIEST_BIRTH)) {
            errors.add(new FieldError("birthDate", "생년월일은 1900-01-01 이후여야 합니다."));
        } else if (birth != null && joined != null && !birth.isBefore(joined)) {
            errors.add(new FieldError("birthDate", "생년월일은 입사일 이전이어야 합니다."));
        }
        String organization = text(row.organizationCode(), 40, "조직코드", "organizationCode", errors, "조직코드를 입력해 주세요.");
        String grade = text(row.gradeName(), 120, "직급", "gradeName", errors, null);
        String position = text(row.positionName(), 120, "직책", "positionName", errors, null);
        return new Parsed(row.clientRowId(), number, name, joined, birth, organization, grade, position, List.copyOf(errors));
    }

    /** requiredMessage가 null이면 선택 항목이다. */
    private static String text(String raw, int maximum, String label, String field, List<FieldError> errors, String requiredMessage) {
        String value = blankToNull(raw);
        if (value == null) {
            if (requiredMessage != null) errors.add(new FieldError(field, requiredMessage));
            return null;
        }
        try {
            return OrgCommands.requiredText(value, maximum, label);
        } catch (OrgException error) {
            errors.add(new FieldError(field, error.getMessage()));
            return null;
        }
    }

    private static LocalDate date(String raw, String label, String field, List<FieldError> errors) {
        String value = blankToNull(raw);
        if (value == null) {
            errors.add(new FieldError(field, label + "을 입력해 주세요."));
            return null;
        }
        if (SERIAL.matcher(value).matches()) {
            errors.add(new FieldError(field, "날짜 형식으로 표시한 뒤 복사하세요."));
            return null;
        }
        var match = DATE.matcher(value);
        if (!match.matches()) {
            errors.add(new FieldError(field, "YYYY-MM-DD 형식으로 입력해 주세요."));
            return null;
        }
        try {
            return LocalDate.of(Integer.parseInt(match.group(1)), Integer.parseInt(match.group(3)), Integer.parseInt(match.group(4)));
        } catch (DateTimeException error) {
            errors.add(new FieldError(field, "올바른 날짜가 아닙니다."));
            return null;
        }
    }

    private static String blankToNull(String value) {
        if (value == null) return null;
        String stripped = value.strip();
        return stripped.isEmpty() ? null : stripped;
    }
}
