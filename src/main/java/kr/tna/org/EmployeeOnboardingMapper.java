package kr.tna.org;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import kr.tna.org.EmployeeOnboardingData.*;
import org.apache.ibatis.annotations.*;

@Mapper
public interface EmployeeOnboardingMapper {
    String ROW = """
            SELECT o.id,o.state,o.version,o.display_name,o.joined_on,o.left_on,o.organization_id,o.schedule_version_id,
                o.employee_id,e.employee_no,o.organization_assignment_id,o.schedule_assignment_id,o.decided_by,o.decided_at,
                COALESCE(o.organization_code,org.code) AS organization_code,
                COALESCE(o.organization_name,org.name) AS organization_name,
                COALESCE(o.schedule_template_name,template.name) AS schedule_template_name,
                COALESCE(o.schedule_revision,schedule.revision) AS schedule_revision,
                actor.display_name AS decided_by_name,audit.reason AS decision_reason
            FROM tna.employee_onboarding o
            LEFT JOIN tna.employee e ON e.tenant_id=o.tenant_id AND e.id=o.employee_id
            LEFT JOIN tna.organization org ON org.tenant_id=o.tenant_id AND org.id=o.organization_id
            LEFT JOIN tna.schedule_version schedule ON schedule.tenant_id=o.tenant_id AND schedule.id=o.schedule_version_id
            LEFT JOIN tna.schedule_template template ON template.tenant_id=schedule.tenant_id AND template.id=schedule.template_id
            LEFT JOIN tna.tenant_account account ON account.tenant_id=o.tenant_id AND account.id=o.decided_by
            LEFT JOIN tna.employee actor ON actor.tenant_id=account.tenant_id AND actor.id=account.employee_id
            LEFT JOIN LATERAL (
                SELECT a.reason FROM tna.org_audit_event a WHERE a.tenant_id=o.tenant_id AND a.target_id=o.id
                    AND a.action IN ('ONBOARDING_CONFIRM','ONBOARDING_WITHDRAW') ORDER BY a.occurred_at DESC,a.id DESC LIMIT 1
            ) audit ON true
            """;

    @Select("SELECT pg_advisory_xact_lock_shared(hashtextextended(CAST(#{tenantId} AS text),0))::text")
    String lock(@Param("tenantId") UUID tenantId);

    @Select(ROW + """
            WHERE o.tenant_id=#{tenantId} AND o.id=#{id}
            """)
    Row find(@Param("tenantId") UUID tenantId, @Param("id") UUID id);

    @Select(ROW + """
            WHERE o.tenant_id=#{tenantId} AND (CAST(#{state} AS varchar) IS NULL OR o.state=#{state})
                AND (CAST(#{from} AS date) IS NULL OR o.joined_on>=#{from})
                AND (CAST(#{to} AS date) IS NULL OR o.joined_on<=#{to})
                AND (#{query}='' OR strpos(lower(o.display_name),lower(#{query}))>0)
            ORDER BY o.joined_on DESC,o.id
            OFFSET #{offset} LIMIT 51
            """)
    List<Row> list(@Param("tenantId") UUID tenantId, @Param("query") String query,
                   @Param("state") State state, @Param("from") LocalDate from,
                   @Param("to") LocalDate to, @Param("offset") int offset);

    @Select("""
            SELECT EXISTS (SELECT 1 FROM tna.employee_onboarding
                WHERE tenant_id=#{tenantId} AND state='PLANNED' AND id IS DISTINCT FROM CAST(#{id} AS uuid)
                    AND lower(display_name)=lower(#{name}) AND joined_on=#{joinedOn})
                OR EXISTS (SELECT 1 FROM tna.employee
                    WHERE tenant_id=#{tenantId} AND lower(display_name)=lower(#{name}) AND joined_on=#{joinedOn})
            """)
    boolean possibleDuplicate(@Param("tenantId") UUID tenantId, @Param("id") UUID id,
                              @Param("name") String name, @Param("joinedOn") LocalDate joinedOn);

