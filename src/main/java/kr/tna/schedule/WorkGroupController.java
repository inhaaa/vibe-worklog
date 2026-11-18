package kr.tna.schedule;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import kr.tna.common.auth.TnaPrincipal;
import kr.tna.org.OrgException;
import kr.tna.schedule.WorkGroupData.*;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping(value="/admin/schedules/workgroups", produces=MediaType.APPLICATION_JSON_VALUE)
public final class WorkGroupController {
    private final WorkGroupService groups;
    private final WorkGroupAssignmentService assignments;
    public WorkGroupController(WorkGroupService groups, WorkGroupAssignmentService assignments) {
        this.groups=groups;
        this.assignments=assignments;
    }

    @GetMapping("/groups")
    ResponseEntity<List<Group>> groups(@AuthenticationPrincipal TnaPrincipal actor,@RequestParam(required=false) LocalDate date) {
        return response(groups.groups(actor,date));
    }
    @GetMapping("/employees")
    ResponseEntity<List<Employee>> employees(@AuthenticationPrincipal TnaPrincipal actor,@RequestParam(required=false) LocalDate date,
            @RequestParam(defaultValue="") String q,@RequestParam(defaultValue="0") int page) {
        return response(assignments.employees(actor,date,q,page));
    }
    @PostMapping(value="/groups/preview", consumes=MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<GroupPreview> previewGroup(@AuthenticationPrincipal TnaPrincipal actor,@RequestBody GroupCommand form) {
        return response(groups.previewGroup(actor,form));
    }
    @PostMapping(value="/groups/save", consumes=MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<Map<String,UUID>> saveGroup(@AuthenticationPrincipal TnaPrincipal actor,@RequestBody GroupCommand form) {
        return response(Map.of("id",groups.saveGroup(actor,form)));
    }
    @PostMapping(value="/assignments/preview", consumes=MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<AssignmentPreview> previewAssignments(@AuthenticationPrincipal TnaPrincipal actor,@RequestBody AssignmentCommand form) {
        return response(assignments.previewAssignments(actor,form));
    }
    @PostMapping(value="/assignments/save", consumes=MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<Map<String,UUID>> saveAssignments(@AuthenticationPrincipal TnaPrincipal actor,@RequestBody AssignmentCommand form) {
        return response(Map.of("id",assignments.saveAssignments(actor,form)));
    }

    @ExceptionHandler(OrgException.class)
    ResponseEntity<Map<String,String>> error(OrgException error) {
        return ResponseEntity.status(error.status()).cacheControl(CacheControl.noStore()).body(Map.of("message",error.getMessage()));
    }
    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<Map<String,String>> invalid() {
        return error(OrgException.invalid("입력 형식과 날짜를 확인해 주세요."));
    }
    private static <T> ResponseEntity<T> response(T body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }
}
