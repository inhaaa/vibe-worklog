package kr.tna.org;

import java.util.List;
import java.util.UUID;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface EmployeeImportMapper {
    record Organization(UUID id, String code, String name) { }

    @Select("SELECT id, code, name FROM tna.organization WHERE tenant_id=#{tenantId} AND active ORDER BY code, id")
    List<Organization> activeOrganizations(@Param("tenantId") UUID tenantId);

    @Select("SELECT after_state::text FROM tna.org_audit_event WHERE tenant_id=#{tenantId} AND command_id=#{commandId}")
    String result(@Param("tenantId") UUID tenantId, @Param("commandId") UUID commandId);
}
