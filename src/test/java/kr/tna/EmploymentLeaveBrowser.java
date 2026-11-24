package kr.tna;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.InstantSource;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.IntStream;
import kr.tna.LeaveBrowserServer.Member;
import kr.tna.clock.ClockData.ConfirmationForm;
import kr.tna.clock.ClockService;
import kr.tna.common.auth.TnaPrincipal;
import kr.tna.org.AccountData.ApproverForm;
import kr.tna.org.ApproverService;
import kr.tna.request.AnnualLeaveCycleService;
import kr.tna.request.LeaveAttributionData;
import kr.tna.request.LeaveAttributionService;
import kr.tna.request.LeaveWorkflowService;
import kr.tna.request.RequestData.BucketForm;
import kr.tna.request.RequestData.DecisionForm;
import kr.tna.request.RequestData.LeaveForm;
import kr.tna.schedule.ScheduleData.AssignmentForm;
import kr.tna.schedule.ScheduleData.DayForm;
import kr.tna.schedule.ScheduleData.TemplateForm;
import kr.tna.schedule.ScheduleService;
import kr.tna.worktime.WorktimeService;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

/** Chrome handles the monthly decisions, E101 approval and exit. E202 has an already used historical opening. */
public final class EmploymentLeaveBrowser implements LeaveBrowserServer.Scenario {
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final LocalDate HIRED = LocalDate.of(2026, 4, 15);
    private static final LocalDate OPENED = HIRED.plusMonths(1);
    private static final LocalDate COMPARISON_LEAVE = LocalDate.of(2026, 5, 18);
    private static final LocalDate REVIEW = LocalDate.of(2026, 7, 15);
    private static final LocalDate ABSENCE = LocalDate.of(2026, 6, 1);
    private static final LocalDate LAST = LocalDate.of(2026, 7, 17);
    private static final String COMPANY = "EMPLOYMENT-LEAVE";
    private final UUID tenant = UUID.randomUUID();
    private final Member worker = new Member("E101", "가상입퇴사");
    private final Member comparison = new Member("E202", "가상대조");
    private final Member admin = new Member("E900", "가상담당");
    private final Member approver = new Member("E901", "가상결재");
    private volatile LocalDate preparationDate = HIRED;

    public static void main(String[] args) throws Exception {
        LeaveBrowserServer.run(args, "scripts/verify-employment-leave.cjs", "EMPLOYMENT_LEAVE_BROWSER_READY", new EmploymentLeaveBrowser());
    }

    @Override public Clock clock(Path run) {
        Path exit = run.resolve("exit-day"), departed = run.resolve("departed");
        if (Files.exists(exit) || Files.exists(departed)) throw new IllegalArgumentException("새 근무기간 검증 경로가 필요합니다.");
        InstantSource timeline = () -> {
            LocalDate setup = preparationDate;
            var date = setup != null ? setup : Files.exists(departed) ? LAST.plusDays(1) : Files.exists(exit) ? LAST : REVIEW;
            return date.atTime(10, 0).atZone(SEOUL).toInstant();
        };
        return timeline.withZone(SEOUL);
    }

