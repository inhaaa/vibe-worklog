package kr.tna.schedule;

import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;
import kr.tna.common.auth.TnaPrincipal;
import kr.tna.org.OrgException;
import kr.tna.schedule.WorkTimeCodeData.*;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping(path="/admin/schedules/workgroups/codes", produces=MediaType.APPLICATION_JSON_VALUE)
public final class WorkTimeCodeController {
    private final WorkTimeCodeService codes;
    public WorkTimeCodeController(WorkTimeCodeService codes) { this.codes=codes; }

    @GetMapping
    ResponseEntity<Catalog> list(@AuthenticationPrincipal TnaPrincipal actor,@RequestParam(required=false) LocalDate date) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(codes.catalog(actor,date));
    }
    @PostMapping(consumes=MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<Map<String,UUID>> save(@AuthenticationPrincipal TnaPrincipal actor,@RequestBody Command command) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(Map.of("id",codes.save(actor,command)));
    }
    @ExceptionHandler(OrgException.class)
    ResponseEntity<Map<String,String>> problem(OrgException e) {
        return ResponseEntity.status(e.status()).cacheControl(CacheControl.noStore()).body(Map.of("message",e.getMessage()));
    }
    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<Map<String,String>> unreadable() {
        return problem(new OrgException(400,"근무시간코드 입력 형식을 확인해 주세요."));
    }
}
