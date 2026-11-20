package kr.tna.schedule;

import static kr.tna.org.OrgCommands.*;

import java.time.*;
import java.util.*;
import kr.tna.common.auth.TnaPrincipal;
import kr.tna.org.OrgCommands;
import kr.tna.org.OrgException;
import kr.tna.org.PublicDemoIdentityGuard;
import kr.tna.schedule.ScheduleData.*;
import kr.tna.schedule.WorkGroupData.*;
import org.springframework.stereotype.Service;

@Service
public final class WorkGroupService {
    private final WorkGroupMapper groups;
    private final ScheduleMapper schedules;
    private final OrgCommands commands;
    private final Clock clock;
    private final PublicDemoIdentityGuard demo;

    public WorkGroupService(WorkGroupMapper groups, ScheduleMapper schedules, OrgCommands commands, Clock clock,
            PublicDemoIdentityGuard demo) {
        this.demo=demo;
        this.groups=groups;
        this.schedules=schedules;
        this.commands=commands;
        this.clock=clock;
    }

    public List<Group> groups(TnaPrincipal actor, LocalDate date) {
        LocalDate asOf=date==null?commands.today():date;
        return commands.readShared(actor,()->groups.groups(actor.tenantId()).stream().map(template->{
            var revisions=groups.revisions(actor.tenantId(),template.id());
            var revision=effective(revisions,asOf);
            return new Group(template.id(),template.code(),template.name(),template.version(),
                    revision==null?null:revision.id(),revision==null?null:revision.revision(),
                    revision==null?null:revision.validFrom(),readDays(actor.tenantId(),revision),revisions);
        }).toList());
    }

    public GroupPreview previewGroup(TnaPrincipal actor, GroupCommand form) {
        return commands.readShared(actor,()->preview(actor.tenantId(),form));
    }

    public UUID saveGroup(TnaPrincipal actor, GroupCommand form) {
        if(form==null) throw OrgException.invalid("근무조 정보를 입력해 주세요.");
        return commands.execute(actor,form.commandId(),"WORK_GROUP_PUBLISH","WORK_GROUP",
                fingerprint(form),form.reason(),()->{
            UUID tenant=actor.tenantId();
            var preview=preview(tenant,form);
            if(form.id()!=null) demo.requireMutableEmployees(actor,()->groups.membersUntilNextRevision(tenant,form.id(),form.validFrom()));
            confirmPreview(form.token(),preview.token(),preview.conflicts());
            UUID id=form.id()==null?UUID.randomUUID():form.id();
            String name=requiredText(form.name(),120,"근무조 이름");
            String reason=requiredText(form.reason(),500,"변경 사유");
            if(form.id()==null) {
                schedules.insertTemplate(tenant,id,form.code(),name,actor.accountId());
                groups.insertGroup(tenant,id);
            } else {
                changed(schedules.updateTemplate(tenant,id,name,form.expectedVersion(),actor.accountId()));
            }
            int revision=Math.toIntExact(form.id()==null?1:form.expectedVersion()+2);
            UUID version=UUID.randomUUID();
            schedules.insertVersion(tenant,new TemplateVersion(version,id,revision,form.validFrom(),reason),actor.accountId());
            for(var day:preview.days()) {
                boolean workday=day.codeVersionId()!=null;
                schedules.insertDay(tenant,version,new Day(day.weekday(),workday,day.startTime(),day.endTime(),day.breaks()));
                if(workday) groups.bindDay(tenant,version,day.weekday(),day.codeVersionId());
                for(int i=0;i<day.breaks().size();i++)
                    schedules.insertBreak(tenant,version,day.weekday(),i+1,day.breaks().get(i));
            }
            return new Change(id,preview.beforeDays(),new Published(version,revision,form.validFrom(),preview.days(),
                    preview.affected().stream().map(Employee::id).toList()));
        });
    }

