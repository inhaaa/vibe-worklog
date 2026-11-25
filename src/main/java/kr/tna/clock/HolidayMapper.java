package kr.tna.clock;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import kr.tna.worktime.ClosedAttendanceMonths;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface HolidayMapper extends ClosedAttendanceMonths {
    record YearRow(long version, Instant appliedAt, String appliedBy) { }

    @Select("""
            SELECT id,holiday_date AS date,name,category,basis FROM tna.holiday
            WHERE tenant_id=#{tenantId} AND holiday_date BETWEEN #{from} AND #{through} ORDER BY holiday_date
            """)
    List<HolidayService.Row> rows(@Param("tenantId") UUID tenantId, @Param("from") LocalDate from, @Param("through") LocalDate through);

    @Select("""
            SELECT y.version,y.applied_at,e.display_name AS applied_by FROM tna.holiday_year y
            LEFT JOIN tna.tenant_account a ON a.tenant_id=y.tenant_id AND a.id=y.applied_by
            LEFT JOIN tna.employee e ON e.tenant_id=a.tenant_id AND e.id=a.employee_id
            WHERE y.tenant_id=#{tenantId} AND y.year=#{year}
            """)
    YearRow year(@Param("tenantId") UUID tenantId, @Param("year") int year);

    @Insert("""
            INSERT INTO tna.holiday_year(tenant_id,year,version,applied_at,applied_by)
            VALUES (#{tenantId},#{year},1,#{now},#{actorId})
            ON CONFLICT (tenant_id,year) DO UPDATE
            SET version=tna.holiday_year.version+1,applied_at=EXCLUDED.applied_at,applied_by=EXCLUDED.applied_by
            """)
    int saveYear(@Param("tenantId") UUID tenantId, @Param("year") int year, @Param("now") Instant now, @Param("actorId") UUID actorId);

    @Insert("""
            INSERT INTO tna.holiday(tenant_id,id,holiday_date,name,category,basis,created_by,updated_by)
            VALUES (#{tenantId},#{id},#{date},#{name},#{category},#{basis,jdbcType=VARCHAR},#{actorId},#{actorId})
            """)
    int insertHoliday(@Param("tenantId") UUID tenantId, @Param("id") UUID id, @Param("date") LocalDate date, @Param("name") String name,
            @Param("category") String category, @Param("basis") String basis, @Param("actorId") UUID actorId);

    @Update("""
            UPDATE tna.holiday SET name=#{name},category=#{category},basis=#{basis,jdbcType=VARCHAR},
                updated_by=#{actorId},updated_at=CURRENT_TIMESTAMP
            WHERE tenant_id=#{tenantId} AND holiday_date=#{date}
            """)
    int updateHoliday(@Param("tenantId") UUID tenantId, @Param("date") LocalDate date, @Param("name") String name,
            @Param("category") String category, @Param("basis") String basis, @Param("actorId") UUID actorId);

    /** 달력이 바뀐 날의 저장 결과를 오래됨으로 만든다. 검토 개정을 올리면 개정만 비교하는 조회(홈 표시·예외 탭)도 그 결과를 쓰지 않는다. */
    @Update("""
            UPDATE tna.day_input_revision SET review_revision=review_revision+1,recalculation_required=true
            WHERE tenant_id=#{tenantId} AND work_date=#{date}
            """)
    int flagRecalculation(@Param("tenantId") UUID tenantId, @Param("date") LocalDate date);

    @Delete("DELETE FROM tna.holiday WHERE tenant_id=#{tenantId} AND holiday_date=#{date}")
    int deleteHoliday(@Param("tenantId") UUID tenantId, @Param("date") LocalDate date);

    @Select("""
            SELECT work_date AS date,kind,name FROM tna.calendar_effective_day
            WHERE tenant_id=#{tenantId} AND work_date BETWEEN #{from} AND #{through}
            """)
    List<HolidayReflection.Effective> effective(@Param("tenantId") UUID tenantId, @Param("from") LocalDate from, @Param("through") LocalDate through);

    @Select("""
            SELECT valid_from,valid_to FROM tna.calendar_version
            WHERE tenant_id=#{tenantId} AND scope='PERIOD' AND valid_from<=#{through} AND valid_to>#{from}
            """)
    List<HolidayReflection.Period> periods(@Param("tenantId") UUID tenantId, @Param("from") LocalDate from, @Param("through") LocalDate through);

    @Select("SELECT COALESCE(max(revision),0)+1 FROM tna.calendar_version WHERE tenant_id=#{tenantId}")
    int nextRevision(@Param("tenantId") UUID tenantId);

    @Insert("""
            INSERT INTO tna.calendar_version(tenant_id,id,revision,valid_from,valid_to,scope,reason,created_by)
            VALUES (#{tenantId},#{id},#{revision},#{from},#{to},#{scope},#{reason},#{actorId})
            """)
    int insertVersion(@Param("tenantId") UUID tenantId, @Param("id") UUID id, @Param("revision") int revision,
            @Param("from") LocalDate from, @Param("to") LocalDate to, @Param("scope") String scope,
            @Param("reason") String reason, @Param("actorId") UUID actorId);

    @Insert("""
            INSERT INTO tna.calendar_day(tenant_id,version_id,work_date,kind,name,basis)
            VALUES (#{tenantId},#{versionId},#{date},#{kind},#{name},#{basis})
            """)
    int insertDay(@Param("tenantId") UUID tenantId, @Param("versionId") UUID versionId, @Param("date") LocalDate date,
            @Param("kind") String kind, @Param("name") String name, @Param("basis") String basis);

    @Select("SELECT after_state::text FROM tna.org_audit_event WHERE tenant_id=#{tenantId} AND command_id=#{commandId}")
    String result(@Param("tenantId") UUID tenantId, @Param("commandId") UUID commandId);
}
