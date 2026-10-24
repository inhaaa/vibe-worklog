package kr.tna.schedule;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;
import kr.tna.schedule.ScheduleData.*;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface ScheduleMapper {
    @Select("SELECT id,code,name,active,version FROM tna.schedule_template WHERE tenant_id=#{tenantId} ORDER BY code,id")
    List<Template> list(@Param("tenantId") UUID tenantId);

    @Select("SELECT id,code,name,active,version FROM tna.schedule_template WHERE tenant_id=#{tenantId} AND id=#{id}")
    Template template(@Param("tenantId") UUID tenantId,@Param("id") UUID id);

    @Select("SELECT id,template_id,revision,valid_from,reason FROM tna.schedule_version WHERE tenant_id=#{tenantId} AND template_id=#{templateId} ORDER BY revision DESC,id")
    List<TemplateVersion> versions(@Param("tenantId") UUID tenantId,@Param("templateId") UUID templateId);

    @Select("SELECT id,template_id,revision,valid_from,reason FROM tna.schedule_version WHERE tenant_id=#{tenantId} AND id=#{id}")
    TemplateVersion version(@Param("tenantId") UUID tenantId,@Param("id") UUID id);

    @Select("""
            SELECT v.id,v.template_id,v.revision,v.valid_from,v.reason FROM tna.schedule_version v
            JOIN tna.schedule_template t ON t.tenant_id=v.tenant_id AND t.id=v.template_id
            WHERE v.tenant_id=#{tenantId} AND t.active ORDER BY t.code,v.revision DESC,v.id
            """)
    List<TemplateVersion> assignableVersions(@Param("tenantId") UUID tenantId);

    @Select("SELECT weekday,workday,start_time,end_time FROM tna.schedule_day WHERE tenant_id=#{tenantId} AND version_id=#{versionId} ORDER BY weekday")
    List<DayRow> days(@Param("tenantId") UUID tenantId,@Param("versionId") UUID versionId);

    @Select("SELECT start_time,end_time FROM tna.schedule_break WHERE tenant_id=#{tenantId} AND version_id=#{versionId} AND weekday=#{weekday} ORDER BY sequence")
    List<Break> breaks(@Param("tenantId") UUID tenantId,@Param("versionId") UUID versionId,@Param("weekday") int weekday);

    @Insert("""
            INSERT INTO tna.schedule_template(tenant_id,id,code,name,created_by,updated_by)
            VALUES (#{tenantId},#{id},#{code},#{name},#{actorId},#{actorId})
            """)
    int insertTemplate(@Param("tenantId") UUID tenantId,@Param("id") UUID id,@Param("code") String code,
                       @Param("name") String name,@Param("actorId") UUID actorId);

    @Update("""
            UPDATE tna.schedule_template SET name=#{name},version=version+1,updated_at=CURRENT_TIMESTAMP,updated_by=#{actorId}
            WHERE tenant_id=#{tenantId} AND id=#{id} AND version=#{expectedVersion}
            """)
    int updateTemplate(@Param("tenantId") UUID tenantId,@Param("id") UUID id,@Param("name") String name,
                       @Param("expectedVersion") long expectedVersion,@Param("actorId") UUID actorId);

    @Insert("""
            INSERT INTO tna.schedule_version(tenant_id,id,template_id,revision,valid_from,reason,created_by)
            VALUES (#{tenantId},#{version.id},#{version.templateId},#{version.revision},#{version.validFrom},#{version.reason},#{actorId})
            """)
    int insertVersion(@Param("tenantId") UUID tenantId,@Param("version") TemplateVersion version,@Param("actorId") UUID actorId);

    @Insert("""
            INSERT INTO tna.schedule_day(tenant_id,version_id,weekday,workday,start_time,end_time)
            VALUES (#{tenantId},#{versionId},#{day.weekday},#{day.workday},#{day.startTime},#{day.endTime})
            """)
    int insertDay(@Param("tenantId") UUID tenantId,@Param("versionId") UUID versionId,@Param("day") Day day);

    @Insert("""
            INSERT INTO tna.schedule_break(tenant_id,version_id,weekday,sequence,start_time,end_time)
            VALUES (#{tenantId},#{versionId},#{weekday},#{sequence},#{interval.startTime},#{interval.endTime})
            """)
    int insertBreak(@Param("tenantId") UUID tenantId,@Param("versionId") UUID versionId,@Param("weekday") int weekday,
                    @Param("sequence") int sequence,@Param("interval") Break interval);

    @Select("""
            SELECT a.id,a.employee_id,a.schedule_version_id,t.name AS template_name,v.revision,
                   a.valid_from,a.valid_to,a.reason,a.version FROM tna.schedule_assignment a
            JOIN tna.schedule_version v ON v.tenant_id=a.tenant_id AND v.id=a.schedule_version_id
            JOIN tna.schedule_template t ON t.tenant_id=v.tenant_id AND t.id=v.template_id
            WHERE a.tenant_id=#{tenantId} AND a.employee_id=#{employeeId} ORDER BY a.valid_from DESC,a.id
            """)
    List<Assignment> assignments(@Param("tenantId") UUID tenantId,@Param("employeeId") UUID employeeId);

    @Update("""
            UPDATE tna.schedule_assignment SET valid_to=#{validTo},version=version+1,
                updated_at=CURRENT_TIMESTAMP,updated_by=#{actorId}
            WHERE tenant_id=#{tenantId} AND id=#{id} AND valid_to IS NULL AND version=#{expectedVersion}
            """)
    int closeAssignment(@Param("tenantId") UUID tenantId,@Param("id") UUID id,@Param("validTo") LocalDate validTo,
                        @Param("expectedVersion") long expectedVersion,@Param("actorId") UUID actorId);

    @Insert("""
            INSERT INTO tna.schedule_assignment(tenant_id,id,employee_id,schedule_version_id,valid_from,reason,created_by,updated_by)
            VALUES (#{tenantId},#{id},#{employeeId},#{versionId},#{validFrom},#{reason},#{actorId},#{actorId})
            """)
    int insertAssignment(@Param("tenantId") UUID tenantId,@Param("id") UUID id,@Param("employeeId") UUID employeeId,
                         @Param("versionId") UUID versionId,@Param("validFrom") LocalDate validFrom,
                         @Param("reason") String reason,@Param("actorId") UUID actorId);

    record DayRow(int weekday,boolean workday,LocalTime startTime,LocalTime endTime) { }
}
