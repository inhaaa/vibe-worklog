package kr.tna;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import kr.tna.clock.ClockService;
import kr.tna.clock.ClockData.ConfirmationForm;
import kr.tna.common.auth.TnaPrincipal;
import kr.tna.common.tenant.TenantTransactions;
import kr.tna.org.EmployeeService;
import kr.tna.worktime.WorktimeService;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.test.util.TestPropertyValues;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.json.JsonMapper;

/** Explicit local-only probe; intentionally outside the default Test/IT discovery patterns. */
public final class Phase6LoadProbe {
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final int EMPLOYEES = 300;
    private final JdbcTemplate admin;
    private final ClockService clocks;
    private final EmployeeService employees;
    private final WorktimeService reports;
    private final TenantTransactions transactions;
    private final JdbcTemplate app;
    private final YearMonth month;
    private final List<Group> groups = new ArrayList<>();
    private final Map<String,Object> result = new LinkedHashMap<>();

    private Phase6LoadProbe(org.springframework.context.ConfigurableApplicationContext context) {
        admin = new JdbcTemplate(DatabaseFixture.start().adminDataSource());
        clocks = context.getBean(ClockService.class);
        employees = context.getBean(EmployeeService.class);
        reports = context.getBean(WorktimeService.class);
        transactions = context.getBean(TenantTransactions.class);
        app = new JdbcTemplate(context.getBean("dataSource", javax.sql.DataSource.class));
        YearMonth selected = YearMonth.from(LocalDate.now(SEOUL)).minusMonths(1);
        while (selected.lengthOfMonth() != 31) selected = selected.minusMonths(1);
        month = selected;
    }

    public static void main(String[] args) throws Exception {
        // Do not accept a target URL; DatabaseFixture validates any explicitly supplied test DB.
        if (args.length != 0) throw new IllegalArgumentException("This probe takes no database arguments");
        var fixture = DatabaseFixture.start();
        var application = new SpringApplication(TnaApplication.class);
        application.addInitializers(context -> TestPropertyValues.of(Map.of(
                "spring.datasource.url", fixture.jdbcUrl(),
                "spring.datasource.username", DatabaseFixture.APP_USER,
                "spring.datasource.password", DatabaseFixture.APP_PASSWORD,
                "tna.auth.url", fixture.jdbcUrl(),
                "tna.auth.username", DatabaseFixture.AUTH_USER,
                "tna.auth.password", DatabaseFixture.AUTH_PASSWORD,
                "server.address", "127.0.0.1", "server.port", "0",
                "tna.operator.enabled", "false")).applyTo(context));
        try (var context = application.run()) {
            var probe = new Phase6LoadProbe(context);
            Path output = Path.of("target/phase6-load-result.json");
            try { probe.run(); }
            finally {
                Files.writeString(output, JsonMapper.builder().build().writerWithDefaultPrettyPrinter().writeValueAsString(probe.result));
                System.out.println("QUALITY_PROBE_RESULT=" + output.toAbsolutePath());
            }
        }
    }

