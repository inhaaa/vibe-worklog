package kr.tna.request;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import kr.tna.common.auth.TnaPrincipal;
import kr.tna.org.ApproverMapper;
import kr.tna.org.EmployeeAdminMapper;
import kr.tna.org.OrgException;
import kr.tna.request.RequestData.ApprovalPreview;

/** Uses the enclosing command's tenant lock; a configured but invalid route never falls through. */
final class ApprovalRouteResolver {
    private final LeaveWorkflowMapper mapper;
    private final ApproverMapper approvers;
    private final EmployeeAdminMapper employees;

    ApprovalRouteResolver(LeaveWorkflowMapper mapper,ApproverMapper approvers,EmployeeAdminMapper employees) {
        this.mapper=mapper;this.approvers=approvers;this.employees=employees;
    }

    Resolution resolve(TnaPrincipal actor,UUID target,LocalDate today) {
        var context=mapper.routeOrganization(actor.tenantId(),target,today);
        var route=mapper.routeBinding(actor.tenantId(),"EMPLOYEE",target,null,today);
        String source="EMPLOYEE_ROUTE";
        LeaveWorkflowMapper.ApproverBinding legacy=null;
        if(route==null) {
            legacy=mapper.binding(actor.tenantId(),target,today);
            if(legacy!=null)source="LEGACY";
            else {
                route=context==null?null:mapper.routeBinding(actor.tenantId(),"ORGANIZATION",null,context.organizationId(),today);
                source=route==null?"NONE":"ORGANIZATION_ROUTE";
            }
        }
        var head=route!=null&&"ORGANIZATION_HEAD".equals(route.approverKind())&&context!=null
                ?mapper.routeHead(actor.tenantId(),context.organizationId(),today):null;
        UUID candidate=legacy!=null?legacy.approverEmployeeId():route==null?null:
                "DIRECT_EMPLOYEE".equals(route.approverKind())?route.employeeId():head==null?null:head.employeeId();
        var employee=candidate==null?null:employees.get(actor.tenantId(),candidate);
        String message=null;
        if(route==null&&legacy==null)message="유효한 결재선 또는 지정 승인자가 필요합니다. 담당자에게 배정을 요청해 주세요.";
        else if(route!=null&&!route.routeActive())message="선택된 결재선이 비활성 상태입니다. 담당자에게 재지정을 요청해 주세요.";
        else if(route!=null&&("ORGANIZATION_ROUTE".equals(source)||"ORGANIZATION_HEAD".equals(route.approverKind()))
                &&(context==null||!context.organizationActive()))message="현재 소속 조직이 미배정 또는 비활성 상태입니다. 담당자에게 확인해 주세요.";
        else if(candidate==null)message="현재 소속 조직의 조직장이 공석입니다. 담당자에게 배정을 요청해 주세요.";
        else if(target.equals(candidate))message="대상 사원은 자기 신청을 승인할 수 없습니다. 결재선을 재지정해 주세요.";
        else if(actor.employeeId().equals(candidate))message="신청 작성자는 직접 승인할 수 없습니다. 결재선을 재지정해 주세요.";
        else if(employee==null||!approvers.eligible(actor.tenantId(),candidate,today))
            message="현재 결재자의 재직·계정 수락·결재 권한을 확인하고 재지정해 주세요.";
        return new Resolution(source,route,legacy,context,head,candidate,employee==null?null:employee.displayName(),message);
    }

    void persist(UUID tenant,Resolution resolution,UUID snapshotId,UUID requestId,Instant at) {
        if(snapshotId==null)return;
        var route=resolution.route();var context=resolution.organization();var head=resolution.head();
        if(mapper.insertRouteSnapshot(tenant,snapshotId,requestId,route.routeVersionId(),route.assignmentId(),route.assignmentVersion(),
                context==null?null:context.organizationId(),context==null?null:context.assignmentId(),
                context==null?null:context.assignmentVersion(),head==null?null:head.id(),head==null?null:head.version(),
                resolution.approverId(),at)!=1)throw OrgException.conflict("결재선 해석을 저장하지 못했습니다. 최신 내용을 확인해 주세요.");
    }

    record Resolution(String source,LeaveWorkflowMapper.RouteBinding route,LeaveWorkflowMapper.ApproverBinding legacy,
            LeaveWorkflowMapper.RouteOrganization organization,LeaveWorkflowMapper.RouteHead head,UUID approverId,
            String approverName,String message) {
        boolean eligible(){return message==null;}
        ApprovalPreview preview(){return new ApprovalPreview(source,route==null?null:route.routeName(),approverName,eligible(),message);}
        void requireEligible(){if(!eligible())throw OrgException.invalid(message);}
    }
}
