package kr.tna;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.UUID;
import kr.tna.common.auth.TnaPrincipal;
import org.springframework.jdbc.core.JdbcTemplate;

/** 테스트용 근태 달력 기간(PERIOD) 시드. admin 연결로 넣는다. */
final class CalendarSeed {
    private CalendarSeed() { }

    /** from~through(포함)의 모든 날을 WORKDAY로 만들고, exceptions의 `날짜|종류|이름|근거` 줄로 덮는다. 버전 id를 돌려준다. */
    static UUID period(JdbcTemplate admin, TnaPrincipal actor, LocalDate from, LocalDate through, String basis, String exceptions) {
        var overrides = new HashMap<LocalDate, String[]>();
        if (exceptions != null) {
            for (String line : exceptions.lines().filter(line -> !line.isBlank()).toList()) {
                String[] parts = line.split("[|]", -1);
                overrides.put(LocalDate.parse(parts[0].strip()), parts);
            }
        }
        UUID id = UUID.randomUUID();
        admin.update("""
                INSERT INTO tna.calendar_version(tenant_id,id,revision,valid_from,valid_to,reason,created_by)
                SELECT ?,?,COALESCE(max(revision),0)+1,?,?,'합성 달력',? FROM tna.calendar_version WHERE tenant_id=?
                """, actor.tenantId(), id, from, through.plusDays(1), actor.accountId(), actor.tenantId());
        for (LocalDate date = from; !date.isAfter(through); date = date.plusDays(1)) {
            String[] day = overrides.getOrDefault(date, new String[] {null, "WORKDAY", "검토한 기본 근로일", basis});
            admin.update("INSERT INTO tna.calendar_day(tenant_id,version_id,work_date,kind,name,basis) VALUES (?,?,?,?,?,?)",
                    actor.tenantId(), id, date, day[1].strip(), day[2].strip(), day[3].strip());
        }
        return id;
    }
}
