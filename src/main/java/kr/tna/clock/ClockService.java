package kr.tna.clock;

import static kr.tna.org.OrgCommands.*;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import kr.tna.common.auth.TnaPrincipal;
import kr.tna.common.tenant.BusinessCommands;
import kr.tna.clock.ClockData.*;
import kr.tna.org.OrgCommands.Change;
import kr.tna.org.OrgException;
import kr.tna.schedule.ScheduleData;
import kr.tna.schedule.ScheduleMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

@Service
public final class ClockService {
    private static final ZoneId SEOUL=ZoneId.of("Asia/Seoul");
    private final ClockMapper mapper;
    private final ScheduleMapper schedules;
    private final BusinessCommands commands;
    private final Clock clock;
    private final JsonMapper json=JsonMapper.builder().build();
    public ClockService(ClockMapper mapper,ScheduleMapper schedules,BusinessCommands commands,Clock clock) {
        this.mapper=mapper;this.schedules=schedules;this.commands=commands;this.clock=clock;
    }
    public DaySnapshot day(TnaPrincipal actor,LocalDate date) {
        return commands.read(actor,()->snapshot(actor.tenantId(),actor.employeeId(),date));
    }
    /** Internal use only: callers must authorize the target employee before entering a matching tenant transaction. */
    public DaySnapshot snapshot(UUID tenantId,UUID employeeId,LocalDate date) {
        if(!TransactionSynchronizationManager.isActualTransactionActive()||tenantId==null
                ||!tenantId.toString().equals(mapper.currentTenant()))throw new IllegalStateException("A matching tenant transaction is required");
        if(date==null)throw OrgException.invalid("근무일을 입력해 주세요.");
        mapper.lock(tenantId);
        if(!mapper.employeeExists(tenantId,employeeId))throw OrgException.notFound();
        List<Event> events=List.copyOf(mapper.events(tenantId,employeeId,date));
        var assignments=mapper.schedules(tenantId,employeeId,date,date.getDayOfWeek().getValue());
        var breaks=assignments.size()==1?schedules.breaks(tenantId,assignments.getFirst().scheduleVersionId(),date.getDayOfWeek().getValue())
                :List.<ScheduleData.Break>of();
        var calendars=mapper.calendar(tenantId,date);
        var revision=mapper.revision(tenantId,employeeId,date);
        long input=revision==null?0:revision.inputRevision(),review=revision==null?0:revision.reviewRevision();
        var correction=mapper.currentCorrection(tenantId,employeeId,date);
        return projectSnapshot(employeeId,date,events,assignments,breaks,calendars.size()==1?calendars.getFirst():null,
                input,review,correction,source->confirmation(mapper.findConfirmation(tenantId,employeeId,date,input,source)));
    }

    /** Builds the same effective day from an individual lookup or a month of preloaded evidence. */
    public DaySnapshot projectSnapshot(UUID employeeId,LocalDate date,List<Event> events,List<ClockMapper.ScheduleRow> assignments,
            List<ScheduleData.Break> breaks,ClockMapper.CalendarRow calendar,long input,long review,
            ClockMapper.CorrectionRow correction,Function<String,Confirmation> findConfirmation) {
        ScheduleData.Day schedule=null;UUID scheduleVersion=null;
        if(assignments.size()==1) {
            var a=assignments.getFirst();scheduleVersion=a.scheduleVersionId();
            schedule=new ScheduleData.Day(a.weekday(),a.workday(),a.startTime(),a.endTime(),List.copyOf(breaks),
                    a.workMode(),a.coreStart(),a.coreEnd(),a.standardMinutes(),a.settlementPeriod());
        }
        UUID calendarVersion=calendar==null?null:calendar.versionId();
        String dayKind=calendar==null?"UNCONFIRMED":calendar.kind();
        List<Event> effectiveEvents=correction==null?events:project(correction,date);
        String source=sourceFingerprint(employeeId,date,events,assignments,schedule,calendarVersion,dayKind);
        if(correction!=null)source=fingerprint(source,correction.effectId(),effectiveEvents);
        var confirmation=findConfirmation.apply(source);
        if(confirmation==null&&correction!=null)confirmation=correctionConfirmation(correction,input,review,source,scheduleVersion,calendarVersion);
        return new DaySnapshot(employeeId,date,events,schedule,scheduleVersion,calendarVersion,dayKind,input,review,confirmation,source,effectiveEvents);
    }

