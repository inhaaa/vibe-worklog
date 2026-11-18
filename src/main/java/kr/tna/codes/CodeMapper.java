package kr.tna.codes;

import java.util.List;
import java.util.UUID;
import kr.tna.codes.CodeData.*;
import org.apache.ibatis.annotations.*;

@Mapper
public interface CodeMapper {
    @Select("SELECT pg_advisory_xact_lock_shared(hashtextextended(CAST(#{tenantId} AS text),0))")
    Object lock(@Param("tenantId") UUID tenantId);
    String GROUP_SELECT="SELECT id,code,name,description,sort_order,active,system_owned,version FROM tna.common_code_group WHERE tenant_id=#{tenantId}";
    @Select(GROUP_SELECT+" AND (#{q}='' OR strpos(lower(code),lower(#{q}))>0 OR strpos(lower(name),lower(#{q}))>0) ORDER BY sort_order,code,id LIMIT 51 OFFSET #{offset}")
    List<Group> groups(@Param("tenantId")UUID tenantId,@Param("q")String q,@Param("offset")int offset);
    @Select(GROUP_SELECT+" AND id=#{id}") Group group(@Param("tenantId")UUID tenantId,@Param("id")UUID id);
    @Select(GROUP_SELECT+" AND code=#{code}") Group groupCode(@Param("tenantId")UUID tenantId,@Param("code")String code);
    @Insert("INSERT INTO tna.common_code_group(tenant_id,id,code,name,description,sort_order,active,created_by,updated_by) VALUES(#{tenantId},#{r.id},#{r.code},#{r.name},#{r.description},#{r.sortOrder},#{r.active},#{actor},#{actor})")
    int insertGroup(@Param("tenantId")UUID tenantId,@Param("r")Group group,@Param("actor")UUID actor);
    @Update("UPDATE tna.common_code_group SET name=#{r.name},description=#{r.description},sort_order=#{r.sortOrder},active=#{r.active},version=version+1,updated_at=CURRENT_TIMESTAMP,updated_by=#{actor} WHERE tenant_id=#{tenantId} AND id=#{r.id} AND version=#{expected} AND NOT system_owned")
    int updateGroup(@Param("tenantId")UUID tenantId,@Param("r")Group group,@Param("expected")long expected,@Param("actor")UUID actor);
    String CODE_SELECT="SELECT id,group_id,code,name,description,sort_order,active,system_owned,version FROM tna.common_code WHERE tenant_id=#{tenantId}";
    @Select(CODE_SELECT+" AND group_id=#{groupId} AND (#{q}='' OR strpos(lower(code),lower(#{q}))>0 OR strpos(lower(name),lower(#{q}))>0) ORDER BY sort_order,code,id LIMIT 51 OFFSET #{offset}")
    List<Code> codes(@Param("tenantId")UUID tenantId,@Param("groupId")UUID groupId,@Param("q")String q,@Param("offset")int offset);
    @Select(CODE_SELECT+" AND id=#{id}") Code code(@Param("tenantId")UUID tenantId,@Param("id")UUID id);
    @Select(CODE_SELECT+" AND group_id=#{groupId} AND code=#{code}") Code codeValue(@Param("tenantId")UUID tenantId,@Param("groupId")UUID groupId,@Param("code")String code);
    @Insert("INSERT INTO tna.common_code(tenant_id,id,group_id,code,name,description,sort_order,active,created_by,updated_by) VALUES(#{tenantId},#{r.id},#{r.groupId},#{r.code},#{r.name},#{r.description},#{r.sortOrder},#{r.active},#{actor},#{actor})")
    int insertCode(@Param("tenantId")UUID tenantId,@Param("r")Code code,@Param("actor")UUID actor);
    @Update("UPDATE tna.common_code SET name=#{r.name},description=#{r.description},sort_order=#{r.sortOrder},active=#{r.active},version=version+1,updated_at=CURRENT_TIMESTAMP,updated_by=#{actor} WHERE tenant_id=#{tenantId} AND id=#{r.id} AND version=#{expected} AND NOT system_owned")
    int updateCode(@Param("tenantId")UUID tenantId,@Param("r")Code code,@Param("expected")long expected,@Param("actor")UUID actor);

