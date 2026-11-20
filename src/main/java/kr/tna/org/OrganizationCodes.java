package kr.tna.org;

import java.time.Instant;
import java.time.ZoneId;
import java.util.Collection;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

final class OrganizationCodes {
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private final String year;
    private final Set<String> reserved;
    private int sequence = 1;

    OrganizationCodes(Instant createdAt, Collection<String> codes) {
        year = String.format(Locale.ROOT, "%04d", createdAt.atZone(SEOUL).getYear());
        reserved = new HashSet<>(codes);
    }

    static String normalize(String code) {
        return code == null || code.isBlank() ? null : OrgCommands.requiredText(code, 40, "조직 코드");
    }

    String issue(String requestedCode) {
        if (requestedCode != null) {
            throw OrgException.invalid("신규 조직 코드는 자동으로 발급합니다. 화면을 새로 열어 저장해 주세요.");
        }
        while (sequence <= 9_999) {
            String candidate = year + String.format(Locale.ROOT, "%04d", sequence++);
            if (reserved.add(candidate)) return candidate;
        }
        throw OrgException.invalid("현재 고객사의 " + year + "년 조직 코드 발급 한도에 도달했습니다.");
    }
}