    @Select("""
            SELECT EXISTS (SELECT 1 FROM tna.employee_onboarding
                WHERE tenant_id=#{tenantId} AND employee_id=#{employeeId} AND state='CONFIRMED')
            """)
    boolean isConfirmedEmployee(@Param("tenantId") UUID tenantId, @Param("employeeId") UUID employeeId);

    @Select("""
            SELECT id,code,name,version FROM tna.organization WHERE tenant_id=#{tenantId} AND active ORDER BY code,id
            """)
    List<OrganizationOption> organizations(@Param("tenantId") UUID tenantId);

    @Select("""
            SELECT v.id,v.template_id,t.name AS template_name,v.revision,v.valid_from,t.version AS template_version
            FROM tna.schedule_version v JOIN tna.schedule_template t ON t.tenant_id=v.tenant_id AND t.id=v.template_id
            WHERE v.tenant_id=#{tenantId} AND t.active ORDER BY t.code,v.revision DESC,v.id
            """)
    List<ScheduleOption> scheduleVersions(@Param("tenantId") UUID tenantId);

    @Insert("""
            INSERT INTO tna.employee_onboarding(tenant_id,id,display_name,joined_on,left_on,organization_id,
                schedule_version_id,created_by,updated_by)
            VALUES(#{tenantId},#{id},#{form.displayName},#{form.joinedOn},#{form.leftOn},#{form.organizationId},
                #{form.scheduleVersionId},#{actorId},#{actorId})
            """)
    int insert(@Param("tenantId") UUID tenantId, @Param("id") UUID id,
               @Param("form") Form form, @Param("actorId") UUID actorId);

    @Update("""
            UPDATE tna.employee_onboarding SET display_name=#{form.displayName},joined_on=#{form.joinedOn},
                left_on=#{form.leftOn},organization_id=#{form.organizationId},schedule_version_id=#{form.scheduleVersionId},
                version=version+1,updated_by=#{actorId},updated_at=CURRENT_TIMESTAMP
            WHERE tenant_id=#{tenantId} AND id=#{id} AND version=#{form.expectedVersion} AND state='PLANNED'
            """)
    int edit(@Param("tenantId") UUID tenantId, @Param("id") UUID id,
             @Param("form") Form form, @Param("actorId") UUID actorId);

    @Update("""
            UPDATE tna.employee_onboarding SET state='WITHDRAWN',version=version+1,
                decided_by=#{actorId},decided_at=CURRENT_TIMESTAMP,updated_by=#{actorId},updated_at=CURRENT_TIMESTAMP
            WHERE tenant_id=#{tenantId} AND id=#{id} AND version=#{version} AND state='PLANNED'
            """)
    int withdraw(@Param("tenantId") UUID tenantId, @Param("id") UUID id,
                 @Param("version") long version, @Param("actorId") UUID actorId);

    @Update("""
            UPDATE tna.employee_onboarding SET state='CONFIRMED',version=version+1,
                organization_id=#{organization.id},schedule_version_id=#{schedule.id},employee_id=#{employeeId},
                organization_assignment_id=#{organizationAssignmentId},schedule_assignment_id=#{scheduleAssignmentId},
                organization_code=#{organization.code},organization_name=#{organization.name},
                schedule_template_name=#{template.name},schedule_revision=#{schedule.revision},
                decided_by=#{actorId},decided_at=CURRENT_TIMESTAMP,updated_by=#{actorId},updated_at=CURRENT_TIMESTAMP
            WHERE tenant_id=#{tenantId} AND id=#{id} AND version=#{version} AND state='PLANNED'
            """)
    int confirm(@Param("tenantId") UUID tenantId, @Param("id") UUID id, @Param("version") long version,
                @Param("organization") OrgData.Organization organization,
                @Param("schedule") kr.tna.schedule.ScheduleData.TemplateVersion schedule,
                @Param("template") kr.tna.schedule.ScheduleData.Template template,
                @Param("employeeId") UUID employeeId, @Param("organizationAssignmentId") UUID organizationAssignmentId,
                @Param("scheduleAssignmentId") UUID scheduleAssignmentId, @Param("actorId") UUID actorId);
}
