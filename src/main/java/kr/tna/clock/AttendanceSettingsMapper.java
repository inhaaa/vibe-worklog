package kr.tna.clock;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import kr.tna.clock.AttendanceSettingsData.*;
import org.apache.ibatis.annotations.*;

@Mapper
public interface AttendanceSettingsMapper {
    @Select("SELECT id,network::text AS network,label,valid_from,valid_to,reason,version FROM tna.allowed_network WHERE tenant_id=#{tenantId} ORDER BY valid_from DESC,id")
    List<Network> networks(@Param("tenantId") UUID tenantId);
    @Select("SELECT id,network::text AS network,label,valid_from,valid_to,reason,version FROM tna.allowed_network WHERE tenant_id=#{tenantId} AND id=#{id}")
    Network network(@Param("tenantId") UUID tenantId,@Param("id") UUID id);
    @Select("SELECT EXISTS(SELECT 1 FROM tna.allowed_network WHERE tenant_id=#{tenantId} AND network=CAST(#{network} AS cidr) AND valid_to IS NULL)")
    boolean duplicate(@Param("tenantId") UUID tenantId,@Param("network") String network);
    @Insert("""
            INSERT INTO tna.allowed_network(tenant_id,id,network,label,valid_from,reason,created_by)
            VALUES (#{tenantId},#{id},CAST(#{network} AS cidr),#{label},#{now},#{reason},#{actorId})
            """)
    int addNetwork(@Param("tenantId") UUID tenantId,@Param("id") UUID id,@Param("network") String network,
            @Param("label") String label,@Param("now") Instant now,@Param("reason") String reason,@Param("actorId") UUID actorId);
    @Update("UPDATE tna.allowed_network SET valid_to=#{now},version=version+1 WHERE tenant_id=#{tenantId} AND id=#{id} AND version=#{version} AND valid_to IS NULL")
    int closeNetwork(@Param("tenantId") UUID tenantId,@Param("id") UUID id,@Param("version") long version,@Param("now") Instant now);
}
