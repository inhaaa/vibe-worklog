package kr.tna.worktime;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import kr.tna.clock.CheckoutOmission;
import kr.tna.clock.ClockService;
import kr.tna.common.auth.TnaPrincipal;
import kr.tna.common.tenant.BusinessCommands;
import kr.tna.org.EmployeeAdminMapper;
import kr.tna.org.OrgCommands;
import kr.tna.org.OrgException;
import org.springframework.stereotype.Service;

@Service
public final class CheckoutFollowUpService {
    public record Form(UUID employeeId, LocalDate workDate, String reason, UUID commandId) { }
    public record DayKey(UUID employeeId, LocalDate workDate) { }
    public record Summary(UUID id, LocalDate workDate, String reason, String status, UUID approvalRequestId, UUID resultId) { }
    public record Inbox(List<Summary> rows, int page, boolean hasNext) { }

    private static final String ACTION = "CHECKOUT_FOLLOW_UP_REQUEST";
    private final WorktimeCalculator calculator = new WorktimeCalculator();
    private final CheckoutFollowUpMapper followUps;
    private final WorktimeMapper worktime;
    private final EmployeeAdminMapper employees;
    private final ClockService clocks;
    private final OrgCommands administration;
    private final BusinessCommands commands;

    public CheckoutFollowUpService(CheckoutFollowUpMapper followUps, WorktimeMapper worktime, EmployeeAdminMapper employees,
            ClockService clocks, OrgCommands administration, BusinessCommands commands) {
        this.followUps = followUps;
        this.worktime = worktime;
        this.employees = employees;
        this.clocks = clocks;
        this.administration = administration;
        this.commands = commands;
    }

    public UUID request(TnaPrincipal actor, Form form) {
        if (form == null || form.employeeId() == null || form.workDate() == null) throw OrgException.invalid("대상 사원과 근무일을 확인해 주세요.");
        String reason = OrgCommands.requiredText(form.reason(), 500, "보완 요청 사유");
        LocalDate date = form.workDate();
        UUID target = form.employeeId();
        return administration.execute(actor, form.commandId(), ACTION, "CHECKOUT_FOLLOW_UP",
                OrgCommands.fingerprint(ACTION, target, date, reason), reason, () -> {
            var employee = employees.get(actor.tenantId(), target);
            if (employee == null) throw OrgException.notFound();
            if (!date.isBefore(administration.today()) || date.isBefore(employee.joinedOn())
                    || employee.leftOn() != null && date.isAfter(employee.leftOn())) {
                throw OrgException.invalid("어제까지의 재직 중 근무일을 선택해 주세요.");
            }
            var snapshot = clocks.snapshot(actor.tenantId(), target, date);
            var leave = WorktimeMapper.LeaveEffect.totalDays(worktime.leaveEffects(actor.tenantId(), target, date));
            boolean pending = !worktime.pendingCorrections(actor.tenantId(), target, date).isEmpty();
            if (!CheckoutOmission.eligible(snapshot, leave, pending)) throw OrgException.conflict("이미 처리됐거나 퇴근 누락 보완 대상이 아닙니다. 목록을 다시 조회해 주세요.");
            UUID id = UUID.randomUUID();
            if (followUps.insert(actor.tenantId(), id, target, date, reason, actor.accountId()) != 1) {
                throw OrgException.conflict("같은 사원·근무일에 진행 중인 보완 요청이 있습니다.");
            }
            return new OrgCommands.Change(id, null, Map.of("employeeId", target, "workDate", date, "status", "REQUESTED"));
        });
    }

    public Inbox mine(TnaPrincipal actor, int page, int size) {
        if (page < 0 || page > 100_000 || size < 1 || size > 50) throw OrgException.invalid("조회 페이지를 확인해 주세요.");
        return commands.read(actor, () -> {
            var rows = followUps.mine(actor.tenantId(), actor.employeeId(), size + 1, page * size);
            return new Inbox(rows.stream().limit(size).map(this::summary).toList(), page, rows.size() > size);
        });
    }

    /** Caller has selected these employee-days in an authorized tenant month read transaction. */
    Map<DayKey, Summary> openForDays(UUID tenantId, List<DayKey> keys) {
        if (keys.isEmpty()) return Map.of();
        Map<DayKey, Summary> result = new HashMap<>();
        for (var row : followUps.forDays(tenantId, keys)) result.put(new DayKey(row.employeeId(), row.workDate()), summary(row));
        return Map.copyOf(result);
    }

    private Summary summary(CheckoutFollowUpMapper.OpenRow row) {
        String status = "REQUESTED";
        UUID linkedRequest = row.lastRequestId();
        if ("PENDING".equals(row.requestStatus())) {
            status = "PENDING_APPROVAL";
        } else if (linkedRequest != null && row.effectiveRequestId() != null) {
            var snapshot = MonthEndReviewQueries.snapshot(clocks, row);
            var calculation = calculator.day(WorktimeService.dayInput(snapshot, false, false, row.approvedLeaveDays()));
            if (calculation.actualWorkMillis() != null || calculation.presenceMillis() != null) {
                status = calculation.actualWorkMillis() != null ? "AWAITING_CALCULATION" : "NEEDS_CONFIRMATION";
                linkedRequest = row.effectiveRequestId();
            }
        }
        return new Summary(row.id(), row.workDate(), row.reason(), status, linkedRequest, null);
    }

}
