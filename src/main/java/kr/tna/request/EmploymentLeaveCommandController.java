package kr.tna.request;

import java.io.InputStream;
import java.util.Map;
import java.util.UUID;
import kr.tna.common.auth.TnaPrincipal;
import kr.tna.org.OrgException;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.ObjectReader;
import tools.jackson.databind.json.JsonMapper;

@RestController
@RequestMapping(value=EmploymentLeaveController.PATH, consumes=MediaType.APPLICATION_JSON_VALUE, produces=MediaType.APPLICATION_JSON_VALUE)
public final class EmploymentLeaveCommandController {
    private final EmploymentAccrualService monthly;
    private final LeaveExitService exits;
    private final ObjectReader commands;

    public EmploymentLeaveCommandController(EmploymentAccrualService monthly, LeaveExitService exits, JsonMapper json) {
        this.monthly = monthly;
        this.exits = exits;
        this.commands = json.rebuild().disable(MapperFeature.ALLOW_COERCION_OF_SCALARS).build().reader()
                .with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    }

    @PostMapping("/monthly")
    Map<String, UUID> review(@AuthenticationPrincipal TnaPrincipal actor, InputStream body) {
        return Map.of("id", monthly.post(actor, read(body, EmploymentAccrualData.PostForm.class)));
    }

    @PostMapping("/settle")
    Map<String, UUID> settle(@AuthenticationPrincipal TnaPrincipal actor, InputStream body) {
        return Map.of("id", exits.post(actor, read(body, LeaveExitData.PostForm.class)));
    }

    private <T> T read(InputStream input, Class<T> type) {
        try {
            T form = commands.forType(type).readValue(input);
            if (form != null) return form;
        } catch (JacksonException invalid) {
            throw new OrgException(400, "날짜·확인 항목과 요청 형식을 확인해 주세요.");
        }
        throw new OrgException(400, "처리할 내용을 입력해 주세요.");
    }

    @ExceptionHandler(OrgException.class)
    ResponseEntity<Map<String, String>> error(OrgException error) {
        return ResponseEntity.status(error.status()).body(Map.of("message", error.getMessage()));
    }
}
