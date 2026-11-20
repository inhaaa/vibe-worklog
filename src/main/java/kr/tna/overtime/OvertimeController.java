package kr.tna.overtime;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Map;
import java.util.UUID;
import kr.tna.common.auth.TnaPrincipal;
import kr.tna.org.OrgException;
import kr.tna.request.LeaveWorkflowService;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.ModelAndView;

@Controller
public class OvertimeController {
    public record Form(LocalDate date, LocalTime start, LocalTime end, String reason,
                       String basisToken, String routeToken, UUID commandId) {}
    private final OvertimeDirectory directory;
    private final LeaveWorkflowService workflow;

    public OvertimeController(OvertimeDirectory directory, LeaveWorkflowService workflow) {
        this.directory=directory;
        this.workflow=workflow;
    }
    @GetMapping("/me/overtime")
    ModelAndView employee(@AuthenticationPrincipal TnaPrincipal actor, @RequestParam(required=false) LocalDate date,
                          @RequestParam(defaultValue="0") int page) {
        return new ModelAndView("overtime/employee").addObject("overtime",directory.mine(actor,date,page));
    }
    @GetMapping("/admin/overtime")
    ModelAndView company(@AuthenticationPrincipal TnaPrincipal actor, @RequestParam(required=false) LocalDate from,
                         @RequestParam(required=false) LocalDate to, @RequestParam(defaultValue="") String q,
                         @RequestParam(defaultValue="") String status, @RequestParam(defaultValue="0") int page) {
        return new ModelAndView("overtime/admin").addObject("overtime",directory.company(actor,from,to,q,status,page));
    }
    @PostMapping(value="/me/overtime/preview", consumes="application/json")
    ResponseEntity<OvertimeDirectory.Preview> preview(@AuthenticationPrincipal TnaPrincipal actor,@RequestBody Form form) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(directory.preview(actor,form.date(),form.start(),form.end()));
    }
    @PostMapping(value="/me/overtime/submit", consumes="application/json")
    ResponseEntity<Map<String,UUID>> submit(@AuthenticationPrincipal TnaPrincipal actor,@RequestBody Form form) {
        UUID id=workflow.submitOvertime(actor,form.date(),form.start(),form.end(),form.reason(),
                form.basisToken(),form.routeToken(),form.commandId());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(Map.of("id",id));
    }
    @ExceptionHandler(OrgException.class)
    ResponseEntity<Map<String,String>> error(OrgException e) {
        return ResponseEntity.status(e.status()).cacheControl(CacheControl.noStore()).body(Map.of("message",e.getMessage()));
    }
    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<Map<String,String>> invalidInput() { return error(new OrgException(400,"근무일과 입력 시각을 확인해 주세요.")); }
}
