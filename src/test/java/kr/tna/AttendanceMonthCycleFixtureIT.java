package kr.tna;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.*;

class AttendanceMonthCycleFixtureIT {
    @Test
    void startsAtMonthEndWithOnlyRawInputsAndAdvancesAcrossTheSeoulBoundary(@TempDir Path run) throws Exception {
        var database = DatabaseFixture.start();
        var db = new JdbcTemplate(database.adminDataSource());
        String code = "MC-" + UUID.randomUUID();
        try (var app = AttendanceMonthCycleBrowser.start(database, run, code)) {
            assertEquals(3, db.queryForObject("""
                    SELECT count(*) FROM tna.employee e
                    JOIN platform.tenant_registry t ON t.id=e.tenant_id WHERE t.company_code=?
                    """, Integer.class, code), "서로 다른 두 부서의 신청자·대조 사원·담당자가 있어야 한다.");
            UUID tenant = db.queryForObject("SELECT id FROM platform.tenant_registry WHERE company_code=?", UUID.class, code);
            assertEquals(List.of("C101/2026-09-29/IN", "C202/2026-09-30/IN", "C202/2026-09-30/OUT"),
                    db.queryForList("""
                            SELECT e.employee_no||'/'||a.assigned_work_date||'/'||a.event_kind
                            FROM tna.attendance_event a JOIN tna.employee e ON (e.tenant_id,e.id)=(a.tenant_id,a.employee_id)
                            WHERE a.tenant_id=? ORDER BY e.employee_no,a.occurred_at
                            """, String.class, tenant));
            assertEquals("C202", db.queryForObject("""
                    SELECT e.employee_no FROM tna.work_confirmation c
                    JOIN tna.employee e ON (e.tenant_id,e.id)=(c.tenant_id,c.employee_id)
                    WHERE c.tenant_id=? AND c.work_date=DATE '2026-09-30' AND c.presence_as_work AND c.breaks_match
                    """, String.class, tenant));
            for (String table : List.of("approval_request", "request_effect", "day_result", "attendance_month_closure")) {
                assertEquals(0, db.queryForObject("SELECT count(*) FROM tna." + table + " WHERE tenant_id=?",
                        Integer.class, tenant), table + " 업무는 브라우저에서 시작해야 한다.");
            }
            var metadata = new JsonMapper().readTree(Files.readString(run.resolve("fixture.json")));
            assertEquals(code, metadata.required("companyCode").asString());
            assertEquals("C101", metadata.required("correctionEmployee").required("employeeNo").asString());
            assertEquals(metadata.required("correctionEmployee"), metadata.required("leaveEmployee"));
            assertEquals(3, metadata.required("expected").required("employees").size());
            var clock = app.getBean(Clock.class).withZone(ZoneId.of("Asia/Seoul"));
            assertEquals(LocalDateTime.of(2026, 9, 30, 13, 15), LocalDateTime.now(clock));
            Files.createFile(Path.of(metadata.required("advanceFile").asString()));
            assertEquals(LocalDateTime.of(2026, 10, 1, 8, 0), LocalDateTime.now(clock));
        }
    }
}
