package kr.tna;

import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import kr.tna.clock.ClockData.ConfirmationForm;
import kr.tna.clock.ClockService;
import kr.tna.common.auth.TnaPrincipal;
import org.springframework.jdbc.core.JdbcTemplate;

final class AttendanceMonthCycleData {
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final LocalDate CORRECTION_DATE = LocalDate.of(2026, 9, 29);
    private static final LocalDate LEAVE_DATE = LocalDate.of(2026, 9, 30);
    static final Instant MONTH_END = LEAVE_DATE.atTime(13, 15).atZone(SEOUL).toInstant();
    static final Instant NEXT_MONTH = LEAVE_DATE.plusDays(1).atTime(8, 0).atZone(SEOUL).toInstant();
    private static final String LEAVE_NAME = "경조휴가";

    record Employee(String employeeNo, String name, String email, UUID employeeId, UUID accountId) {
        Employee(String employeeNo, String name) {
            this(employeeNo, name, employeeNo.toLowerCase(Locale.ROOT) + "@example.test", UUID.randomUUID(), UUID.randomUUID());
        }
    }
    record Department(UUID id, String code, String name) {
        Department(String code, String name) { this(UUID.randomUUID(), code, name); }
    }
    record Shift(String start, String end, String breakStart, String breakEnd) {}
    record ExpectedDay(LocalDate date, String departmentCode, int actualMinutes, int paidLeaveMinutes, int leaveDays) {}

    private final JdbcTemplate db;
    private final UUID tenant = UUID.randomUUID();
    private final Department research = new Department("A01", "연구개발팀");
    private final Department support = new Department("B02", "고객지원팀");
    private final Employee applicant = new Employee("C101", "합성정정");
    private final Employee comparison = new Employee("C202", "합성대조");
    private final Employee manager = new Employee("C900", "합성담당");

    AttendanceMonthCycleData(JdbcTemplate db) { this.db = db; }

    void seed(String companyCode, String passwordHash) {
        db.update("INSERT INTO platform.tenant_registry(id,company_code,display_name) VALUES (?,?,'가상 월마감 회사')",
                tenant, companyCode);
        employee(applicant, CORRECTION_DATE, passwordHash);
        employee(comparison, LEAVE_DATE, passwordHash);
        employee(manager, LEAVE_DATE, passwordHash);
        db.update("INSERT INTO tna.role_grant(tenant_id,account_id,role) VALUES (?,?,'ATTENDANCE_ADMIN'),(?,?,'APPROVER')",
                tenant, manager.accountId(), tenant, manager.accountId());
        for (Department department : List.of(research, support)) {
            db.update("INSERT INTO tna.organization(tenant_id,id,code,name,created_by,updated_by) VALUES (?,?,?,?,?,?)",
                    tenant, department.id(), department.code(), department.name(), manager.accountId(), manager.accountId());
        }
        assign(applicant, research, CORRECTION_DATE);
        assign(comparison, support, LEAVE_DATE);
        assign(manager, support, LEAVE_DATE);
        db.update("""
                INSERT INTO tna.approver_assignment(tenant_id,id,employee_id,approver_employee_id,valid_from,reason,created_by,updated_by)
                VALUES (?,gen_random_uuid(),?,?,?,'지정 승인자',?,?)
                """, tenant, applicant.employeeId(), manager.employeeId(), LEAVE_DATE, manager.accountId(), manager.accountId());
        schedule(applicant, CORRECTION_DATE, Map.of(2, new Shift("09:00","18:00","12:00","13:00"),
                3, new Shift("14:00","23:00","18:00","19:00")));
        schedule(comparison, LEAVE_DATE, Map.of(3, new Shift("08:30","13:00","11:00","11:30")));
        schedule(manager, LEAVE_DATE, Map.of());
        calendar();
        companyLeave();
        UUID network = UUID.randomUUID();
        db.update("""
                INSERT INTO tna.allowed_network(tenant_id,id,label,network,valid_from,reason,created_by)
                VALUES (?,?,'로컬 근무지','127.0.0.1/32'::cidr,?,'합성 원천',?)
                """, tenant, network, Timestamp.from(CORRECTION_DATE.atStartOfDay(SEOUL).toInstant()), manager.accountId());
        clockEvent(applicant, CORRECTION_DATE, "IN", 9, 0, network);
        clockEvent(comparison, LEAVE_DATE, "IN", 8, 30, network);
        clockEvent(comparison, LEAVE_DATE, "OUT", 13, 0, network);
    }

