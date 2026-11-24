package kr.tna.clock;

import static kr.tna.org.OrgCommands.*;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import kr.tna.common.auth.TnaPrincipal;
import kr.tna.clock.AttendanceSettingsData.*;
import kr.tna.org.OrgCommands;
import kr.tna.org.OrgException;
import org.springframework.stereotype.Service;

@Service
public final class AttendanceSettingsService {
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
}
