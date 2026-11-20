package kr.tna.worktime;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.UUID;
import kr.tna.org.OrgException;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** 마감 상태는 호출한 업무와 같은 테넌트 트랜잭션에서 확인한다. */
public interface ClosedAttendanceMonths {
    @Select("""
            SELECT month_start FROM (
                SELECT DISTINCT ON (month_start) month_start, status FROM tna.attendance_month_closure
                WHERE tenant_id=#{tenantId} AND month_start >= date_trunc('month', #{from}::date::timestamp)::date
                  AND (#{through}::date IS NULL OR month_start <= #{through}::date)
                ORDER BY month_start, version DESC
            ) latest WHERE status='CLOSED'
            ORDER BY month_start
            """)
    List<LocalDate> closedMonths(@Param("tenantId") UUID tenantId, @Param("from") LocalDate from,
            @Param("through") LocalDate through);

    default void requireOpen(UUID tenantId, LocalDate from, LocalDate through) {
        var closed = closedMonths(tenantId, from, through);
        if (!closed.isEmpty()) throw closedMonth(closed.getFirst());
    }

    static OrgException closedMonth(LocalDate month) {
        return OrgException.conflict(YearMonth.from(month) + " 근태가 마감됐습니다. 근태 담당자가 해당 월을 재개한 뒤 다시 처리해 주세요.");
    }
}
