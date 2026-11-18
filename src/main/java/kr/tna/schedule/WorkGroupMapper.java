package kr.tna.schedule;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;
import kr.tna.schedule.ScheduleData.Break;
import kr.tna.schedule.ScheduleData.Template;
import kr.tna.schedule.WorkGroupData.*;
import org.apache.ibatis.annotations.*;

@Mapper
public interface WorkGroupMapper {
    @Select("""
            SELECT t.id,t.code,t.name,t.active,t.version FROM tna.work_group g
            JOIN tna.schedule_template t ON t.tenant_id=g.tenant_id AND t.id=g.id
            WHERE g.tenant_id=#{tenant} ORDER BY t.code
            """)
    List<Template> groups(UUID tenant);

    @Select("""
            SELECT t.id,t.code,t.name,t.active,t.version FROM tna.work_group g
            JOIN tna.schedule_template t ON t.tenant_id=g.tenant_id AND t.id=g.id
            WHERE g.tenant_id=#{tenant} AND g.id=#{id}
            """)
    Template group(UUID tenant, UUID id);

    @Select("SELECT EXISTS(SELECT 1 FROM tna.schedule_template WHERE tenant_id=#{tenant} AND code=#{code})")
    boolean usedCode(UUID tenant, String code);

    @Select("""
            SELECT v.id,v.revision,v.valid_from,v.reason FROM tna.schedule_version v
            JOIN tna.work_group g ON g.tenant_id=v.tenant_id AND g.id=v.template_id
            WHERE v.tenant_id=#{tenant} AND g.id=#{group} ORDER BY v.valid_from DESC,v.revision DESC
            """)
    List<Revision> revisions(UUID tenant, UUID group);

    @Select("""
            SELECT d.weekday,gd.code_version_id,c.code,d.start_time,d.end_time
            FROM tna.schedule_day d
            LEFT JOIN tna.work_group_day gd ON gd.tenant_id=d.tenant_id AND gd.schedule_version_id=d.version_id AND gd.weekday=d.weekday
            LEFT JOIN tna.work_time_code_version v ON v.tenant_id=gd.tenant_id AND v.id=gd.code_version_id
            LEFT JOIN tna.work_time_code c ON c.tenant_id=v.tenant_id AND c.id=v.code_id
            WHERE d.tenant_id=#{tenant} AND d.version_id=#{version} ORDER BY d.weekday
            """)
    List<DayTimes> days(UUID tenant, UUID version);

    @Select("""
            SELECT v.id,c.code,v.start_time,v.end_time,v.effective_from,v.enabled,
                   (SELECT u.id FROM tna.work_time_code_version u WHERE u.tenant_id=v.tenant_id
                    AND u.code_id=v.code_id AND u.effective_from<=#{date}
                    ORDER BY u.effective_from DESC,u.revision DESC LIMIT 1) AS current_version
            FROM tna.work_time_code_version v JOIN tna.work_time_code c ON c.tenant_id=v.tenant_id AND c.id=v.code_id
            WHERE v.tenant_id=#{tenant} AND v.id=#{id}
            """)
    CodeTimes code(UUID tenant, UUID id, LocalDate date);

    @Select("""
            SELECT start_time,end_time FROM tna.work_time_code_break
            WHERE tenant_id=#{tenant} AND version_id=#{id} AND kind='REGULAR' ORDER BY start_time
            """)
    List<Break> codeBreaks(UUID tenant, UUID id);

    @Insert("INSERT INTO tna.work_group(tenant_id,id) VALUES (#{tenant},#{id})")
    int insertGroup(UUID tenant, UUID id);

    @Insert("""
            INSERT INTO tna.work_group_day(tenant_id,schedule_version_id,weekday,code_version_id)
            VALUES (#{tenant},#{version},#{weekday},#{codeVersion})
            """)
    int bindDay(UUID tenant, UUID version, int weekday, UUID codeVersion);

