package kr.tna.clock;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;
import kr.tna.clock.ClockData.Event;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface ClockMapper {
    @Select("SELECT current_setting('app.tenant_id',true)")
    String currentTenant();
    @Select("SELECT pg_advisory_xact_lock(hashtextextended(CAST(#{tenantId} AS text),0))")
    Object lock(@Param("tenantId") UUID tenantId);
    @Select("SELECT EXISTS(SELECT 1 FROM tna.employee WHERE tenant_id=#{tenantId} AND id=#{employeeId})")
    boolean employeeExists(@Param("tenantId") UUID tenantId,@Param("employeeId") UUID employeeId);
    @Select("""
            SELECT id,event_kind AS kind,occurred_at,assigned_work_date AS work_date
            FROM tna.attendance_event WHERE tenant_id=#{tenantId} AND employee_id=#{employeeId} AND assigned_work_date=#{date}
            ORDER BY occurred_at,id
            """)
    List<Event> events(@Param("tenantId") UUID tenantId,@Param("employeeId") UUID employeeId,@Param("date") LocalDate date);
    @Select("""
            SELECT a.schedule_version_id,d.weekday,d.workday,d.start_time,d.end_time FROM tna.schedule_assignment a
            JOIN tna.schedule_version v ON v.tenant_id=a.tenant_id AND v.id=a.schedule_version_id
            JOIN tna.schedule_day d ON d.tenant_id=v.tenant_id AND d.version_id=v.id AND d.weekday=#{weekday}
            WHERE a.tenant_id=#{tenantId} AND a.employee_id=#{employeeId}
              AND a.valid_from<=#{date} AND(a.valid_to IS NULL OR a.valid_to>#{date}) AND v.valid_from<=#{date}
            ORDER BY a.id
            """)
    List<ScheduleRow> schedules(@Param("tenantId") UUID tenantId,@Param("employeeId") UUID employeeId,
                               @Param("date") LocalDate date,@Param("weekday") int weekday);
    @Select("""
            SELECT v.id AS version_id,d.kind FROM tna.calendar_version v
            JOIN tna.calendar_day d ON d.tenant_id=v.tenant_id AND d.version_id=v.id AND d.work_date=#{date}
            WHERE v.tenant_id=#{tenantId} AND v.valid_from<=#{date} AND v.valid_to>#{date} ORDER BY v.id
            """)
    List<CalendarRow> calendar(@Param("tenantId") UUID tenantId,@Param("date") LocalDate date);
    @Select("SELECT input_revision,review_revision FROM tna.day_input_revision WHERE tenant_id=#{tenantId} AND employee_id=#{employeeId} AND work_date=#{date}")
    Revision revision(@Param("tenantId") UUID tenantId,@Param("employeeId") UUID employeeId,@Param("date") LocalDate date);
    @Select("""
            SELECT id,confirmed_input_revision,review_revision,presence_as_work,breaks_match,source_fingerprint,
                   break_snapshot::text AS break_snapshot,confirmed_at FROM tna.work_confirmation
            WHERE tenant_id=#{tenantId} AND employee_id=#{employeeId} AND work_date=#{date}
              AND confirmed_input_revision=#{inputRevision} AND source_fingerprint=#{fingerprint}
            ORDER BY review_revision DESC,id DESC LIMIT 1
            """)
    ConfirmationRow findConfirmation(@Param("tenantId") UUID tenantId,@Param("employeeId") UUID employeeId,@Param("date") LocalDate date,
                                  @Param("inputRevision") long inputRevision,@Param("fingerprint") String fingerprint);
    @Select("""
            SELECT id::text FROM tna.allowed_network WHERE tenant_id=#{tenantId} AND network >>= CAST(#{address} AS inet)
              AND valid_from<=#{at} AND(valid_to IS NULL OR valid_to>#{at}) ORDER BY masklen(network) DESC,id LIMIT 1
            """)
    String network(@Param("tenantId") UUID tenantId,@Param("address") String address,@Param("at") Instant at);
    @Insert("""
            INSERT INTO tna.attendance_event(tenant_id,id,employee_id,event_kind,occurred_at,received_at,
                client_command_id,source,validated_ip,network_id,assigned_work_date)
            VALUES (#{tenantId},#{event.id},#{employeeId},#{event.kind},#{event.occurredAt},#{event.occurredAt},
                #{commandId},'IP',CAST(#{address} AS inet),#{networkId},#{event.workDate})
            """)
    int event(@Param("tenantId") UUID tenantId,@Param("employeeId") UUID employeeId,@Param("event") Event event,
              @Param("commandId") UUID commandId,@Param("address") String address,@Param("networkId") UUID networkId);
    @Insert("""
            INSERT INTO tna.day_input_revision(tenant_id,employee_id,work_date,input_revision,review_revision,recalculation_required)
            VALUES (#{tenantId},#{employeeId},#{date},1,0,true)
            ON CONFLICT(tenant_id,employee_id,work_date) DO UPDATE
            SET input_revision=day_input_revision.input_revision+1,recalculation_required=true
            WHERE day_input_revision.tenant_id=#{tenantId}
            """)
    int advanceInput(@Param("tenantId") UUID tenantId,@Param("employeeId") UUID employeeId,@Param("date") LocalDate date);
    @Update("""
            UPDATE tna.day_input_revision SET review_revision=review_revision+1,recalculation_required=true
            WHERE tenant_id=#{tenantId} AND employee_id=#{employeeId} AND work_date=#{date}
              AND input_revision=#{inputRevision} AND review_revision=#{reviewRevision}
            """)
    int advanceReview(@Param("tenantId") UUID tenantId,@Param("employeeId") UUID employeeId,@Param("date") LocalDate date,
                      @Param("inputRevision") long inputRevision,@Param("reviewRevision") long reviewRevision);
    @Insert("""
            INSERT INTO tna.work_confirmation(tenant_id,id,employee_id,work_date,confirmed_input_revision,review_revision,
                source_fingerprint,presence_as_work,breaks_match,confirmed_at,confirmed_by,break_snapshot,client_command_id)
            VALUES (#{tenantId},#{id},#{employeeId},#{date},#{inputRevision},#{reviewRevision},#{fingerprint},true,true,
                    #{at},#{actorId},CAST(#{breaks} AS jsonb),#{commandId})
            """)
    int insertConfirmation(@Param("tenantId") UUID tenantId,@Param("id") UUID id,@Param("employeeId") UUID employeeId,
                     @Param("date") LocalDate date,@Param("inputRevision") long inputRevision,@Param("reviewRevision") long reviewRevision,
                     @Param("fingerprint") String fingerprint,@Param("at") Instant at,@Param("actorId") UUID actorId,
                     @Param("breaks") String breaks,@Param("commandId") UUID commandId);
    record ScheduleRow(UUID scheduleVersionId,int weekday,boolean workday,LocalTime startTime,LocalTime endTime) { }
    record CalendarRow(UUID versionId,String kind) { }
    record Revision(long inputRevision,long reviewRevision) { }
    record ConfirmationRow(UUID id,long confirmedInputRevision,long reviewRevision,boolean presenceAsWork,
                           boolean breaksMatch,String sourceFingerprint,String breakSnapshot,Instant confirmedAt) { }
}
