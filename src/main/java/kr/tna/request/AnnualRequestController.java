package kr.tna.request;

import jakarta.servlet.http.HttpServletRequest;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import kr.tna.common.auth.TnaPrincipal;
import kr.tna.org.OrgException;
import kr.tna.request.AnnualRequestData.*;
import kr.tna.request.AnnualRequestService.Draft;
import kr.tna.request.RequestData.*;
import org.springframework.format.annotation.DateTimeFormat;
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
public final class AnnualRequestController {
    private static final Set<String> QUERY=Set.of("year","typeId","status","page","authoredPage","createdId");
    private static final Set<String> CONTEXT=Set.of("year","typeId","status","page","authoredPage","mode","kind","_csrf");
    private static final Set<String> LEAVE=Set.of("routeToken","bucketId","from","to","leavePart","predecessorRequestId","reason","commandId");
    private static final Set<String> CORRECTION=Set.of("routeToken","workDate","baseInputRevision","sourceFingerprint","inAction","requestedInAt","outAction","requestedOutAt","presenceAsWork","breaksConfirmed","_presenceAsWork","_breaksConfirmed","actualBreaks","replacesRequestId","predecessorRequestId","reason","commandId");
    private final AnnualRequestService service;
    private final LeaveWorkflowService flow;
    public AnnualRequestController(AnnualRequestService service,LeaveWorkflowService flow){this.service=service;this.flow=flow;}

