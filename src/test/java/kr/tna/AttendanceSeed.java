package kr.tna;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import kr.tna.clock.ClockService;
import kr.tna.common.auth.TnaPrincipal;
import kr.tna.common.tenant.TenantTransactions;
import kr.tna.worktime.WorktimeService;
import org.springframework.jdbc.core.JdbcTemplate;

/** 연차 출근율 시험용 근태. from~through 회사 달력은 workdays만 근로일이고 나머지는 비근로일이다. 일정은 매일 09~18시(휴게 12~13시)다. 회사마다 한 번만 만든다. */
final class AttendanceSeed {
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private final JdbcTemplate db;
    private final TenantTransactions transactions;
    private final ClockService clocks;
    private final WorktimeService worktime;
    private final TnaPrincipal admin;
    private final LocalDate from, through;
    private final List<LocalDate> workdays;
    private final UUID schedule = UUID.randomUUID(), network = UUID.randomUUID(), calendar = UUID.randomUUID();

    private AttendanceSeed(JdbcTemplate db, TenantTransactions transactions, ClockService clocks, WorktimeService worktime,
            TnaPrincipal admin, LocalDate from, LocalDate through, List<LocalDate> workdays) {
        this.db = db; this.transactions = transactions; this.clocks = clocks; this.worktime = worktime;
        this.admin = admin; this.from = from; this.through = through; this.workdays = workdays;
    }

    static AttendanceSeed calendar(JdbcTemplate db, TenantTransactions transactions, ClockService clocks, WorktimeService worktime,
            TnaPrincipal admin, LocalDate from, LocalDate through, LocalDate... workdays) {
        var seed = new AttendanceSeed(db, transactions, clocks, worktime, admin, from, through, List.of(workdays));
        UUID tenant = admin.tenantId(), account = admin.accountId(), template = UUID.randomUUID(), calendar = seed.calendar;
        db.update("INSERT INTO tna.schedule_template(tenant_id,id,code,name,created_by,updated_by) VALUES (?,?,?,'합성 출근율 일정',?,?)",
                tenant, template, template.toString(), account, account);
        db.update("INSERT INTO tna.schedule_version(tenant_id,id,template_id,revision,valid_from,reason,created_by) VALUES (?,?,?,1,?,'합성 출근율 일정',?)",
                tenant, seed.schedule, template, from, account);
        for (int day = 1; day <= 7; day++) {
            db.update("INSERT INTO tna.schedule_day(tenant_id,version_id,weekday,workday,start_time,end_time) VALUES (?,?,?,true,TIME '09:00',TIME '18:00')",
                    tenant, seed.schedule, day);
            db.update("INSERT INTO tna.schedule_break(tenant_id,version_id,weekday,sequence,start_time,end_time) VALUES (?,?,?,1,TIME '12:00',TIME '13:00')",
                    tenant, seed.schedule, day);
        }
        db.update("""
                INSERT INTO tna.calendar_version(tenant_id,id,revision,valid_from,valid_to,reason,created_by)
                SELECT ?,?,COALESCE(max(revision),0)+1,?,?,'합성 출근율 달력',? FROM tna.calendar_version WHERE tenant_id=?
                """, tenant, calendar, from, through.plusDays(1), account, tenant);
        db.update("""
                INSERT INTO tna.calendar_day(tenant_id,version_id,work_date,kind,name,basis)
                SELECT ?,?,d::date,'NON_WORKDAY','합성 비근로일','합성 출근율 근거' FROM generate_series(CAST(? AS date),CAST(? AS date),interval '1 day') d
                """, tenant, calendar, from, through);
        for (var day : workdays)
            db.update("UPDATE tna.calendar_day SET kind='WORKDAY',name='합성 근로일' WHERE tenant_id=? AND version_id=? AND work_date=?", tenant, calendar, day);
        db.update("INSERT INTO tna.allowed_network(tenant_id,id,network,label,valid_from,reason,created_by) VALUES (?,?,CAST('127.0.0.1/32' AS cidr),'합성 출근율',?,'합성 근거',?)",
                tenant, seed.network, java.sql.Timestamp.from(from.atStartOfDay(SEOUL).toInstant()), account);
        return seed;
    }

    List<LocalDate> workdays() { return workdays; }

