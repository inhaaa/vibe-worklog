package kr.tna;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import kr.tna.clock.AttendanceSettingsData.CalendarForm;
import kr.tna.clock.AttendanceSettingsService;
import kr.tna.common.auth.PasswordPolicy;
import kr.tna.common.auth.TnaPrincipal;
import kr.tna.org.AccountData.ApproverForm;
import kr.tna.org.ApproverService;
import kr.tna.schedule.ScheduleData.AssignmentForm;
import kr.tna.schedule.ScheduleData.DayForm;
import kr.tna.schedule.ScheduleData.TemplateForm;
import kr.tna.schedule.ScheduleService;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.test.util.TestPropertyValues;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/** A January balance cycle in a disposable company. All current-year ledger mutations come from Chrome. */
public final class AnnualLeaveCycleBrowser {
    private static final String COMPANY = "ANNUAL-CYCLE";
    private static final LocalDate TODAY = LocalDate.of(2026, 1, 5);
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final Clock CLOCK = Clock.fixed(TODAY.atTime(10, 0).atZone(SEOUL).toInstant(), SEOUL);

    private record Member(String employeeNo, String name, String email, UUID employeeId, UUID accountId) {
        Member(String no, String name) {
            this(no, name, no.toLowerCase(Locale.ROOT) + "@example.test", UUID.randomUUID(), UUID.randomUUID());
        }
    }

    private final UUID tenant = UUID.randomUUID();
    private final Member worker = new Member("L101", "합성이월");
    private final Member comparison = new Member("L202", "합성신규");
    private final Member admin = new Member("L900", "합성담당");
    private final Member approver = new Member("L901", "합성결재");

    public static void main(String[] args) throws Exception {
        if (args.length < 2 || args.length > 3 || !Set.of("pc", "mobile", "--serve").contains(args[0])) {
            throw new IllegalArgumentException("Usage: AnnualLeaveCycleBrowser <pc|mobile|--serve> <fresh-run-directory> [evidence-directory]");
        }
        if (System.getenv("TNA_TEST_JDBC_URL") != null || System.getProperty("tna.test.jdbc-url") != null) {
            throw new IllegalArgumentException("연차 검증 전용 PostgreSQL 컨테이너가 필요합니다.");
        }
        Path run = Path.of(args[1]).toAbsolutePath().normalize();
        Path manifest = run.resolve("fixture.json");
        if (Files.exists(manifest) || Files.exists(run.resolve("stop"))) {
            throw new IllegalArgumentException("새 검증 경로를 지정해 주세요.");
        }
        Files.createDirectories(run);
        var fixture = new AnnualLeaveCycleBrowser();
        var database = DatabaseFixture.start();
        String password = "Annual-" + UUID.randomUUID();
        var db = new JdbcTemplate(database.adminDataSource());
        new TransactionTemplate(new DataSourceTransactionManager(database.adminDataSource())).executeWithoutResult(
                tx -> fixture.seed(db, PasswordPolicy.passwordEncoder().encode(password)));
        var application = new SpringApplication(TnaApplication.class);
        application.addInitializers(context -> {
            ((GenericApplicationContext) context).registerBean("annualBrowserClock", Clock.class,
                    () -> CLOCK, definition -> definition.setPrimary(true));
            TestPropertyValues.of("spring.datasource.url=" + database.jdbcUrl(),
                    "spring.datasource.username=" + DatabaseFixture.APP_USER, "spring.datasource.password=" + DatabaseFixture.APP_PASSWORD,
                    "tna.auth.url=" + database.jdbcUrl(), "tna.auth.username=" + DatabaseFixture.AUTH_USER,
                    "tna.auth.password=" + DatabaseFixture.AUTH_PASSWORD).applyTo(context);
        });
        try (var app = application.run("--spring.config.location=classpath:/", "--spring.profiles.active=local",
                "--server.address=127.0.0.1", "--server.port=0", "--tna.operator.enabled=false")) {
            fixture.workAndApproval(app, db);
            var configuration = Map.of("baseUrl", "http://127.0.0.1:" + app.getEnvironment().getRequiredProperty("local.server.port"),
                    "companyCode", COMPANY, "password", password, "worker", fixture.worker, "comparison", fixture.comparison,
                    "admin", fixture.admin, "approver", fixture.approver, "today", TODAY, "leaveDate", TODAY.plusDays(1));
            Files.writeString(manifest, new JsonMapper().writerWithDefaultPrettyPrinter().writeValueAsString(configuration),
                    StandardOpenOption.CREATE_NEW);
            System.out.println("ANNUAL_LEAVE_BROWSER_READY " + manifest);
            if ("--serve".equals(args[0])) {
                long deadline = System.nanoTime() + TimeUnit.HOURS.toNanos(2);
                while (!Files.exists(run.resolve("stop")) && System.nanoTime() < deadline) Thread.sleep(1000);
            } else {
                Path evidence = args.length == 3 ? Path.of(args[2]).toAbsolutePath() : run.resolve("browser");
                var browser = new ProcessBuilder("node", "scripts/verify-annual-leave-cycle.cjs", manifest.toString(),
                        args[0], evidence.toString()).inheritIO().start();
                if (browser.waitFor() != 0) throw new IllegalStateException("연차 원장 브라우저 검증 실패");
            }
        }
    }