    private void employee(Employee person, LocalDate joining, String hash) {
        db.update("INSERT INTO tna.employee(tenant_id,id,employee_no,display_name,joined_on) VALUES (?,?,?,?,?)",
                tenant, person.employeeId(), person.employeeNo(), person.name(), joining);
        db.update("INSERT INTO tna.tenant_account(tenant_id,id,employee_id,email,password_hash) VALUES (?,?,?,?,?)",
                tenant, person.accountId(), person.employeeId(), person.email(), hash);
        db.update("INSERT INTO tna.role_grant(tenant_id,account_id,role) VALUES (?,?,'EMPLOYEE')", tenant, person.accountId());
    }

    private void assign(Employee person, Department department, LocalDate from) {
        db.update("""
                INSERT INTO tna.employee_assignment(tenant_id,id,employee_id,organization_id,valid_from,reason,created_by,updated_by)
                VALUES (?,gen_random_uuid(),?,?,?,'최초 소속',?,?)
                """, tenant, person.employeeId(), department.id(), from, manager.accountId(), manager.accountId());
    }

    private void schedule(Employee person, LocalDate from, Map<Integer,Shift> shifts) {
        UUID template = UUID.randomUUID(), version = UUID.randomUUID(), actor = manager.accountId();
        db.update("INSERT INTO tna.schedule_template(tenant_id,id,code,name,created_by,updated_by) VALUES (?,?,?,'월말 고정 근무',?,?)",
                tenant, template, person.employeeNo(), actor, actor);
        db.update("INSERT INTO tna.schedule_version(tenant_id,id,template_id,revision,valid_from,reason,created_by) VALUES (?,?,?,1,?,'합성 근무',?)",
                tenant, version, template, from, actor);
        for (int weekday = 1; weekday <= 7; weekday++) {
            Shift shift = shifts.get(weekday);
            db.update("""
                    INSERT INTO tna.schedule_day(tenant_id,version_id,weekday,workday,start_time,end_time)
                    VALUES (?,?,?,?,CAST(? AS time),CAST(? AS time))
                    """, tenant, version, weekday, shift != null, shift == null ? null : shift.start(), shift == null ? null : shift.end());
            if (shift != null) {
                db.update("""
                        INSERT INTO tna.schedule_break(tenant_id,version_id,weekday,sequence,start_time,end_time)
                        VALUES (?,?,?,1,CAST(? AS time),CAST(? AS time))
                        """, tenant, version, weekday, shift.breakStart(), shift.breakEnd());
            }
        }
        db.update("""
                INSERT INTO tna.schedule_assignment(tenant_id,id,employee_id,schedule_version_id,valid_from,reason,created_by,updated_by)
                VALUES (?,gen_random_uuid(),?,?,?,'고정 근무 배정',?,?)
                """, tenant, person.employeeId(), version, from, actor, actor);
    }

    private void calendar() {
        UUID version = UUID.randomUUID();
        db.update("""
                INSERT INTO tna.calendar_version(tenant_id,id,revision,valid_from,valid_to,reason,created_by)
                VALUES (?,?,1,DATE '2026-09-01',DATE '2026-11-01','월말 검증 달력',?)
                """, tenant, version, manager.accountId());
        db.update("""
                INSERT INTO tna.calendar_day(tenant_id,version_id,work_date,kind,name,basis)
                SELECT ?,?,d::date,CASE WHEN extract(isodow FROM d)<6 THEN 'WORKDAY' ELSE 'NON_WORKDAY' END,
                    '합성 달력일','가상 회사 달력'
                FROM generate_series(DATE '2026-09-01', DATE '2026-10-31', INTERVAL '1 day') d
                """, tenant, version);
    }

