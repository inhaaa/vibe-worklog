package kr.tna.request;

import java.io.InputStream;
import java.util.Map;
import java.util.UUID;
import kr.tna.common.auth.TnaPrincipal;
import kr.tna.org.OrgException;
import kr.tna.request.AnnualLeaveCycleData.PostForm;
import kr.tna.request.AnnualLeaveCycleData.Preview;
import kr.tna.request.AnnualLeaveCycleData.Rule;
import kr.tna.request.AnnualLeaveCycleData.RuleForm;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectReader;
import tools.jackson.databind.json.JsonMapper;

@RestController
@RequestMapping(value = AnnualLeaveCycleController.ADMIN_PATH, consumes = MediaType.APPLICATION_JSON_VALUE,
        produces = MediaType.APPLICATION_JSON_VALUE)
public final class AnnualLeavePostingController {
    private final AnnualLeaveCycleService cycles;
    private final EmploymentAccrualService monthly;
    private final ObjectReader input;

    public AnnualLeavePostingController(AnnualLeaveCycleService cycles, EmploymentAccrualService monthly, JsonMapper json) {
        this.cycles = cycles;
        this.monthly = monthly;
        // A fractional year, month or revision must never select a different period by truncation.
        this.input = json.reader().without(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
                .with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    }

    public record PreviewForm(Integer year, String month) {}

    @PostMapping("/policy")
    Rule savePolicy(@AuthenticationPrincipal TnaPrincipal actor, InputStream body) {
        UUID id = cycles.saveRule(actor, form(body, RuleForm.class));
        return cycles.savedRule(actor, id);
    }

    @PostMapping("/preview")
    Preview preview(@AuthenticationPrincipal TnaPrincipal actor, InputStream body) {
        var form = form(body, PreviewForm.class);
        if (form.month() != null) return cycles.previewMonth(actor, AnnualLeaveCycleService.month(form.month()));
        if (form.year() == null) throw OrgException.invalid("미리 볼 회계연도나 월을 입력해 주세요.");
        return cycles.preview(actor, form.year());
    }

    @PostMapping("/apply")
    Map<String, UUID> apply(@AuthenticationPrincipal TnaPrincipal actor, InputStream body) {
        return Map.of("id", cycles.post(actor, form(body, PostForm.class)));
    }

    @PostMapping("/monthly-preview")
    EmploymentAccrualData.BulkPreview monthlyPreview(@AuthenticationPrincipal TnaPrincipal actor) {
        return monthly.bulkPreview(actor);
    }

    @PostMapping("/monthly-apply")
    Map<String, UUID> monthlyApply(@AuthenticationPrincipal TnaPrincipal actor, InputStream body) {
        return Map.of("id", monthly.postBulk(actor, form(body, EmploymentAccrualData.BulkForm.class)));
    }

    private <T> T form(InputStream body, Class<T> type) {
        try {
            T result = input.forType(type).readValue(body);
            if (result != null) return result;
        } catch (JacksonException invalid) {
            throw new OrgException(400, "회계연도·시작월·기준 버전은 정수로 입력하고 요청 형식을 확인해 주세요.");
        }
        throw new OrgException(400, "요청 내용을 입력해 주세요.");
    }

    @ExceptionHandler(OrgException.class)
    ResponseEntity<Map<String, String>> error(OrgException error) {
        return ResponseEntity.status(error.status()).body(Map.of("message", error.getMessage()));
    }
}