    private GroupPreview preview(UUID tenant, GroupCommand form) {
        if(form==null) throw OrgException.invalid("근무조 정보를 입력해 주세요.");
        checkDate(form.validFrom());
        if(form.code()==null||!form.code().matches("[A-Z0-9]{2,8}"))
            throw OrgException.invalid("근무조 코드는 영문 대문자와 숫자 2~8자리로 입력해 주세요.");
        requiredText(form.name(),120,"근무조 이름");
        requiredText(form.reason(),500,"변경 사유");
        List<Revision> revisions=List.of();
        var conflicts=new ArrayList<String>();
        if(form.id()!=null) {
            var group=requireGroup(tenant,form.id());
            if(!group.code().equals(form.code())) throw OrgException.invalid("등록한 근무조 코드는 변경할 수 없습니다.");
            checkVersion(form.expectedVersion(),group.version());
            revisions=groups.revisions(tenant,form.id());
        } else if(groups.usedCode(tenant,form.code())) {
            conflicts.add("이미 사용 중인 근무조 또는 기존 일정 코드입니다.");
        }

        var date=form.validFrom();
        var prior=effective(revisions,date);
        UUID reservation=prior!=null&&date.equals(prior.validFrom())?prior.id():null;
        if(!Objects.equals(reservation,form.replaceRevisionId()))
            conflicts.add(date+" 개정 예약이 변경되었거나 같은 날 예약이 있습니다. 해당 예약을 선택해 주세요.");
        var until=revisions.stream().map(Revision::validFrom).filter(d->d.isAfter(date)).min(LocalDate::compareTo).orElse(null);
        var days=buildWeek(tenant,form.days(),date);
        var before=readDays(tenant,prior);
        var affected=form.id()==null?List.<Employee>of():groups.affected(tenant,form.id(),date);
        var departing=form.id()==null?List.<Employee>of():groups.departing(tenant,form.id(),date);
        var incoming=form.id()==null?List.<Employee>of():groups.incoming(tenant,form.id(),date,until);
        if(date.equals(commands.today())) {
            LocalTime nextStart=days.get(date.getDayOfWeek().getValue()-1).startTime();
            for(var employee:affected) {
                String error=todayConflict(tenant,employee.id(),date,true,nextStart);
                if(error!=null) conflicts.add(employee.number()+" "+employee.name()+" · "+date+" · "+error);
            }
        }
        var token=fingerprint(form.id(),form.code(),form.name(),form.expectedVersion(),form.reason(),form.replaceRevisionId(),
                date,until,prior,days,affected,departing,incoming);
        return new GroupPreview(token,before,days,days.stream().mapToInt(WeekDay::minutes).sum(),
                affected,departing,incoming,List.copyOf(conflicts));
    }

    private List<WeekDay> buildWeek(UUID tenant, List<WeekInput> inputs, LocalDate date) {
        if(inputs==null||inputs.size()!=7) throw OrgException.invalid("월~일 일곱 요일을 한 번씩 지정해 주세요.");
        var weekdays=new TreeMap<Integer,WeekDay>();
        var codes=new HashMap<UUID,WorkGroupMapper.CodeTimes>();
        var breaks=new HashMap<UUID,List<Break>>();
        for(var input:inputs) {
            if(input==null||input.weekday()<1||input.weekday()>7||weekdays.containsKey(input.weekday()))
                throw OrgException.invalid("각 요일을 한 번씩 지정해 주세요.");
            UUID id=input.codeVersionId();
            if(id==null) {
                weekdays.put(input.weekday(),WeekDay.off(input.weekday()));
                continue;
            }
            var code=codes.computeIfAbsent(id,k->groups.code(tenant,k,date));
            if(code==null) throw OrgException.invalid("근무시간코드 버전을 찾을 수 없습니다.");
            if(date.isBefore(code.effectiveFrom())) throw OrgException.invalid(code.code()+" 사용 시작일은 "+code.effectiveFrom()+"입니다.");
            if(!code.enabled()||!id.equals(code.currentVersion()))
                throw OrgException.invalid(code.code()+"의 적용일 현재 사용 가능한 버전을 선택해 주세요.");
            var intervals=breaks.computeIfAbsent(id,k->groups.codeBreaks(tenant,k));
            weekdays.put(input.weekday(),day(code.day(input.weekday()),intervals));
        }
        if(codes.isEmpty()) throw OrgException.invalid("최소 한 근로일에 근무시간코드를 지정해 주세요.");
        if(codes.values().stream().map(WorkGroupMapper.CodeTimes::workMode).distinct().count()>1)
            throw OrgException.invalid("한 근무조의 근로일은 모두 고정근무 또는 모두 선택근무로 지정해 주세요.");
        if(codes.values().stream().map(WorkGroupMapper.CodeTimes::settlementPeriod).distinct().count()>1)
            throw OrgException.invalid("한 근무조의 선택근무일은 같은 정산기간을 사용해야 합니다.");
        return List.copyOf(weekdays.values());
    }

