package kr.tna.org;

import java.util.UUID;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface EmployeeMapper {
    @Select("""
            SELECT e.display_name AS employee_name, r.display_name AS company_name
            FROM tna.employee e
            JOIN platform.tenant_registry r ON r.id = e.tenant_id
            WHERE e.tenant_id = #{tenantId} AND e.id = #{employeeId} AND e.active AND r.active
            """)
    EmployeeOverview findSelf(@Param("tenantId") UUID tenantId, @Param("employeeId") UUID employeeId);
}
