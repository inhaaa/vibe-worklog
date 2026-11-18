package kr.tna.schedule;

import static kr.tna.org.OrgCommands.*;
import static kr.tna.schedule.WorkGroupService.*;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.*;
import kr.tna.common.auth.TnaPrincipal;
import kr.tna.org.BulkData;
import kr.tna.org.EmployeeAdminMapper;
import kr.tna.org.OrgCommands;
import kr.tna.org.OrgException;
import kr.tna.org.PublicDemoIdentityGuard;
import kr.tna.schedule.WorkGroupData.*;
import org.springframework.stereotype.Service;

@Service
public final class WorkGroupAssignmentService {
    private final WorkGroupMapper groups;
    private final WorkGroupService schedules;
    private final EmployeeAdminMapper employees;
    private final OrgCommands commands;

    private final PublicDemoIdentityGuard demo;

    public WorkGroupAssignmentService(WorkGroupMapper groups, WorkGroupService schedules, EmployeeAdminMapper employees,
            OrgCommands commands, PublicDemoIdentityGuard demo) {
        this.demo=demo;
        this.groups=groups;
        this.schedules=schedules;
        this.employees=employees;
        this.commands=commands;
    }

    public List<Employee> employees(TnaPrincipal actor, LocalDate date, String q, int page) {
        return commands.readShared(actor,()->groups.employees(actor.tenantId(),date==null?commands.today():date,
                q==null?"":q.strip(),offset(page)));
    }
    public AssignmentPreview previewAssignments(TnaPrincipal actor, AssignmentCommand form) {
        return commands.readShared(actor,()->prepare(actor.tenantId(),form).preview());
    }
    public UUID saveAssignments(TnaPrincipal actor, AssignmentCommand form) {
        if(form==null) throw OrgException.invalid("배정 정보를 입력해 주세요.");
        return commands.executeBatch(actor,form.commandId(),"WORK_GROUP_ASSIGN","EMPLOYEE",
                fingerprint(form),form.reason(),()->{
            var batch=prepare(actor.tenantId(),form);
            demo.requireMutableEmployees(actor,form::employeeIds);
            confirmPreview(form.token(),batch.preview().token(),batch.preview().conflicts());
            var changes=new ArrayList<BulkData.Change>();
            for(var edit:batch.edits()) {
                var period=edit.period();
                UUID target=edit.employee().id();
                UUID assignmentId;
                if(period!=null&&form.validFrom().equals(period.validFrom())) {
                    assignmentId=period.id();
                    changed(groups.replace(actor.tenantId(),assignmentId,form.groupId(),batch.revision().id(),edit.until(),
                            form.reason(),period.version(),actor.accountId()));
                } else {
                    if(period!=null) changed(groups.shorten(actor.tenantId(),period.id(),form.validFrom(),period.version(),actor.accountId()));
                    assignmentId=UUID.randomUUID();
                    changed(groups.assign(actor.tenantId(),assignmentId,target,form.groupId(),batch.revision().id(),
                            form.validFrom(),edit.until(),form.reason(),actor.accountId()));
                }
                changed(employees.advanceVersion(actor.tenantId(),target,edit.employee().version(),actor.accountId()));
                changes.add(new BulkData.Change(target.toString(),target,edit.employee().version()+1,period,
                        new Assigned(assignmentId,form.groupId(),batch.revision().id(),form.validFrom(),edit.until())));
            }
            return List.copyOf(changes);
        }).batchId();
    }

