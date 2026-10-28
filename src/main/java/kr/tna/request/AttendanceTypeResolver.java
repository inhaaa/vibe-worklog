package kr.tna.request;

import java.time.LocalDate;
import java.util.Set;
import java.util.UUID;
import kr.tna.org.OrgException;
import kr.tna.request.RequestData.AttendancePolicy;
import kr.tna.request.RequestData.RequestView;

/** Resolves only supported system handlers inside the enclosing tenant command's lock. */
final class AttendanceTypeResolver {
    private final LeaveWorkflowMapper mapper;

    AttendanceTypeResolver(LeaveWorkflowMapper mapper) { this.mapper=mapper; }

    AttendancePolicy forSubmission(UUID tenant,String kind,LocalDate from,LocalDate to) {
        String systemKind=systemKind(kind);
        var policy=mapper.effectiveAttendancePolicy(tenant,systemKind,from);
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
        String expected=systemKind(kind);
        boolean supported=policy!=null && expected.equals(policy.systemKind()) && policy.enabled()
                && "NOT_SUPPORTED".equals(policy.recognitionRule())
                && policy.payKind()!=null && Set.of("UNSPECIFIED","PAID","UNPAID").contains(policy.payKind());
        if(supported) supported="ANNUAL_LEAVE".equals(expected)
                ?"DAY".equals(policy.unit()) && "LEAVE_DAY".equals(policy.deductionRule())
                :"NONE".equals(policy.unit()) && "NONE".equals(policy.deductionRule());
        if(!supported) throw OrgException.invalid("신청 가능한 지원 근태코드 정책이 아닙니다. 담당자에게 사용 상태와 정책을 확인해 주세요.");
    }

    private static String systemKind(String kind) {
        return switch(kind) {
            case "LEAVE_DAY", "CANCEL_LEAVE" -> "ANNUAL_LEAVE";
            case "ATTENDANCE_CORRECTION" -> "ATTENDANCE_CORRECTION";
            default -> throw OrgException.invalid("지원하지 않는 신청 종류입니다.");
        };
    }
}