    private void run() throws Exception {
        long start = System.nanoTime();
        for (int i = 0; i < 3; i++) groups.add(seed());
        result.put("measuredAt", Instant.now().toString());
        result.put("java", System.getProperty("java.version"));
        result.put("availableProcessors", Runtime.getRuntime().availableProcessors());
        result.put("jvmMaxHeapBytes", Runtime.getRuntime().maxMemory());
        result.put("database", admin.queryForObject("SELECT version()", String.class));
        result.put("appPoolSize", ((com.zaxxer.hikari.HikariDataSource)app.getDataSource()).getMaximumPoolSize());
        result.put("tenants", groups.size()); result.put("employeesPerTenant", EMPLOYEES);
        result.put("historyMonth", month.toString()); result.put("historyDays", 31);
        result.put("rawEvents", 3 * EMPLOYEES * 31 * 2);
        result.put("fullyConfirmedCalculatedEmployeesPerTenant",1);
        result.put("timing", "Execution latency after worker dequeue; includes lock/pool waiting; excludes submission queue, fixture and 3 warmups");
        result.put("percentiles", "Nearest rank over successful samples; with 9 samples p95 equals max; exploratory, not SLA");
        result.put("fixtureSetupMillis", TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start));
        result.put("scope", "Service + JDBC + PostgreSQL; excludes HTTP, rendering, login hashing and external network");
        var measurements = new ArrayList<Object>();
        result.put("measurements",measurements);
        for (int tenantCount : List.of(1,3)) {
            String suffix = tenantCount == 1 ? "oneTenant" : "threeTenants";
            measurements.add(measure("employeeList."+suffix, 48, 8, i -> {
                var group=group(i,tenantCount);
                check(employees.list(group.manager(),"",0).size()==51,"List page must include a next-row sentinel");
            }));
            measurements.add(measure("month."+suffix, 48, 8, i -> {
                var group=group(i,tenantCount);
                check(reports.month(group.manager(),group.manager().employeeId(),month).days().size()==31,"Month day count");
            }));
            measurements.add(measure("resultDetail."+suffix, 48, 8, i -> {
                var group=group(i,tenantCount);
                check(reports.detail(group.manager(),group.result()).completeEvidence(),"Stored evidence incomplete");
            }));
            measurements.add(measure("auditPage."+suffix, 48, 8, i -> {
                var page=reports.audits(group(i,tenantCount).manager(),null,null);
                check(page.rows().size()==50,"Audit page size");
            }));
            measurements.add(measure("clockRecord."+suffix, 48, 8, i -> {
                var group=group(i,tenantCount);
                clocks.record(group.members().get(i+1),"IN",UUID.randomUUID(),"127.0.0.1");
            }));
        }
        measurements.add(measure("recalculate31Days.threeTenants",9,3,i -> {
            var group=group(i,3);
            reports.recalculate(group.manager(),group.manager().employeeId(),month.atDay(1),month.atEndOfMonth(),UUID.randomUUID(),"합성 부하 재집계");
        }));
        measurements.add(measure("prepareAndDownload31Days.threeTenants",9,3,i -> {
            var group=group(i,3);
            UUID id=reports.prepareExport(group.manager(),group.manager().employeeId(),month.atDay(1),month.atEndOfMonth(),UUID.randomUUID(),"합성 부하 출력");
            check(reports.download(group.manager(),id).length>1000,"Empty workbook");
        }));
        concurrentReplay();
        result.put("plans",plans());
        check(measurements.stream().allMatch(row -> ((Number)((Map<?,?>)row).get("failures")).intValue()==0),"Load operations failed; inspect result JSON");
    }

    private Group group(int index,int count) { return groups.get(index%count); }
    private Map<String,Object> measure(String name,int samples,int concurrency,Operation operation) throws Exception {
        for(int i=0;i<3;i++) operation.run(i+100);
        var tasks=new ArrayList<Callable<Long>>();
        for(int i=0;i<samples;i++) {
            int index=i;
            tasks.add(()->{long at=System.nanoTime();operation.run(index);return System.nanoTime()-at;});
        }
        long wall=System.nanoTime();
        var times=new ArrayList<Long>();
        var failures=new ArrayList<String>();
        try(var executor=Executors.newFixedThreadPool(concurrency)) {
            for(var future:executor.invokeAll(tasks,120,TimeUnit.SECONDS)) {
                try { times.add(future.get()); }
                catch(java.util.concurrent.ExecutionException error) { failures.add(error.getCause().getClass().getSimpleName()); }
                catch(java.util.concurrent.CancellationException error) { failures.add("TimedOut"); }
            }
        }
        double seconds=(System.nanoTime()-wall)/1_000_000_000.0;
        times.sort(Long::compareTo);
        var row=new LinkedHashMap<String,Object>();
        row.put("name",name); row.put("samples",samples); row.put("concurrency",concurrency);row.put("successful",times.size());row.put("failures",failures.size());
        row.put("failureTypes",failures);
        row.put("p50Millis",times.isEmpty()?null:millis(times.get((int)Math.ceil(times.size()*.50)-1)));
        row.put("p95Millis",times.isEmpty()?null:millis(times.get((int)Math.ceil(times.size()*.95)-1)));
        row.put("maxMillis",times.isEmpty()?null:millis(times.getLast())); row.put("completedPerSecond",Math.round(times.size()/seconds*100.0)/100.0);
        System.out.println("QUALITY_MEASURE="+row);
        return row;
    }

    private void concurrentReplay() throws Exception {
        var group=groups.getFirst();var member=group.members().getLast();UUID command=UUID.randomUUID();
        try(var executor=Executors.newFixedThreadPool(8)) {
            var tasks=new ArrayList<Callable<UUID>>();
            for(int i=0;i<16;i++) tasks.add(()->clocks.record(member,"IN",command,"127.0.0.1"));
            var ids=new java.util.HashSet<UUID>();
            for(var future:executor.invokeAll(tasks,30,TimeUnit.SECONDS)) ids.add(future.get());
            check(ids.size()==1,"Concurrent replay returned distinct events");
        }
        check(admin.queryForObject("SELECT count(*) FROM tna.attendance_event WHERE tenant_id=? AND employee_id=? AND client_command_id=?",Integer.class,member.tenantId(),member.employeeId(),command)==1,"Replay duplicated raw events");
        check(admin.queryForObject("SELECT count(*) FROM tna.org_audit_event WHERE tenant_id=? AND command_id=?",Integer.class,member.tenantId(),command)==1,"Replay duplicated audit");
        result.put("concurrentReplay",Map.of("requests",16,"concurrency",8,"events",1,"audits",1,"distinctReturnedIds",1));
    }

    private List<Object> plans() {
        var group=groups.getFirst();var actor=group.manager();
        return transactions.execute(actor.tenantId(),()->List.of(
                Map.of("query","rawEventsForEmployeeDay","plan",app.queryForList("EXPLAIN (ANALYZE,BUFFERS,FORMAT JSON) SELECT id,event_kind,occurred_at FROM tna.attendance_event WHERE tenant_id=? AND employee_id=? AND assigned_work_date=? ORDER BY occurred_at,id",String.class,actor.tenantId(),actor.employeeId(),month.atDay(1))),
                Map.of("query","auditFirstPage","plan",app.queryForList("EXPLAIN (ANALYZE,BUFFERS,FORMAT JSON) SELECT id FROM tna.org_audit_event WHERE tenant_id=? ORDER BY occurred_at DESC,id DESC LIMIT 51",String.class,actor.tenantId()))));
    }

    private Group seed() {
        UUID tenant=UUID.randomUUID(),template=UUID.randomUUID(),version=UUID.randomUUID(),network=UUID.randomUUID();
        LocalDate from=month.atDay(1),to=month.atEndOfMonth();
        admin.update("INSERT INTO platform.tenant_registry(id,company_code,display_name) VALUES (?,?,'합성 품질 고객사')",tenant,"LOAD-"+tenant);
        admin.update("INSERT INTO tna.employee(tenant_id,id,employee_no,display_name,joined_on) SELECT ?,md5(?||':e:'||n)::uuid,'E'||lpad(n::text,4,'0'),'합성 사원 '||n,DATE '2000-01-01' FROM generate_series(1,300) n",tenant,tenant.toString());
        admin.update("INSERT INTO tna.tenant_account(tenant_id,id,employee_id,email,password_hash) SELECT ?,md5(?||':a:'||n)::uuid,md5(?||':e:'||n)::uuid,'load'||n||'@example.test','synthetic-load-not-a-login-hash' FROM generate_series(1,300) n",tenant,tenant.toString(),tenant.toString());
        admin.update("INSERT INTO tna.role_grant(tenant_id,account_id,role) SELECT tenant_id,id,'EMPLOYEE' FROM tna.tenant_account WHERE tenant_id=?",tenant);
        var members=admin.query("SELECT id,employee_id FROM tna.tenant_account WHERE tenant_id=? ORDER BY email",(r,i)->new TnaPrincipal(tenant,r.getObject(1,UUID.class),r.getObject(2,UUID.class),1,Set.of("EMPLOYEE"),Instant.now()),tenant);
        var first=members.getFirst();admin.update("INSERT INTO tna.role_grant(tenant_id,account_id,role) VALUES (?,?,'ATTENDANCE_ADMIN')",tenant,first.accountId());
        var manager=new TnaPrincipal(tenant,first.accountId(),first.employeeId(),1,Set.of("EMPLOYEE","ATTENDANCE_ADMIN"),Instant.now());
        admin.update("INSERT INTO tna.schedule_template(tenant_id,id,code,name,created_by,updated_by) VALUES (?,?,?,'합성 일정',?,?)",tenant,template,template.toString(),manager.accountId(),manager.accountId());
        admin.update("INSERT INTO tna.schedule_version(tenant_id,id,template_id,revision,valid_from,reason,created_by) VALUES (?,?,?,1,?,'합성 부하',?)",tenant,version,template,from.minusDays(7),manager.accountId());
        admin.update("INSERT INTO tna.schedule_day(tenant_id,version_id,weekday,workday,start_time,end_time) SELECT ?,?,n,true,TIME '09:00',TIME '18:00' FROM generate_series(1,7) n",tenant,version);
        admin.update("INSERT INTO tna.schedule_break(tenant_id,version_id,weekday,sequence,start_time,end_time) SELECT ?,?,n,1,TIME '12:00',TIME '13:00' FROM generate_series(1,7) n",tenant,version);
        admin.update("INSERT INTO tna.schedule_assignment(tenant_id,id,employee_id,schedule_version_id,valid_from,reason,created_by,updated_by) SELECT tenant_id,md5(id::text||':schedule')::uuid,id,?,?,'합성 부하',?,? FROM tna.employee WHERE tenant_id=?",version,from.minusDays(7),manager.accountId(),manager.accountId(),tenant);
        UUID calendar=UUID.randomUUID();
        admin.update("INSERT INTO tna.calendar_version(tenant_id,id,revision,valid_from,valid_to,reason,created_by) VALUES (?,?,1,?,?,'합성 부하',?)",tenant,calendar,from.minusDays(7),to.plusDays(8),manager.accountId());
        admin.update("INSERT INTO tna.calendar_day(tenant_id,version_id,work_date,kind,name,basis) SELECT ?,?,d::date,'WORKDAY','합성 근무일','합성 검토' FROM generate_series(CAST(? AS date),CAST(? AS date),INTERVAL '1 day') d",tenant,calendar,from.minusDays(7),to.plusDays(7));
        admin.update("INSERT INTO tna.allowed_network(tenant_id,id,network,label,valid_from,reason,created_by) VALUES (?,?,CAST('127.0.0.1/32' AS cidr),'합성 로컬',?,'합성 부하',?)",tenant,network,java.sql.Timestamp.from(from.atStartOfDay(SEOUL).toInstant()),manager.accountId());
        admin.update("""
                INSERT INTO tna.attendance_event(tenant_id,id,employee_id,event_kind,occurred_at,received_at,client_command_id,source,validated_ip,network_id,assigned_work_date)
                SELECT e.tenant_id,md5(e.id::text||d::text||k)::uuid,e.id,k,
                  (d::date+CASE WHEN k='IN' THEN TIME '09:00' ELSE TIME '18:00' END) AT TIME ZONE 'Asia/Seoul',
                  (d::date+CASE WHEN k='IN' THEN TIME '09:00' ELSE TIME '18:00' END) AT TIME ZONE 'Asia/Seoul',
                  md5('command'||e.id::text||d::text||k)::uuid,'IP',CAST('127.0.0.1' AS inet),?,d::date
                FROM tna.employee e CROSS JOIN generate_series(CAST(? AS date),CAST(? AS date),INTERVAL '1 day') d
                CROSS JOIN (VALUES('IN'),('OUT')) kinds(k) WHERE e.tenant_id=?
                """,network,from,to,tenant);
        admin.update("INSERT INTO tna.day_input_revision(tenant_id,employee_id,work_date,input_revision,review_revision) SELECT e.tenant_id,e.id,d::date,2,0 FROM tna.employee e CROSS JOIN generate_series(CAST(? AS date),CAST(? AS date),INTERVAL '1 day') d WHERE e.tenant_id=?",from,to,tenant);
        for(LocalDate date=from;!date.isAfter(to);date=date.plusDays(1)) {
            var day=clocks.day(manager,date);
            clocks.confirm(manager,new ConfirmationForm(date,2L,0L,true,true,day.sourceFingerprint(),UUID.randomUUID()));
        }
        reports.recalculate(manager,manager.employeeId(),from,to,UUID.randomUUID(),"합성 기준 계산");
        UUID id=reports.days(manager,manager.employeeId(),from,from).getFirst().id();
        // Populate a realistic cursor history without fabricating employee business effects.
        admin.update("INSERT INTO tna.org_audit_event(tenant_id,id,command_id,actor_account_id,action,target_kind,target_id,input_hash,reason,before_state,after_state,request_trace_id) SELECT ?,md5(?||n)::uuid,md5('command'||?||n)::uuid,?,'SYNTHETIC_LOAD','TENANT',?,repeat('0',64),'합성 부하','null'::jsonb,'{}'::jsonb,md5('trace'||?||n)::uuid FROM generate_series(1,1000) n",tenant,tenant.toString(),tenant.toString(),manager.accountId(),tenant,tenant.toString());
        admin.execute("ANALYZE tna.attendance_event");admin.execute("ANALYZE tna.org_audit_event");
        return new Group(manager,List.copyOf(members),id);
    }

    private static double millis(long nanos) { return Math.round(nanos/10_000.0)/100.0; }
    private static void check(boolean condition,String message) { if(!condition)throw new AssertionError(message); }
    @FunctionalInterface private interface Operation { void run(int index) throws Exception; }
    private record Group(TnaPrincipal manager,List<TnaPrincipal> members,UUID result) { }
}
