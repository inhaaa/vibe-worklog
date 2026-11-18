package kr.tna.request;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.apache.ibatis.annotations.*;
import kr.tna.request.RequestData.DecisionStep;

/** Statements executed inside the ordinary tenant-scoped workflow transaction. */
public interface FormStepMapper {
    record RouteDefinition(UUID id,String name,String approverKind,String terminalUnit,UUID employeeId,boolean active) {}
    record OrgNode(UUID id,String name,UUID parentId,String approvalUnit,boolean active,long version) {}

    @Select("""
        SELECT v.id,v.name,v.approver_kind,v.terminal_unit,v.employee_id,r.active
        FROM tna.approval_route_version v JOIN tna.approval_route r ON r.tenant_id=v.tenant_id AND r.id=v.route_id
        WHERE v.tenant_id=#{tenant} AND v.id=#{id}
        """)
    RouteDefinition formDefinition(UUID tenant,UUID id);
    @Select("SELECT id,name,parent_id,approval_unit,active,version FROM tna.organization WHERE tenant_id=#{tenant} AND id=#{id}")
    OrgNode formOrganization(UUID tenant,UUID id);

    @Insert("""
        INSERT INTO tna.approval_route_snapshot(tenant_id,id,request_id,route_version_id,form_version_id,
            organization_path,organization_id,organization_assignment_id,organization_assignment_version,
            resolved_approver_employee_id,resolved_at)
        VALUES(#{tenant},#{snapshot},#{request},#{route},#{form},CAST(#{path} AS jsonb),
            #{membership.organizationId},#{membership.assignmentId},#{membership.assignmentVersion},#{first},#{at})
        """)
    int insertFormSnapshot(UUID tenant,UUID snapshot,UUID request,UUID route,UUID form,String path,
                           LeaveWorkflowMapper.RouteOrganization membership,UUID first,Instant at);
    @Insert("""
        INSERT INTO tna.request_approval_step(tenant_id,request_id,step_number,role,original_approver_id,approver_id,status)
        VALUES(#{tenant},#{request},#{step.stepNumber},#{step.role},#{step.approverId},#{step.approverId},#{status})
        """)
    int insertStep(UUID tenant,UUID request,RequestData.PreviewStep step,String status);
    @Select("""
        SELECT s.step_number,s.role,o.display_name AS original_approver_name,e.display_name AS approver_name,
               s.status,d.display_name AS decided_by_name,s.decided_at,s.reason
        FROM tna.request_approval_step s
        JOIN tna.employee o ON o.tenant_id=s.tenant_id AND o.id=s.original_approver_id
        JOIN tna.employee e ON e.tenant_id=s.tenant_id AND e.id=s.approver_id
        LEFT JOIN tna.employee d ON d.tenant_id=s.tenant_id AND d.id=s.decided_by
        WHERE s.tenant_id=#{tenant} AND s.request_id=#{request} ORDER BY s.step_number
        """)
    List<DecisionStep> decisionSteps(UUID tenant,UUID request);
    @Select("SELECT EXISTS(SELECT 1 FROM tna.request_approval_step WHERE tenant_id=#{tenant} AND request_id=#{request} AND decided_by=#{employee})")
    boolean hasDecided(UUID tenant,UUID request,UUID employee);
    @Update("""
        UPDATE tna.request_approval_step SET status=#{decision},decided_by=#{actor},decided_at=CURRENT_TIMESTAMP,reason=#{reason}
        WHERE tenant_id=#{tenant} AND request_id=#{request} AND step_number=#{step} AND approver_id=#{actor} AND status='ACTIVE'
        """)
    int decideStep(UUID tenant,UUID request,int step,UUID actor,String decision,String reason);
    @Update("""
        UPDATE tna.request_approval_step SET status='ACTIVE'
        WHERE tenant_id=#{tenant} AND request_id=#{request} AND step_number=#{step} AND status='WAITING'
        """)
    int activateStep(UUID tenant,UUID request,int step);
    @Update("""
        UPDATE tna.approval_request r SET current_step=s.step_number,current_approver_id=s.approver_id,version=r.version+1
        FROM tna.request_approval_step s WHERE r.tenant_id=#{tenant} AND r.id=#{request} AND r.status='PENDING'
        AND r.version=#{version} AND s.tenant_id=r.tenant_id AND s.request_id=r.id AND s.status='ACTIVE' AND s.step_number=r.current_step+1
        """)
    int advanceStep(UUID tenant,UUID request,long version);
    @Update("UPDATE tna.request_approval_step SET status='CLOSED' WHERE tenant_id=#{tenant} AND request_id=#{request} AND status IN ('ACTIVE','WAITING')")
    int closeSteps(UUID tenant,UUID request);
    @Select("SELECT EXISTS(SELECT 1 FROM tna.request_approval_step WHERE tenant_id=#{tenant} AND request_id=#{request} AND step_number<>#{step} AND approver_id=#{candidate})")
    boolean assignedElsewhere(UUID tenant,UUID request,int step,UUID candidate);
    @Update("UPDATE tna.request_approval_step SET approver_id=#{candidate} WHERE tenant_id=#{tenant} AND request_id=#{request} AND step_number=#{step} AND status='ACTIVE'")
    int reassignStep(UUID tenant,UUID request,int step,UUID candidate);
}
