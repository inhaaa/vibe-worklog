package kr.tna.request;

import static kr.tna.org.OrgCommands.*;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import kr.tna.common.auth.TnaPrincipal;
import kr.tna.org.OrgCommands;
import kr.tna.org.OrgException;
import kr.tna.request.LeaveAttributionData.*;
import org.springframework.stereotype.Service;

@Service
public final class LeaveAttributionService {
    private final LeaveAttributionMapper mapper;
    private final LeaveWorkflowMapper workflow;
    private final OrgCommands commands;
    public LeaveAttributionService(LeaveAttributionMapper mapper,LeaveWorkflowMapper workflow,OrgCommands commands) {
        this.mapper=mapper;this.workflow=workflow;this.commands=commands;
    }
    public View view(TnaPrincipal actor,UUID bucketId,Form draft) {
        return read(actor,()->{
            var bucket=bucket(actor.tenantId(),bucketId);var history=List.copyOf(mapper.history(actor.tenantId(),bucketId));
            Attribution latest=history.isEmpty()?null:history.getFirst();
            var form=draft!=null?draft:new Form(latest==null?0L:latest.revision(),latest==null?"UNCLASSIFIED":latest.classificationStatus(),
                    latest==null?null:latest.entitlementYear(),false,latest==null?"":latest.basis(),"",UUID.randomUUID());
            return new View(bucket,history,form);
        });
    }
    public UUID save(TnaPrincipal actor,UUID bucketId,Form form) {
        if(form==null || form.expectedRevision()==null || form.expectedRevision()<0)throw OrgException.invalid("현재 귀속 개정 번호를 확인해 주세요.");
        if(form.classificationStatus()==null || !Set.of("CONFIRMED","UNCLASSIFIED").contains(form.classificationStatus()))
            throw OrgException.invalid("확정 또는 미분류 상태를 선택해 주세요.");
        if(form.classificationStatus().equals("CONFIRMED")) {
            if(form.entitlementYear()==null || form.entitlementYear()<1900 || form.entitlementYear()>9998 || !Boolean.TRUE.equals(form.singleYearConfirmed()))
                throw OrgException.invalid("묶음 전체가 단일 귀속연도라는 근거를 확인하고 1900~9998년을 입력해 주세요.");
        } else if(form.entitlementYear()!=null) throw OrgException.invalid("미분류로 해제할 때는 귀속연도를 비워 주세요.");
        String basis=requiredText(form.basis(),500,"귀속 근거"),reason=requiredText(form.reason(),500,"변경 사유");
        return commands.execute(actor,form.commandId(),"LEAVE_BUCKET_ATTRIBUTION_ADD","LEAVE_BUCKET_ATTRIBUTION",
                fingerprint(bucketId,form.expectedRevision(),form.classificationStatus(),form.entitlementYear(),Boolean.TRUE.equals(form.singleYearConfirmed()),basis,reason),reason,()->{
                    var bucket=bucket(actor.tenantId(),bucketId);
                    if(actor.employeeId().equals(bucket.employeeId()))throw OrgException.forbidden();
                    var history=mapper.history(actor.tenantId(),bucketId);Attribution previous=history.isEmpty()?null:history.getFirst();
                    long revision=previous==null?0:previous.revision();checkVersion(form.expectedRevision(),revision);
                    UUID type=mapper.annualLeaveType(actor.tenantId());if(type==null)throw OrgException.invalid("시스템 연차 유형을 확인해 주세요.");
                    var current=new Attribution(UUID.randomUUID(),bucketId,revision+1,type,form.classificationStatus(),form.entitlementYear(),basis,reason,actor.accountId(),null,null);
                    if(mapper.insert(actor.tenantId(),current)!=1)throw OrgException.conflict("다른 변경을 확인한 뒤 다시 저장해 주세요.");
                    var stored=mapper.history(actor.tenantId(),bucketId).getFirst();
                    return new Change(stored.id(),previous,stored);
                });
    }
    private RequestData.Bucket bucket(UUID tenant,UUID id) {
        var bucket=workflow.findBucket(tenant,id,commands.today());if(bucket==null)throw OrgException.notFound();return bucket;
    }
    private <T>T read(TnaPrincipal actor,Supplier<T> action) {
        return commands.read(actor,()->{mapper.lockShared(actor.tenantId());return commands.read(actor,action);});
    }
}