    @GetMapping("/me/requests")
    ModelAndView annual(@AuthenticationPrincipal TnaPrincipal actor,@RequestParam(required=false) Integer year,
            @RequestParam(required=false) UUID typeId,@RequestParam(defaultValue="") String status,@RequestParam(defaultValue="0") int page,
            @RequestParam(defaultValue="0") int authoredPage,@RequestParam(required=false) UUID createdId,HttpServletRequest request){
        parameters(request,QUERY);return model(service.view(actor,new Filter(year,typeId,status,page,authoredPage),null,createdId));
    }
    @GetMapping("/me/requests/new")
    ModelAndView compose(@AuthenticationPrincipal TnaPrincipal actor,@RequestParam(required=false) Integer year,
            @RequestParam(required=false) UUID typeId,@RequestParam(defaultValue="") String status,@RequestParam(defaultValue="0") int page,
            @RequestParam(defaultValue="0") int authoredPage,@RequestParam(defaultValue="MODAL") String mode,
            @RequestParam(defaultValue="LEAVE_DAY") String kind,@RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(required=false) UUID predecessorRequestId,HttpServletRequest request){
        parameters(request,union(CONTEXT,Set.of("kind","date","predecessorRequestId")));
        var draft=new Draft(mode,kind,date,predecessorRequestId,null,null,false,false);service.validateNewContext(draft);
        return model(service.view(actor,new Filter(year,typeId,status,page,authoredPage),draft,null));
    }
    @PostMapping("/me/requests/preview")
    ModelAndView preview(@AuthenticationPrincipal TnaPrincipal actor,@ModelAttribute LeaveForm leave,BindingResult leaveBinding,
            @ModelAttribute CorrectionInput correction,BindingResult correctionBinding,@RequestParam(required=false) Integer year,
            @RequestParam(defaultValue="LEAVE_DAY") String kind,@RequestParam(defaultValue="MODAL") String mode,HttpServletRequest request){
        parameters(request,union(CONTEXT,union(LEAVE,union(CORRECTION,Set.of("kind")))));binding(leaveBinding);binding(correctionBinding);
        var filter=filter(request,year);var safe=normal(correction);
        var draft=new Draft(mode,kind,null,kind.equals("LEAVE_DAY")?leave.predecessorRequestId():safe.predecessorRequestId(),leave,safe,true,false);
        return attempt(actor,filter,draft,()->service.view(actor,filter,draft,null));
    }
    @PostMapping(value="/me/requests/leave",params="year")
    ModelAndView leave(@AuthenticationPrincipal TnaPrincipal actor,@ModelAttribute LeaveForm form,BindingResult binding,
            @RequestParam Integer year,@RequestParam(defaultValue="MODAL") String mode,HttpServletRequest request){
        parameters(request,union(CONTEXT,LEAVE));expectedKind(request,"LEAVE_DAY");binding(binding);var filter=filter(request,year);
        var draft=new Draft(mode,"LEAVE_DAY",null,form.predecessorRequestId(),form,null,false,false);
        return submit(actor,filter,draft,()->{service.requireResubmission(actor,form.predecessorRequestId(),"LEAVE_DAY");return flow.submitLeave(actor,form,request.getParameter("routeToken"));});
    }
    @PostMapping(value="/me/requests/correction",params="year")
    ModelAndView correction(@AuthenticationPrincipal TnaPrincipal actor,@ModelAttribute CorrectionInput form,BindingResult binding,
            @RequestParam Integer year,@RequestParam(defaultValue="MODAL") String mode,HttpServletRequest request){
        parameters(request,union(CONTEXT,CORRECTION));expectedKind(request,"ATTENDANCE_CORRECTION");binding(binding);var filter=filter(request,year);var safe=normal(form);
        var draft=new Draft(mode,"ATTENDANCE_CORRECTION",null,safe.predecessorRequestId(),null,safe,false,false);
        return submit(actor,filter,draft,()->{service.requireResubmission(actor,safe.predecessorRequestId(),"ATTENDANCE_CORRECTION");return flow.submitCorrection(actor,safe,request.getParameter("routeToken"));});
    }
    private ModelAndView submit(TnaPrincipal actor,Filter filter,Draft draft,java.util.function.Supplier<UUID> mutation){
        // An invalid navigation context must fail before an otherwise valid request is committed.
        service.validateNewContext(draft);service.view(actor,filter,null,null);
        try{UUID id=mutation.get();var uri=UriComponentsBuilder.fromPath("/me/requests").queryParam("year",filter.year()).queryParam("createdId",id).build().encode().toUriString();
            var redirect=new RedirectView(uri,true);redirect.setStatusCode(HttpStatus.SEE_OTHER);return new ModelAndView(redirect);
        }catch(OrgException error){return failure(actor,filter,draft,error);}
    }
    private ModelAndView attempt(TnaPrincipal actor,Filter filter,Draft draft,java.util.function.Supplier<View> query){
        try{return model(query.get());}catch(OrgException error){return failure(actor,filter,draft,error);}
    }
    private ModelAndView failure(TnaPrincipal actor,Filter filter,Draft draft,OrgException error){
        if(error.status()!=409&&error.status()!=422)throw error;
        var view=model(service.view(actor,filter,draft.withoutPreview(error.status()==409),null));
        view.addObject("error",error.getMessage());view.setStatus(HttpStatusCode.valueOf(error.status()));return view;
    }
    static ModelAndView model(View view){return new ModelAndView("request/mine").addObject("annual",view.annual()).addObject("compose",view.compose())
            .addObject("requests",view.annual().requests()).addObject("buckets",view.buckets()).addObject("form",view.form());}
    private static Filter filter(HttpServletRequest request,Integer year){try{
        String type=request.getParameter("typeId");return new Filter(year,type==null||type.isBlank()?null:UUID.fromString(type),value(request,"status",""),Integer.parseInt(value(request,"page","0")),Integer.parseInt(value(request,"authoredPage","0")));
    }catch(IllegalArgumentException e){throw malformed();}}
    private static CorrectionForm normal(CorrectionInput f){return new CorrectionForm(null,f.workDate(),f.baseInputRevision(),f.sourceFingerprint(),f.inAction(),f.requestedInAt(),f.outAction(),f.requestedOutAt(),Boolean.TRUE.equals(f.presenceAsWork()),Boolean.TRUE.equals(f.breaksConfirmed()),f.actualBreaks(),f.replacesRequestId(),f.predecessorRequestId(),false,f.reason(),f.commandId());}
    private static void parameters(HttpServletRequest request,Set<String> allowed){request.getParameterMap().forEach((name,values)->{if(!allowed.contains(name)||values.length!=1)throw malformed();});}
    private static void expectedKind(HttpServletRequest request,String expected){String kind=request.getParameter("kind");if(kind!=null&&!kind.equals(expected))throw malformed();}
    private static Set<String> union(Set<String> first,Set<String> second){var result=new HashSet<>(first);result.addAll(second);return Set.copyOf(result);}
    private static String value(HttpServletRequest r,String name,String fallback){String value=r.getParameter(name);return value==null?fallback:value;}
    private static void binding(BindingResult errors){if(errors.hasErrors())throw malformed();}
    private static OrgException malformed(){return new OrgException(400,"조회·신청 입력 형식을 확인해 주세요.");}
}