    private Batch prepare(UUID tenant, AssignmentCommand form) {
        if(form==null) throw OrgException.invalid("배정 정보를 입력해 주세요.");
        schedules.checkDate(form.validFrom());
        requiredText(form.reason(),500,"배정 사유");
        var template=schedules.requireGroup(tenant,form.groupId());
        var date=form.validFrom();
        var history=groups.revisions(tenant,form.groupId());
        var revision=effective(history,date);
        if(revision==null) throw OrgException.invalid(history.isEmpty()?"먼저 근무조 개정을 발행해 주세요.":
                "근무조의 첫 적용 가능일은 "+history.getLast().validFrom()+"입니다.");
        if(form.employeeIds()==null||form.employeeIds().isEmpty()||form.employeeIds().size()>50||
                form.employeeIds().stream().anyMatch(Objects::isNull)||new HashSet<>(form.employeeIds()).size()!=form.employeeIds().size())
            throw OrgException.invalid("중복 없이 사원 1~50명을 선택해 주세요.");
        var replacements=form.replaceAssignmentIds()==null?List.<UUID>of():form.replaceAssignmentIds();
        if(replacements.size()>form.employeeIds().size()||replacements.stream().anyMatch(Objects::isNull)
                ||new HashSet<>(replacements).size()!=replacements.size()) throw OrgException.invalid("교체할 기존 예약을 확인해 주세요.");
        var selected=new HashSet<>(replacements);
        var rows=new ArrayList<AssignmentRow>();
        var edits=new ArrayList<Edit>();
        var conflicts=new ArrayList<String>();
        LocalTime nextStart=null;
        if(date.equals(commands.today())) {
            int weekday=date.getDayOfWeek().getValue();
            nextStart=groups.days(tenant,revision.id()).stream().filter(d->d.weekday()==weekday)
                    .map(WorkGroupMapper.DayTimes::startTime).filter(Objects::nonNull).findFirst().orElse(null);
        }
        for(UUID id:form.employeeIds()) {
            var employee=employees.get(tenant,id);
            if(employee==null) throw OrgException.notFound();
            var person=groups.employee(tenant,id,date);
            var period=groups.period(tenant,id,date);
            LocalDate next=groups.next(tenant,id,date);
            LocalDate until=employee.leftOn()==null?next:earlier(next,employee.leftOn().plusDays(1));
            if(period!=null&&period.validTo()!=null) until=earlier(until,period.validTo());
            String error=null;
            if(!employee.active()||date.isBefore(employee.joinedOn())||employee.leftOn()!=null&&date.isAfter(employee.leftOn())) {
                error="재직 기간 밖입니다.";
            } else if(period!=null&&form.groupId().equals(period.workGroupId())) {
                error="이미 같은 근무조에 배정되어 있습니다.";
            } else {
                error=schedules.todayConflict(tenant,id,date,period!=null,nextStart);
            }
            boolean sameDate=period!=null&&date.equals(period.validFrom());
            UUID replaceable=error==null&&sameDate?period.id():null;
            if(sameDate&&!selected.remove(period.id())&&replaceable!=null)
                error="같은 적용일에 예약이 있습니다. 교체할 예약을 선택해 주세요.";
            if(error!=null) conflicts.add(person.number()+" "+person.name()+" · "+date+" · "+error);
            rows.add(new AssignmentRow(id,person.number(),person.name(),person.currentGroup(),template.name(),
                    period==null||period.validFrom().equals(date)?null:date.minusDays(1),date,next,
                    replaceable,error));
            edits.add(new Edit(person,period,until));
        }
        if(!selected.isEmpty()) conflicts.add("교체 대상으로 선택한 예약이 변경되었습니다.");
        String token=fingerprint(form.groupId(),date,form.reason(),revision.id(),template.version(),edits,rows,replacements);
        return new Batch(revision,edits,new AssignmentPreview(token,List.copyOf(rows),List.copyOf(conflicts)));
    }
    private static LocalDate earlier(LocalDate a, LocalDate b) {
        return a==null||b.isBefore(a)?b:a;
    }
    private record Edit(Employee employee,WorkGroupMapper.Period period,LocalDate until) {}
    private record Batch(Revision revision,List<Edit> edits,AssignmentPreview preview) {}
    private record Assigned(UUID assignmentId,UUID groupId,UUID scheduleVersionId,LocalDate validFrom,LocalDate validTo) {}
}