    @Select("""
            SELECT DISTINCT a.employee_id FROM tna.schedule_assignment a
            WHERE a.tenant_id=#{tenant} AND a.work_group_id=#{group}
              AND (a.valid_to IS NULL OR a.valid_to>#{date})
              AND a.valid_from<COALESCE((
                    SELECT min(r.valid_from) FROM tna.schedule_version r WHERE r.tenant_id=#{tenant} AND r.template_id=#{group}
                    AND r.valid_from>#{date}), 'infinity'::date)
            """)
    List<UUID> membersUntilNextRevision(UUID tenant, UUID group, LocalDate date);

    String EMPLOYEE_SELECT = """
            SELECT e.id,e.employee_no AS number,e.display_name AS name,o.name AS organization,
                   CASE WHEN a.id IS NULL THEN NULL WHEN a.work_group_id IS NULL THEN '기존 고정배정 · '||t.name
                        ELSE t.name END AS current_group,e.version
            FROM tna.employee e
            LEFT JOIN tna.employee_assignment org ON org.tenant_id=e.tenant_id AND org.employee_id=e.id
                AND org.valid_from<=#{date} AND (org.valid_to IS NULL OR org.valid_to>#{date})
            LEFT JOIN tna.organization o ON o.tenant_id=org.tenant_id AND o.id=org.organization_id
            LEFT JOIN tna.schedule_assignment a ON a.tenant_id=e.tenant_id AND a.employee_id=e.id
                AND a.valid_from<=#{date} AND (a.valid_to IS NULL OR a.valid_to>#{date})
            LEFT JOIN tna.schedule_version v ON v.tenant_id=a.tenant_id AND v.id=a.schedule_version_id
            LEFT JOIN tna.schedule_template t ON t.tenant_id=v.tenant_id AND t.id=v.template_id
            WHERE e.tenant_id=#{tenant}
            """;
    @Select(EMPLOYEE_SELECT + """
            AND e.active AND e.joined_on<=#{date} AND (e.left_on IS NULL OR e.left_on>=#{date})
            AND (strpos(lower(e.employee_no),lower(#{q}))>0 OR strpos(lower(e.display_name),lower(#{q}))>0
                 OR strpos(lower(o.name),lower(#{q}))>0)
            ORDER BY e.employee_no,e.id LIMIT 50 OFFSET #{offset}
            """)
    List<Employee> employees(UUID tenant, LocalDate date, String q, int offset);

    @Select(EMPLOYEE_SELECT + " AND e.id=#{id}")
    Employee employee(UUID tenant, UUID id, LocalDate date);

    @Select(EMPLOYEE_SELECT + """
            AND a.work_group_id=#{group} AND e.active AND e.joined_on<=#{date} AND (e.left_on IS NULL OR e.left_on>=#{date})
            ORDER BY e.employee_no,e.id
            """)
    List<Employee> affected(UUID tenant, UUID group, LocalDate date);

    @Select(EMPLOYEE_SELECT + """
            AND a.id IS NOT NULL AND a.work_group_id IS DISTINCT FROM #{group}
            AND EXISTS(SELECT 1 FROM tna.schedule_assignment h WHERE h.tenant_id=e.tenant_id AND h.employee_id=e.id
                       AND h.work_group_id=#{group} AND h.valid_to=#{date})
            ORDER BY e.employee_no,e.id
            """)
    List<Employee> departing(UUID tenant, UUID group, LocalDate date);

    @Select(EMPLOYEE_SELECT + """
            AND EXISTS(SELECT 1 FROM tna.schedule_assignment h WHERE h.tenant_id=e.tenant_id AND h.employee_id=e.id
                       AND h.work_group_id=#{group} AND h.valid_from>#{date}
                       AND (CAST(#{until} AS date) IS NULL OR h.valid_from<#{until})
                       AND e.active AND e.joined_on<=h.valid_from AND (e.left_on IS NULL OR e.left_on>=h.valid_from))
            ORDER BY e.employee_no,e.id
            """)
    List<Employee> incoming(UUID tenant, UUID group, LocalDate date, LocalDate until);

