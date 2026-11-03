package kr.tna.request;

import java.util.List;
import java.util.UUID;
import kr.tna.request.LeaveAttributionData.Attribution;
import org.apache.ibatis.annotations.*;

@Mapper
public interface LeaveAttributionMapper {
    @Select("SELECT pg_advisory_xact_lock_shared(hashtextextended(CAST(#{tenantId} AS text),0))")
    Object lockShared(@Param("tenantId") UUID tenantId);
    @Select("""
        SELECT x.id,x.bucket_id,x.revision,x.attendance_type_id,x.classification_status,x.entitlement_year,
          x.basis,x.reason,x.created_by,e.display_name AS created_by_name,x.created_at
        FROM tna.leave_bucket_attribution x
        JOIN tna.tenant_account a ON a.tenant_id=x.tenant_id AND a.id=x.created_by
        JOIN tna.employee e ON e.tenant_id=a.tenant_id AND e.id=a.employee_id
        WHERE x.tenant_id=#{tenantId} AND x.bucket_id=#{bucketId}
        ORDER BY x.revision DESC
        """)
    List<Attribution> history(@Param("tenantId") UUID tenantId,@Param("bucketId") UUID bucketId);
    @Select("SELECT id FROM tna.attendance_type WHERE tenant_id=#{tenantId} AND system_kind='ANNUAL_LEAVE'")
    UUID annualLeaveType(@Param("tenantId") UUID tenantId);
    @Insert("""
        INSERT INTO tna.leave_bucket_attribution
          (tenant_id,id,bucket_id,revision,attendance_type_id,classification_status,entitlement_year,basis,reason,created_by)
        VALUES(#{tenantId},#{r.id},#{r.bucketId},#{r.revision},#{r.attendanceTypeId},#{r.classificationStatus},#{r.entitlementYear},#{r.basis},#{r.reason},#{r.createdBy})
        """)
    int insert(@Param("tenantId") UUID tenantId,@Param("r") Attribution row);
}
