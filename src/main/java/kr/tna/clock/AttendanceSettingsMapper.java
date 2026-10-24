package kr.tna.clock;

import java.time.Instant;
import java.time.LocalDate;
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
    @Select("SELECT id,revision,valid_from,valid_to,reason FROM tna.calendar_version WHERE tenant_id=#{tenantId} ORDER BY revision DESC,id")
    List<CalendarVersion> calendars(@Param("tenantId") UUID tenantId);
    @Select("SELECT COALESCE(max(revision),0)+1 FROM tna.calendar_version WHERE tenant_id=#{tenantId}")
    int nextRevision(@Param("tenantId") UUID tenantId);
    @Insert("""
            INSERT INTO tna.calendar_version(tenant_id,id,revision,valid_from,valid_to,reason,created_by)
            VALUES (#{tenantId},#{id},#{revision},#{from},#{to},#{reason},#{actorId})
            """)
    int calendar(@Param("tenantId") UUID tenantId,@Param("id") UUID id,@Param("revision") int revision,
            @Param("from") LocalDate from,@Param("to") LocalDate to,@Param("reason") String reason,@Param("actorId") UUID actorId);
    @Insert("""
            INSERT INTO tna.calendar_day(tenant_id,version_id,work_date,kind,name,basis)
            VALUES (#{tenantId},#{versionId},#{date},#{kind},#{name},#{basis})
            """)
    int day(@Param("tenantId") UUID tenantId,@Param("versionId") UUID versionId,@Param("date") LocalDate date,
            @Param("kind") String kind,@Param("name") String name,@Param("basis") String basis);
}
