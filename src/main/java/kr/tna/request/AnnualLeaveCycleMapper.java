package kr.tna.request;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import kr.tna.request.AnnualLeaveCycleData.Entry;
import kr.tna.request.AnnualLeaveCycleData.Rule;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface AnnualLeaveCycleMapper {
    @Select("""
            SELECT version, fiscal_start_month, grant_days, carry_limit_days FROM tna.annual_leave_rule
            WHERE tenant_id=#{tenantId} ORDER BY version DESC LIMIT 1
            """)
    Rule currentRule(UUID tenantId);

    @Select("""
            SELECT version, fiscal_start_month, grant_days, carry_limit_days FROM tna.annual_leave_rule
            WHERE tenant_id=#{tenantId} AND id=#{id}
            """)
    Rule savedRule(@Param("tenantId") UUID tenantId, @Param("id") UUID id);

    @Select("""
            SELECT r.version, r.fiscal_start_month, r.grant_days, r.carry_limit_days
            FROM tna.annual_leave_cycle c JOIN tna.annual_leave_rule r ON r.tenant_id=c.tenant_id AND r.version=c.rule_version
            WHERE c.tenant_id=#{tenantId} AND c.entitlement_year=#{year}
            """)
    Rule appliedRule(@Param("tenantId") UUID tenantId, @Param("year") int year);

    @Select("""
            SELECT EXISTS (SELECT 1 FROM tna.annual_leave_cycle WHERE tenant_id=#{tenantId})
                OR EXISTS (SELECT 1 FROM tna.leave_monthly_review WHERE tenant_id=#{tenantId})
            """)
    boolean hasCycles(UUID tenantId);

    @Insert("""
            INSERT INTO tna.annual_leave_rule(tenant_id, id, version, fiscal_start_month, grant_days, carry_limit_days, created_by)
            VALUES (#{tenantId},#{id},#{rule.version},#{rule.fiscalStartMonth},#{rule.grantDays},#{rule.carryLimitDays},#{actor})
            """)
    int insertRule(@Param("tenantId") UUID tenantId, @Param("id") UUID id, @Param("rule") Rule rule, @Param("actor") UUID actor);

    @Select("SELECT id FROM tna.annual_leave_cycle WHERE tenant_id=#{tenantId} AND entitlement_year=#{year}")
    UUID cycleId(@Param("tenantId") UUID tenantId, @Param("year") int year);

    @Insert("""
            INSERT INTO tna.annual_leave_cycle(tenant_id, id, entitlement_year, rule_version, created_by)
            VALUES (#{tenantId},#{id},#{year},#{ruleVersion},#{actor})
            """)
    int insertCycle(@Param("tenantId") UUID tenantId,@Param("id") UUID id,@Param("year") int year,
            @Param("ruleVersion") long ruleVersion,@Param("actor") UUID actor);

    record Candidate(UUID employeeId, String employeeNo, String employeeName, long version,
            LocalDate joinedOn, LocalDate leftOn, boolean posted, boolean confirmed, boolean later) { }

    String LATEST_ATTRIBUTION = """
            LEFT JOIN LATERAL (
                SELECT a.revision, a.classification_status, a.entitlement_year, a.attendance_type_id
                FROM tna.leave_bucket_attribution a WHERE a.tenant_id=b.tenant_id AND a.bucket_id=b.id
                ORDER BY a.revision DESC LIMIT 1
            ) a ON true
            """;

    @Select("""
            WITH confirmed AS (
                SELECT DISTINCT b.employee_id FROM tna.leave_bucket b
            """ + LATEST_ATTRIBUTION + """
                JOIN tna.attendance_type t ON t.tenant_id=b.tenant_id AND t.id=a.attendance_type_id AND t.system_kind='ANNUAL_LEAVE'
                WHERE b.tenant_id=#{tenantId} AND a.classification_status='CONFIRMED' AND a.entitlement_year=#{year}
                    AND NOT EXISTS (SELECT 1 FROM tna.leave_monthly_review m WHERE m.tenant_id=b.tenant_id AND m.bucket_id=b.id)
            ), later AS (
                SELECT DISTINCT employee_id FROM tna.annual_leave_award WHERE tenant_id=#{tenantId} AND entitlement_year>#{year}
            )
            SELECT e.id AS employee_id,e.employee_no,e.display_name AS employee_name,e.version,e.joined_on,e.left_on,
                w.bucket_id IS NOT NULL AS posted, confirmed.employee_id IS NOT NULL AS confirmed, later.employee_id IS NOT NULL AS later
            FROM tna.employee e
            LEFT JOIN tna.annual_leave_award w ON w.tenant_id=e.tenant_id AND w.employee_id=e.id AND w.entitlement_year=#{year}
            LEFT JOIN confirmed ON confirmed.employee_id=e.id
            LEFT JOIN later ON later.employee_id=e.id
            WHERE e.tenant_id=#{tenantId} AND e.id<>#{self} AND e.active AND e.joined_on<=#{start}
                AND (e.left_on IS NULL OR e.left_on>=#{start})
                AND NOT EXISTS (SELECT 1 FROM tna.leave_exit_settlement x WHERE x.tenant_id=e.tenant_id AND x.employee_id=e.id)
            ORDER BY e.employee_no,e.id
            """)
    List<Candidate> candidates(@Param("tenantId") UUID tenantId,@Param("self") UUID self,
            @Param("year") int year,@Param("start") LocalDate start);

    record Source(UUID employeeId, UUID bucketId, long version, long revision, BigDecimal balance, BigDecimal closingBalance,
            boolean pending) { }

    @Select("""
            WITH pending AS (
                SELECT DISTINCT CASE WHEN r.kind='CANCEL_LEAVE' THEN original.leave_bucket_id ELSE r.leave_bucket_id END AS bucket_id
                FROM tna.approval_request r
                LEFT JOIN tna.approval_request original ON r.kind='CANCEL_LEAVE' AND original.tenant_id=r.tenant_id
                    AND original.id=r.original_request_id AND original.target_employee_id=r.target_employee_id
                WHERE r.tenant_id=#{tenantId} AND r.status='PENDING' AND r.kind IN ('LEAVE_DAY','CANCEL_LEAVE')
            )
            SELECT b.employee_id,b.id AS bucket_id,b.version,a.revision,COALESCE(sum(l.signed_quantity_days),0) AS balance,
                COALESCE(sum(l.signed_quantity_days) FILTER (WHERE l.effective_on<=b.usable_to OR l.entry_kind='USE'),0) AS closing_balance,
                pending.bucket_id IS NOT NULL AS pending
            FROM tna.leave_bucket b
            """ + LATEST_ATTRIBUTION + """
            JOIN tna.attendance_type t ON t.tenant_id=b.tenant_id AND t.id=a.attendance_type_id AND t.system_kind='ANNUAL_LEAVE'
            LEFT JOIN tna.leave_ledger l ON l.tenant_id=b.tenant_id AND l.bucket_id=b.id
            LEFT JOIN pending ON pending.bucket_id=b.id
            WHERE b.tenant_id=#{tenantId} AND b.employee_id<>#{self} AND b.usable_to=#{lastDay} AND b.base_date<=b.usable_to
                AND a.classification_status='CONFIRMED' AND a.entitlement_year=#{previousYear}
                AND NOT EXISTS (SELECT 1 FROM tna.leave_monthly_review m WHERE m.tenant_id=b.tenant_id AND m.bucket_id=b.id)
                AND NOT EXISTS (SELECT 1 FROM tna.leave_ledger settled WHERE settled.tenant_id=b.tenant_id AND settled.bucket_id=b.id
                    AND settled.entry_kind IN ('CARRY_OUT','EXPIRE'))
            GROUP BY b.tenant_id,b.id,a.revision,pending.bucket_id
            ORDER BY b.employee_id,b.usable_from,b.id
            """)
    List<Source> sources(@Param("tenantId") UUID tenantId, @Param("self") UUID self,
            @Param("previousYear") int previousYear, @Param("lastDay") LocalDate lastDay);

    @Insert("""
            INSERT INTO tna.annual_leave_award (tenant_id,entitlement_year,employee_id,bucket_id,created_by)
            VALUES (#{tenantId},#{year},#{employeeId},#{bucketId},#{actor})
            """)
    int insertAward(@Param("tenantId") UUID tenantId,@Param("year") int year,@Param("employeeId") UUID employeeId,
            @Param("bucketId") UUID bucketId,@Param("actor") UUID actor);

    @Insert("""
            INSERT INTO tna.leave_ledger(tenant_id,id,bucket_id,employee_id,entry_kind,signed_quantity_days,
                effective_on,annual_award_bucket_id,source_reference,reason,created_by)
            VALUES (#{tenantId},#{id},#{bucketId},#{employeeId},#{kind},#{days},#{date},#{awardBucket},#{source},#{reason},#{actor})
            """)
    int movement(@Param("tenantId") UUID tenantId,@Param("id") UUID id,@Param("bucketId") UUID bucketId,
            @Param("employeeId") UUID employeeId,@Param("kind") String kind,@Param("days") BigDecimal days,
            @Param("date") LocalDate date,@Param("awardBucket") UUID awardBucket,@Param("source") String source,
            @Param("reason") String reason,@Param("actor") UUID actor);

    @Select("""
            SELECT l.id,b.id AS bucket_id,e.employee_no,a.entitlement_year,l.entry_kind,l.signed_quantity_days,
                l.effective_on,l.leave_date,
                CASE WHEN m.included_bucket_id IS NULL THEN l.source_reference
                     ELSE l.source_reference || ' · 기초 원장 ' || m.included_source_reference END AS source_reference,
                l.reason,m.decision AS monthly_decision
            FROM tna.leave_ledger l JOIN tna.leave_bucket b ON b.tenant_id=l.tenant_id AND b.id=l.bucket_id AND b.employee_id=l.employee_id
            JOIN tna.employee e ON e.tenant_id=l.tenant_id AND e.id=l.employee_id
            """ + LATEST_ATTRIBUTION + """
            LEFT JOIN tna.leave_monthly_review m ON m.tenant_id=l.tenant_id AND m.id=l.monthly_review_id AND m.employee_id=l.employee_id
            LEFT JOIN tna.annual_leave_award w ON w.tenant_id=l.tenant_id AND w.bucket_id=l.annual_award_bucket_id AND w.employee_id=l.employee_id
            WHERE l.tenant_id=#{tenantId} AND l.employee_id=#{employeeId}
                AND (a.entitlement_year=#{year} OR w.entitlement_year=#{year} OR (l.effective_on>=#{start} AND l.effective_on<#{end}))
            ORDER BY l.effective_on DESC,l.created_at DESC,l.id
            """)
    List<Entry> entries(@Param("tenantId") UUID tenantId,@Param("employeeId") UUID employeeId,@Param("year") int year,
            @Param("start") LocalDate start,@Param("end") LocalDate end);

    @Select("""
            SELECT COALESCE(sum(l.signed_quantity_days),0) FROM tna.leave_bucket b
            JOIN tna.leave_ledger l ON l.tenant_id=b.tenant_id AND l.bucket_id=b.id AND l.employee_id=b.employee_id
            WHERE b.tenant_id=#{tenantId} AND b.employee_id=#{employeeId} AND b.base_date<=#{today} AND b.usable_from<=#{today}
                AND (b.usable_to IS NULL OR b.usable_to>=#{today})
            """)
    BigDecimal available(@Param("tenantId") UUID tenantId,@Param("employeeId") UUID employeeId,@Param("today") LocalDate today);

    @Select("""
            SELECT left_on,settlement_days,reason FROM tna.leave_exit_settlement
            WHERE tenant_id=#{tenantId} AND employee_id=#{employeeId} AND left_on>=#{start} AND left_on<#{end}
            """)
    AnnualLeaveCycleData.ExitReceipt exitReceipt(@Param("tenantId") UUID tenantId, @Param("employeeId") UUID employeeId,
            @Param("start") LocalDate start, @Param("end") LocalDate end);
}
