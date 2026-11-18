package kr.tna.approval;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import kr.tna.approval.RequestFormData.*;
import kr.tna.common.auth.TnaPrincipal;
import kr.tna.org.OrgCommands;
import kr.tna.org.OrgException;
import org.springframework.stereotype.Service;

@Service
public final class RequestFormService {
    private static final Set<String> KINDS=Set.of("LEAVE_DAY","CANCEL_LEAVE","ATTENDANCE_CORRECTION");
    private final OrgCommands commands;
    private final RequestFormMapper forms;
    private final ApprovalRouteMapper routes;

    public RequestFormService(OrgCommands commands,RequestFormMapper forms,ApprovalRouteMapper routes) {
        this.commands=commands; this.forms=forms; this.routes=routes;
    }

    public Page page(TnaPrincipal actor,String query,UUID selectedId) {
        return commands.read(actor,()->{
            UUID tenant=actor.tenantId();
            routes.lock(tenant);
            String q=query==null?"":query.strip();
            if(q.length()>120)throw OrgException.invalid("검색어는 120자까지 입력할 수 있습니다.");
            var today=commands.today();
            var list=forms.list(tenant,q,today);
            var selected=selectedId==null?null:forms.row(tenant,selectedId,today);
            if(selectedId!=null && selected==null)throw OrgException.notFound();
            return new Page(q,today,list,selected,selected==null?List.of():forms.history(tenant,selected.id()),
                    forms.routes(tenant),forms.employees(tenant),q.isEmpty()?list:forms.list(tenant,"",today));
        });
    }

    public UUID save(TnaPrincipal actor,Command c) {
        if(c==null)throw OrgException.invalid("변경할 신청서를 선택해 주세요.");
        return commands.execute(actor,c.commandId(),"REQUEST_FORM_SAVE","REQUEST_FORM",
                OrgCommands.fingerprint(c.id(),c.expectedVersion(),c.code(),c.kind(),c.name(),c.effectiveFrom(),
                        c.enabled(),c.routeVersionId(),c.replaceFormId(),c.replaceExpectedVersion(),c.reason()),
                c.reason(),()->saveLocked(actor,c));
    }

    private OrgCommands.Change saveLocked(TnaPrincipal actor,Command c) {
        if(c.code()==null || !c.code().matches("[A-Z0-9]{2,8}"))throw OrgException.invalid("신청서코드는 영문 대문자와 숫자 2~8자리로 입력해 주세요.");
        if(c.kind()==null || !KINDS.contains(c.kind()))throw OrgException.invalid("지원하는 신청 업무를 선택해 주세요.");
        String name=OrgCommands.requiredText(c.name(),120,"신청서명");
        if(c.effectiveFrom()==null || c.effectiveFrom().isBefore(commands.today()) || c.effectiveFrom().getYear()>9999)
            throw OrgException.invalid("적용 시작일은 서울 기준 오늘 이후 날짜로 입력해 주세요.");
        if(c.enabled()==null)throw OrgException.invalid("사용 여부를 선택해 주세요.");
        UUID tenant=actor.tenantId();
        var existing=c.id()==null?null:forms.master(tenant,c.id());
        if(c.id()!=null) {
            if(existing==null)throw OrgException.notFound();
            OrgCommands.checkVersion(c.expectedVersion(),existing.version());
            if(!existing.code().equals(c.code()) || !existing.kind().equals(c.kind()))throw OrgException.invalid("저장된 코드와 업무 종류는 변경할 수 없습니다.");
        } else if(c.expectedVersion()!=null)throw OrgException.invalid("새 신청서의 버전을 확인해 주세요.");
        if(c.routeVersionId()!=null) {
            var route=routes.version(tenant,c.routeVersionId());
            if(route==null || !route.active())throw OrgException.invalid("사용 중인 결재선 버전을 선택해 주세요.");
        }
        UUID id=existing==null?UUID.randomUUID():existing.id();
        var active=forms.effective(tenant,c.kind(),c.effectiveFrom());
        UUID replaced=null;
        if(c.enabled() && (active==null || !active.formId().equals(id))) {
            LocalDate scheduled=forms.nextSchedule(tenant,c.kind(),c.effectiveFrom());
            if(scheduled!=null)throw OrgException.conflict("같은 업무에 "+scheduled+" 적용 예약이 있습니다. 적용일을 확인해 주세요.");
            if(active!=null) {
                if(!active.formId().equals(c.replaceFormId()))throw OrgException.conflict("현재 사용 중인 "+active.code()+" 교체를 확인해 주세요.");
                var previous=forms.master(tenant,active.formId());
                OrgCommands.checkVersion(c.replaceExpectedVersion(),previous.version());
                advance(tenant,previous);
                forms.append(tenant,UUID.randomUUID(),previous.id(),c.effectiveFrom(),false,active.name(),active.routeVersionId(),c.reason(),actor.accountId());
                replaced=previous.id();
            } else if(c.replaceFormId()!=null)throw OrgException.conflict("교체 대상이 변경되었습니다. 다시 조회해 주세요.");
        }
        if(existing==null)forms.create(tenant,id,c.code(),c.kind());
        else advance(tenant,existing);
        var versionId=UUID.randomUUID();
        forms.append(tenant,versionId,id,c.effectiveFrom(),c.enabled(),name,c.routeVersionId(),c.reason(),actor.accountId());
        return new OrgCommands.Change(id,existing,new Saved(versionId,c.enabled(),c.routeVersionId(),replaced));
    }

    private void advance(UUID tenant,Master master) {
        if(forms.advance(tenant,master.id(),master.version())!=1)throw OrgException.conflict("신청서가 변경되었습니다. 다시 조회해 주세요.");
    }

    private record Saved(UUID versionId,boolean enabled,UUID routeVersionId,UUID replacedFormId) { }
}
