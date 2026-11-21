package kr.tna.request;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface LeaveExitMapper {
    record Employee(UUID id, String employeeNo, String employeeName, LocalDate joinedOn, LocalDate leftOn, long version) { }
    record Source(UUID bucketId, String sourceReference, LocalDate baseDate, LocalDate usableFrom, LocalDate usableTo,
            Integer entitlementYear, boolean confirmed, long revision, long version,
            BigDecimal openingDays, BigDecimal grantDays, BigDecimal carryDays, BigDecimal usedDays, BigDecimal restoredDays,
            BigDecimal adjustmentDays, BigDecimal expireDays, BigDecimal balanceDays, BigDecimal afterExitDays) { }

    @Select("""
            SELECT id,employee_no,display_name AS employee_name,joined_on,left_on,version FROM tna.employee
            WHERE tenant_id=#{tenantId} AND id=#{employeeId}
            """)
    Employee employee(@Param("tenantId") UUID tenantId, @Param("employeeId") UUID employeeId);

    @Select("""
            SELECT snapshot::text FROM tna.leave_exit_settlement WHERE tenant_id=#{tenantId} AND employee_id=#{employeeId}
            """)
    String receipt(@Param("tenantId") UUID tenantId, @Param("employeeId") UUID employeeId);

    @Select("""
            SELECT b.id AS bucket_id,b.source_reference,b.base_date,b.usable_from,b.usable_to,a.entitlement_year,
                COALESCE(a.classification_status='CONFIRMED' AND t.system_kind='ANNUAL_LEAVE',false) AS confirmed,
                COALESCE(a.revision,0) AS revision,b.version,
                COALESCE(sum(l.signed_quantity_days) FILTER (WHERE l.entry_kind='OPENING'),0) AS opening_days,
                COALESCE(sum(l.signed_quantity_days) FILTER (WHERE l.entry_kind IN ('GRANT','MONTHLY_ACCRUAL')),0) AS grant_days,
                COALESCE(sum(l.signed_quantity_days) FILTER (WHERE l.entry_kind IN ('CARRY_IN','CARRY_OUT')),0) AS carry_days,
                -COALESCE(sum(l.signed_quantity_days) FILTER (WHERE l.entry_kind='USE'),0) AS used_days,
                COALESCE(sum(l.signed_quantity_days) FILTER (WHERE l.entry_kind='RESTORE'),0) AS restored_days,
                COALESCE(sum(l.signed_quantity_days) FILTER (WHERE l.entry_kind='ADJUSTMENT'),0) AS adjustment_days,
                -COALESCE(sum(l.signed_quantity_days) FILTER (WHERE l.entry_kind='EXPIRE'),0) AS expire_days,
                COALESCE(sum(l.signed_quantity_days),0) AS balance_days,
                COALESCE(sum(l.signed_quantity_days) FILTER (WHERE l.entry_kind NOT IN ('USE','RESTORE')
                    AND l.effective_on>#{leftOn}::date),0) AS after_exit_days
            FROM tna.leave_bucket b
            """ + AnnualLeaveCycleMapper.LATEST_ATTRIBUTION + """
            LEFT JOIN tna.attendance_type t ON t.tenant_id=b.tenant_id AND t.id=a.attendance_type_id
            LEFT JOIN tna.leave_ledger l ON l.tenant_id=b.tenant_id AND l.bucket_id=b.id AND l.employee_id=b.employee_id
            WHERE b.tenant_id=#{tenantId} AND b.employee_id=#{employeeId}
            GROUP BY b.tenant_id,b.id,a.entitlement_year,a.classification_status,a.revision,t.system_kind
            ORDER BY b.usable_from,b.id
            """)
    List<Source> sources(@Param("tenantId") UUID tenantId, @Param("employeeId") UUID employeeId, @Param("leftOn") LocalDate leftOn);

    @Select("""
            SELECT EXISTS (SELECT 1 FROM tna.approval_request r
                LEFT JOIN tna.approval_request original ON original.tenant_id=r.tenant_id AND original.id=r.original_request_id
                    AND original.target_employee_id=r.target_employee_id
                WHERE r.tenant_id=#{tenantId} AND r.target_employee_id=#{employeeId} AND r.status='PENDING'
                    AND ((r.kind='LEAVE_DAY' AND r.leave_bucket_id IS NOT NULL)
                         OR (r.kind='CANCEL_LEAVE' AND COALESCE(original.leave_bucket_id,r.leave_bucket_id) IS NOT NULL)))
            """)
    boolean pendingLeave(@Param("tenantId") UUID tenantId, @Param("employeeId") UUID employeeId);

    @Select("""
            SELECT EXISTS (SELECT 1 FROM tna.leave_ledger u
                WHERE u.tenant_id=#{tenantId} AND u.employee_id=#{employeeId} AND u.entry_kind='USE' AND u.leave_date>#{leftOn}
                    AND NOT EXISTS (SELECT 1 FROM tna.leave_ledger r WHERE r.tenant_id=u.tenant_id AND r.reverses_entry_id=u.id))
            OR EXISTS (SELECT 1 FROM tna.approval_request r
                JOIN tna.request_effect f ON f.tenant_id=r.tenant_id AND f.request_id=r.id AND f.employee_id=r.target_employee_id
                WHERE r.tenant_id=#{tenantId} AND r.target_employee_id=#{employeeId} AND r.kind='LEAVE_DAY' AND r.status='APPROVED'
                    AND r.leave_bucket_id IS NOT NULL AND f.effect_status='EFFECTIVE' AND r.to_date>#{leftOn}
                    AND (EXISTS (SELECT 1 FROM tna.leave_request_day d WHERE d.tenant_id=r.tenant_id AND d.request_id=r.id
                                    AND d.quantity_days>0 AND d.leave_date>#{leftOn})
                         OR (NOT EXISTS (SELECT 1 FROM tna.leave_request_day d WHERE d.tenant_id=r.tenant_id AND d.request_id=r.id)
                             AND NOT EXISTS (SELECT 1 FROM tna.leave_ledger l WHERE l.tenant_id=r.tenant_id AND l.request_effect_id=f.id))))
            """)
    boolean futureLeave(@Param("tenantId") UUID tenantId, @Param("employeeId") UUID employeeId, @Param("leftOn") LocalDate leftOn);

    @Insert("""
            INSERT INTO tna.leave_exit_settlement(tenant_id,employee_id,id,left_on,settlement_days,reason,prior_history_confirmed,created_by,snapshot)
            VALUES(#{tenantId},#{employeeId},#{id},#{leftOn},#{days},#{reason},#{history},#{actor},CAST(#{snapshot} AS jsonb))
            """)
    int insert(@Param("tenantId") UUID tenantId, @Param("employeeId") UUID employeeId, @Param("id") UUID id,
            @Param("leftOn") LocalDate leftOn, @Param("days") BigDecimal days, @Param("reason") String reason,
            @Param("history") boolean history, @Param("actor") UUID actor, @Param("snapshot") String snapshot);

    @Insert("""
            INSERT INTO tna.leave_ledger(tenant_id,bucket_id,employee_id,id,entry_kind,signed_quantity_days,effective_on,
                exit_settlement_id,source_reference,reason,created_by)
            VALUES(#{tenantId},#{bucket},#{employee},#{id},'EXIT_SETTLEMENT',#{days},#{date},#{exitId},#{source},#{reason},#{actor})
            """)
    int debit(@Param("tenantId") UUID tenantId, @Param("bucket") UUID bucket, @Param("employee") UUID employee,
            @Param("id") UUID id, @Param("days") BigDecimal days, @Param("date") LocalDate date, @Param("exitId") UUID exitId,
            @Param("source") String source, @Param("reason") String reason, @Param("actor") UUID actor);
}
