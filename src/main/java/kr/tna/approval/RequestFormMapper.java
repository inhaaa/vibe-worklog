package kr.tna.approval;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import kr.tna.approval.RequestFormData.*;
import org.apache.ibatis.annotations.*;

@Mapper
public interface RequestFormMapper {
    String FIELDS = """
            v.id,f.id AS form_id,f.code,f.kind,v.revision,v.effective_from,v.enabled,v.name,v.route_version_id,
            v.reason,v.created_by,v.created_at,e.display_name AS changed_by
            """;
    String AUTHOR = """
            LEFT JOIN tna.tenant_account a ON a.tenant_id=v.tenant_id AND a.id=v.created_by
            LEFT JOIN tna.employee e ON e.tenant_id=a.tenant_id AND e.id=a.employee_id
            """;
    String AT_DATE = """
            FROM tna.request_form f JOIN LATERAL (
                SELECT * FROM tna.request_form_version WHERE tenant_id=f.tenant_id AND form_id=f.id
                AND effective_from<=#{date} ORDER BY effective_from DESC,revision DESC LIMIT 1
            ) v ON true
            """;
    @Select("SELECT "+FIELDS+AT_DATE+AUTHOR+" WHERE f.tenant_id=#{tenantId} AND f.kind=#{kind} AND v.enabled")
    Version effective(@Param("tenantId") UUID tenantId,@Param("kind") String kind,@Param("date") LocalDate date);

    @Select("SELECT "+FIELDS+" FROM tna.request_form f JOIN tna.request_form_version v ON v.tenant_id=f.tenant_id AND v.form_id=f.id "
            +AUTHOR+" WHERE f.tenant_id=#{tenantId} AND f.id=#{id} ORDER BY v.effective_from DESC,v.revision DESC")
    List<Version> history(@Param("tenantId") UUID tenantId,@Param("id") UUID id);

    // A future-only code is visible as scheduled, using its first planned name.
    String ROW_SELECT = """
            SELECT f.id,f.code,f.kind,f.version,coalesce(v.name,n.name) AS name,coalesce(v.enabled,false) AS enabled,
                v.route_version_id,r.name AS route_name,r.revision AS route_revision,n.effective_from AS next_from
            FROM tna.request_form f LEFT JOIN LATERAL (
                SELECT * FROM tna.request_form_version WHERE tenant_id=f.tenant_id AND form_id=f.id AND effective_from<=#{date}
                ORDER BY effective_from DESC,revision DESC LIMIT 1
            ) v ON true LEFT JOIN LATERAL (
                SELECT name,effective_from FROM tna.request_form_version WHERE tenant_id=f.tenant_id AND form_id=f.id AND effective_from>#{date}
                ORDER BY effective_from,revision DESC LIMIT 1
            ) n ON true LEFT JOIN tna.approval_route_version r ON r.tenant_id=v.tenant_id AND r.id=v.route_version_id
            WHERE f.tenant_id=#{tenantId}
            """;
    @Select(ROW_SELECT+" AND (strpos(lower(f.code),lower(#{q}))>0 OR strpos(lower(coalesce(v.name,n.name)),lower(#{q}))>0) ORDER BY f.code")
    List<Row> list(@Param("tenantId") UUID tenantId,@Param("q") String q,@Param("date") LocalDate date);
    @Select(ROW_SELECT+" AND f.id=#{id}")
    Row row(@Param("tenantId") UUID tenantId,@Param("id") UUID id,@Param("date") LocalDate date);
    @Select("SELECT id,code,kind,version FROM tna.request_form WHERE tenant_id=#{tenantId} AND id=#{id}")
    Master master(@Param("tenantId") UUID tenantId,@Param("id") UUID id);
    @Select("""
            SELECT min(v.effective_from) FROM tna.request_form_version v JOIN tna.request_form f ON f.tenant_id=v.tenant_id AND f.id=v.form_id
            WHERE f.tenant_id=#{tenantId} AND f.kind=#{kind} AND v.effective_from>#{date}
            """)
    LocalDate nextSchedule(@Param("tenantId") UUID tenantId,@Param("kind") String kind,@Param("date") LocalDate date);
    @Select("""
            SELECT v.id,r.code,v.name,v.revision,v.approver_kind,v.terminal_unit,r.active
            FROM tna.approval_route_version v JOIN tna.approval_route r ON r.tenant_id=v.tenant_id AND r.id=v.route_id
            WHERE v.tenant_id=#{tenantId} ORDER BY r.code,v.revision DESC
            """)
    List<RouteOption> routes(@Param("tenantId") UUID tenantId);
    @Select("SELECT id,employee_no,display_name AS name FROM tna.employee WHERE tenant_id=#{tenantId} ORDER BY employee_no,id")
    List<EmployeeOption> employees(@Param("tenantId") UUID tenantId);
    @Insert("INSERT INTO tna.request_form(tenant_id,id,code,kind) VALUES(#{tenantId},#{id},#{code},#{kind})")
    int create(@Param("tenantId") UUID tenantId,@Param("id") UUID id,@Param("code") String code,@Param("kind") String kind);
    @Update("UPDATE tna.request_form SET version=version+1 WHERE tenant_id=#{tenantId} AND id=#{id} AND version=#{version}")
    int advance(@Param("tenantId") UUID tenantId,@Param("id") UUID id,@Param("version") long version);
    @Insert("""
            INSERT INTO tna.request_form_version(tenant_id,id,form_id,revision,effective_from,enabled,name,route_version_id,reason,created_by)
            SELECT #{tenantId},#{uuid},#{formId},coalesce(max(revision),0)+1,#{date},#{enabled},#{name},#{route},#{reason},#{actor}
            FROM tna.request_form_version WHERE tenant_id=#{tenantId} AND form_id=#{formId}
            """)
    int append(@Param("tenantId") UUID tenantId,@Param("uuid") UUID uuid,@Param("formId") UUID formId,
               @Param("date") LocalDate date,@Param("enabled") boolean enabled,@Param("name") String name,
               @Param("route") UUID route,@Param("reason") String reason,@Param("actor") UUID actor);
}