    private void seed(JdbcTemplate db, String passwordHash) {
        db.update("INSERT INTO platform.tenant_registry(id,company_code,display_name) VALUES (?,?,'가상 연차 회사')",
                tenant, COMPANY);
        LocalDate oldHire = LocalDate.of(2024, 1, 1), newHire = LocalDate.of(2026, 1, 2);
        for (var person : List.of(worker, comparison, admin, approver)) {
            db.update("INSERT INTO tna.employee(tenant_id,id,employee_no,display_name,joined_on) VALUES (?,?,?,?,?)",
                    tenant, person.employeeId(), person.employeeNo(), person.name(), person.equals(worker) || person.equals(comparison) ? oldHire : newHire);
            db.update("INSERT INTO tna.tenant_account(tenant_id,id,employee_id,email,password_hash) VALUES (?,?,?,?,?)",
                    tenant, person.accountId(), person.employeeId(), person.email(), passwordHash);
            db.update("INSERT INTO tna.role_grant(tenant_id,account_id,role) VALUES (?,?,'EMPLOYEE')", tenant, person.accountId());
        }
        db.update("INSERT INTO tna.role_grant(tenant_id,account_id,role) VALUES (?,?,'ATTENDANCE_ADMIN'),(?,?,'APPROVER')",
                tenant, admin.accountId(), tenant, approver.accountId());
        UUID bucket = UUID.randomUUID();
        db.update("""
                INSERT INTO tna.leave_bucket(tenant_id,id,employee_id,base_date,usable_from,usable_to,source_reference,created_by)
                VALUES (?,?,?,DATE '2025-01-01',DATE '2025-01-01',DATE '2025-12-31','가상 2025년 이관 원장',?)
                """, tenant, bucket, worker.employeeId(), admin.accountId());
        db.update("""
                INSERT INTO tna.leave_ledger(tenant_id,id,bucket_id,employee_id,entry_kind,signed_quantity_days,effective_on,
                    source_reference,reason,created_by) VALUES (?,gen_random_uuid(),?,?,'OPENING',4.5,DATE '2025-01-01',
                    '가상 2025년 이관 원장','전년도 확정 잔여',?)
                """, tenant, bucket, worker.employeeId(), admin.accountId());
        db.update("""
                INSERT INTO tna.leave_bucket_attribution(tenant_id,id,bucket_id,revision,attendance_type_id,classification_status,
                    entitlement_year,basis,reason,created_by)
                SELECT tenant_id,gen_random_uuid(),?,1,id,'CONFIRMED',2025,'가상 전년 귀속 확인','전년도 확정 원장',?
                FROM tna.attendance_type WHERE tenant_id=? AND system_kind='ANNUAL_LEAVE'
                """, bucket, admin.accountId(), tenant);
    }

    private void workAndApproval(ConfigurableApplicationContext app, JdbcTemplate db) {
        var manager = new TnaPrincipal(tenant, admin.accountId(), admin.employeeId(), 1,
                Set.of("EMPLOYEE", "ATTENDANCE_ADMIN"), CLOCK.instant());
        app.getBean(ApproverService.class).set(manager, new ApproverForm(worker.employeeId(), approver.employeeId(),
                TODAY, 0L, "가상 독립 승인자", UUID.randomUUID()));
        var days = IntStream.rangeClosed(1, 7).mapToObj(day -> day <= 5
                ? new DayForm(day, true, "09:00", "18:00", "12:00-13:00")
                : new DayForm(day, false, null, null, null)).toList();
        var schedules = app.getBean(ScheduleService.class);
        UUID template = schedules.create(manager, new TemplateForm("ANNUAL-QA", "가상 평일 고정 근무", TODAY,
                days, null, "반차 사용일 근무 기준", UUID.randomUUID()));
        UUID version = schedules.versions(manager, template).getFirst().id();
        long employeeVersion = db.queryForObject("SELECT version FROM tna.employee WHERE tenant_id=? AND id=?",
                Long.class, tenant, worker.employeeId());
        schedules.assign(manager, worker.employeeId(), new AssignmentForm(version, TODAY, employeeVersion,
                "반차 사용일 근무 배정", UUID.randomUUID()));
        app.getBean(AttendanceSettingsService.class).calendar(manager, new CalendarForm(TODAY, TODAY.plusDays(7), true,
                "가상 연차 검증 주간", "", "가상 평일 근무 달력", UUID.randomUUID()));
    }
}