    private List<WeekDay> readDays(UUID tenant, Revision revision) {
        if(revision==null) return List.of();
        return groups.days(tenant,revision.id()).stream().map(d->day(d,
                d.codeVersionId()==null?List.of():schedules.breaks(tenant,revision.id(),d.weekday()))).toList();
    }

    private static WeekDay day(WorkGroupMapper.DayTimes d, List<Break> breaks) {
        if(d.codeVersionId()==null) return WeekDay.off(d.weekday());
        int minutes="SELECTIVE".equals(d.workMode())?d.standardMinutes():
                Math.toIntExact(Duration.between(d.startTime(),d.endTime()).toMinutes()
                -breaks.stream().mapToLong(b->Duration.between(b.startTime(),b.endTime()).toMinutes()).sum());
        return new WeekDay(d.weekday(),d.codeVersionId(),d.code(),d.startTime(),d.endTime(),breaks,minutes,
                d.workMode(),d.coreStart(),d.coreEnd(),d.standardMinutes(),d.settlementPeriod());
    }

    Template requireGroup(UUID tenant, UUID id) {
        Template template=id==null?null:groups.group(tenant,id);
        if(template==null) throw OrgException.notFound();
        return template;
    }

    void checkDate(LocalDate date) {
        if(date==null||date.isBefore(commands.today())) throw OrgException.invalid("서울 기준 오늘 이후의 적용일을 입력해 주세요.");
    }

    String todayConflict(UUID tenant, UUID employee, LocalDate date, boolean hasAssignment, LocalTime nextStart) {
        if(!date.equals(commands.today())) return null;
        if(groups.hasFacts(tenant,employee,date)) return "이미 출퇴근·근로확인·정정 기록이 있습니다.";
        if(!hasAssignment) return null;
        LocalTime before=groups.scheduledStart(tenant,employee,date,date.getDayOfWeek().getValue());
        LocalTime now=LocalTime.now(clock.withZone(ZoneId.of("Asia/Seoul")));
        if(before!=null&&!now.isBefore(before)||nextStart!=null&&!now.isBefore(nextStart))
            return "적용일의 근무 시작 시각이 지났습니다.";
        return null;
    }

    static Revision effective(List<Revision> revisions, LocalDate date) {
        return revisions.stream().filter(r->!r.validFrom().isAfter(date)).findFirst().orElse(null);
    }
    static void confirmPreview(String supplied, String actual, List<String> conflicts) {
        if(!conflicts.isEmpty()) throw OrgException.conflict(String.join(" / ",conflicts));
        if(!actual.equals(supplied)) throw OrgException.conflict("근무 기준 또는 대상이 달라졌습니다. 미리보기를 다시 확인해 주세요.");
    }
    static void changed(int count) {
        if(count!=1) throw OrgException.conflict("최신 내용을 다시 확인해 주세요.");
    }
    private record Published(UUID scheduleVersionId,int revision,LocalDate validFrom,List<WeekDay> days,List<UUID> employees) {}
}
