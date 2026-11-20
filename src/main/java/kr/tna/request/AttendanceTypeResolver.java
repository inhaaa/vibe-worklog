package kr.tna.request;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import kr.tna.codes.CompanyLeavePolicy;
import kr.tna.org.OrgException;
import kr.tna.request.AnnualRequestData.LeaveTypeOption;
import kr.tna.request.RequestData.AttendancePolicy;
import kr.tna.request.RequestData.LeaveForm;
import kr.tna.request.RequestData.RequestView;

/** Resolves the dated policy while the enclosing command holds the tenant lock. */
final class AttendanceTypeResolver {
    private final LeaveWorkflowMapper mapper;

    AttendanceTypeResolver(LeaveWorkflowMapper mapper) { this.mapper=mapper; }

    AttendancePolicy forSubmission(UUID tenant,String kind,LocalDate from,LocalDate to) {
        String systemKind=systemKind(kind);
        var policy=mapper.effectiveAttendancePolicy(tenant,systemKind,from);
        return checkPeriod(tenant,kind,from,to,policy);
    }

    AttendancePolicy forLeave(UUID tenant, LeaveForm form, boolean submitting) {
        if (form.attendanceTypeId() == null) {
            if (form.expectedAttendanceTypeVersionId() != null) throw OrgException.invalid("휴가 종류를 선택해 주세요.");
            return forSubmission(tenant, "LEAVE_DAY", form.from(), form.to());
        }
        var policy = mapper.effectivePolicyByType(tenant, form.attendanceTypeId(), form.from());
        if (policy == null) throw OrgException.notFound();
        var expected = form.expectedAttendanceTypeVersionId();
        if ((submitting || expected != null) && !policy.id().equals(expected)) {
            throw OrgException.conflict("휴가 기준이 변경되었습니다. 새 적용 기준을 확인하고 다시 제출해 주세요.");
        }
        return checkPeriod(tenant,"LEAVE_DAY",form.from(),form.to(),policy);
    }

    List<LeaveTypeOption> leaveOptions(UUID tenant, LocalDate date) {
        return mapper.effectiveLeavePolicies(tenant,date).stream().filter(AttendanceTypeResolver::supportsLeave)
                .map(LeaveTypeOption::from).toList();
    }

    private AttendancePolicy checkPeriod(UUID tenant,String kind,LocalDate from,LocalDate to,AttendancePolicy policy) {
        requireSupported(policy,kind);
        if(mapper.attendancePolicyBoundary(tenant,policy.typeId(),from,to))
            throw OrgException.invalid("선택 기간에 근태코드 정책이 변경됩니다. 적용일을 기준으로 나누어 신청해 주세요.");
        return policy;
    }

    AttendancePolicy stored(UUID tenant,RequestView request) {
        var policy=request.attendanceTypeVersionId()==null?null:mapper.attendancePolicy(tenant,request.attendanceTypeVersionId());
        requireSupported(policy,request.kind());
        return policy;
    }

    private static void requireSupported(AttendancePolicy policy,String kind) {
        boolean supported = "ANNUAL_LEAVE".equals(systemKind(kind)) ? supportsLeave(policy)
                : supportedSystem(policy,"ATTENDANCE_CORRECTION","NONE","NONE");
        if(!supported) throw OrgException.invalid("신청 가능한 지원 근태코드 정책이 아닙니다. 담당자에게 사용 상태와 정책을 확인해 주세요.");
    }

    private static boolean supportsLeave(AttendancePolicy policy) {
        return supportedSystem(policy,"ANNUAL_LEAVE","DAY","LEAVE_DAY")
                || (policy != null && policy.enabled() && policy.systemKind() == null
                    && CompanyLeavePolicy.supports(policy.unit(),policy.payKind(),policy.deductionRule(),policy.recognitionRule()));
    }

    private static boolean supportedSystem(AttendancePolicy policy,String kind,String unit,String deduction) {
        return policy!=null && policy.enabled() && kind.equals(policy.systemKind()) && unit.equals(policy.unit())
                && deduction.equals(policy.deductionRule()) && "NOT_SUPPORTED".equals(policy.recognitionRule())
                && policy.payKind()!=null && Set.of("UNSPECIFIED","PAID","UNPAID").contains(policy.payKind());
    }

    private static String systemKind(String kind) {
        return switch(kind) {
            case "LEAVE_DAY", "CANCEL_LEAVE" -> "ANNUAL_LEAVE";
            case "ATTENDANCE_CORRECTION" -> "ATTENDANCE_CORRECTION";
            default -> throw OrgException.invalid("지원하지 않는 신청 종류입니다.");
        };
    }
}
