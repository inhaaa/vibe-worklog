package kr.tna.workspace;

import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import kr.tna.common.auth.TnaPrincipal;
import kr.tna.common.tenant.BusinessCommands;
import kr.tna.common.tenant.CompanyReadAccess;
import kr.tna.org.OrgException;
import kr.tna.worktime.WorktimeService;
import kr.tna.workspace.ProfileCompanyData.*;
import org.springframework.stereotype.Service;

@Service
public final class ProfileCompanyQueries {
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private final BusinessCommands personal;
    private final CompanyReadAccess company;
    private final ProfileCompanyMapper mapper;
    private final WorktimeService worktime;
    private final Clock clock;

    public ProfileCompanyQueries(BusinessCommands personal, CompanyReadAccess company, ProfileCompanyMapper mapper,
            WorktimeService worktime, Clock clock) {
        this.personal = personal;
        this.company = company;
        this.mapper = mapper;
        this.worktime = worktime;
        this.clock = clock;
    }

    public ProfileView profile(TnaPrincipal actor) {
        return personal.read(actor, () -> {
            mapper.lock(actor.tenantId());
            return personal.read(actor, () -> {
                var row = mapper.profile(actor.tenantId(), actor.accountId(), actor.employeeId(), today());
                if (row == null) throw OrgException.notFound();
                return new ProfileView(row.employeeNo(), row.employeeName(), row.companyName(), row.email(),
                        row.joinedOn(), row.leftOn(), row.organizationName(), row.scheduleName(), row.approverName(),
                        row.approverEligible(), List.copyOf(mapper.roles(actor.tenantId(), actor.accountId())), clock.instant());
            });
        });
    }

    public CompanyAttendance attendance(TnaPrincipal actor, YearMonth requestedMonth, UUID organizationId,
            UUID employeeId, int page, String q) {
        int offset = offset(page);
        String query = q == null ? "" : q.strip();
        if (query.length() > 100) throw OrgException.invalid("검색어는 100자 이내로 입력해 주세요.");
        return company.read(actor, () -> {
            LocalDate today = today();
            YearMonth month = requestedMonth == null ? YearMonth.from(today) : requestedMonth;
            year(month.getYear());
            requireOrganization(actor.tenantId(), organizationId);
            var found = mapper.employees(actor.tenantId(), today, organizationId, query, offset);
            var employees = List.copyOf(found.subList(0, Math.min(50, found.size())));
            var selected = employeeId == null ? (employees.isEmpty() ? null : employees.getFirst())
                    : mapper.employee(actor.tenantId(), today, organizationId, employeeId);
            if (employeeId != null && selected == null) throw OrgException.notFound();
            var days = selected == null ? List.<AttendanceDay>of()
                    : worktime.companyDays(actor, selected.id(), month).stream().map(day -> new AttendanceDay(
                            day.workDate(), day.current() ? day.result().scheduledMillis() : null,
                            day.current() ? day.result().actualWorkMillis() : null,
                            day.current() ? day.result().leaveDays() : null,
                            day.current() ? day.result().status().name() : "REVIEW_REQUIRED", day.current())).toList();
            return new CompanyAttendance(month, clock.instant(), organizationId, selected == null ? null : selected.id(),
                    query, page, found.size() > 50, List.copyOf(mapper.organizations(actor.tenantId())), employees, selected, days);
        });
    }

    public CompanyRequests requests(TnaPrincipal actor, Integer requestedYear, UUID organizationId,
            String requestedStatus, int page) {
        int offset = offset(page);
        String status = requestedStatus == null ? "" : requestedStatus.strip();
        if (!Set.of("", "PENDING", "APPROVED", "REJECTED", "WITHDRAWN").contains(status)) {
            throw OrgException.invalid("신청 상태를 확인해 주세요.");
        }
        return company.read(actor, () -> {
            LocalDate today = today();
            int selectedYear = requestedYear == null ? today.getYear() : requestedYear;
            year(selectedYear);
            requireOrganization(actor.tenantId(), organizationId);
            var found = mapper.requests(actor.tenantId(), today, organizationId, status,
                    LocalDate.of(selectedYear, 1, 1), LocalDate.of(selectedYear, 12, 31), offset);
            return new CompanyRequests(selectedYear, clock.instant(), organizationId, status, page, found.size() > 50,
                    List.copyOf(mapper.organizations(actor.tenantId())), List.copyOf(found.subList(0, Math.min(50, found.size()))));
        });
    }

    private void requireOrganization(UUID tenantId, UUID id) {
        if (id != null && !mapper.organizationExists(tenantId, id)) throw OrgException.notFound();
    }

    private static int offset(int page) {
        if (page < 0 || page > 100_000) throw OrgException.invalid("올바른 조회 페이지를 선택해 주세요.");
        return page * 50;
    }

    private static void year(int value) {
        if (value < 1 || value > 9999) throw OrgException.invalid("1~9999년의 조회 기간을 선택해 주세요.");
    }

    private LocalDate today() { return LocalDate.now(clock.withZone(SEOUL)); }
}
