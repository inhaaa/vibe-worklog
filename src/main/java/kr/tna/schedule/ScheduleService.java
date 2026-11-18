package kr.tna.schedule;

import static kr.tna.org.OrgCommands.*;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import kr.tna.common.auth.TnaPrincipal;
import kr.tna.org.EmployeeAdminMapper;
import kr.tna.org.OrgCommands;
import kr.tna.org.OrgData.Employee;
import kr.tna.org.OrgException;
import kr.tna.schedule.ScheduleData.*;
import org.springframework.stereotype.Service;

@Service
public final class ScheduleService {
    private final ScheduleMapper mapper;
    private final EmployeeAdminMapper employees;
    private final OrgCommands commands;

    public ScheduleService(ScheduleMapper mapper,EmployeeAdminMapper employees,OrgCommands commands) {
        this.mapper=mapper;this.employees=employees;this.commands=commands;
    }

    public List<Template> list(TnaPrincipal actor) {
        return commands.read(actor,()->mapper.list(actor.tenantId()));
    }
    public Template get(TnaPrincipal actor,UUID id) {
        return commands.read(actor,()->requireTemplate(actor.tenantId(),id));
    }
    public List<TemplateVersion> versions(TnaPrincipal actor,UUID templateId) {
        return commands.read(actor,()->{requireTemplate(actor.tenantId(),templateId);return mapper.versions(actor.tenantId(),templateId);});
    }
    public List<TemplateVersion> assignableVersions(TnaPrincipal actor) {
        return commands.read(actor,()->mapper.assignableVersions(actor.tenantId()));
    }
    public List<Day> days(TnaPrincipal actor,UUID versionId) {
        return commands.read(actor,()->{requireVersion(actor.tenantId(),versionId);return readDays(actor.tenantId(),versionId);});
    }

    public UUID create(TnaPrincipal actor,TemplateForm form) {
        Values values=normalize(form);
        String action="SCHEDULE_CREATE";
        return commands.execute(actor,form.commandId(),action,"SCHEDULE_TEMPLATE",hash(action,null,values),values.reason(),()->{
            UUID id=UUID.randomUUID();
            changed(mapper.insertTemplate(actor.tenantId(),id,values.code(),values.name(),actor.accountId()));
            TemplateVersion version=insertVersion(actor,id,1,values);
            return new Change(id,null,new VersionState(requireTemplate(actor.tenantId(),id),version,values.days()));
        });
    }

    public UUID revise(TnaPrincipal actor,UUID templateId,TemplateForm form) {
        Values values=normalize(form);
        String action="SCHEDULE_REVISE";
        return commands.execute(actor,form.commandId(),action,"SCHEDULE_TEMPLATE",hash(action,templateId,values),values.reason(),()->{
            Template before=requireTemplate(actor.tenantId(),templateId);
            requireFixedTemplate(actor.tenantId(), templateId);
            checkVersion(form.expectedVersion(),before.version());
            if(!before.code().equals(values.code()))throw OrgException.invalid("템플릿 코드는 변경할 수 없습니다.");
            List<TemplateVersion> history=mapper.versions(actor.tenantId(),templateId);
            TemplateVersion previous=history.isEmpty()?null:history.getFirst();
            int revision=previous==null?1:Math.incrementExact(previous.revision());
            changed(mapper.updateTemplate(actor.tenantId(),templateId,values.name(),before.version(),actor.accountId()));
            TemplateVersion version=insertVersion(actor,templateId,revision,values);
            return new Change(templateId,new VersionState(before,previous,previous==null?List.of():readDays(actor.tenantId(),previous.id())),
                    new VersionState(requireTemplate(actor.tenantId(),templateId),version,values.days()));
        });
    }

    public List<Assignment> assignments(TnaPrincipal actor,UUID employeeId) {
        return commands.read(actor,()->{requireEmployee(actor.tenantId(),employeeId);return mapper.assignments(actor.tenantId(),employeeId);});
    }

