package kr.tna.request;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import kr.tna.request.EmploymentAccrualData.Period;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface EmploymentAccrualMapper {
    record Review(UUID id, UUID bucketId, LocalDate joinedOn, int entitlementYear, int monthNumber, LocalDate periodStart,
            LocalDate periodEnd, LocalDate accrualOn, int scheduledDays, int attendedDays, int leaveDays,
            int unresolvedDays, String decision, String reason, int grantedDays, UUID includedBucketId) {
        Period period() {
            return new Period(monthNumber,periodStart,periodEnd,accrualOn,scheduledDays,attendedDays,leaveDays,
                    unresolvedDays,decision,reason,grantedDays,null,includedBucketId);
        }
    }

    @Select("""
        SELECT id,bucket_id,joined_on,entitlement_year,month_number,period_start,period_end,accrual_on,scheduled_days,
            attended_days,leave_days,unresolved_days,decision,reason,granted_days,included_bucket_id
        FROM tna.leave_monthly_review WHERE tenant_id=#{tenant} AND employee_id=#{employee} ORDER BY month_number
        """)
    List<Review> reviews(UUID tenant, UUID employee);

    record Source(UUID bucketId, String sourceReference, BigDecimal creditedDays, BigDecimal linkedDays,
            long version, long revision, int entitlementYear, LocalDate usableTo) {
        boolean covers(LocalDate accrualOn, AnnualLeaveCycleData.Rule rule) {
            return entitlementYear == AnnualLeaveCycleData.currentYear(accrualOn,rule) && (usableTo == null || !accrualOn.isAfter(usableTo));
        }
    }

    @Select("""
        SELECT b.id AS bucket_id,b.source_reference,credits.days AS credited_days,
            linked.days AS linked_days,
            b.version,a.revision,a.entitlement_year,b.usable_to
        FROM tna.leave_bucket b
        """ + AnnualLeaveCycleMapper.LATEST_ATTRIBUTION + """
        JOIN tna.attendance_type t ON t.tenant_id=b.tenant_id AND t.id=a.attendance_type_id AND t.system_kind='ANNUAL_LEAVE'
        JOIN LATERAL (SELECT count(*) AS days FROM tna.leave_monthly_review m
            WHERE m.tenant_id=b.tenant_id AND m.included_bucket_id=b.id) linked ON true
        JOIN LATERAL (SELECT greatest(0,
                COALESCE(sum(l.signed_quantity_days) FILTER (WHERE l.entry_kind IN ('OPENING','GRANT') AND l.signed_quantity_days>0),0)
                + least(0,COALESCE(sum(l.signed_quantity_days) FILTER (WHERE l.entry_kind='ADJUSTMENT'),0))) AS days
            FROM tna.leave_ledger l
            WHERE l.tenant_id=b.tenant_id AND l.employee_id=b.employee_id AND l.bucket_id=b.id
              AND l.entry_kind IN ('OPENING','GRANT','ADJUSTMENT') AND l.effective_on<=#{today}
        ) credits ON credits.days>0 OR linked.days>0
        WHERE b.tenant_id=#{tenant} AND b.employee_id=#{employee} AND a.classification_status='CONFIRMED'
            AND b.base_date<=#{today} AND b.usable_from<=#{through} AND (b.usable_to IS NULL OR b.usable_to>=#{firstAccrual})
            AND NOT EXISTS (SELECT 1 FROM tna.leave_monthly_review m WHERE m.tenant_id=b.tenant_id AND m.bucket_id=b.id)
        ORDER BY b.base_date,b.id
        """)
    List<Source> openingSources(UUID tenant, UUID employee, LocalDate today, LocalDate through, LocalDate firstAccrual);

    @Select("SELECT EXISTS (SELECT 1 FROM tna.leave_exit_settlement WHERE tenant_id=#{tenant} AND employee_id=#{employee})")
    boolean settled(UUID tenant, UUID employee);

    @Insert("""
        INSERT INTO tna.leave_monthly_review(tenant_id,id,employee_id,joined_on,month_number,period_start,period_end,accrual_on,
            usable_to,entitlement_year,scheduled_days,attended_days,leave_days,unresolved_days,decision,granted_days,bucket_id,
            evidence_token,reason,created_by,included_bucket_id,included_source_reference)
        VALUES (#{tenant},#{id},#{employee},#{joined},#{p.monthNumber},#{p.periodStart},#{p.periodEnd},#{p.accrualOn},
            #{usableTo},#{year},#{p.scheduledDays},#{p.attendedDays},#{p.leaveDays},#{p.unresolvedDays},#{decision},#{quantity},
            #{bucket},#{p.token},#{reason},#{actor},#{includedBucketId},#{includedSourceReference})
        """)
    int insert(UUID tenant, UUID id, UUID employee, LocalDate joined, Period p, LocalDate usableTo, int year,
            String decision, int quantity, UUID bucket, String reason, UUID actor, UUID includedBucketId, String includedSourceReference);

    @Insert("""
        INSERT INTO tna.leave_ledger(tenant_id,id,bucket_id,employee_id,entry_kind,signed_quantity_days,effective_on,
            monthly_review_id,source_reference,reason,created_by) VALUES
            (#{tenant},#{id},#{bucket},#{employee},'MONTHLY_ACCRUAL',#{quantity},#{date},#{review},#{source},#{reason},#{actor})
        """)
    int movement(UUID tenant, UUID id, UUID bucket, UUID employee, int quantity, LocalDate date, UUID review,
            String source, String reason, UUID actor);
}
