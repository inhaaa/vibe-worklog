package kr.tna.request;

import java.util.UUID;
import kr.tna.common.auth.TnaPrincipal;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.ModelAndView;

@Controller
public final class LeaveOperationsController {
    private final LeaveOperationsQueries operations;

    LeaveOperationsController(LeaveOperationsQueries operations) { this.operations = operations; }

    @GetMapping(value = LeaveOperationsQueries.PATH, params = "scope=LEAVE")
    ModelAndView leave(@AuthenticationPrincipal TnaPrincipal actor,
            @RequestParam(defaultValue = "PENDING") String status, @RequestParam(defaultValue = "") String leaveTypeCode,
            @RequestParam(defaultValue = "") String requestKind, @RequestParam(defaultValue = "") String from,
            @RequestParam(defaultValue = "") String to, @RequestParam(required = false) UUID organizationId,
            @RequestParam(defaultValue = "") String q, @RequestParam(defaultValue = "0") int page) {
        var result = operations.read(actor, status, leaveTypeCode, requestKind, from, to, organizationId, q, page);
        var view = new ModelAndView("request/list").addObject("title", "휴가 신청 현황")
                .addObject("requestScope", RequestScope.LEAVE).addObject("leaveOperations", result);
        if (result.error() != null) view.setStatus(HttpStatus.UNPROCESSABLE_CONTENT);
        return view;
    }
}
