package kr.tna.request;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import kr.tna.request.AnnualRequestData.*;
import kr.tna.request.RequestData.RequestView;
import org.apache.ibatis.annotations.*;

@Mapper
public interface AnnualRequestMapper {
    @Select("SELECT pg_advisory_xact_lock_shared(hashtextextended(CAST(#{tenantId} AS text),0))")
    Object lock(@Param("tenantId") UUID tenantId);
    String TYPE_SELECT="""
        SELECT t.id,t.code,v.name,t.system_kind,(t.system_kind IS NOT NULL OR v.recognition_rule='COMPANY_LEAVE') AS supported
        FROM tna.attendance_type t JOIN LATERAL (
          SELECT name,recognition_rule FROM tna.attendance_type_version WHERE tenant_id=t.tenant_id AND type_id=t.id ORDER BY revision DESC LIMIT 1
        )v ON true WHERE t.tenant_id=#{tenantId}
        """;
    @Select(TYPE_SELECT+" ORDER BY t.code,t.id") List<TypeChoice> types(@Param("tenantId") UUID tenantId);
    @Select(TYPE_SELECT+" AND t.id=#{id}") TypeChoice type(@Param("tenantId") UUID tenantId,@Param("id") UUID id);
    @Select(LeaveWorkflowMapper.REQUEST_SELECT+"""
        WHERE r.tenant_id=#{tenantId} AND r.target_employee_id=#{employeeId} AND r.kind IN ('LEAVE_DAY','CANCEL_LEAVE','ATTENDANCE_CORRECTION') AND r.from_date<#{to} AND r.to_date>=#{from}
          AND(#{status}='' OR r.status=#{status})
          AND(#{typeId}::uuid IS NULL OR EXISTS(SELECT 1 FROM tna.attendance_type_version v WHERE v.tenant_id=r.tenant_id AND v.id=r.attendance_type_version_id AND v.type_id=#{typeId}))
        ORDER BY r.from_date DESC,r.submitted_at DESC,r.id LIMIT 51 OFFSET #{offset}
        """)
    List<RequestView> requests(@Param("tenantId") UUID tenantId,@Param("employeeId") UUID employeeId,
            @Param("from") LocalDate from,@Param("to") LocalDate to,@Param("typeId") UUID typeId,@Param("status") String status,@Param("offset") int offset);
    @Select(LeaveWorkflowMapper.REQUEST_SELECT+"""
        WHERE r.tenant_id=#{tenantId} AND r.author_account_id=#{accountId} AND r.target_employee_id<>#{employeeId}
          AND r.from_date<#{to} AND r.to_date>=#{from}
        ORDER BY r.from_date DESC,r.submitted_at DESC,r.id LIMIT 51 OFFSET #{offset}
        """)
    List<RequestView> authored(@Param("tenantId") UUID tenantId,@Param("accountId") UUID accountId,@Param("employeeId") UUID employeeId,
            @Param("from") LocalDate from,@Param("to") LocalDate to,@Param("offset") int offset);
    @Select("""
        SELECT t.id AS type_id,t.code,
          CASE WHEN t.system_kind IS NULL THEN (array_agg(v.name ORDER BY v.revision DESC))[1] ELSE display.name END AS name,
          r.kind,count(*) AS request_count,
          count(*) FILTER(WHERE r.status='PENDING') AS pending_count,
          count(*) FILTER(WHERE r.status='APPROVED') AS approved_count,
          count(*) FILTER(WHERE r.status='REJECTED') AS rejected_count,
          count(*) FILTER(WHERE r.status='WITHDRAWN') AS withdrawn_count,
          COALESCE(sum(d.used_days),0) AS used_days,COALESCE(sum(d.planned_days),0) AS planned_days,
          COALESCE(sum(d.pending_days),0) AS pending_days
        FROM tna.approval_request r
        JOIN tna.attendance_type_version v ON v.tenant_id=r.tenant_id AND v.id=r.attendance_type_version_id
        JOIN tna.attendance_type t ON t.tenant_id=v.tenant_id AND t.id=v.type_id
        JOIN LATERAL (SELECT name FROM tna.attendance_type_version WHERE tenant_id=t.tenant_id AND type_id=t.id ORDER BY revision DESC LIMIT 1)display ON true
        LEFT JOIN tna.request_effect f ON f.tenant_id=r.tenant_id AND f.request_id=r.id AND f.effect_kind='LEAVE'
        LEFT JOIN LATERAL (
          SELECT sum(CASE WHEN r.kind='LEAVE_DAY' AND r.status='APPROVED' AND f.effect_status='EFFECTIVE' AND d.leave_date<=#{today} THEN d.quantity_days ELSE 0 END) AS used_days,
            sum(CASE WHEN r.kind='LEAVE_DAY' AND r.status='APPROVED' AND f.effect_status='EFFECTIVE' AND d.leave_date>#{today} THEN d.quantity_days ELSE 0 END) AS planned_days,
            sum(CASE WHEN r.kind='LEAVE_DAY' AND r.status='PENDING' THEN d.quantity_days ELSE 0 END) AS pending_days
          FROM tna.leave_request_day d WHERE d.tenant_id=r.tenant_id AND d.request_id=r.id AND d.leave_date>=#{from} AND d.leave_date<#{to}
        )d ON true
        WHERE r.tenant_id=#{tenantId} AND r.target_employee_id=#{employeeId} AND r.from_date<#{to} AND r.to_date>=#{from}
        GROUP BY t.id,t.code,t.system_kind,display.name,r.kind ORDER BY t.code,r.kind
        """)
    List<TypeSummary> summaries(@Param("tenantId") UUID tenantId,@Param("employeeId") UUID employeeId,
            @Param("from") LocalDate from,@Param("to") LocalDate to,@Param("today") LocalDate today);
    @Select("""
        SELECT b.id AS bucket_id,a.entitlement_year,COALESCE(a.classification_status,'UNCLASSIFIED') AS classification_status,a.basis,
          b.usable_from,b.usable_to,COALESCE(l.balance_days,0) AS balance_days,
          CASE WHEN b.base_date<=#{today} AND b.usable_from<=#{today} AND(b.usable_to IS NULL OR b.usable_to>=#{today}) THEN COALESCE(l.balance_days,0) ELSE 0 END AS available_days,
          COALESCE(l.opening_adjustment_days,0) AS opening_adjustment_days,
          CASE WHEN b.base_date>#{today} OR b.usable_from>#{today} THEN 'NOT_STARTED' WHEN b.usable_to<#{today} THEN 'EXPIRED' ELSE 'CURRENT' END AS availability
        FROM tna.leave_bucket b
        LEFT JOIN LATERAL(SELECT classification_status,entitlement_year,basis FROM tna.leave_bucket_attribution WHERE tenant_id=b.tenant_id AND bucket_id=b.id ORDER BY revision DESC LIMIT 1)a ON true
        LEFT JOIN LATERAL(SELECT sum(signed_quantity_days) AS balance_days,
          sum(signed_quantity_days) FILTER(WHERE entry_kind IN ('OPENING','ADJUSTMENT')) AS opening_adjustment_days
          FROM tna.leave_ledger WHERE tenant_id=b.tenant_id AND bucket_id=b.id)l ON true
        WHERE b.tenant_id=#{tenantId} AND b.employee_id=#{employeeId} ORDER BY b.usable_from,b.id
        """)
    List<BucketYear> buckets(@Param("tenantId") UUID tenantId,@Param("employeeId") UUID employeeId,@Param("today") LocalDate today);
}
