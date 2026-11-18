package kr.tna.request;

import static kr.tna.org.OrgCommands.fingerprint;
import java.time.*;
import java.util.*;
import kr.tna.approval.RequestFormMapper;
import kr.tna.org.*;
import kr.tna.request.RequestData.*;
import tools.jackson.databind.json.JsonMapper;

/** Resolves both previews and submissions while the caller holds the tenant lock. */
final class FormRouteResolver {
    private final LeaveWorkflowMapper mapper;
    private final RequestFormMapper forms;
    private final EmployeeAdminMapper employees;
    private final ApproverMapper approvers;
    private final ApprovalRouteResolver defaults;
    private final JsonMapper json=JsonMapper.builder().build();

    FormRouteResolver(LeaveWorkflowMapper mapper,RequestFormMapper forms,EmployeeAdminMapper employees,
                      ApproverMapper approvers,ApprovalRouteResolver defaults) {
        this.mapper=mapper;this.forms=forms;this.employees=employees;this.approvers=approvers;this.defaults=defaults;
    }

    record Trace(UUID organizationId,String organizationName,long organizationVersion,String unit,UUID parentId,
                 UUID headAssignmentId,Long headVersion,UUID employeeId,String disposition) {}
    record Result(UUID formVersionId,UUID routeVersionId,ApprovalRouteResolver.Resolution inherited,
                  LeaveWorkflowMapper.RouteOrganization membership,List<Trace> path,ApprovalPreview preview) {}

    Result form(UUID tenant,UUID author,UUID target,String kind,LocalDate date) {
        if(!Set.of("LEAVE_DAY","CANCEL_LEAVE","ATTENDANCE_CORRECTION").contains(kind))
            throw OrgException.invalid("신청 종류를 확인해 주세요.");
        var form=forms.effective(tenant,kind,date);
        if(form==null)return failure(null,null,null,null,null,date,"사용 중인 신청서가 없습니다. 담당자에게 신청서코드 설정을 요청해 주세요.");
        return resolve(tenant,author,target,date,form.id(),form.code(),form.name(),form.routeVersionId());
    }

    Result resolve(UUID tenant,UUID author,UUID target,LocalDate date,UUID formId,String code,String name,UUID routeId) {
        if(routeId==null) {
            var legacy=defaults.resolve(tenant,author,target,date);
            var preview=legacy.preview();
            var steps=legacy.eligible()?List.of(new PreviewStep(1,"결재자",legacy.approverId(),legacy.approverName(),null)):List.<PreviewStep>of();
            String token=fingerprint(tenant,author,target,date,formId,legacy);
            return new Result(formId,null,legacy,legacy.organization(),List.of(),new ApprovalPreview(preview.source(),preview.routeName(),
                    preview.approverName(),preview.eligible(),preview.message(),code,name,date,steps,token));
        }
        var definition=mapper.formDefinition(tenant,routeId);
        String routeName=definition==null?null:definition.name();
        if(definition==null||!definition.active())
            return failure(formId,routeId,code,name,routeName,date,"연결한 결재선이 비활성 또는 미등록입니다. 결재선 관리에서 확인해 주세요.");
        var membership=mapper.routeOrganization(tenant,target,date);
        var steps=new ArrayList<PreviewStep>();
        var path=new ArrayList<Trace>();
        try {
            if("DIRECT_EMPLOYEE".equals(definition.approverKind())) {
                addApprover(tenant,target,author,date,steps,"지정 결재자",definition.employeeId(),null);
            } else {
                if(membership==null)throw OrgException.invalid("대상 사원의 주 소속이 없습니다. 인사기본에서 소속을 확인해 주세요.");
                var visited=new HashSet<UUID>();
                boolean chain="ORGANIZATION_CHAIN".equals(definition.approverKind());
                boolean sawTeam=false;
                UUID next=membership.organizationId();
                while(true) {
                    if(next==null)throw OrgException.invalid("종료 결재 단위에 도달하지 못했습니다. 상위 조직과 결재 단위를 확인해 주세요.");
                    if(!visited.add(next))throw OrgException.invalid("상위 조직이 순환합니다. 조직 관리에서 확인해 주세요.");
                    var node=mapper.formOrganization(tenant,next);
                    if(node==null||!node.active())throw OrgException.invalid("결재 경로에 비활성 조직이 있습니다. 조직 관리에서 확인해 주세요.");
                    String unit=node.approvalUnit();
                    if(chain&&unit==null)throw OrgException.invalid(node.name()+"의 결재 단위가 미지정입니다. 조직 관리에서 지정해 주세요.");
                    if(chain&&"PASS_THROUGH".equals(unit)) {
                        path.add(trace(node,null,"PASS_THROUGH"));next=node.parentId();continue;
                    }
                    if(chain&&"TEAM".equals(definition.terminalUnit())&&!"TEAM".equals(unit))
                        throw OrgException.invalid(node.name()+" 경로에 팀이 없습니다. 종료 단위를 확인해 주세요.");
                    boolean team="TEAM".equals(unit);
                    if(chain&&team&&sawTeam)throw OrgException.invalid(node.name()+"의 팀 결재 단위가 중복됩니다.");
                    sawTeam|=team;
                    var head=mapper.routeHead(tenant,node.id(),date);
                    if(head==null)throw OrgException.invalid(node.name()+" 조직장이 공석입니다. 조직도에서 배정해 주세요.");
                    boolean terminal=!chain||Objects.equals(unit,definition.terminalUnit());
                    boolean skip=chain&&!terminal&&team&&head.employeeId().equals(target);
                    path.add(trace(node,head,skip?"SELF_EXCLUDED":"APPROVER"));
                    if(!skip)addApprover(tenant,target,author,date,steps,chain?(team?"팀장":"실장"):"조직장",head.employeeId(),node.name());
                    if(terminal)break;
                    next=node.parentId();
                }
            }
        } catch(OrgException error) {
            if(error.status()!=422)throw error;
            return failure(formId,routeId,code,name,routeName,date,error.getMessage());
        }
        String token=fingerprint(tenant,author,target,date,formId,routeId,membership,path,steps);
        return new Result(formId,routeId,null,membership,List.copyOf(path),new ApprovalPreview("FORM",routeName,
                steps.getFirst().approverName(),true,null,code,name,date,List.copyOf(steps),token));
    }