    @Override public void seed(JdbcTemplate db, String hash) {
        db.update("INSERT INTO platform.tenant_registry(id,company_code,display_name) VALUES (?,?,'가상 입퇴사 연차 회사')", tenant, COMPANY);
        for (var person : List.of(worker, comparison, admin, approver)) {
            db.update("INSERT INTO tna.employee(tenant_id,id,employee_no,display_name,joined_on,left_on) VALUES (?,?,?,?,?,?)",
                    tenant, person.employeeId(), person.employeeNo(), person.name(),
                    person.equals(worker) || person.equals(comparison) ? HIRED : HIRED.minusYears(2),
                    person.equals(worker) ? LAST : null);
            db.update("INSERT INTO tna.tenant_account(tenant_id,id,employee_id,email,password_hash) VALUES (?,?,?,?,?)",
                    tenant, person.accountId(), person.employeeId(), person.email(), hash);
            db.update("INSERT INTO tna.role_grant(tenant_id,account_id,role) VALUES (?,?,'EMPLOYEE')", tenant, person.accountId());
        }
        db.update("INSERT INTO tna.role_grant(tenant_id,account_id,role) VALUES (?,?,'ATTENDANCE_ADMIN'),(?,?,'APPROVER')",
                tenant, admin.accountId(), tenant, approver.accountId());
        UUID network = UUID.randomUUID();
        db.update("""
                INSERT INTO tna.allowed_network(tenant_id,id,network,label,reason,valid_from,created_by)
                VALUES (?,?,'127.0.0.1/32','합성 근무지','과거 출퇴근 대조',?,?)
                """, tenant, network, Timestamp.from(HIRED.atStartOfDay(SEOUL).toInstant()), admin.accountId());
        for (var date : recordedDates()) {
            for (var kind : List.of("IN", "OUT")) {
                var at = Timestamp.from(date.atTime(kind.equals("IN") ? 9 : 18, 0).atZone(SEOUL).toInstant());
                db.update("""
                        INSERT INTO tna.attendance_event(tenant_id,id,employee_id,event_kind,occurred_at,received_at,
                            client_command_id,source,validated_ip,network_id,assigned_work_date)
                        VALUES (?,gen_random_uuid(),?,?,?,?,gen_random_uuid(),'IP','127.0.0.1'::inet,?,?)
                        """, tenant, worker.employeeId(), kind, at, at, network, date);
            }
            db.update("INSERT INTO tna.day_input_revision(tenant_id,employee_id,work_date,input_revision,review_revision) VALUES (?,?,?,2,0)",
                    tenant, worker.employeeId(), date);
        }
    }

    private List<LocalDate> recordedDates() {
        return HIRED.datesUntil(REVIEW).filter(d -> d.getDayOfWeek().getValue() <= 5 && !d.equals(ABSENCE)).toList();
    }

