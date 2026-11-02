package kr.tna.request;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Set;
import java.util.UUID;
import kr.tna.common.auth.TnaPrincipal;
import kr.tna.org.OrgException;
import kr.tna.request.LeaveAttributionData.Form;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.servlet.view.RedirectView;

@Controller
public final class LeaveAttributionController {
    private static final Set<String> PARAMETERS=Set.of("expectedRevision","classificationStatus","entitlementYear","singleYearConfirmed","_singleYearConfirmed","basis","reason","commandId","_csrf");
    private final LeaveAttributionService service;
    public LeaveAttributionController(LeaveAttributionService service){this.service=service;}
    @GetMapping("/admin/leave-balances/buckets/{id}/attributions")
    ModelAndView view(@AuthenticationPrincipal TnaPrincipal actor,@PathVariable UUID id){return page(actor,id,null);}
    @PostMapping("/admin/leave-balances/buckets/{id}/attributions")
    ModelAndView save(@AuthenticationPrincipal TnaPrincipal actor,@PathVariable UUID id,@ModelAttribute Form form,BindingResult binding,HttpServletRequest request) {
        request.getParameterMap().forEach((key,values)->{if(!PARAMETERS.contains(key)||values.length!=1)throw malformed();});
        if(binding.hasErrors())throw malformed();
        try {
            service.save(actor,id,form);var redirect=new RedirectView("/admin/leave-balances/buckets/"+id+"/attributions?saved",true);
            redirect.setStatusCode(HttpStatus.SEE_OTHER);return new ModelAndView(redirect);
        } catch(OrgException error) {
            if(error.status()!=409 && error.status()!=422)throw error;
            var response=page(actor,id,form).addObject("error",error.getMessage());response.setStatus(HttpStatusCode.valueOf(error.status()));return response;
        }
    }
    private ModelAndView page(TnaPrincipal actor,UUID bucketId,Form draft){return new ModelAndView("request/attributions").addObject("leaveAttributions",service.view(actor,bucketId,draft));}
    private static OrgException malformed(){return new OrgException(400,"귀속연도 입력 형식을 확인해 주세요.");}
}
