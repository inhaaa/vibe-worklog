package kr.tna.workspace;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;
import kr.tna.schedule.WorkTimeCodeData.Break;
import kr.tna.schedule.WorkTimeCodeData;
import kr.tna.schedule.WorkTimeCodeRules;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface PersonalScheduleMapper {
    @Select("""
            SELECT (g.id IS NOT NULL) AS "group",t.name,t.code,c.code AS work_code,v.name AS work_name,
                   v.id AS code_version_id,v.start_time,v.end_time,v.before_start,v.before_end,v.after_start,v.after_end,
                   COALESCE(v.work_mode,'FIXED') AS work_mode,v.core_start,v.core_end,v.standard_minutes,v.settlement_period
            FROM tna.schedule_version s
            JOIN tna.schedule_template t ON t.tenant_id=s.tenant_id AND t.id=s.template_id
            LEFT JOIN tna.work_group g ON g.tenant_id=t.tenant_id AND g.id=t.id
            LEFT JOIN tna.work_group_day d ON d.tenant_id=s.tenant_id AND d.schedule_version_id=s.id AND d.weekday=#{weekday}
            LEFT JOIN tna.work_time_code_version v ON v.tenant_id=d.tenant_id AND v.id=d.code_version_id
            LEFT JOIN tna.work_time_code c ON c.tenant_id=v.tenant_id AND c.id=v.code_id
            WHERE s.tenant_id=#{tenant} AND s.id=#{version}
            """)
    Policy policy(UUID tenant, UUID version, int weekday);

    @Select("""
            SELECT kind,start_time AS start,end_time AS "end" FROM tna.work_time_code_break
            WHERE tenant_id=#{tenant} AND version_id=#{version} ORDER BY start_time,end_time
            """)
    List<Break> breaks(UUID tenant, UUID version);

    // 최신 결과가 오래되면(입력·검토 개정이 바뀌면) 표시하지 않는다. 홈은 기록 규칙으로 돌아간다.
    @Select("""
            SELECT r.work_date,r.late_millis,r.early_leave_millis,r.absence
            FROM (SELECT DISTINCT ON(work_date) work_date,input_revision,review_revision,late_millis,early_leave_millis,absence
                  FROM tna.day_result
                  WHERE tenant_id=#{tenant} AND employee_id=#{employee} AND work_date BETWEEN #{from} AND #{to}
                  ORDER BY work_date,result_revision DESC) r
            LEFT JOIN tna.day_input_revision rev ON rev.tenant_id=#{tenant} AND rev.employee_id=#{employee} AND rev.work_date=r.work_date
            WHERE r.input_revision=COALESCE(rev.input_revision,0) AND r.review_revision=COALESCE(rev.review_revision,0)
            """)
    List<AttendanceMark> attendanceMarks(UUID tenant, UUID employee, LocalDate from, LocalDate to);

    record AttendanceMark(LocalDate workDate, Long lateMillis, Long earlyLeaveMillis, String absence) { }

    record Policy(boolean group, String name, String code, String workCode, String workName, UUID codeVersionId,
                  LocalTime startTime, LocalTime endTime, LocalTime beforeStart, LocalTime beforeEnd,
                  LocalTime afterStart, LocalTime afterEnd, String workMode, LocalTime coreStart,
                  LocalTime coreEnd, Integer standardMinutes, String settlementPeriod) implements WorkTimeCodeData.Policy {
        @org.apache.ibatis.annotations.AutomapConstructor public Policy {}
        public Policy(boolean group, String name, String code, String workCode, String workName, UUID codeVersionId,
                      LocalTime startTime, LocalTime endTime, LocalTime beforeStart, LocalTime beforeEnd,
                      LocalTime afterStart, LocalTime afterEnd) {
            this(group,name,code,workCode,workName,codeVersionId,startTime,endTime,beforeStart,beforeEnd,
                    afterStart,afterEnd,"FIXED",null,null,null,null);
        }
    }

    record Assignment(boolean group, String name, String code, String workCode, String workName,
                      LocalTime beforeStart, LocalTime beforeEnd, LocalTime afterStart, LocalTime afterEnd,
                      List<Break> overtimeBreaks, long overtimeMinutes, String workMode, LocalTime coreStart,
                      LocalTime coreEnd, Integer standardMinutes, String settlementPeriod) {}

    default Assignment day(UUID tenant, UUID version, int weekday) {
        Policy policy = version == null ? null : policy(tenant, version, weekday);
        if (policy == null) return null;
        var breaks = policy.codeVersionId() == null ? List.<Break>of() : breaks(tenant, policy.codeVersionId());
        long minutes = policy.codeVersionId() == null ? 0 : WorkTimeCodeRules.validate(policy, breaks).overtimeMinutes();
        return new Assignment(policy.group(), policy.name(), policy.code(), policy.workCode(), policy.workName(),
                policy.beforeStart(), policy.beforeEnd(), policy.afterStart(), policy.afterEnd(),
                breaks.stream().filter(b -> !"REGULAR".equals(b.kind())).toList(), minutes, policy.workMode(),
                policy.coreStart(), policy.coreEnd(), policy.standardMinutes(), policy.settlementPeriod());
    }
}