    @Override public Map<String,Object> prepare(ConfigurableApplicationContext app, JdbcTemplate db, Path run) {
        var clock = app.getBean(Clock.class);
        var hr = new TnaPrincipal(tenant, admin.accountId(), admin.employeeId(), 1, Set.of("EMPLOYEE","ATTENDANCE_ADMIN"), clock.instant());
        var days = IntStream.rangeClosed(1, 7).mapToObj(i -> i <= 5 ? new DayForm(i,true,"09:00","18:00","12:00-13:00")
                : new DayForm(i,false,null,null,null)).toList();
        var schedules = app.getBean(ScheduleService.class);
        var template = schedules.create(hr, new TemplateForm("NEW-HIRE","입사일부터 주 5일",HIRED,days,null,"입사 기준",UUID.randomUUID()));
        var version = schedules.versions(hr,template).getFirst();
        CalendarSeed.period(db, hr, HIRED,LAST, "가상 근무 달력", "");
        for (var person : List.of(worker, comparison)) {
            schedules.assign(hr,person.employeeId(),new AssignmentForm(version.id(),HIRED,0L,"합성 고정 근무",UUID.randomUUID()));
            long employeeVersion = db.queryForObject("SELECT version FROM tna.employee WHERE tenant_id=? AND id=?",
                    Long.class, tenant, person.employeeId());
            app.getBean(ApproverService.class).set(hr,new ApproverForm(person.employeeId(),approver.employeeId(),HIRED,employeeVersion,
                    "독립 휴가 결재자",UUID.randomUUID()));
        }
        preparationDate = OPENED;
        prepareUsedOpening(app, hr, clock);
        preparationDate = null;
        var employee = new TnaPrincipal(tenant, worker.accountId(), worker.employeeId(), 1, Set.of("EMPLOYEE"), clock.instant());
        var attendance = app.getBean(ClockService.class);
        for (var date : recordedDates()) {
            var day = attendance.day(employee,date);
            attendance.confirm(employee,new ConfirmationForm(date,day.inputRevision(),day.reviewRevision(),true,true,day.sourceFingerprint(),UUID.randomUUID()));
        }
        var worktime = app.getBean(WorktimeService.class);
        for (LocalDate start = HIRED; start.isBefore(REVIEW); ) {
            var end = YearMonth.from(start).atEndOfMonth();
            if (!end.isBefore(REVIEW)) end = REVIEW.minusDays(1);
            worktime.recalculate(hr,worker.employeeId(),start,end,UUID.randomUUID(),"과거 출근 근거 집계");
            start = end.plusDays(1);
        }
        var historicalOwner = new TnaPrincipal(tenant, comparison.accountId(), comparison.employeeId(), 1, Set.of("EMPLOYEE"), clock.instant());
        var historical = app.getBean(AnnualLeaveCycleService.class).ledger(historicalOwner,REVIEW.getYear());
        boolean opening = historical.entries().stream().anyMatch(e -> "OPENING".equals(e.entryKind())
                && OPENED.equals(e.effectiveOn()) && Integer.valueOf(REVIEW.getYear()).equals(e.entitlementYear())
                && e.signedQuantityDays().compareTo(BigDecimal.ONE)==0);
        boolean used = historical.entries().stream().anyMatch(e -> "USE".equals(e.entryKind()) && COMPARISON_LEAVE.equals(e.leaveDate())
                && e.signedQuantityDays().compareTo(BigDecimal.ONE.negate())==0);
        if (!opening || !used || historical.entries().size()!=2 || historical.availableDays().signum()!=0)
            throw new IllegalStateException("E202의 2026년 귀속 기초 1일·승인 사용 1일·잔여 0일을 확인할 수 없습니다.");
        return Map.of("companyCode",COMPANY,"admin",admin,"worker",worker,"approver",approver,"comparison",comparison,
                "today",REVIEW,"leaveDate",REVIEW.plusDays(1),"lastOn",LAST,
                "exitMarker",run.resolve("exit-day").toString(),"departedMarker",run.resolve("departed").toString());
    }

    private void prepareUsedOpening(ConfigurableApplicationContext app, TnaPrincipal hr, Clock clock) {
        var flow = app.getBean(LeaveWorkflowService.class);
        String source = "2026년 5월 15일 입사 1개월분 포함 기초";
        UUID bucket = flow.openBucket(hr,new BucketForm(comparison.employeeId(),OPENED,OPENED,HIRED.plusYears(1).minusDays(1),
                1,source,"비교 사원 월별 발생 이관",UUID.randomUUID()));
        app.getBean(LeaveAttributionService.class).save(hr,bucket,
                new LeaveAttributionData.Form(0L,"CONFIRMED",OPENED.getYear(),true,source,"2026년 이관 근거 대조",UUID.randomUUID()));
        var employee = new TnaPrincipal(tenant,comparison.accountId(),comparison.employeeId(),1,Set.of("EMPLOYEE"),clock.instant());
        var reviewer = new TnaPrincipal(tenant,approver.accountId(),approver.employeeId(),1,Set.of("EMPLOYEE","APPROVER"),clock.instant());
        var form = new LeaveForm(bucket,COMPARISON_LEAVE,COMPARISON_LEAVE,null,"비교 사원 이관 연차 1일 사용",UUID.randomUUID());
        var route = flow.previewLeave(employee,form).approval();
        UUID request = flow.submitLeave(employee,form,route.routeToken());
        var pending = flow.get(reviewer,request).request();
        flow.decide(reviewer,request,new DecisionForm("APPROVE",pending.version(),"비교 사원 종일 연차 승인",
                UUID.randomUUID(),pending.currentStep()));
        var approved = flow.get(employee,request).request();
        if (!"APPROVED".equals(approved.status()) || !"EFFECTIVE".equals(approved.effectStatus()))
            throw new IllegalStateException("비교 사원의 종일 연차 승인 효력을 확인할 수 없습니다.");
    }
}