    String TYPE_SELECT="""
        SELECT t.id,t.code,t.system_kind,t.active,t.version,v.id AS type_version_id,v.revision,v.effective_from,v.enabled,v.name,
         v.unit,v.pay_kind,v.deduction_rule,v.recognition_rule,v.basis,t.system_kind IS NOT NULL AS supported,
         CASE t.system_kind WHEN 'ANNUAL_LEAVE' THEN '종일 1일·반차 0.5일, 최종 승인 시 차감'
          WHEN 'ATTENDANCE_CORRECTION' THEN '기존 출퇴근·휴게 정정 절차' ELSE '계산·차감 계약 미지원: 초안만 등록 가능' END AS capability_message
        FROM tna.attendance_type t JOIN LATERAL (
         SELECT * FROM tna.attendance_type_version WHERE tenant_id=t.tenant_id AND type_id=t.id ORDER BY revision DESC LIMIT 1
        )v ON true WHERE t.tenant_id=#{tenantId}
        """;
    @Select(TYPE_SELECT+"""
        AND (#{q}='' OR strpos(lower(t.code),lower(#{q}))>0 OR strpos(lower(v.name),lower(#{q}))>0
             OR strpos(lower(t.system_kind),lower(#{q}))>0)
        ORDER BY t.code,t.id LIMIT 51 OFFSET #{offset}
        """)
    List<Type> types(@Param("tenantId")UUID tenantId,@Param("q")String q,@Param("offset")int offset);
    @Select(TYPE_SELECT+" AND t.id=#{id}") Type type(@Param("tenantId")UUID tenantId,@Param("id")UUID id);
    @Select("SELECT EXISTS(SELECT 1 FROM tna.attendance_type WHERE tenant_id=#{tenantId} AND upper(code)=#{code})")
    boolean typeCodeExists(@Param("tenantId")UUID tenantId,@Param("code")String code);
    @Select("""
        SELECT v.id,v.type_id,t.code,v.revision,v.effective_from,v.enabled,v.name,v.unit,v.pay_kind,v.deduction_rule,
         v.recognition_rule,v.basis,v.reason,v.created_at,v.created_by,e.display_name AS created_by_name
        FROM tna.attendance_type_version v JOIN tna.attendance_type t ON t.tenant_id=v.tenant_id AND t.id=v.type_id
         LEFT JOIN tna.tenant_account a ON a.tenant_id=v.tenant_id AND a.id=v.created_by
         LEFT JOIN tna.employee e ON e.tenant_id=a.tenant_id AND e.id=a.employee_id
        WHERE v.tenant_id=#{tenantId} AND v.type_id=#{typeId} ORDER BY v.effective_from DESC,v.revision DESC
        """)
    List<TypeVersion> versions(@Param("tenantId")UUID tenantId,@Param("typeId")UUID typeId);
    @Insert("INSERT INTO tna.attendance_type(tenant_id,id,code,active,created_by,updated_by) VALUES(#{tenantId},#{r.id},#{r.code},#{r.active},#{actor},#{actor})")
    int insertType(@Param("tenantId")UUID tenantId,@Param("r")Type type,@Param("actor")UUID actor);
    @Update("UPDATE tna.attendance_type SET active=#{r.active},version=version+1,updated_at=CURRENT_TIMESTAMP,updated_by=#{actor} WHERE tenant_id=#{tenantId} AND id=#{r.id} AND version=#{expected}")
    int updateType(@Param("tenantId")UUID tenantId,@Param("r")Type type,@Param("expected")long expected,@Param("actor")UUID actor);
    @Insert("""
        INSERT INTO tna.attendance_type_version(tenant_id,id,type_id,revision,effective_from,enabled,name,unit,pay_kind,deduction_rule,recognition_rule,basis,reason,created_by)
        VALUES(#{tenantId},#{r.typeVersionId},#{r.id},#{r.revision},#{r.effectiveFrom},#{r.enabled},#{r.name},#{r.unit},#{r.payKind},#{r.deductionRule},#{r.recognitionRule},#{r.basis},#{reason},#{actor})
        """)
    int insertTypeVersion(@Param("tenantId")UUID tenantId,@Param("r")Type type,@Param("reason")String reason,@Param("actor")UUID actor);
}
