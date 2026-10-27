package kr.tna.org;

import java.util.UUID;
import kr.tna.common.auth.InvitationController;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.ModelAndView;

@ControllerAdvice(assignableTypes = {OrgAdminController.class, AccountAdminController.class, InvitationController.class, kr.tna.schedule.ScheduleController.class, kr.tna.clock.AttendanceController.class, kr.tna.common.web.TodayController.class, kr.tna.request.RequestController.class, kr.tna.worktime.WorktimeController.class, kr.tna.workspace.WorkspaceController.class, kr.tna.workspace.ProfileCompanyController.class})
public class OrgErrorAdvice {
    @ExceptionHandler(OrgException.class)
    ModelAndView domain(OrgException exception) { return problem(exception.status(), exception.getMessage()); }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ModelAndView malformed() { return problem(400, "입력 형식을 확인해 주세요."); }

    @ExceptionHandler(DataAccessException.class)
    ModelAndView unavailable() { return problem(503, "일시적으로 처리할 수 없습니다. 잠시 후 다시 확인해 주세요."); }

    private ModelAndView problem(int status, String message) {
        var view = new ModelAndView("org/problem").addObject("status", status).addObject("message", message)
                .addObject("traceId", UUID.randomUUID());
        view.setStatus(HttpStatusCode.valueOf(status));
        return view;
    }
}
