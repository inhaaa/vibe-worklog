package kr.tna.overtime;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import kr.tna.clock.ClockData.Event;
import kr.tna.clock.ClockService;
import kr.tna.common.auth.TnaPrincipal;
import kr.tna.common.tenant.BusinessCommands;
import kr.tna.org.OrgCommands;
import kr.tna.org.OrgException;
import kr.tna.request.LeaveWorkflowMapper;
import kr.tna.request.LeaveWorkflowService;
import kr.tna.request.RequestData.PreviewStep;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;
import org.springframework.stereotype.Service;

@Service
public class OvertimeDirectory {
    public record Row(UUID id, UUID employeeId, String employeeNo, String employeeName, String kind, String status,
                      String effectStatus, LocalDate workDate, LocalTime startTime, LocalTime endTime,
                      int requestedMinutes, int approvedMinutes, int currentStep, int totalSteps) {}
    public record Item(@com.fasterxml.jackson.annotation.JsonUnwrapped Row request, LocalTime actualIn,
                       LocalTime actualOut, boolean multipleIn, boolean multipleOut) {}
    public record Page(LocalDate today, LocalDate date, LocalDate from, LocalDate to, String q, String status,
                       int page, boolean hasNext, OvertimePolicyService.Day day, List<Item> rows) {}
    public record Preview(int requestedMinutes, String basisToken, String routeToken, List<PreviewStep> steps) {}
    private record EmployeeDate(UUID employee, LocalDate date) {}

    @Mapper
    public interface DirectoryMapper {
        @Select("""
            SELECT r.id,r.target_employee_id AS employee_id,e.employee_no,e.display_name AS employee_name,
                   r.kind,r.status,coalesce(f.effect_status,'NONE') AS effect_status,
                   d.work_date,d.start_time,d.end_time,d.requested_minutes,
                   CASE WHEN r.kind='OVERTIME' AND r.status='APPROVED' AND f.effect_status='EFFECTIVE'
                        THEN d.requested_minutes ELSE 0 END AS approved_minutes,r.current_step,r.total_steps
            FROM tna.approval_request r
            JOIN tna.employee e ON e.tenant_id=r.tenant_id AND e.id=r.target_employee_id
            JOIN tna.overtime_request_detail d ON d.tenant_id=r.tenant_id AND d.request_id=
                CASE WHEN r.kind='CANCEL_OVERTIME' THEN r.original_request_id ELSE r.id END
            LEFT JOIN tna.request_effect f ON f.tenant_id=r.tenant_id AND f.request_id=r.id
            WHERE r.tenant_id=#{tenant} AND r.kind IN ('OVERTIME','CANCEL_OVERTIME')
              AND (CAST(#{employee} AS uuid) IS NULL OR r.target_employee_id=#{employee})
              AND (CAST(#{from} AS date) IS NULL OR d.work_date>=#{from})
              AND (CAST(#{to} AS date) IS NULL OR d.work_date<=#{to}) AND (#{status}='' OR r.status=#{status})
              AND (strpos(lower(e.employee_no),lower(#{q}))>0 OR strpos(lower(e.display_name),lower(#{q}))>0)
            ORDER BY d.work_date DESC,r.submitted_at DESC,r.id LIMIT 51 OFFSET #{offset}
            """)
        List<Row> rows(UUID tenant, UUID employee, LocalDate from, LocalDate to, String q, String status, int offset);
    }

    private final DirectoryMapper mapper;
    private final OvertimePolicyService policy;
    private final LeaveWorkflowService workflow;
    private final LeaveWorkflowMapper requests;
    private final ClockService clocks;
    private final OrgCommands admin;
    private final BusinessCommands employee;

    public OvertimeDirectory(DirectoryMapper mapper, OvertimePolicyService policy, LeaveWorkflowService workflow,
                             LeaveWorkflowMapper requests, ClockService clocks, OrgCommands admin, BusinessCommands employee) {
        this.mapper=mapper; this.policy=policy; this.workflow=workflow; this.requests=requests;
        this.clocks=clocks; this.admin=admin; this.employee=employee;
    }
    public Page mine(TnaPrincipal actor, LocalDate date, int page) {
        return employee.read(actor, () -> {
            requests.readLock(actor.tenantId());
            var selected=date==null?admin.today():date;
            var day=policy.day(actor.tenantId(),actor.employeeId(),selected);
            return page(actor,actor.employeeId(),selected,null,null,"","",page,day);
        });
    }
    public Preview preview(TnaPrincipal actor, LocalDate date, LocalTime start, LocalTime end) {
        return employee.read(actor, () -> {
            requests.readLock(actor.tenantId());
            var basis=policy.evaluate(actor.tenantId(),actor.employeeId(),date,start,end,true);
            workflow.requireWeeklyOvertimeLimit(actor.tenantId(),actor.employeeId(),date,basis.requestedMinutes());
            var route=workflow.formPreview(actor,null,"OVERTIME");
            if (!route.eligible()) throw OrgException.invalid(route.message());
            return new Preview(basis.requestedMinutes(),basis.basisToken(),route.routeToken(),route.steps());
        });
    }
    public Page company(TnaPrincipal actor, LocalDate from, LocalDate to, String q, String status, int page) {
        if (!Set.of("","PENDING","APPROVED","REJECTED","WITHDRAWN").contains(status) ||
                from!=null && to!=null && to.isBefore(from)) throw OrgException.invalid("조회 기간과 처리 상태를 확인해 주세요.");
        return admin.readShared(actor,()->page(actor,null,null,from,to,q.strip(),status,page,null));
    }
    private Page page(TnaPrincipal actor, UUID owner, LocalDate date, LocalDate from, LocalDate to, String q,
                      String status, int page, OvertimePolicyService.Day day) {
        var rows=mapper.rows(actor.tenantId(),owner,from,to,q,status,OrgCommands.offset(page));
        var events=new HashMap<EmployeeDate,List<Event>>();
        var items=rows.stream().limit(50).map(row->{
            if (owner!=null) return new Item(row,null,null,false,false);
            var effective=events.computeIfAbsent(new EmployeeDate(row.employeeId(),row.workDate()), key->
                    clocks.snapshot(actor.tenantId(),key.employee(),key.date()).effectiveEvents());
            var ins=effective.stream().filter(e->"IN".equals(e.kind())).toList();
            var outs=effective.stream().filter(e->"OUT".equals(e.kind())).toList();
            return new Item(row,time(ins),time(outs),ins.size()>1,outs.size()>1);
        }).toList();
        return new Page(admin.today(),date,from,to,q,status,page,rows.size()>50,day,items);
    }
    private static LocalTime time(List<Event> events) {
        return events.size()==1?events.getFirst().occurredAt().atZone(ZoneId.of("Asia/Seoul")).toLocalTime():null;
    }
}