    /** 사원을 이 일정에 배정하고, 재직 중인 근로일 중 absent가 아닌 날에 출퇴근을 넣은 뒤 calculated 날짜만 계산한다. */
    void employee(UUID employee, Set<LocalDate> absent, Collection<LocalDate> calculated) {
        UUID tenant = admin.tenantId(), account = admin.accountId();
        LocalDate joined = db.queryForObject("SELECT joined_on FROM tna.employee WHERE tenant_id=? AND id=?", LocalDate.class, tenant, employee);
        db.update("""
                INSERT INTO tna.schedule_assignment(tenant_id,id,employee_id,schedule_version_id,valid_from,valid_to,reason,created_by,updated_by)
                VALUES (?,?,?,?,?,?,'합성 출근율 배정',?,?)""",
                tenant, UUID.randomUUID(), employee, schedule, joined.isAfter(from) ? joined : from, through.plusDays(1), account, account);
        for (var day : workdays) {
            if (day.isBefore(joined) || absent.contains(day)) continue;
            for (String kind : List.of("IN", "OUT")) {
                var at = java.sql.Timestamp.from(day.atTime(kind.equals("IN") ? 9 : 18, 0).atZone(SEOUL).toInstant());
                db.update("""
                        INSERT INTO tna.attendance_event(tenant_id,id,employee_id,event_kind,occurred_at,received_at,client_command_id,
                            source,validated_ip,network_id,assigned_work_date) VALUES (?,?,?,?,?,?,?,'IP',CAST('127.0.0.1' AS inet),?,?)
                        """, tenant, UUID.randomUUID(), employee, kind, at, at, UUID.randomUUID(), network, day);
            }
            db.update("INSERT INTO tna.day_input_revision(tenant_id,employee_id,work_date,input_revision,review_revision) VALUES (?,?,?,2,0)",
                    tenant, employee, day);
        }
        var dates = calculated.stream().filter(day -> !day.isBefore(joined)).toList();
        if (dates.isEmpty()) return;
        transactions.execute(tenant, () -> {
            for (var day : dates) clocks.autoConfirm(tenant, employee, day, null);
            return worktime.calculateDatesAsSystem(tenant, null, employee, dates);
        });
    }

    void attend(UUID employee) { employee(employee, Set.of(), workdays); }

    /** employee()보다 먼저 부른다. day에 담당자를 결재자로 승인된 반차(0.5일) 연차를 넣는다. 그날 출근 기록이 없으면 일일 근태가 반일 결근으로 판정한다. */
    void halfDayLeave(TnaPrincipal owner, LocalDate day) {
        UUID tenant = admin.tenantId(), request = UUID.randomUUID(), bucket = UUID.randomUUID(), assignment = UUID.randomUUID();
        db.update("INSERT INTO tna.approver_assignment(tenant_id,id,employee_id,approver_employee_id,valid_from,reason,created_by,updated_by) VALUES (?,?,?,?,?,'합성 결재자',?,?)",
                tenant, assignment, owner.employeeId(), admin.employeeId(), from, admin.accountId(), admin.accountId());
        db.update("INSERT INTO tna.leave_bucket(tenant_id,id,employee_id,base_date,usable_from,source_reference,created_by) VALUES (?,?,?,?,?,'합성 반차 연차',?)",
                tenant, bucket, owner.employeeId(), day, day, admin.accountId());
        db.update("""
                INSERT INTO tna.approval_request(tenant_id,id,target_employee_id,author_account_id,kind,status,current_approver_id,
                  approver_assignment_id,approver_assignment_version,reason,leave_bucket_id,from_date,to_date,client_command_id,attendance_type_version_id)
                VALUES (?,?,?,?,'LEAVE_DAY','APPROVED',?,?,0,'합성 반차',?,?,?,gen_random_uuid(),
                  (SELECT v.id FROM tna.attendance_type_version v JOIN tna.attendance_type t ON t.tenant_id=v.tenant_id AND t.id=v.type_id
                   WHERE t.tenant_id=? AND t.system_kind='ANNUAL_LEAVE' AND v.revision=1))
                """, tenant, request, owner.employeeId(), owner.accountId(), admin.employeeId(), assignment, bucket, day, day, tenant);
        db.update("INSERT INTO tna.leave_request_day(tenant_id,id,request_id,leave_date,quantity_days,leave_part,schedule_version_id,calendar_version_id) VALUES (?,gen_random_uuid(),?,?,0.5,'AM',?,?)",
                tenant, request, day, schedule, calendar);
        db.update("INSERT INTO tna.request_effect(tenant_id,id,request_id,employee_id,effect_kind,created_by) VALUES (?,gen_random_uuid(),?,?,'LEAVE',?)",
                tenant, request, owner.employeeId(), admin.accountId());
    }
}
