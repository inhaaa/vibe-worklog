package kr.tna.org;

import java.time.LocalDate;
import java.util.Collection;
import java.util.Locale;

/** Call only after acquiring the tenant mutation lock and checking command replay. */
public final class EmployeeNumbers {
    private EmployeeNumbers() { }

    public static String next(LocalDate joinedOn, Collection<String> reserved) {
        String prefix = String.format(Locale.ROOT, "%02d", joinedOn.getYear() % 100);
        int maximum = reserved.stream()
                .filter(number -> number.matches("[0-9]{5}") && number.startsWith(prefix) && !number.equals("99999"))
                .mapToInt(number -> Integer.parseInt(number.substring(2)))
                .max().orElse(0);
        int sequence = maximum + 1;
        String number = String.format(Locale.ROOT, "%s%03d", prefix, sequence);
        if (sequence > 999 || number.equals("99999")) {
            throw OrgException.invalid("해당 입사연도의 자동 사번 한도(001~999, 예약 99999 제외)에 도달했습니다. 사번을 직접 입력해 주세요.");
        }
        return number;
    }
}
