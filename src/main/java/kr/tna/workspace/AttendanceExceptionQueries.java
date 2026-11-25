package kr.tna.workspace;

import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import kr.tna.common.auth.TnaPrincipal;
import kr.tna.common.tenant.CompanyReadAccess;
import kr.tna.org.OrgCommands;
import kr.tna.org.OrgException;
import kr.tna.worktime.AttendanceLabel;
import org.springframework.stereotype.Service;

/** 담당자 '지각·조퇴·결근' 탭: 월의 직원·날짜별 최신 결과 중 지각·조퇴·결근만 보인다. */
@Service
public final class AttendanceExceptionQueries {
    static final List<String> KINDS = List.of("ALL", "LATE", "EARLY_LEAVE", "ABSENT");
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    public record Row(UUID employeeId, String employeeNo, String employeeName, LocalDate workDate, String label) { }
    public record Counts(int late, int earlyLeave, int absent) { }
    public record View(String month, String kind, Counts counts, List<Row> rows, boolean truncated, String error) { }
    public record Result(UUID employeeId, String employeeNo, String employeeName, LocalDate workDate,
            Long lateMillis, Long earlyLeaveMillis, String absence) { }
    private static final int LIMIT = 1000;

    private final CompanyReadAccess company;
    private final OrgCommands administration;
    private final ProfileCompanyMapper mapper;
    private final Clock clock;

    public AttendanceExceptionQueries(CompanyReadAccess company, OrgCommands administration, ProfileCompanyMapper mapper,
            Clock clock) {
        this.company = company;
        this.administration = administration;
        this.mapper = mapper;
        this.clock = clock;
    }

    public View read(TnaPrincipal actor, String requestedMonth, String kind) {
        if (actor == null || !actor.roles().contains("ATTENDANCE_ADMIN")) throw OrgException.forbidden();
        return company.read(actor, () -> administration.read(actor, () -> month(actor.tenantId(), requestedMonth, kind)));
    }

    private View month(UUID tenantId, String requestedMonth, String kind) {
        var latest = YearMonth.from(LocalDate.now(clock.withZone(SEOUL)));
        var none = new Counts(0, 0, 0);
        YearMonth month = latest;
        if (requestedMonth != null) {
            if (!requestedMonth.matches("[0-9]{4}-(0[1-9]|1[0-2])") || YearMonth.parse(requestedMonth).getYear() == 0
                    || YearMonth.parse(requestedMonth).isAfter(latest))
                return new View(requestedMonth, KINDS.contains(kind) ? kind : "ALL", none, List.of(), false, "조회 월을 확인해 주세요.");
            month = YearMonth.parse(requestedMonth);
        }
        if (!KINDS.contains(kind)) return new View(month.toString(), "ALL", none, List.of(), false, "올바른 구분을 선택해 주세요.");
        LocalDate from = month.atDay(1), to = month.plusMonths(1).atDay(1);
        var results = mapper.attendanceExceptions(tenantId, from, to, kind);
        var rows = results.stream().limit(LIMIT).map(r -> new Row(r.employeeId(), r.employeeNo(), r.employeeName(), r.workDate(),
                AttendanceLabel.of(r.lateMillis(), r.earlyLeaveMillis(), r.absence()))).toList();
        return new View(month.toString(), kind, mapper.attendanceExceptionCounts(tenantId, from, to), rows,
                results.size() > LIMIT, null);
    }
}
