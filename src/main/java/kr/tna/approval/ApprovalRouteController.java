package kr.tna.approval;

import jakarta.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import kr.tna.approval.ApprovalRouteData.*;
import kr.tna.common.auth.TnaPrincipal;
import kr.tna.org.*;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.servlet.view.RedirectView;
import org.springframework.web.util.UriComponentsBuilder;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Controller
public final class ApprovalRouteController {
    private static final JsonMapper JSON=JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    private static final Set<String> BULK_PARAMETERS=Set.of("commandId","reason","rows","q","page","_csrf");
    private static final Set<String> ASSIGN_PARAMETERS=Set.of("commandId","reason","scopeKind","employeeId","organizationId","routeVersionId","validFrom","expectedVersion","_csrf");
    private static final Set<String> FIELDS=Set.of("clientRowId","id","expectedVersion","operation","code","name","active","approverKind","employeeId","terminalUnit");
    private final ApprovalRouteService service;
    public ApprovalRouteController(ApprovalRouteService service) { this.service=service; }

    @GetMapping("/admin/approval-routes")
    ModelAndView routes(@AuthenticationPrincipal TnaPrincipal actor,@RequestParam(defaultValue="") String q,@RequestParam(defaultValue="0") int page) {
        return routePage(actor,q,page,null,List.of());
    }

    @PostMapping("/admin/approval-routes/bulk")
    ModelAndView bulk(@AuthenticationPrincipal TnaPrincipal actor,HttpServletRequest request) {
        parameters(request,BULK_PARAMETERS);
        var rows=new ArrayList<RouteRow>();
        UUID command; int page;
        String q=parameter(request,"q",""),reason=parameter(request,"reason","");
        try { command=UUID.fromString(parameter(request,"commandId",""));page=Integer.parseInt(parameter(request,"page","0")); }
        catch (IllegalArgumentException e) { throw malformed(); }
        String raw=request.getParameter("rows");
        if (raw==null || raw.length()>200_000) throw malformed();
        try {
            var tree=JSON.readTree(raw);
            if (tree==null || !tree.isArray()) throw malformed();
            for (JsonNode node:tree) {
                if (!node.isObject()) throw malformed();
                for (String field:node.propertyNames()) if (!FIELDS.contains(field)) throw malformed();
                String clientId=text(node,"clientRowId");
                if (!UUID.fromString(clientId).toString().equals(clientId)) throw malformed();
                var expected=node.get("expectedVersion");
                if (expected!=null && !expected.isNull() && (!expected.isIntegralNumber() || !expected.canConvertToLong())) throw malformed();
                var active=node.get("active");
                if (active==null || !active.isBoolean()) throw malformed();
                rows.add(new RouteRow(clientId,uuid(node,"id"),expected==null||expected.isNull()?null:expected.asLong(),
                        BulkData.Operation.valueOf(text(node,"operation")),text(node,"code"),text(node,"name"),active.asBoolean(),text(node,"approverKind"),uuid(node,"employeeId"),text(node,"terminalUnit")));
            }
        } catch (JacksonException | IllegalArgumentException e) { throw malformed(); }
        var form=new BulkData.Form<>(command,reason,List.copyOf(rows));
        routePage(actor,q,page,null,List.of());
        try {
            service.save(actor,form);
            return redirect(UriComponentsBuilder.fromPath("/admin/approval-routes").queryParam("q",q).queryParam("page",page).queryParam("saved","").build().encode().toUriString());
        } catch (OrgException error) {
            if (error.status()!=409 && error.status()!=422) throw error;
            var errors=error instanceof BulkException bulk?bulk.errors():List.of(new BulkData.FieldError("","rows",error.getMessage()));
            return failure(routePage(actor,q,page,form,errors),error);
        }
    }

    @GetMapping("/admin/approval-route-assignments")
    ModelAndView assignments(@AuthenticationPrincipal TnaPrincipal actor,@RequestParam(required=false) String scopeKind,
            @RequestParam(required=false) UUID employeeId,@RequestParam(required=false) UUID organizationId) {
        return assignmentPage(actor,scopeKind,employeeId,organizationId,null);
    }
    @PostMapping("/admin/approval-route-assignments")
    ModelAndView assign(@AuthenticationPrincipal TnaPrincipal actor,@ModelAttribute AssignmentForm form,BindingResult binding,HttpServletRequest request) {
        return assign(actor,form,binding,request,false);
    }
    @PostMapping("/admin/approval-route-assignments/end")
    ModelAndView end(@AuthenticationPrincipal TnaPrincipal actor,@ModelAttribute AssignmentForm form,BindingResult binding,HttpServletRequest request) {
        return assign(actor,form,binding,request,true);
    }
    private ModelAndView assign(TnaPrincipal actor,AssignmentForm form,BindingResult binding,HttpServletRequest request,boolean ending) {
        parameters(request,ASSIGN_PARAMETERS);
        if (binding.hasErrors()) throw malformed();
        try {
            service.assign(actor,form,ending);
            var uri=UriComponentsBuilder.fromPath("/admin/approval-route-assignments").queryParam("scopeKind",form.scopeKind()).queryParam("saved","");
            if (form.employeeId()!=null) uri.queryParam("employeeId",form.employeeId());
            if (form.organizationId()!=null) uri.queryParam("organizationId",form.organizationId());
            return redirect(uri.build().encode().toUriString());
        } catch (OrgException error) {
            if (error.status()!=409 && error.status()!=422) throw error;
            return failure(assignmentPage(actor,form.scopeKind(),form.employeeId(),form.organizationId(),form),error);
        }
    }
    private ModelAndView routePage(TnaPrincipal actor,String q,int page,BulkData.Form<RouteRow> form,List<BulkData.FieldError> errors) {
        return new ModelAndView("org/approval-routes").addObject("approvalRoutes",service.management(actor,q,page,form,errors));
    }
    private ModelAndView assignmentPage(TnaPrincipal actor,String scope,UUID employee,UUID organization,AssignmentForm form) {
        return new ModelAndView("org/approval-route-assignments").addObject("approvalRouteAssignments",service.assignments(actor,scope,employee,organization,form));
    }
    private static ModelAndView redirect(String uri) { var view=new RedirectView(uri,true);view.setStatusCode(HttpStatus.SEE_OTHER);return new ModelAndView(view); }
    private static ModelAndView failure(ModelAndView view,OrgException error) { view.addObject("error",error.getMessage());view.setStatus(HttpStatusCode.valueOf(error.status()));return view; }
    private static void parameters(HttpServletRequest request,Set<String> allowed) {
        request.getParameterMap().forEach((name,values)->{if (!allowed.contains(name) || values.length!=1) throw malformed();});
    }
    private static String text(JsonNode node,String field) { var value=node.get(field);if(value==null||value.isNull())return "";if(!value.isString())throw malformed();return value.asString(); }
    private static UUID uuid(JsonNode node,String field) { String value=text(node,field);return value.isEmpty()?null:UUID.fromString(value); }
    private static String parameter(HttpServletRequest request,String name,String fallback) { String value=request.getParameter(name);return value==null?fallback:value; }
    private static OrgException malformed() { return new OrgException(400,"행의 필드·식별자·날짜·버전 입력 형식을 확인해 주세요."); }
}
