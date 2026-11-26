package kr.tna.workspace;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.List;
import java.util.UUID;
import kr.tna.common.auth.TnaPrincipal;
import kr.tna.common.tenant.CompanyReadAccess;
import kr.tna.org.OrgCommands;
import kr.tna.org.OrgException;
import org.springframework.stereotype.Service;

/** 담당자 '주 52시간' 탭: 조회 월과 겹치는 주(월~일) 중 실근로 합계 48시간 이상인 직원-주. */
@Service
public final class WeeklyHoursQueries {
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final long OVER_MILLIS = 187_200_000L;
    private static final int LIMIT = 1000;
    public record Found(UUID employeeId, String employeeNo, String employeeName, LocalDate weekStart,
            long actualMillis, long approvedOvertimeMillis, int unknownDays) { }
    public record Row(UUID employeeId, String employeeNo, String employeeName, LocalDate weekStart, LocalDate weekEnd,
            long actualMillis, long approvedOvertimeMillis, int unknownDays, String status) { }   // OVER(>52h) | NEAR(48~52h)
    public record View(String month, List<Row> rows, boolean truncated, String error) { }

    private final CompanyReadAccess company;
    private final OrgCommands administration;
    private final ProfileCompanyMapper mapper;
    private final Clock clock;

    public WeeklyHoursQueries(CompanyReadAccess company, OrgCommands administration, ProfileCompanyMapper mapper, Clock clock) {
        this.company = company;
        this.administration = administration;
        this.mapper = mapper;
        this.clock = clock;
    }

    public View read(TnaPrincipal actor, String requestedMonth) {
        if (actor == null || !actor.roles().contains("ATTENDANCE_ADMIN")) throw OrgException.forbidden();
        return company.read(actor, () -> administration.read(actor, () -> month(actor.tenantId(), requestedMonth)));
    }

    private View month(UUID tenantId, String requestedMonth) {
        var latest = YearMonth.from(LocalDate.now(clock.withZone(SEOUL)));
        YearMonth month = latest;
        if (requestedMonth != null) {
            if (!requestedMonth.matches("[0-9]{4}-(0[1-9]|1[0-2])") || YearMonth.parse(requestedMonth).getYear() == 0
                    || YearMonth.parse(requestedMonth).isAfter(latest))
                return new View(requestedMonth, List.of(), false, "조회 월을 확인해 주세요.");
            month = YearMonth.parse(requestedMonth);
        }
        LocalDate from = month.atDay(1).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        LocalDate to = month.atEndOfMonth().with(TemporalAdjusters.nextOrSame(DayOfWeek.SUNDAY)).plusDays(1);
        var found = mapper.weeklyHours(tenantId, from, to);
        var rows = found.stream().limit(LIMIT).map(f -> new Row(f.employeeId(), f.employeeNo(), f.employeeName(), f.weekStart(),
                f.weekStart().plusDays(6), f.actualMillis(), f.approvedOvertimeMillis(), f.unknownDays(),
                f.actualMillis() > OVER_MILLIS ? "OVER" : "NEAR")).toList();
        return new View(month.toString(), rows, found.size() > LIMIT, null);
    }
}
