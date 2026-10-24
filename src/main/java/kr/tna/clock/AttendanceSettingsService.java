package kr.tna.clock;

import static kr.tna.org.OrgCommands.*;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import kr.tna.common.auth.TnaPrincipal;
import kr.tna.clock.AttendanceSettingsData.*;
import kr.tna.org.OrgCommands;
import kr.tna.org.OrgException;
import org.springframework.stereotype.Service;

@Service
public final class AttendanceSettingsService {
    private static final Set<String> KINDS=Set.of("WORKDAY","NON_WORKDAY","HOLIDAY");
    private final AttendanceSettingsMapper mapper;
    private final OrgCommands commands;
    private final Clock clock;
    public AttendanceSettingsService(AttendanceSettingsMapper mapper,OrgCommands commands,Clock clock) {
        this.mapper=mapper;this.commands=commands;this.clock=clock;
    }
    public List<Network> networks(TnaPrincipal actor) { return commands.read(actor,()->mapper.networks(actor.tenantId())); }
    public UUID addNetwork(TnaPrincipal actor,NetworkForm form) {
        var network=network(form.network());
        String label=requiredText(form.label(),120,"네트워크 명칭"),reason=requiredText(form.reason(),500,"등록 사유");
        if(network.broad()&&!form.wideConfirmed()) throw OrgException.invalid("광범위 허용망의 위험을 확인해 주세요.");
        String action="NETWORK_ADD";
        return commands.execute(actor,form.commandId(),action,"NETWORK",fingerprint(action,network.value(),label,form.wideConfirmed(),reason),reason,()->{
            if(mapper.duplicate(actor.tenantId(),network.value())) throw OrgException.conflict("이미 유효한 동일 허용망이 있습니다.");
            UUID id=UUID.randomUUID();
            mapper.addNetwork(actor.tenantId(),id,network.value(),label,clock.instant().truncatedTo(ChronoUnit.MILLIS),reason,actor.accountId());
            return new Change(id,null,mapper.network(actor.tenantId(),id));
        });
    }
    public UUID closeNetwork(TnaPrincipal actor,UUID id,Long version,String reason,UUID commandId) {
        String checked=requiredText(reason,500,"종료 사유"),action="NETWORK_CLOSE";
        return commands.execute(actor,commandId,action,"NETWORK",fingerprint(action,id,version,checked),checked,()->{
            var before=mapper.network(actor.tenantId(),id);if(before==null) throw OrgException.notFound();
            checkVersion(version,before.version());
            if(before.validTo()!=null) throw OrgException.conflict("이미 종료한 허용망입니다.");
            if(mapper.closeNetwork(actor.tenantId(),id,before.version(),clock.instant().truncatedTo(ChronoUnit.MILLIS))!=1)
                throw OrgException.conflict("다른 변경이 반영되었습니다.");
            return new Change(id,before,mapper.network(actor.tenantId(),id));
        });
    }
    public List<CalendarVersion> calendars(TnaPrincipal actor) { return commands.read(actor,()->mapper.calendars(actor.tenantId())); }
    public UUID calendar(TnaPrincipal actor,CalendarForm form) {
        if(form.from()==null||form.to()==null||form.from().isAfter(form.to())||!form.reviewed()
                ||ChronoUnit.DAYS.between(form.from(),form.to())>=366||form.to().equals(LocalDate.MAX))
            throw OrgException.invalid("전체 기간 검토를 확인하고 366일 이내의 올바른 날짜를 입력해 주세요.");
        String basis=requiredText(form.basis(),500,"기간 검토 근거"),reason=requiredText(form.reason(),500,"캘린더 사유");
        Map<LocalDate,CalendarException> exceptions=exceptions(form);
        String action="CALENDAR_CREATE";
        String normalized=exceptions.entrySet().stream().sorted(Map.Entry.comparingByKey())
                .map(e->fingerprint(e.getKey(),e.getValue().kind(),e.getValue().name(),e.getValue().basis())).reduce("",String::concat);
        return commands.execute(actor,form.commandId(),action,"CALENDAR",fingerprint(action,form.from(),form.to(),basis,normalized,reason),reason,()->{
            UUID id=UUID.randomUUID();int revision=mapper.nextRevision(actor.tenantId());
            mapper.calendar(actor.tenantId(),id,revision,form.from(),form.to().plusDays(1),reason,actor.accountId());
            for(LocalDate date=form.from();!date.isAfter(form.to());date=date.plusDays(1)) {
                var day=exceptions.getOrDefault(date,new CalendarException("WORKDAY","검토한 기본 근로일",basis));
                mapper.day(actor.tenantId(),id,date,day.kind(),day.name(),day.basis());
            }
            return new Change(id,null,new CalendarVersion(id,revision,form.from(),form.to().plusDays(1),reason));
        });
    }
    private Map<LocalDate,CalendarException> exceptions(CalendarForm form) {
        var result=new HashMap<LocalDate,CalendarException>();String text=form.exceptions();
        if(text==null||text.isBlank()) return result;
        if(text.length()>250_000) throw OrgException.invalid("휴일 예외 입력이 너무 깁니다.");
        for(String line:text.lines().toList()) {
            if(line.isBlank()) continue;String[] parts=line.split("\\|",-1);
            if(parts.length!=4) throw OrgException.invalid("예외는 날짜|종류|이름|근거 형식으로 입력해 주세요.");
            LocalDate date;
            try { date=LocalDate.parse(parts[0].strip()); } catch(RuntimeException ex) { throw OrgException.invalid("예외 날짜를 확인해 주세요."); }
            String kind=parts[1].strip();
            if(date.isBefore(form.from())||date.isAfter(form.to())||!KINDS.contains(kind)||result.containsKey(date))
                throw OrgException.invalid("예외 날짜 중복·범위·종류를 확인해 주세요.");
            result.put(date,new CalendarException(kind,requiredText(parts[2],120,"날짜 이름"),requiredText(parts[3],500,"날짜 근거")));
        }
        return result;
    }
    public static String normalizeAddress(String raw) {
        if(raw==null||raw.length()>80||!raw.matches("[0-9a-fA-F:.]+")||!raw.contains(":")&&!raw.contains("."))
            throw OrgException.invalid("숫자 IP 주소를 확인해 주세요.");
        if(raw.contains(".")) {
            String v4=raw.substring(raw.lastIndexOf(':')+1);String[] octets=v4.split("\\.",-1);
            if(octets.length!=4) throw OrgException.invalid("IPv4 주소를 확인해 주세요.");
            for(String octet:octets) {
                if(!octet.matches("0|[1-9][0-9]{0,2}")||Integer.parseInt(octet)>255) throw OrgException.invalid("모호한 IPv4 주소는 허용하지 않습니다.");
            }
        }
        try { return InetAddress.ofLiteral(raw).getHostAddress(); }
        catch(IllegalArgumentException ex) { throw OrgException.invalid("숫자 IP 주소를 확인해 주세요."); }
    }
    private NormalizedNetwork network(String raw) {
        String text=requiredText(raw,90,"CIDR"),parts[]=text.split("/",-1);
        if(parts.length!=2||!parts[1].matches("0|[1-9][0-9]{0,2}")) throw OrgException.invalid("IP/접두 길이 형식의 CIDR을 입력해 주세요.");
        String address=normalizeAddress(parts[0]);byte[] bytes=InetAddress.ofLiteral(address).getAddress();int prefix=Integer.parseInt(parts[1]);
        if(parts[0].contains(":")&&bytes.length==4) {if(prefix<96||prefix>128) throw OrgException.invalid("매핑 IPv6의 접두 길이는96~128이어야 합니다.");prefix-=96;}
        if(prefix>bytes.length*8) throw OrgException.invalid("CIDR 접두 길이를 확인해 주세요.");
        for(int i=0;i<bytes.length;i++) {int remaining=Math.max(0,Math.min(8,prefix-i*8));bytes[i]=(byte)(bytes[i]& (remaining==0?0:0xff << (8-remaining)));}
        try {return new NormalizedNetwork(InetAddress.getByAddress(bytes).getHostAddress()+"/"+prefix,prefix<(bytes.length==4?8:16));}
        catch(UnknownHostException ex) {throw OrgException.invalid("CIDR 주소를 확인해 주세요.");}
    }
    private record NormalizedNetwork(String value,boolean broad) { }
    private record CalendarException(String kind,String name,String basis) { }
}
