package kr.tna.org;

import static kr.tna.org.OrganizationHeadData.*;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;

/** Plans one change without writing so the preview and the transaction use the same date rules. */
final class HeadScheduleChange {
    private static final LocalDate LATEST = LocalDate.of(9999, 12, 31);

    private HeadScheduleChange() { }

    record Plan(HeadAssignment target, LocalDate closeAt, HeadPreview preview) { }

    static Plan plan(HeadCommand form, OrganizationOption organization, LocalDate today,
            List<HeadAssignment> history, HeadAssignment target, OrgData.Employee employee) {
        var other = history.stream().filter(row -> row.supersededAt() == null && row != target)
                .sorted(Comparator.comparing(HeadAssignment::validFrom)).toList();
        var operation = form.operation();
        boolean futureOnly = operation == HeadOperation.CANCEL || operation == HeadOperation.RESCHEDULE;
        if (futureOnly && !target.validFrom().isAfter(today)) {
            throw OrgException.invalid("아직 시작하지 않은 배정만 예약을 수정하거나 취소할 수 있습니다.");
        }
        if ((operation == HeadOperation.REPLACE || operation == HeadOperation.CHANGE_END)
                && !covers(target, today)) {
            throw OrgException.invalid("오늘 유효한 배정을 선택해 주세요.");
        }
        if (operation == HeadOperation.CANCEL) {
            return removal(target, target.validFrom(), other);
        }
        if (operation == HeadOperation.CHANGE_END && form.endsToday()) {
            if (form.lastDay() != null) throw OrgException.invalid("오늘부터 미배정과 종료일 중 하나를 선택해 주세요.");
            return removal(target, today, other);
        }
        if (form.endsToday()) throw OrgException.invalid("오늘부터 미배정은 현재 배정에서 선택해 주세요.");

        boolean changingEnd = operation == HeadOperation.CHANGE_END;
        if (!organization.active() && !changingEnd) throw OrgException.invalid("활성 조직에만 조직장을 배정할 수 있습니다.");
        LocalDate from = changingEnd ? target.validFrom() : form.validFrom();
        if (!changingEnd && (from == null || from.isBefore(today) || from.isAfter(LATEST))) {
            throw OrgException.invalid("시작일은 서울 기준 오늘 이후의 올바른 날짜로 지정해 주세요.");
        }
        if (operation == HeadOperation.REPLACE && !covers(target, from)) {
            throw OrgException.invalid("후임 시작일 " + from + "은 선택한 전임의 담당 기간에 속해야 합니다.");
        }
        LocalDate minEnd = changingEnd ? today : from;
        if (form.lastDay() != null && (form.lastDay().isBefore(minEnd) || form.lastDay().isAfter(LATEST))) {
            throw OrgException.invalid("종료일은 " + minEnd + " 이후의 올바른 날짜로 지정해 주세요.");
        }
        var overlap = other.stream().filter(row -> covers(row, from)).findFirst().orElse(null);
        if (overlap != null) throw OrgException.invalid(from + "에 이미 다른 조직장 배정이 있습니다.");
        var next = next(other, from);
        boolean extending = changingEnd && target.validTo() != null
                && (form.lastDay() == null || !form.lastDay().isBefore(target.validTo()));
        LocalDate employmentLimit = employee.leftOn();
        if (changingEnd && !extending && employmentLimit != null && employmentLimit.isBefore(today)) {
            employmentLimit = null;
        }
        LocalDate limit = earlier(next == null ? null : lastDay(next.validFrom()), employmentLimit);
        if (operation == HeadOperation.REPLACE) limit = earlier(limit, lastDay(target.validTo()));
        if (form.lastDay() != null && limit != null && form.lastDay().isAfter(limit)) {
            throw OrgException.invalid("가능한 마지막 담당일은 " + limit + "입니다. 다음 예약 또는 재직 기간을 확인해 주세요.");
        }
        LocalDate lastDay = form.lastDay() == null ? limit : form.lastDay();
        if (lastDay != null && lastDay.isBefore(minEnd)) {
            throw OrgException.invalid("재직 기간에 맞춰 담당자를 재지정하거나 오늘부터 미배정으로 처리해 주세요.");
        }
        if (changingEnd && target.validTo() != null
                && (lastDay == null || !lastDay.isBefore(target.validTo()))
                && (!employee.active() || employee.joinedOn().isAfter(today) || !organization.active())) {
            throw OrgException.invalid("비활성 조직이나 사원의 담당 기간을 연장할 수 없습니다.");
        }
        var after = new HeadPeriod(employee.id(), employee.displayName(), from, lastDay);
        LocalDate oldLast = operation == HeadOperation.REPLACE && from.isAfter(target.validFrom()) ? lastDay(from) : null;
        var trailing = vacancy(boundary(lastDay), other);
        Vacancy moved = null;
        if (operation == HeadOperation.RESCHEDULE && from.isAfter(target.validFrom())) {
            var gap = vacancy(target.validFrom(), other);
            if (gap != null) moved = new Vacancy(gap.from(), earlier(gap.through(), lastDay(from)));
        }
        return new Plan(target, switch (operation) {
            case REPLACE -> from;
            case RESCHEDULE -> target.validFrom();
            default -> null;
        }, new HeadPreview(period(target), after, oldLast, period(next), moved == null ? trailing : moved,
                moved == null ? null : trailing));
    }

    static boolean covers(HeadAssignment row, LocalDate date) {
        return !row.validFrom().isAfter(date) && (row.validTo() == null || row.validTo().isAfter(date));
    }

    static HeadAssignment target(HeadCommand form, List<HeadAssignment> history) {
        if (form.operation() == HeadOperation.ASSIGN) {
            if (form.assignmentId() != null) throw OrgException.invalid("신규 배정에는 변경할 배정을 지정하지 마세요.");
            return null;
        }
        if (form.assignmentId() == null) throw OrgException.invalid("변경할 조직장 배정을 선택해 주세요.");
        var row = history.stream().filter(item -> item.id().equals(form.assignmentId())).findFirst().orElseThrow(OrgException::notFound);
        if (row.supersededAt() != null) throw OrgException.invalid("이미 변경되거나 취소된 배정입니다.");
        return row;
    }

    private static Plan removal(HeadAssignment target, LocalDate date, List<HeadAssignment> other) {
        return new Plan(target, date, new HeadPreview(period(target), null,
                date.isAfter(target.validFrom()) ? lastDay(date) : null,
                period(next(other, date)), vacancy(date, other), null));
    }

    private static Vacancy vacancy(LocalDate from, List<HeadAssignment> other) {
        if (from == null || from.isAfter(LATEST) || other.stream().anyMatch(row -> covers(row, from))) return null;
        var next = next(other, from);
        return new Vacancy(from, next == null ? null : lastDay(next.validFrom()));
    }

    private static HeadAssignment next(List<HeadAssignment> sorted, LocalDate from) {
        return sorted.stream().filter(row -> row.validFrom().isAfter(from)).findFirst().orElse(null);
    }

    static HeadPeriod period(HeadAssignment row) {
        return row == null ? null : new HeadPeriod(row.employeeId(), row.employeeName(), row.validFrom(), lastDay(row.validTo()));
    }

    static LocalDate boundary(LocalDate lastDay) { return lastDay == null ? null : lastDay.plusDays(1); }

    private static LocalDate lastDay(LocalDate boundary) { return boundary == null ? null : boundary.minusDays(1); }

    static LocalDate earlier(LocalDate first, LocalDate second) {
        if (first == null) return second;
        return second == null || first.isBefore(second) ? first : second;
    }
}
