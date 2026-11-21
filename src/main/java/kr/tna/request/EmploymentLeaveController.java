package kr.tna.request;

import jakarta.servlet.http.HttpServletRequest;
import java.time.LocalDate;
import java.util.Set;
import java.util.UUID;
import kr.tna.common.auth.LocalDemoLogin;
import kr.tna.common.auth.TnaPrincipal;
import kr.tna.org.EmployeeService;
import kr.tna.org.OrgCommands;
import kr.tna.org.OrgData.Employee;
import kr.tna.org.OrgException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.ModelAndView;

@Controller
public final class EmploymentLeaveController {
    static final String PATH = "/admin/leave-balances/employment";
    private static final Set<String> FILTERS = Set.of("q", "page", "employeeId");
    private final EmployeeService people;
    private final EmploymentAccrualService monthly;
    private final LeaveExitService exits;
    private final OrgCommands commands;
    private final LocalDemoLogin demo;

    public EmploymentLeaveController(EmployeeService people, EmploymentAccrualService monthly, LeaveExitService exits,
            OrgCommands commands, LocalDemoLogin demo) {
        this.people = people;
        this.monthly = monthly;
        this.exits = exits;
        this.commands = commands;
        this.demo = demo;
    }

    record Person(UUID id, String employeeNo, String employeeName, LocalDate joinedOn, LocalDate leftOn) {
        Person(Employee employee) {
            this(employee.id(), employee.employeeNo(), employee.displayName(), employee.joinedOn(), employee.leftOn());
        }
    }

    @GetMapping(PATH)
    ModelAndView directory(@AuthenticationPrincipal TnaPrincipal actor, @RequestParam(defaultValue="") String q,
            @RequestParam(defaultValue="0") int page, @RequestParam(required=false) UUID employeeId, HttpServletRequest request) {
        if (request.getParameterMap().entrySet().stream().anyMatch(p -> !FILTERS.contains(p.getKey()) || p.getValue().length != 1)) {
            throw new OrgException(400, "검색어·페이지·사원을 각각 하나씩 입력해 주세요.");
        }
        return commands.readShared(actor, () -> {
            var list = people.listOtherEmployees(actor, q, page);
            var rows = list.stream().limit(50).map(Person::new).toList();
            var view = new ModelAndView("request/employment-leave").addObject("q", q.strip()).addObject("page", page)
                    .addObject("employees", rows).addObject("hasNext", list.size() > 50)
                    .addObject("today", commands.today()).addObject("publicDemo", demo.publicDemo());
            if (employeeId != null) {
                if (employeeId.equals(actor.employeeId())) throw OrgException.forbidden();
                view.addObject("monthly", monthly.preview(actor, employeeId));
                view.addObject("exit", exits.preview(actor, employeeId));
            }
            return view;
        });
    }
}
