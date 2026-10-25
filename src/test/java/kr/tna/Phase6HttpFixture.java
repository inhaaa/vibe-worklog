package kr.tna;

import com.zaxxer.hikari.HikariDataSource;
import java.lang.management.ManagementFactory;
import java.net.Inet4Address;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import kr.tna.clock.ClockData.ConfirmationForm;
import kr.tna.clock.ClockService;
import kr.tna.common.auth.PasswordPolicy;
import kr.tna.common.auth.CompanyAuthenticationDetails;
import kr.tna.common.auth.CompanyAuthenticationProvider;
import kr.tna.common.auth.TnaPrincipal;
import kr.tna.org.AccountData.ApproverForm;
import kr.tna.org.ApproverService;
import kr.tna.request.LeaveWorkflowService;
import kr.tna.request.RequestData.BucketForm;
import kr.tna.request.RequestData.LeaveForm;
import kr.tna.worktime.WorktimeData.AssessmentForm;
import kr.tna.worktime.WorktimeData.PolicyForm;
import kr.tna.worktime.WorktimeService;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.test.util.TestPropertyValues;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import tools.jackson.databind.json.JsonMapper;

/** Disposable authenticated HTTP fixture. Credentials and samples stay in ignored target/. */
public final class Phase6HttpFixture {
    private static final ZoneId SEOUL=ZoneId.of("Asia/Seoul");
    private static final JsonMapper JSON=JsonMapper.builder().build();
    private final ConfigurableApplicationContext context;
    private final JdbcTemplate db;
    private final LocalDate today=LocalDate.now(SEOUL);
    private final YearMonth month;
    private final String password="Synthetic HTTP "+UUID.randomUUID()+"!";
    private final String hash=PasswordPolicy.passwordEncoder().encode(password);
    private final List<Map<String,Object>> users=new ArrayList<>();
    private final List<UUID> tenantIds=new ArrayList<>();

