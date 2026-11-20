package kr.tna.request;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.time.LocalDate;
import java.util.UUID;
import kr.tna.org.OrgException;
import kr.tna.request.RequestData.AttendancePolicy;
import kr.tna.request.RequestData.RequestView;
import org.junit.jupiter.api.Test;

class CompanyLeavePolicyTest {
    private final UUID tenant = UUID.randomUUID();
    private final LeaveWorkflowMapper mapper = mock(LeaveWorkflowMapper.class);
    private final AttendanceTypeResolver resolver = new AttendanceTypeResolver(mapper);

    @Test void approvedAndCanceledRequestsUseTheSubmittedCompanyPolicy() {
        var version = policy("DAY_HALF", "PAID", "NONE", "COMPANY_LEAVE");
        assertSame(version, resolver.stored(tenant, request("LEAVE_DAY", version)));
        assertSame(version, resolver.stored(tenant, request("CANCEL_LEAVE", version)));
    }

    @Test void aCompanyPolicyCannotDriveACorrectionAndADraftCannotDriveLeave() {
        var version = policy("DAY", "UNPAID", "LEAVE_DAY", "COMPANY_LEAVE");
        assertThrows(OrgException.class, () -> resolver.stored(tenant, request("ATTENDANCE_CORRECTION", version)));
        var draft = policy("HOUR", "UNSPECIFIED", "NONE", "NOT_SUPPORTED");
        assertThrows(OrgException.class, () -> resolver.stored(tenant, request("LEAVE_DAY", draft)));
    }

    private AttendancePolicy policy(String unit, String pay, String deduction, String recognition) {
        var policy = new AttendancePolicy(UUID.randomUUID(), UUID.randomUUID(), "L10", null, 1,
                LocalDate.of(2026, 1, 1), true, "회사 휴가", unit, pay, deduction, recognition, "회사 기준");
        when(mapper.attendancePolicy(tenant, policy.id())).thenReturn(policy);
        return policy;
    }

    private static RequestView request(String kind, AttendancePolicy policy) {
        var request = mock(RequestView.class);
        when(request.kind()).thenReturn(kind);
        when(request.attendanceTypeVersionId()).thenReturn(policy.id());
        return request;
    }
}
