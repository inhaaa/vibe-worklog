package kr.tna.common.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import kr.tna.org.OrgException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.servlet.view.RedirectView;

@Controller
public final class PasswordResetController {
    private static final Logger LOG=LoggerFactory.getLogger(PasswordResetController.class);
    private final PasswordResetService resets;
    public PasswordResetController(PasswordResetService resets){this.resets=resets;}
    @GetMapping("/password-reset") ModelAndView form(){return new ModelAndView("password-reset");}
    @PostMapping("/password-reset/request")
    ModelAndView request(@RequestParam(defaultValue="") String companyCode,@RequestParam(defaultValue="") String email,
            HttpServletRequest request,HttpServletResponse response){
        try{resets.request(companyCode,email,request.getRemoteAddr());return redirect("/password-reset?requested");}
        catch(OrgException error){return problem(error,response);}
    }
    @PostMapping("/password-reset/complete")
    ModelAndView complete(@RequestParam(defaultValue="") String companyCode,@RequestParam(defaultValue="") String email,
            @RequestParam(defaultValue="") String token,@RequestParam(defaultValue="") String password,
            @RequestParam(defaultValue="") String confirmation,HttpServletRequest request,HttpServletResponse response){
        try{
            if(!password.equals(confirmation)){LOG.warn("event=password_reset_complete outcome=rejected");throw OrgException.invalid("비밀번호와 확인값이 일치하지 않습니다.");}
            resets.complete(companyCode,email,token,password,request.getRemoteAddr());
            var session=request.getSession(false);if(session!=null)session.invalidate();SecurityContextHolder.clearContext();
            return redirect("/login?reset");
        }catch(OrgException error){return problem(error,response);}
    }
    private ModelAndView problem(OrgException error,HttpServletResponse response){
        if(error.status()==429)response.setHeader("Retry-After","300");
        var view=form().addObject("error",error.getMessage());view.setStatus(HttpStatusCode.valueOf(error.status()));return view;
    }
    private ModelAndView redirect(String url){var view=new RedirectView(url,true);view.setStatusCode(HttpStatus.SEE_OTHER);return new ModelAndView(view);}
}