    @Select("""
            SELECT a.id,a.work_group_id,a.schedule_version_id,a.valid_from,a.valid_to,a.version
            FROM tna.schedule_assignment a WHERE a.tenant_id=#{tenant} AND a.employee_id=#{employee}
              AND a.valid_from<=#{date} AND (a.valid_to IS NULL OR a.valid_to>#{date})
            """)
    Period period(UUID tenant, UUID employee, LocalDate date);

    @Select("""
            SELECT min(valid_from) FROM tna.schedule_assignment WHERE tenant_id=#{tenant}
            AND employee_id=#{employee} AND valid_from>#{date}
            """)
    LocalDate next(UUID tenant, UUID employee, LocalDate date);

    @Select("""
            SELECT d.start_time FROM tna.effective_schedule_assignment a
            JOIN tna.schedule_day d ON d.tenant_id=a.tenant_id AND d.version_id=a.schedule_version_id
            WHERE a.tenant_id=#{tenant} AND a.employee_id=#{employee} AND a.valid_from<=#{date}
            AND (a.valid_to IS NULL OR a.valid_to>#{date}) AND d.weekday=#{weekday}
            """)
    LocalTime scheduledStart(UUID tenant, UUID employee, LocalDate date, int weekday);

    @Select("""
            SELECT EXISTS (SELECT 1 FROM tna.attendance_event WHERE tenant_id=#{tenant} AND employee_id=#{employee}
                           AND (assigned_work_date=#{date} OR (assigned_work_date IS NULL
                            AND (occurred_at AT TIME ZONE 'Asia/Seoul')::date=#{date})))
                OR EXISTS (SELECT 1 FROM tna.work_confirmation WHERE tenant_id=#{tenant} AND employee_id=#{employee} AND work_date=#{date})
                OR EXISTS (SELECT 1 FROM tna.approval_request WHERE tenant_id=#{tenant} AND target_employee_id=#{employee}
                           AND kind='ATTENDANCE_CORRECTION' AND status='APPROVED' AND from_date=#{date})
            """)
    boolean hasFacts(UUID tenant, UUID employee, LocalDate date);

    @Update("""
            UPDATE tna.schedule_assignment SET valid_to=#{date}, version=version+1, updated_at=CURRENT_TIMESTAMP,updated_by=#{actor}
            WHERE tenant_id=#{tenant} AND id=#{id} AND version=#{version}
            """)
    int shorten(UUID tenant, UUID id, LocalDate date, long version, UUID actor);

    @Update("""
            UPDATE tna.schedule_assignment SET work_group_id=#{group},schedule_version_id=#{schedule},reason=#{reason},valid_to=#{until},
                version=version+1,updated_at=CURRENT_TIMESTAMP,updated_by=#{actor}
            WHERE tenant_id=#{tenant} AND id=#{id} AND version=#{version}
            """)
    int replace(UUID tenant, UUID id, UUID group, UUID schedule, LocalDate until, String reason, long version, UUID actor);

    @Insert("""
            INSERT INTO tna.schedule_assignment(tenant_id,id,employee_id,work_group_id,schedule_version_id,
                valid_from,valid_to,reason,created_by,updated_by)
            VALUES (#{tenant},#{id},#{employee},#{group},#{schedule},#{date},#{until},#{reason},#{actor},#{actor})
            """)
    int assign(UUID tenant, UUID id, UUID employee, UUID group, UUID schedule, LocalDate date, LocalDate until, String reason, UUID actor);

    record CodeTimes(UUID id,String code,LocalTime startTime,LocalTime endTime,LocalDate effectiveFrom,boolean enabled,UUID currentVersion) {}
    record DayTimes(int weekday, UUID codeVersionId, String code, LocalTime startTime, LocalTime endTime) {}
    record Period(UUID id, UUID workGroupId, UUID scheduleVersionId, LocalDate validFrom, LocalDate validTo, long version) {}
}
