package kr.tna.console;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;
import java.util.function.Supplier;
import kr.tna.console.OperatorData.*;
import kr.tna.org.OrgException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.servlet.view.RedirectView;

@Controller @ConditionalOnProperty(name="tna.operator.enabled",havingValue="true")
public final class OperatorController {
    private final OperatorService operators;
    public OperatorController(OperatorService operators){this.operators=operators;}
    @GetMapping("/operator/login") ModelAndView login(){return new ModelAndView("operator/login");}
    @GetMapping("/operator/tenants") ModelAndView tenants(@AuthenticationPrincipal OperatorPrincipal actor){return page(actor,null);}
    @PostMapping("/operator/tenants") ModelAndView onboard(@AuthenticationPrincipal OperatorPrincipal actor,@ModelAttribute("form") TenantForm form,BindingResult binding){if(binding.hasErrors())throw new OrgException(400,"입사일·명령 식별자의 형식을 확인해 주세요.");return submit(()->operators.onboard(actor,form),()->page(actor,form));}
    @PostMapping("/operator/tenants/{id}/state") ModelAndView state(@AuthenticationPrincipal OperatorPrincipal actor,@PathVariable UUID id,@ModelAttribute StateForm form,BindingResult binding){if(binding.hasErrors())throw new OrgException(400,"회사 버전·명령 식별자의 형식을 확인해 주세요.");return submit(()->operators.state(actor,id,form),()->page(actor,null));}
    @PostMapping("/operator/tenants/{id}/initial-invitations") ModelAndView reissue(@AuthenticationPrincipal OperatorPrincipal actor,@PathVariable UUID id,@ModelAttribute ReissueForm form,BindingResult binding){if(binding.hasErrors())throw new OrgException(400,"회사 버전·명령 식별자의 형식을 확인해 주세요.");return submit(()->operators.reissue(actor,id,form),()->page(actor,null));}
    private ModelAndView page(OperatorPrincipal actor,TenantForm form){return new ModelAndView("operator/tenants").addObject("tenants",operators.tenants(actor)).addObject("audit",operators.audit(actor)).addObject("form",form!=null?form:new TenantForm("","","","",LocalDate.now(ZoneId.of("Asia/Seoul")),"","","",UUID.randomUUID()));}
    private ModelAndView submit(Supplier<UUID> action,Supplier<ModelAndView> page){try{action.get();var r=new RedirectView("/operator/tenants?saved",true);r.setStatusCode(HttpStatus.SEE_OTHER);return new ModelAndView(r);}catch(OrgException e){if(e.status()!=409&&e.status()!=422)throw e;var v=page.get().addObject("error",e.getMessage());v.setStatus(HttpStatusCode.valueOf(e.status()));return v;}}
    @ExceptionHandler(OrgException.class) ModelAndView domain(OrgException e){var v=new ModelAndView("org/problem").addObject("status",e.status()).addObject("message",e.getMessage()).addObject("traceId",UUID.randomUUID());v.setStatus(HttpStatusCode.valueOf(e.status()));return v;}
    @ExceptionHandler(org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class) ModelAndView malformed(){return domain(new OrgException(400,"입력 형식을 확인해 주세요."));}
}