    private static String sourceFingerprint(UUID employee, LocalDate date, List<Event> events,
            List<ClockMapper.ScheduleRow> rows, ScheduleData.Day schedule, UUID calendar, String dayKind) {
        if (rows.stream().anyMatch(row -> "SELECTIVE".equals(row.workMode())))
            return fingerprint(employee,date,events,rows,schedule,calendar,dayKind);
        // V31 signed the record names, fields and order below. Existing confirmations and closed results use those bytes.
        record ScheduleRow(UUID scheduleVersionId, int weekday, boolean workday, LocalTime startTime, LocalTime endTime) {}
        record Day(int weekday, boolean workday, LocalTime startTime, LocalTime endTime, List<ScheduleData.Break> breaks) {}
        var oldRows = rows.stream().map(row -> new ScheduleRow(row.scheduleVersionId(), row.weekday(),
                row.workday(), row.startTime(), row.endTime())).toList();
        var oldDay = schedule == null ? null : new Day(schedule.weekday(), schedule.workday(),
                schedule.startTime(), schedule.endTime(), schedule.breaks());
        return fingerprint(employee,date,events,oldRows,oldDay,calendar,dayKind);
    }
    private Confirmation confirmation(ClockMapper.ConfirmationRow saved) {
        if(saved==null)return null;
        try {return new Confirmation(saved.id(),saved.confirmedInputRevision(),saved.reviewRevision(),
                saved.presenceAsWork(),saved.breaksMatch(),saved.sourceFingerprint(),
                List.copyOf(Arrays.asList(json.readValue(saved.breakSnapshot(),Interval[].class))),saved.confirmedAt());}
        catch(JacksonException exception){throw new OrgException(503,"근로 확인 자료를 읽을 수 없습니다. 담당자에게 문의해 주세요.");}
    }
    public UUID record(TnaPrincipal actor,String kind,UUID commandId,String remoteAddress) {
        if(kind==null||!Set.of("IN","OUT").contains(kind))throw OrgException.invalid("출근 또는 퇴근 기록을 선택해 주세요.");
        String action="ATTENDANCE_RECORD";
        return commands.execute(actor,commandId,action,"ATTENDANCE_EVENT",fingerprint(action,kind),"본인 출퇴근 기록","EMPLOYEE",()->{
            String address=AttendanceSettingsService.normalizeAddress(remoteAddress);
            Instant now=clock.instant().truncatedTo(ChronoUnit.MILLIS);
            String matched=mapper.network(actor.tenantId(),address,now);
            if(matched==null)throw OrgException.forbidden();
            UUID network=UUID.fromString(matched);
            LocalDate date=now.atZone(SEOUL).toLocalDate();
            if(mapper.recorded(actor.tenantId(),actor.employeeId(),date,kind))
                throw OrgException.conflict(kind.equals("IN")?"오늘 출근은 이미 기록했습니다.":"오늘 퇴근은 이미 기록했습니다.");
            Event event=new Event(UUID.randomUUID(),kind,now,date);
            changed(mapper.event(actor.tenantId(),actor.employeeId(),event,commandId,address,network));
            changed(mapper.advanceInput(actor.tenantId(),actor.employeeId(),date));
            if(kind.equals("OUT"))autoConfirm(actor.tenantId(),actor.employeeId(),date,actor.accountId());
            return new Change(event.id(),null,event);
        });
    }
    /** 본인 출퇴근 기록이 배정된 근무일. 자정 직전 퇴근도 기록 시점의 날짜로 계산하기 위해 쓴다. */
    public LocalDate eventWorkDate(TnaPrincipal actor,UUID eventId) {
        return commands.read(actor,()->mapper.eventWorkDate(actor.tenantId(),actor.employeeId(),eventId));
    }
    public UUID confirm(TnaPrincipal actor,ConfirmationForm form) {
        if(form==null||form.workDate()==null)throw OrgException.invalid("확인할 근무일을 입력해 주세요.");
        String action="WORK_CONFIRM";
        return commands.execute(actor,form.commandId(),action,"WORK_CONFIRMATION",
                fingerprint(action,form.workDate(),form.baseInputRevision(),form.expectedReviewRevision(),form.presenceAsWork(),form.breaksMatch(),form.sourceFingerprint()),
                "본인 실제 근로·예정 휴게 일치 확인","EMPLOYEE",()->{
            mapper.requireOpen(actor.tenantId(),form.workDate(),form.workDate());
            DaySnapshot day=snapshot(actor.tenantId(),actor.employeeId(),form.workDate());
            checkVersion(form.baseInputRevision(),day.inputRevision());checkVersion(form.expectedReviewRevision(),day.reviewRevision());
            if(!day.sourceFingerprint().equals(form.sourceFingerprint()))throw OrgException.conflict("기록·일정·휴일이 변경되었습니다. 최신 내용을 확인해 주세요.");
            Instant now=clock.instant();
            if(day.workDate().isAfter(now.atZone(SEOUL).toLocalDate())||!form.presenceAsWork()||!form.breaksMatch())
                throw OrgException.invalid("실제 근로·예정 휴게가 일치하는지 확인하고 차이가 있으면 정정을 신청해 주세요.");
            var check=confirmationCheck(day,now,false);
            if(check.error()!=null)throw OrgException.invalid(check.error());
            var breaks=check.breaks();
            changed(mapper.advanceReview(actor.tenantId(),actor.employeeId(),day.workDate(),day.inputRevision(),day.reviewRevision()));
            UUID id=UUID.randomUUID();Instant at=clock.instant().truncatedTo(ChronoUnit.MILLIS);
            changed(mapper.insertConfirmation(actor.tenantId(),id,actor.employeeId(),day.workDate(),day.inputRevision(),day.reviewRevision()+1,
                    day.sourceFingerprint(),at,actor.accountId(),json.writeValueAsString(breaks),form.commandId(),"EMPLOYEE"));
            return new Change(id,null,new Confirmation(id,day.inputRevision(),day.reviewRevision()+1,true,true,day.sourceFingerprint(),breaks,at));
        });
    }
    public boolean canConfirm(DaySnapshot day) {
        return confirmableBreaks(day).isPresent();
    }
    public Optional<List<Interval>> confirmableBreaks(DaySnapshot day) {
        Instant now=clock.instant();
        if (day.confirmation()!=null || day.workDate().isAfter(now.atZone(SEOUL).toLocalDate())) return Optional.empty();
        var check = confirmationCheck(day,now,false);
        return check.error()==null ? Optional.of(check.breaks()) : Optional.empty();
    }
    /**
     * 예정 휴게 중 출퇴근 구간 안의 부분을 실제 휴게로 자동 확인한다. 조건을 못 갖추거나 마감한 달이면 아무것도 하지 않고 false.
     * 호출하는 쪽의 트랜잭션·회사 컨텍스트 안에서 실행된다. confirmedBy가 null이면 시스템이다.
     */
    public boolean autoConfirm(UUID tenantId,UUID employeeId,LocalDate date,UUID confirmedBy) {
        DaySnapshot day=snapshot(tenantId,employeeId,date);
        Instant now=clock.instant();
        if(day.confirmation()!=null||date.isAfter(now.atZone(SEOUL).toLocalDate())||!mapper.closedMonths(tenantId,date,date).isEmpty())return false;
        var check=confirmationCheck(day,now,true);
        if(check.error()!=null)return false;
        if(mapper.advanceReview(tenantId,employeeId,date,day.inputRevision(),day.reviewRevision())!=1)return false;
        // 일정·휴일 변경은 입력 개정 없이 확인을 무효로 만들 수 있으므로 검토 개정까지 넣어 다시 확인할 때 겹치지 않게 한다.
        UUID command=UUID.nameUUIDFromBytes(("AUTO_SCHEDULE:"+tenantId+":"+employeeId+":"+date+":"+day.inputRevision()+":"+day.reviewRevision())
                .getBytes(StandardCharsets.UTF_8));
        changed(mapper.insertConfirmation(tenantId,UUID.randomUUID(),employeeId,date,day.inputRevision(),day.reviewRevision()+1,
                day.sourceFingerprint(),now.truncatedTo(ChronoUnit.MILLIS),confirmedBy,json.writeValueAsString(check.breaks()),command,"AUTO_SCHEDULE"));
        return true;
    }
    /** 직원 확인과 자동 확인의 출퇴근 조건. 선택근무와 자동 확인(clipAll)은 휴게를 출퇴근 구간으로 자른다. */
    private static ConfirmationCheck confirmationCheck(DaySnapshot day,Instant now,boolean clipAll) {
        var ins=day.effectiveEvents().stream().filter(e->e.kind().equals("IN")).toList();
        var outs=day.effectiveEvents().stream().filter(e->e.kind().equals("OUT")).toList();
        if(ins.size()!=1||outs.size()!=1||day.schedule()==null||day.calendarVersionId()==null)
            return new ConfirmationCheck(List.of(),"유일한 출퇴근과 유효 일정·휴일 확인이 필요합니다. 누락·중복은 정정을 신청해 주세요.");
        Instant start=ins.getFirst().occurredAt(),end=outs.getFirst().occurredAt();
        if(!start.isBefore(end)||!start.atZone(SEOUL).toLocalDate().equals(day.workDate())
                ||!end.atZone(SEOUL).toLocalDate().equals(day.workDate())||end.isAfter(now))
            return new ConfirmationCheck(List.of(),"확인할 수 없는 출퇴근 구간입니다. 정정을 신청해 주세요.");
        List<Interval> breaks=day.schedule().breaks().stream().map(b->new Interval(
                day.workDate().atTime(b.startTime()).atZone(SEOUL).toInstant(),day.workDate().atTime(b.endTime()).atZone(SEOUL).toInstant())).toList();
        if (clipAll || day.schedule().selective()) {
            var overlapping = breaks.stream().map(b -> new Interval(b.startAt().isBefore(start) ? start : b.startAt(),
                    b.endAt().isAfter(end) ? end : b.endAt())).filter(b -> b.startAt().isBefore(b.endAt())).toList();
            return new ConfirmationCheck(overlapping,null);
        }
        if(breaks.stream().anyMatch(b->b.startAt().isBefore(start)||b.endAt().isAfter(end)))
            return new ConfirmationCheck(List.of(),"예정 휴게가 실제 출퇴근 구간 밖입니다. 실제 휴게 정정을 신청해 주세요.");
        return new ConfirmationCheck(breaks,null);
    }
    private record ConfirmationCheck(List<Interval> breaks,String error) { }
    private List<Event> project(ClockMapper.CorrectionRow correction,LocalDate date) {
        try {
            var before=json.readTree(correction.beforeSnapshot());
            var values=before.get("effectiveEvents");
            if(values==null||values.isNull())values=before.get("events");
            if(values==null||!values.isArray())throw new IllegalArgumentException("Missing correction snapshot");
            List<Event> base=Arrays.asList(json.readValue(values.toString(),Event[].class));
            var result=new ArrayList<Event>();
            projectKind(result,base,correction.effectId(),date,"IN",correction.inAction(),correction.requestedInAt());
            projectKind(result,base,correction.effectId(),date,"OUT",correction.outAction(),correction.requestedOutAt());
            return List.copyOf(result);
        }catch(JacksonException|IllegalArgumentException exception){throw new OrgException(503,"승인 정정 자료를 읽을 수 없습니다. 담당자에게 문의해 주세요.");}
    }
    private void projectKind(List<Event> result,List<Event> base,UUID effect,LocalDate date,String kind,String action,Instant proposed) {
        switch(action) {
            case "CLEAR" -> { }
            case "SET" -> {
                if(proposed==null)throw new IllegalArgumentException("Missing correction time");
                result.add(new Event(projectedId(effect,kind,0),kind,proposed,date));
            }
            case "KEEP" -> {
                int index=0;
                for(Event old:base)if(old.kind().equals(kind))result.add(new Event(projectedId(effect,kind,index++),kind,old.occurredAt(),date));
            }
            default -> throw new IllegalArgumentException("Unsupported correction action");
        }
    }
    private Confirmation correctionConfirmation(ClockMapper.CorrectionRow correction,long input,long review,String source,UUID scheduleVersion,UUID calendarVersion) {
        try {
            var values=json.readValue(correction.validatedInputRefs(),CorrectionEvidence[].class);
            if(values.length==0)return null;
            var evidence=values[0];
            if(!evidence.confirmed()||evidence.inputRevision()!=input||!correction.requestId().equals(evidence.requestId())
                    ||!Objects.equals(evidence.scheduleVersionId(),scheduleVersion)||!Objects.equals(evidence.calendarVersionId(),calendarVersion))return null;
            if(evidence.actualBreaks()==null)throw new IllegalArgumentException("Missing correction break evidence");
            return new Confirmation(correction.effectId(),input,review,true,true,source,List.copyOf(evidence.actualBreaks()),correction.effectiveAt());
        }catch(JacksonException|IllegalArgumentException exception){throw new OrgException(503,"승인 정정 확인 근거를 읽을 수 없습니다. 담당자에게 문의해 주세요.");}
    }
    private static UUID projectedId(UUID effect,String kind,int index) {
        return UUID.nameUUIDFromBytes((effect+":"+kind+":"+index).getBytes(StandardCharsets.US_ASCII));
    }
    private record CorrectionEvidence(long inputRevision,long reviewRevision,UUID requestId,boolean confirmed,List<Interval> actualBreaks,UUID scheduleVersionId,UUID calendarVersionId) { }
    private static void changed(int count){if(count!=1)throw OrgException.conflict("다른 변경이 반영되었습니다. 최신 내용을 확인해 주세요.");}
}
