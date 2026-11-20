package kr.tna.approval;

import java.util.Set;
import java.util.UUID;
import jakarta.servlet.http.HttpServletRequest;
import kr.tna.approval.RequestFormData.Command;
import kr.tna.common.auth.TnaPrincipal;
import kr.tna.org.OrgException;
import kr.tna.request.LeaveWorkflowService;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.servlet.view.RedirectView;
import org.springframework.web.util.UriComponentsBuilder;

@Controller
public final class RequestFormController {
    private final RequestFormService forms;
    private final LeaveWorkflowService flow;
    private static final Set<String> FIELDS=Set.of("id","expectedVersion","code","kind","name","effectiveFrom",
            "enabled","routeVersionId","replaceFormId","replaceExpectedVersion","reason","commandId","q","_csrf");
    public RequestFormController(RequestFormService forms,LeaveWorkflowService flow) { this.forms=forms; this.flow=flow; }

    @GetMapping("/admin/request-forms")
    ModelAndView page(@AuthenticationPrincipal TnaPrincipal actor,@RequestParam(defaultValue="") String q,
                      @RequestParam(required=false) UUID id) {
        return new ModelAndView("org/request-forms").addObject("requestForms",forms.page(actor,q,id));
    }

    @GetMapping("/admin/request-forms/preview")
    ModelAndView preview(@AuthenticationPrincipal TnaPrincipal actor,@RequestParam UUID employeeId,
                         @RequestParam(required=false) UUID routeVersionId) {
        return new ModelAndView("org/request-forms").addObject("routePreview",flow.adminRoutePreview(actor,employeeId,routeVersionId));
    }

    @PostMapping("/admin/request-forms/commands")
    ModelAndView save(@AuthenticationPrincipal TnaPrincipal actor,@ModelAttribute Command command,BindingResult binding,
                      @RequestParam(defaultValue="") String q,HttpServletRequest request) {
        request.getParameterMap().forEach((field,values)->{
            if(!FIELDS.contains(field)||values.length!=1)throw new OrgException(400,"신청서 입력 형식을 확인해 주세요.");
        });
        if(binding.hasErrors())throw OrgException.invalid("날짜·식별자·버전을 확인해 주세요.");
        try {
            UUID id=forms.save(actor,command);
            var path=UriComponentsBuilder.fromPath("/admin/request-forms").queryParam("q",q).queryParam("id",id)
                    .queryParam("saved","").build().encode().toUriString();
            var redirect=new RedirectView(path,true);redirect.setStatusCode(HttpStatus.SEE_OTHER);
            return new ModelAndView(redirect);
        } catch(OrgException e) {
            if(e.status()!=409 && e.status()!=422)throw e;
            var view=page(actor,q,command.id()).addObject("draft",command).addObject("error",e.getMessage());
            view.setStatus(HttpStatusCode.valueOf(e.status())); return view;
        }
    }
}