    private Phase6HttpFixture(ConfigurableApplicationContext context) {
        this.context=context;db=new JdbcTemplate(DatabaseFixture.start().adminDataSource());
        YearMonth selected=YearMonth.from(today).minusMonths(1);
        while(selected.lengthOfMonth()!=31)selected=selected.minusMonths(1);
        month=selected;
    }
    public static void main(String[] args)throws Exception {
        if(args.length!=0||supplied(System.getenv("TNA_TEST_JDBC_URL"))||supplied(System.getProperty("tna.test.jdbc-url")))
            throw new IllegalArgumentException("HTTP fixture requires a new owned container; supplied DB targets are refused");
        String gateway=gatewayNetwork(System.getProperty("tna.http.fixture.gateway-address"));
        var fixture=DatabaseFixture.start();
        var container=fixture.ownedContainer();
        var application=new SpringApplication(TnaApplication.class);
        application.setAdditionalProfiles("local");
        application.addInitializers(context->TestPropertyValues.of(Map.of(
                "spring.datasource.url",fixture.jdbcUrl(),"spring.datasource.username",DatabaseFixture.APP_USER,
                "spring.datasource.password",DatabaseFixture.APP_PASSWORD,"tna.auth.url",fixture.jdbcUrl(),
                "tna.auth.username",DatabaseFixture.AUTH_USER,"tna.auth.password",DatabaseFixture.AUTH_PASSWORD,
                "server.address","127.0.0.1","server.port","0","tna.operator.enabled","false")).applyTo(context));
        try(var context=application.run()) {
            check(List.of(context.getEnvironment().getActiveProfiles()).contains("local"),"Local fixture profile was not activated");
            check(!context.getEnvironment().getRequiredProperty("server.servlet.session.cookie.secure",Boolean.class),"Local HTTP fixture requires the existing local cookie profile");
            var probe=new Phase6HttpFixture(context);
            for(int i=1;i<=3;i++)probe.seed(i,gateway);
            Path stop=Path.of("target/phase6-http-"+UUID.randomUUID()+".stop").toAbsolutePath();
            var metadata=new LinkedHashMap<String,Object>();
            metadata.put("schemaVersion",1);metadata.put("baseUrl","http://127.0.0.1:"+context.getEnvironment().getRequiredProperty("local.server.port"));
            metadata.put("fixtureScope","owned-synthetic");metadata.put("containerId",container.getContainerId());metadata.put("jdbcPort",container.getMappedPort(5432));
            if(gateway!=null)metadata.put("gatewayNetwork",gateway);
            metadata.put("password",probe.password);metadata.put("users",probe.users);metadata.put("historyMonth",probe.month.toString());
            metadata.put("historyDate",probe.month.atDay(1).toString());metadata.put("today",probe.today.toString());
            metadata.put("tenants",3);metadata.put("employeesPerTenant",300);metadata.put("rawHistoryEvents",55_800);
            metadata.put("stopFile",stop.toString());metadata.put("runtimeFile",Path.of("target/phase6-http-runtime.jsonl").toAbsolutePath().toString());
            Files.writeString(Path.of("target/phase6-http-fixture.json"),JSON.writerWithDefaultPrettyPrinter().writeValueAsString(metadata));
            Files.writeString(Path.of("target/phase6-http-runtime.jsonl"),"");
            System.out.println("SYNTHETIC_HTTP_FIXTURE_READY metadata=target/phase6-http-fixture.json users="+probe.users.size());
            long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.HOURS.toNanos(2);
            while(!Files.exists(stop)&&System.nanoTime()<deadline) {
                probe.sample();Thread.sleep(15_000);
            }
            var counts=new LinkedHashMap<String,Object>();
            counts.put("replay",probe.db.queryForList("SELECT tenant_id,employee_id,client_command_id,count(*) AS events FROM tna.attendance_event WHERE assigned_work_date=? GROUP BY tenant_id,employee_id,client_command_id",probe.today));
            counts.put("pending",probe.db.queryForObject("SELECT count(*) FROM tna.approval_request WHERE status='PENDING'",Integer.class));
            counts.put("approved",probe.db.queryForObject("SELECT count(*) FROM tna.approval_request WHERE status='APPROVED'",Integer.class));
            counts.put("rawEvents",probe.db.queryForObject("SELECT count(*) FROM tna.attendance_event",Integer.class));
            counts.put("auditEvents",probe.db.queryForObject("SELECT count(*) FROM tna.org_audit_event",Integer.class));
            for(var row:probe.db.queryForList("SELECT count(*) AS copies FROM tna.attendance_event WHERE assigned_work_date=? GROUP BY tenant_id,employee_id,client_command_id",probe.today))
                check(((Number)row.get("copies")).intValue()==1,"Clock replay created duplicates");
            counts.put("duplicateReplayGroups",0);
            Files.writeString(Path.of("target/phase6-http-fixture-final.json"),JSON.writerWithDefaultPrettyPrinter().writeValueAsString(counts));
            System.out.println("SYNTHETIC_HTTP_FIXTURE_STOPPED replay_duplicates=0");
        }
    }
    private void seed(int number,String gatewayNetwork) {
        UUID tenant=UUID.randomUUID(),template=UUID.randomUUID(),version=UUID.randomUUID(),network=UUID.randomUUID(),calendar=UUID.randomUUID();
        tenantIds.add(tenant);String marker="SOAK_T"+number+"_",company=("SOAK-"+tenant).toUpperCase(Locale.ROOT);
        LocalDate from=month.atDay(1),to=month.atEndOfMonth(),validEnd=today.plusDays(400);
        db.update("INSERT INTO platform.tenant_registry(id,company_code,display_name) VALUES (?,?,?)",tenant,company,marker+"COMPANY");
        db.update("INSERT INTO tna.employee(tenant_id,id,employee_no,display_name,joined_on) SELECT ?,md5(?||':e:'||n)::uuid,'E'||lpad(n::text,4,'0'),?||lpad(n::text,4,'0'),DATE '2000-01-01' FROM generate_series(1,300) n",tenant,tenant.toString(),marker);
        db.update("INSERT INTO tna.tenant_account(tenant_id,id,employee_id,email,password_hash) SELECT ?,md5(?||':a:'||n)::uuid,md5(?||':e:'||n)::uuid,'http'||lpad(n::text,4,'0')||'@example.test',? FROM generate_series(1,300) n",tenant,tenant.toString(),tenant.toString(),hash);
        db.update("INSERT INTO tna.role_grant(tenant_id,account_id,role) SELECT tenant_id,id,'EMPLOYEE' FROM tna.tenant_account WHERE tenant_id=?",tenant);
        var actors=db.query("SELECT id,employee_id,email FROM tna.tenant_account WHERE tenant_id=? ORDER BY email LIMIT 6",(r,i)->new Person(r.getObject(1,UUID.class),r.getObject(2,UUID.class),r.getString(3)),tenant);
        db.update("INSERT INTO tna.role_grant(tenant_id,account_id,role) VALUES (?,?,'ATTENDANCE_ADMIN'),(?,?,'APPROVER')",tenant,actors.get(0).account(),tenant,actors.get(1).account());
        TnaPrincipal manager=actor(tenant,actors.get(0),Set.of("EMPLOYEE","ATTENDANCE_ADMIN"));
        var clocks=context.getBean(ClockService.class);var reports=context.getBean(WorktimeService.class);
        var approvers=context.getBean(ApproverService.class);var workflow=context.getBean(LeaveWorkflowService.class);
        db.update("INSERT INTO tna.schedule_template(tenant_id,id,code,name,created_by,updated_by) VALUES (?,?,?,'합성 HTTP 일정',?,?)",tenant,template,template.toString(),manager.accountId(),manager.accountId());
        db.update("INSERT INTO tna.schedule_version(tenant_id,id,template_id,revision,valid_from,reason,created_by) VALUES (?,?,?,1,?,'합성 HTTP',?)",tenant,version,template,from.minusDays(7),manager.accountId());
        db.update("INSERT INTO tna.schedule_day(tenant_id,version_id,weekday,workday,start_time,end_time) SELECT ?,?,n,true,TIME '09:00',TIME '18:00' FROM generate_series(1,7) n",tenant,version);
        db.update("INSERT INTO tna.schedule_break(tenant_id,version_id,weekday,sequence,start_time,end_time) SELECT ?,?,n,1,TIME '12:00',TIME '13:00' FROM generate_series(1,7) n",tenant,version);
        db.update("INSERT INTO tna.schedule_assignment(tenant_id,id,employee_id,schedule_version_id,valid_from,reason,created_by,updated_by) SELECT tenant_id,md5(id::text||':schedule')::uuid,id,?,?,'합성 HTTP',?,? FROM tna.employee WHERE tenant_id=?",version,from.minusDays(7),manager.accountId(),manager.accountId(),tenant);
        db.update("INSERT INTO tna.calendar_version(tenant_id,id,revision,valid_from,valid_to,reason,created_by) VALUES (?,?,1,?,?,'합성 HTTP',?)",tenant,calendar,from.minusDays(7),validEnd.plusDays(1),manager.accountId());
        db.update("INSERT INTO tna.calendar_day(tenant_id,version_id,work_date,kind,name,basis) SELECT ?,?,d::date,'WORKDAY','합성 근무일','합성 검토' FROM generate_series(CAST(? AS date),CAST(? AS date),INTERVAL '1 day') d",tenant,calendar,from.minusDays(7),validEnd);
        db.update("INSERT INTO tna.allowed_network(tenant_id,id,network,label,valid_from,reason,created_by) VALUES (?,?,CAST('127.0.0.1/32' AS cidr),'합성 로컬',?,'합성 HTTP',?)",tenant,network,java.sql.Timestamp.from(from.atStartOfDay(SEOUL).toInstant()),manager.accountId());
        if(gatewayNetwork!=null)db.update("INSERT INTO tna.allowed_network(tenant_id,id,network,label,valid_from,reason,created_by) VALUES (?,?,CAST(? AS cidr),'합성 Docker gateway',?,'합성 HTTP',?)",
                tenant,UUID.randomUUID(),gatewayNetwork,java.sql.Timestamp.from(from.atStartOfDay(SEOUL).toInstant()),manager.accountId());
        db.update("""
                INSERT INTO tna.attendance_event(tenant_id,id,employee_id,event_kind,occurred_at,received_at,client_command_id,source,validated_ip,network_id,assigned_work_date)
                SELECT e.tenant_id,md5(e.id::text||d::text||k)::uuid,e.id,k,
                  (d::date+CASE WHEN k='IN' THEN TIME '09:00' ELSE TIME '18:00' END) AT TIME ZONE 'Asia/Seoul',
                  (d::date+CASE WHEN k='IN' THEN TIME '09:00' ELSE TIME '18:00' END) AT TIME ZONE 'Asia/Seoul',
                  md5('command'||e.id::text||d::text||k)::uuid,'IP',CAST('127.0.0.1' AS inet),?,d::date
                FROM tna.employee e CROSS JOIN generate_series(CAST(? AS date),CAST(? AS date),INTERVAL '1 day') d
                CROSS JOIN (VALUES('IN'),('OUT')) kinds(k) WHERE e.tenant_id=?
                """,network,from,to,tenant);
        db.update("INSERT INTO tna.day_input_revision(tenant_id,employee_id,work_date,input_revision,review_revision) SELECT e.tenant_id,e.id,d::date,2,0 FROM tna.employee e CROSS JOIN generate_series(CAST(? AS date),CAST(? AS date),INTERVAL '1 day') d WHERE e.tenant_id=?",from,to,tenant);
        reports.reviewPolicy(manager,new PolicyForm(from.minusDays(7),validEnd,true,"합성 HTTP 주 검토","합성 초기 정책",UUID.randomUUID()));
        for(int index=0;index<actors.size();index++) {
            Person person=actors.get(index);Set<String> roles=index==0?Set.of("EMPLOYEE","ATTENDANCE_ADMIN"):index==1?Set.of("EMPLOYEE","APPROVER"):Set.of("EMPLOYEE");
            var principal=actor(tenant,person,roles);
            reports.assess(manager,new AssessmentForm(person.employee(),from.minusDays(7),validEnd,true,"합성 HTTP 일반 적용 검토","합성 초기 검토",UUID.randomUUID()));
            for(LocalDate date=from;!date.isAfter(to);date=date.plusDays(1)) {
                var day=clocks.day(principal,date);
                clocks.confirm(principal,new ConfirmationForm(date,2L,0L,true,true,day.sourceFingerprint(),UUID.randomUUID()));
            }
            reports.recalculate(manager,person.employee(),from,to,UUID.randomUUID(),"합성 HTTP 초기 집계");
            UUID result=reports.days(principal,person.employee(),from,from).getFirst().id();
            UUID bucket=null;
            UUID pending=null;
            if(index>=2) {
                approvers.set(manager,new ApproverForm(person.employee(),actors.get(1).employee(),today,0L,"합성 HTTP 승인자",UUID.randomUUID()));
                bucket=workflow.openBucket(manager,new BucketForm(person.employee(),today,today,validEnd,100,"합성 HTTP 기초","합성 HTTP 초기 원장",UUID.randomUUID()));
                if(index==2)pending=workflow.submitLeave(principal,new LeaveForm(bucket,today.plusDays(390),today.plusDays(390),null,"합성 HTTP 초기 대기",UUID.randomUUID()));
            }
            var data=new LinkedHashMap<String,Object>();data.put("group",number);data.put("marker",marker);data.put("companyCode",company);data.put("tenantId",tenant);
            data.put("employeeId",person.employee());data.put("email",person.email());data.put("kind",index==0?"admin":index==1?"approver":"employee");
            data.put("resultId",result);data.put("bucketId",bucket);data.put("clockCommand",UUID.randomUUID());data.put("firstLeaveOffset",index-1);
            data.put("initialPendingLeaveId",pending);
            users.add(data);
        }
        db.update("INSERT INTO tna.org_audit_event(tenant_id,id,command_id,actor_account_id,action,target_kind,target_id,input_hash,reason,before_state,after_state,request_trace_id) SELECT ?,md5(?||n)::uuid,md5('command'||?||n)::uuid,?,'SYNTHETIC_HTTP','TENANT',?,repeat('0',64),'합성 HTTP','null'::jsonb,'{}'::jsonb,md5('trace'||?||n)::uuid FROM generate_series(1,1000) n",tenant,tenant.toString(),tenant.toString(),manager.accountId(),tenant,tenant.toString());
        db.execute("ANALYZE tna.attendance_event");db.execute("ANALYZE tna.org_audit_event");
        var request=new MockHttpServletRequest();request.setRemoteAddr("127.0.0.1");request.addParameter("companyCode",company);
        var login=UsernamePasswordAuthenticationToken.unauthenticated(actors.getFirst().email(),password);
        login.setDetails(new CompanyAuthenticationDetails(request));
        var authenticated=context.getBean(CompanyAuthenticationProvider.class).authenticate(login);
        check(authenticated.getPrincipal() instanceof TnaPrincipal principal&&tenant.equals(principal.tenantId())
                &&actors.getFirst().account().equals(principal.accountId()),"Fixture login identity did not resolve to its tenant");
    }
    private void sample()throws Exception {
        var memory=ManagementFactory.getMemoryMXBean();var threads=ManagementFactory.getThreadMXBean();
        var pool=context.getBean("dataSource",HikariDataSource.class).getHikariPoolMXBean();
        var row=new LinkedHashMap<String,Object>();row.put("at",Instant.now().toString());row.put("heapUsedBytes",memory.getHeapMemoryUsage().getUsed());
        row.put("heapCommittedBytes",memory.getHeapMemoryUsage().getCommitted());row.put("heapMaxBytes",memory.getHeapMemoryUsage().getMax());
        row.put("threadCount",threads.getThreadCount());row.put("peakThreadCount",threads.getPeakThreadCount());
        row.put("appPoolActive",pool.getActiveConnections());row.put("appPoolIdle",pool.getIdleConnections());row.put("appPoolPending",pool.getThreadsAwaitingConnection());
        row.put("appPoolTotal",pool.getTotalConnections());row.put("gcCollections",ManagementFactory.getGarbageCollectorMXBeans().stream().mapToLong(b->Math.max(0,b.getCollectionCount())).sum());
        row.put("processCpuNanos",((com.sun.management.OperatingSystemMXBean)ManagementFactory.getOperatingSystemMXBean()).getProcessCpuTime());
        row.put("openFileDescriptors",null);row.put("fileDescriptorScope","Windows JVM: unavailable; not asserted");
        row.put("dbBytes",db.queryForObject("SELECT pg_database_size(current_database())",Long.class));
        row.put("dbConnections",db.queryForObject("SELECT count(*) FROM pg_stat_activity WHERE datname=current_database()",Integer.class));
        row.put("waitingAdvisoryLocks",db.queryForObject("SELECT count(*) FROM pg_locks WHERE locktype='advisory' AND NOT granted",Integer.class));
        Files.writeString(Path.of("target/phase6-http-runtime.jsonl"),JSON.writeValueAsString(row)+"\n",java.nio.file.StandardOpenOption.APPEND);
    }
    static String gatewayNetwork(String raw) {
        if(raw==null)return null;
        var address=Inet4Address.ofLiteral(raw);
        if(!address.isSiteLocalAddress()||!raw.equals(address.getHostAddress()))
            throw new IllegalArgumentException("Only one canonical RFC1918 IPv4 gateway address is allowed");
        return raw+"/32";
    }
    private static TnaPrincipal actor(UUID tenant,Person person,Set<String> roles) {return new TnaPrincipal(tenant,person.account(),person.employee(),1,roles,Instant.now());}
    private static boolean supplied(String value){return value!=null&&!value.isBlank();}
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
    private record Person(UUID account,UUID employee,String email) { }
}