    public UUID assign(TnaPrincipal actor,UUID employeeId,AssignmentForm form) {
        if(form==null||form.scheduleVersionId()==null||form.validFrom()==null)throw OrgException.invalid("일정 버전과 적용일을 입력해 주세요.");
        String reason=requiredText(form.reason(),500,"변경 사유");
        String action="SCHEDULE_ASSIGN";
        return commands.execute(actor,form.commandId(),action,"EMPLOYEE",
                fingerprint(action,employeeId,form.scheduleVersionId(),form.validFrom(),form.expectedVersion(),reason),reason,()->{
            Employee employee=requireEmployee(actor.tenantId(),employeeId);
            checkVersion(form.expectedVersion(),employee.version());
            LocalDate start=form.validFrom();
            if(!employee.active()||start.isBefore(commands.today())||start.isBefore(employee.joinedOn())
                    ||employee.leftOn()!=null&&start.isAfter(employee.leftOn())) {
                throw OrgException.invalid("활성 사원의 오늘 이후 재직 기간에 일정을 배정해 주세요.");
            }
            TemplateVersion version=requireVersion(actor.tenantId(),form.scheduleVersionId());
            Template template=requireTemplate(actor.tenantId(),version.templateId());
            if(!template.active()||start.isBefore(version.validFrom()))throw OrgException.invalid("활성 템플릿의 사용 시작일 이후에 배정해 주세요.");
            requireFixedTemplate(actor.tenantId(), template.id());
            if (mapper.hasGroupAssignment(actor.tenantId(), employeeId, start))
                throw OrgException.invalid("근무조 소속 사원의 일정 변경은 사원 배정에서 진행해 주세요.");
            List<Assignment> history=mapper.physicalAssignments(actor.tenantId(),employeeId);
            Assignment previous=history.isEmpty()?null:history.getFirst();
            if(previous!=null&&(!start.isAfter(previous.validFrom())||previous.validTo()!=null&&start.isBefore(previous.validTo()))) {
                throw OrgException.invalid("마지막 일정 배정 시작일보다 늦은 적용일을 입력해 주세요.");
            }
            if(previous!=null&&previous.validTo()==null)changed(mapper.closeAssignment(actor.tenantId(),previous.id(),start,previous.version(),actor.accountId()));
            changed(mapper.insertAssignment(actor.tenantId(),UUID.randomUUID(),employeeId,version.id(),start,reason,actor.accountId()));
            changed(employees.advanceVersion(actor.tenantId(),employeeId,employee.version(),actor.accountId()));
            return new Change(employeeId,new AssignmentState(employee.version(),previous,null),
                    new AssignmentState(employee.version()+1,previous==null?null:new Assignment(previous.id(),previous.employeeId(),previous.scheduleVersionId(),previous.templateName(),previous.revision(),previous.validFrom(),previous.validTo()==null?start:previous.validTo(),previous.reason(),previous.version()+(previous.validTo()==null?1:0)),
                            mapper.assignments(actor.tenantId(),employeeId).getFirst()));
        });
    }

