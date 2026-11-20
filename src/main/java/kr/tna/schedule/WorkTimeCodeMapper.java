package kr.tna.schedule;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import kr.tna.schedule.WorkTimeCodeData.*;
import org.apache.ibatis.annotations.*;

@Mapper
public interface WorkTimeCodeMapper {
    @Select("SELECT pg_advisory_xact_lock_shared(hashtextextended(CAST(#{tenant} AS text),0))")
    Object lock(UUID tenant);

    @Select("SELECT id,code,version FROM tna.work_time_code WHERE tenant_id=#{tenant} ORDER BY code")
    List<Master> masters(UUID tenant);

    @Select("SELECT id,code,version FROM tna.work_time_code WHERE tenant_id=#{tenant} AND id=#{id}")
    Master master(@Param("tenant") UUID tenant,@Param("id") UUID id);

    @Select("""
            SELECT id,code_id,revision,effective_from,enabled,name,start_time,end_time,before_start,before_end,
                   after_start,after_end,reason,work_mode,core_start,core_end,standard_minutes,settlement_period
            FROM tna.work_time_code_version WHERE tenant_id=#{tenant} ORDER BY effective_from DESC,revision DESC
            """)
    List<VersionRow> versions(UUID tenant);

    @Select("""
            SELECT version_id,kind,start_time,end_time FROM tna.work_time_code_break
            WHERE tenant_id=#{tenant} ORDER BY version_id,start_time
            """)
    List<BreakRow> breaks(UUID tenant);

    @Select("""
            SELECT DISTINCT d.code_version_id AS version_id,t.id AS group_id,t.code,t.name
            FROM tna.work_group_revision_period r
            JOIN tna.work_group_day d ON d.tenant_id=r.tenant_id AND d.schedule_version_id=r.schedule_version_id
            JOIN tna.schedule_template t ON t.tenant_id=r.tenant_id AND t.id=r.group_id
            WHERE r.tenant_id=#{tenant} AND r.valid_from<=#{date} AND (r.valid_to IS NULL OR r.valid_to>#{date})
            ORDER BY t.code,t.name
            """)
    List<GroupUse> groupUses(@Param("tenant") UUID tenant,@Param("date") LocalDate date);

    @Select("""
            SELECT id FROM tna.work_time_code_version WHERE tenant_id=#{tenant} AND code_id=#{id}
              AND effective_from=#{date} ORDER BY revision DESC LIMIT 1
            """)
    UUID scheduledVersion(@Param("tenant") UUID tenant,@Param("id") UUID id,@Param("date") LocalDate date);

    @Insert("INSERT INTO tna.work_time_code(tenant_id,id,code) VALUES (#{tenant},#{id},#{code})")
    void create(@Param("tenant") UUID tenant,@Param("id") UUID id,@Param("code") String code);

    @Update("UPDATE tna.work_time_code SET version=version+1 WHERE tenant_id=#{tenant} AND id=#{id} AND version=#{expected}")
    int advance(@Param("tenant") UUID tenant,@Param("id") UUID id,@Param("expected") long expected);

    @Insert("""
            INSERT INTO tna.work_time_code_version(tenant_id,id,code_id,revision,effective_from,enabled,name,start_time,
                end_time,before_start,before_end,after_start,after_end,reason,created_by,
                work_mode,core_start,core_end,standard_minutes,settlement_period)
            VALUES (#{tenant},#{id},#{codeId},#{revision},#{c.effectiveFrom},#{c.enabled},#{name},#{c.startTime},
                #{c.endTime},#{c.beforeStart},#{c.beforeEnd},#{c.afterStart},#{c.afterEnd},#{c.reason},#{actor},
                #{c.workMode},#{c.coreStart},#{c.coreEnd},#{c.standardMinutes},#{c.settlementPeriod})
            """)
    void append(@Param("tenant") UUID tenant,@Param("id") UUID id,@Param("codeId") UUID codeId,
            @Param("revision") long revision,@Param("name") String name,@Param("c") Command c,@Param("actor") UUID actor);

    @Insert("""
            INSERT INTO tna.work_time_code_break(tenant_id,version_id,kind,start_time,end_time)
            VALUES (#{tenant},#{id},#{b.kind},#{b.start},#{b.end})
            """)
    void addBreak(@Param("tenant") UUID tenant,@Param("id") UUID id,@Param("b") Break b);
}