    private void companyLeave() {
        UUID type = UUID.randomUUID();
        db.update("INSERT INTO tna.attendance_type(tenant_id,id,code,created_by,updated_by) VALUES (?,?,'L10',?,?)",
                tenant, type, manager.accountId(), manager.accountId());
        db.update("""
                INSERT INTO tna.attendance_type_version(tenant_id,id,type_id,revision,effective_from,enabled,name,unit,
                    pay_kind,deduction_rule,recognition_rule,basis,reason,created_by)
                VALUES (?,gen_random_uuid(),?,1,?,true,?,'DAY','PAID','NONE','COMPANY_LEAVE','가상 회사 규정','유급 종일 휴가',?)
                """, tenant, type, CORRECTION_DATE, LEAVE_NAME, manager.accountId());
    }

    private void clockEvent(Employee person, LocalDate date, String kind, int hour, int minute, UUID network) {
        var at = Timestamp.from(date.atTime(hour, minute).atZone(SEOUL).toInstant());
        db.update("""
                INSERT INTO tna.attendance_event(tenant_id,id,employee_id,event_kind,occurred_at,received_at,client_command_id,
                    source,validated_ip,network_id,assigned_work_date)
                VALUES (?,gen_random_uuid(),?,?,?,?,gen_random_uuid(),'IP','127.0.0.1'::inet,?,?)
                """, tenant, person.employeeId(), kind, at, at, network, date);
        db.update("""
                INSERT INTO tna.day_input_revision(tenant_id,employee_id,work_date,input_revision,review_revision) VALUES (?,?,?,1,0)
                ON CONFLICT (tenant_id,employee_id,work_date) DO UPDATE SET input_revision=tna.day_input_revision.input_revision+1
                """, tenant, person.employeeId(), date);
    }

    void confirmComparisonDay(ClockService service) {
        var actor = new TnaPrincipal(tenant, comparison.accountId(), comparison.employeeId(), 1, Set.of("EMPLOYEE"), MONTH_END);
        var day = service.day(actor, LEAVE_DATE);
        service.confirm(actor, new ConfirmationForm(LEAVE_DATE, day.inputRevision(), day.reviewRevision(), true, true,
                day.sourceFingerprint(), UUID.randomUUID()));
    }

    Map<String, Object> metadata(String baseUrl, String companyCode, String password, Path advance) {
        var data = new LinkedHashMap<String, Object>();
        data.put("baseUrl", baseUrl);
        data.put("companyCode", companyCode);
        data.put("password", password);
        data.put("month", "2026-09");
        data.put("correctionDate", CORRECTION_DATE);
        data.put("leaveDate", LEAVE_DATE);
        data.put("leaveName", LEAVE_NAME);
        data.put("advanceFile", advance.toString());
        data.put("correctionEmployee", applicant);
        data.put("leaveEmployee", applicant);
        data.put("comparisonEmployee", comparison);
        data.put("admin", manager);
        data.put("departments", List.of(research, support));
        // The oracle comes from the agreed synthetic shifts, independently of the application's reads and exports.
        data.put("expected", Map.of("month", "2026-09", "version", 1, "closedBy", manager.name(),
                "departments", List.of(Map.of("code", research.code(), "name", research.name()),
                        Map.of("code", support.code(), "name", support.name())),
                "employees", List.of(expected(applicant, new ExpectedDay(CORRECTION_DATE, research.code(), 450, 0, 0),
                                new ExpectedDay(LEAVE_DATE, research.code(), 0, 480, 1)),
                        expected(comparison, new ExpectedDay(LEAVE_DATE, support.code(), 240, 0, 0)),
                        expected(manager, new ExpectedDay(LEAVE_DATE, support.code(), 0, 0, 0)))));
        return data;
    }

    private Map<String,Object> expected(Employee person, ExpectedDay... days) {
        return Map.of("employeeNo", person.employeeNo(), "name", person.name(), "days", List.of(days));
    }
}