    private static Trace trace(FormStepMapper.OrgNode node,LeaveWorkflowMapper.RouteHead head,String disposition) {
        return new Trace(node.id(),node.name(),node.version(),node.approvalUnit(),node.parentId(),
                head==null?null:head.id(),head==null?null:head.version(),head==null?null:head.employeeId(),disposition);
    }
    private void addApprover(UUID tenant,UUID target,UUID author,LocalDate date,List<PreviewStep> steps,
                             String role,UUID candidate,String organization) {
        String label=organization==null?role:organization+" "+role;
        if(target.equals(candidate)||author.equals(candidate))throw OrgException.invalid(label+"이 대상 사원 또는 작성자입니다. 독립된 결재자가 필요합니다.");
        if(steps.stream().anyMatch(step->step.approverId().equals(candidate)))throw OrgException.invalid(label+"과 앞 단계 결재자가 같습니다. 조직장을 확인해 주세요.");
        if(candidate==null||!approvers.eligible(tenant,candidate,date))throw OrgException.invalid(label+"의 계정·재직·결재 권한을 확인해 주세요.");
        var employee=employees.get(tenant,candidate);
        if(employee==null)throw OrgException.invalid(label+"의 사원 정보를 확인해 주세요.");
        steps.add(new PreviewStep(steps.size()+1,role,candidate,employee.displayName(),organization));
    }

    private static Result failure(UUID form,UUID route,String code,String name,String routeName,LocalDate date,String message) {
        return new Result(form,route,null,null,List.of(),new ApprovalPreview("FORM",routeName,null,false,message,code,name,date,List.of(),null));
    }

    void persist(UUID tenant,UUID request,UUID snapshot,Instant at,Result result) {
        if(result.inherited()!=null)defaults.persist(tenant,result.inherited(),snapshot,request,at);
        else if(mapper.insertFormSnapshot(tenant,snapshot,request,result.routeVersionId(),result.formVersionId(),
                json.writeValueAsString(result.path()),result.membership(),result.preview().steps().getFirst().approverId(),at)!=1)
            throw OrgException.conflict("신청서 결재선 저장에 실패했습니다.");
        for(var step:result.preview().steps())
            if(mapper.insertStep(tenant,request,step,step.stepNumber()==1?"ACTIVE":"WAITING")!=1)
                throw OrgException.conflict("결재 단계 저장에 실패했습니다.");
    }
}