    private TemplateVersion insertVersion(TnaPrincipal actor,UUID templateId,int revision,Values values) {
        var version=new TemplateVersion(UUID.randomUUID(),templateId,revision,values.validFrom(),values.reason());
        changed(mapper.insertVersion(actor.tenantId(),version,actor.accountId()));
        for(Day day:values.days()) {
            changed(mapper.insertDay(actor.tenantId(),version.id(),day));
            for(int i=0;i<day.breaks().size();i++)changed(mapper.insertBreak(actor.tenantId(),version.id(),day.weekday(),i+1,day.breaks().get(i)));
        }
        return version;
    }
    private List<Day> readDays(UUID tenant,UUID version) {
        return mapper.days(tenant,version).stream().map(d->new Day(d.weekday(),d.workday(),d.startTime(),d.endTime(),List.copyOf(mapper.breaks(tenant,version,d.weekday())))).toList();
    }
    private void requireFixedTemplate(UUID tenant, UUID id) {
        if (mapper.isWorkGroup(tenant, id)) throw OrgException.invalid("근무조 변경은 근무 설정의 근무조에서 진행해 주세요.");
    }
    private Template requireTemplate(UUID tenant,UUID id) {
        Template template=mapper.template(tenant,id);
        if(template==null)throw OrgException.notFound();return template;
    }
    private TemplateVersion requireVersion(UUID tenant,UUID id) {
        TemplateVersion version=mapper.version(tenant,id);
        if(version==null)throw OrgException.notFound();return version;
    }
    private Employee requireEmployee(UUID tenant,UUID id) {
        Employee employee=employees.get(tenant,id);
        if(employee==null)throw OrgException.notFound();return employee;
    }
    private Values normalize(TemplateForm form) {
        if(form==null)throw OrgException.invalid("일정 정보를 입력해 주세요.");
        String code=requiredText(form.code(),40,"템플릿 코드"),name=requiredText(form.name(),120,"템플릿 이름"),reason=requiredText(form.reason(),500,"변경 사유");
        if(form.validFrom()==null||form.validFrom().isBefore(commands.today()))throw OrgException.invalid("버전 사용 시작일은 오늘 이후여야 합니다.");
        if(form.days()==null||form.days().size()!=7)throw OrgException.invalid("월요일부터 일요일까지 일곱 요일을 입력해 주세요.");
        var weekdays=new HashSet<Integer>();var days=new ArrayList<Day>();
        for(DayForm day:form.days()) {
            if(day==null||day.weekday()<1||day.weekday()>7||!weekdays.add(day.weekday()))throw OrgException.invalid("각 요일은 1~7 범위에서 한 번씩 입력해 주세요.");
            if(!day.workday()) {
                if(!blank(day.startTime())||!blank(day.endTime())||!blank(day.breaks()))throw OrgException.invalid("비근로일에는 근무·휴게 시각을 입력할 수 없습니다.");
                days.add(new Day(day.weekday(),false,null,null,List.of()));continue;
            }
            LocalTime start=time(day.startTime()),end=time(day.endTime());
            if(!start.isBefore(end))throw OrgException.invalid("당일 시작 시각보다 늦은 종료 시각을 입력해 주세요.");
            var breaks=new ArrayList<Break>();
            if(!blank(day.breaks())) {
                if(day.breaks().length()>4000)throw OrgException.invalid("휴게 입력이 너무 깁니다.");
                for(String part:day.breaks().strip().split(",",-1)) {
                    String[] pair=part.strip().split("-",-1);
                    if(pair.length!=2)throw OrgException.invalid("휴게는 시작-종료를 쉼표로 구분해 주세요.");
                    LocalTime from=time(pair[0]),to=time(pair[1]);
                    if(!from.isBefore(to)||from.isBefore(start)||to.isAfter(end))throw OrgException.invalid("휴게는 근무 구간 안의 올바른 시작·종료여야 합니다.");
                    breaks.add(new Break(from,to));
                }
                breaks.sort(Comparator.comparing(Break::startTime));
                for(int i=1;i<breaks.size();i++)if(breaks.get(i).startTime().isBefore(breaks.get(i-1).endTime()))throw OrgException.invalid("예정 휴게 구간은 중첩할 수 없습니다.");
            }
            days.add(new Day(day.weekday(),true,start,end,List.copyOf(breaks)));
        }
        if(days.stream().noneMatch(Day::workday))throw OrgException.invalid("최소 한 근로일이 필요합니다.");
        days.sort(Comparator.comparingInt(Day::weekday));
        return new Values(code,name,form.validFrom(),List.copyOf(days),form.expectedVersion(),reason);
    }
    private LocalTime time(String value) {
        if(value==null||!value.strip().matches("[0-9]{2}:[0-9]{2}(?::[0-9]{2}(?:\\.[0-9]{1,3})?)?"))throw OrgException.invalid("시각은 HH:mm[:ss[.SSS]] 형식으로 입력해 주세요.");
        try{return LocalTime.parse(value.strip());}
        catch(DateTimeParseException exception){throw OrgException.invalid("올바른 당일 시각을 입력해 주세요.");}
    }
    private static boolean blank(String value){return value==null||value.isBlank();}
    private static void changed(int count){if(count!=1)throw OrgException.conflict("다른 변경이 반영되었습니다. 최신 내용을 확인해 주세요.");}
    private static String hash(String action,UUID id,Values values){return fingerprint(action,id,values.code(),values.name(),values.validFrom(),values.days(),values.expectedVersion(),values.reason());}
    private record Values(String code,String name,LocalDate validFrom,List<Day> days,Long expectedVersion,String reason) { }
    private record VersionState(Template template,TemplateVersion version,List<Day> days) { }
    private record AssignmentState(long employeeVersion,Assignment previous,Assignment current) { }
}
