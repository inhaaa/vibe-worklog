package kr.tna.overtime;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import kr.tna.clock.ClockService;
import kr.tna.org.EmployeeAdminMapper;
import kr.tna.org.OrgCommands;
import kr.tna.org.OrgException;
import kr.tna.schedule.WorkTimeCodeData.Break;
import kr.tna.workspace.PersonalScheduleMapper;
import org.springframework.stereotype.Service;

@Service
public final class OvertimePolicyService {
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    private final ClockService clocks;
    private final PersonalScheduleMapper schedules;
    private final EmployeeAdminMapper employees;
    private final Clock clock;

    public OvertimePolicyService(ClockService clocks, PersonalScheduleMapper schedules,
                                 EmployeeAdminMapper employees, Clock clock) {
        this.clocks = clocks;
        this.schedules = schedules;
        this.employees = employees;
        this.clock = clock;
    }

    public record Window(String kind, LocalTime start, LocalTime end, List<Break> breaks, int minutes) {}
    public record Day(LocalDate date, UUID scheduleVersionId, UUID codeVersionId, UUID calendarVersionId,
                      String groupName, String workCode, String dayKind, String unavailableReason,
                      List<Window> windows) {}
    public record Basis(UUID scheduleVersionId, UUID codeVersionId, UUID calendarVersionId,
                        int requestedMinutes, String basisToken) {}

    /** The caller authorizes the employee and establishes the tenant transaction before reading policy. */
    public Day day(UUID tenant, UUID employee, LocalDate date) {
        if (date == null || date.getYear() < 1 || date.getYear() > 9999)
            throw OrgException.invalid("근무일을 확인해 주세요.");
        var snapshot = clocks.snapshot(tenant, employee, date);
        var member = employees.get(tenant, employee);
        if (member == null) throw OrgException.notFound();
        UUID schedule = snapshot.scheduleVersionId();
        var policy = schedule == null ? null : schedules.policy(tenant, schedule, date.getDayOfWeek().getValue());
        String kind = snapshot.dayKind();
        String reason = null;
        if (!member.active() || date.isBefore(member.joinedOn()) || member.leftOn() != null && date.isAfter(member.leftOn()))
            reason = "활성 사원의 재직 기간 안에서 신청할 수 있습니다.";
        else if (policy == null) reason = "근무 미배정입니다.";
        else if (!policy.group()) reason = "기존 고정배정에는 연장근무 신청 기준이 없습니다.";
        else if ("HOLIDAY".equals(kind)) reason = "회사 휴일에는 연장근무 사전신청을 할 수 없습니다.";
        else if (!snapshot.schedule().workday() || "NON_WORKDAY".equals(kind))
            reason = "비근로일에는 연장근무 사전신청을 할 수 없습니다.";
        else if (!"WORKDAY".equals(kind) || snapshot.calendarVersionId() == null)
            reason = "근무일의 회사 캘린더를 확인해 주세요.";
        else if (policy.codeVersionId() == null || policy.beforeStart() == null && policy.afterStart() == null)
            reason = "이 근무시간코드에는 연장 신청 허용 시간이 없습니다.";

        var windows = new ArrayList<Window>(2);
        if (reason == null) {
            var breaks = schedules.breaks(tenant, policy.codeVersionId());
            addWindow(windows, "BEFORE", policy.beforeStart(), policy.beforeEnd(), breaks);
            addWindow(windows, "AFTER", policy.afterStart(), policy.afterEnd(), breaks);
        }
        return new Day(date, schedule, policy == null ? null : policy.codeVersionId(), snapshot.calendarVersionId(),
                policy == null || !policy.group() ? null : policy.name(), policy == null ? null : policy.workCode(),
                kind, reason, List.copyOf(windows));
    }

    public Basis evaluate(UUID tenant, UUID employee, LocalDate date, LocalTime start, LocalTime end, boolean requireAdvance) {
        if (start == null || end == null || start.getSecond() != 0 || start.getNano() != 0
                || end.getSecond() != 0 || end.getNano() != 0 || !end.isAfter(start))
            throw OrgException.invalid("같은 근무일에 시작보다 늦은 종료 시각을 분 단위로 입력해 주세요.");
        var day = day(tenant, employee, date);
        if (day.unavailableReason() != null) throw OrgException.invalid(day.unavailableReason());
        if (requireAdvance) requireAdvance(date, start);
        var window = day.windows().stream().filter(item -> !start.isBefore(item.start()) && !end.isAfter(item.end()))
                .findFirst().orElseThrow(() -> OrgException.invalid("출근 전 또는 퇴근 후의 허용 구간 하나 안에서 신청해 주세요."));
        int minutes = netMinutes(start, end, window.breaks());
        if (minutes == 0) throw OrgException.invalid("예정 휴게를 제외한 연장 신청 시간이 있어야 합니다.");
        String token = OrgCommands.fingerprint(tenant, employee, date, start, end, day.scheduleVersionId(),
                day.codeVersionId(), day.calendarVersionId(), window.kind(), minutes);
        return new Basis(day.scheduleVersionId(), day.codeVersionId(), day.calendarVersionId(), minutes, token);
    }

    public void requireAdvance(LocalDate date, LocalTime start) {
        if (date == null || start == null || !clock.instant().isBefore(date.atTime(start).atZone(SEOUL).toInstant()))
            throw OrgException.invalid("사전신청은 연장근무 시작 전까지 가능합니다.");
    }

    private static void addWindow(List<Window> windows, String kind, LocalTime start, LocalTime end, List<Break> breaks) {
        if (start == null) return;
        var pauses = breaks.stream().filter(pause -> kind.equals(pause.kind())).toList();
        windows.add(new Window(kind, start, end, pauses, netMinutes(start, end, pauses)));
    }

    private static int netMinutes(LocalTime start, LocalTime end, List<Break> breaks) {
        long minutes = Duration.between(start, end).toMinutes();
        for (var pause : breaks) {
            LocalTime from = pause.start().isAfter(start) ? pause.start() : start;
            LocalTime until = pause.end().isBefore(end) ? pause.end() : end;
            if (until.isAfter(from)) minutes -= Duration.between(from, until).toMinutes();
        }
        return Math.toIntExact(minutes);
    }
}
