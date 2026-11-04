package kr.tna.org;

import java.util.UUID;
import kr.tna.common.auth.TnaPrincipal;
import kr.tna.org.EmployeePersonnelData.Form;
import kr.tna.org.EmployeePersonnelData.Personnel;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.servlet.view.RedirectView;

@Controller
public final class EmployeePersonnelController {
    private final EmployeePersonnelService personnel;
    private final EmployeePersonalDetailsService details;

    public EmployeePersonnelController(EmployeePersonnelService personnel, EmployeePersonalDetailsService details) {
        this.personnel = personnel; this.details = details;
    }

    @GetMapping("/admin/employees/{id}/personnel")
    ModelAndView get(@AuthenticationPrincipal TnaPrincipal actor, @PathVariable UUID id) {
        return page(actor, id, null);
    }

    @PostMapping("/admin/employees/{id}/personnel")
    ModelAndView update(@AuthenticationPrincipal TnaPrincipal actor, @PathVariable UUID id,
            @ModelAttribute("form") Form form, BindingResult binding) {
        if (binding.hasErrors()) throw new OrgException(400, "날짜·식별자·버전의 입력 형식을 확인해 주세요.");
        try {
            personnel.update(actor, id, form);
            var redirect = new RedirectView("/admin/employees/" + id + "/personnel?saved", true);
            redirect.setStatusCode(HttpStatus.SEE_OTHER);
            return new ModelAndView(redirect);
        } catch (OrgException exception) {
            if (exception.status() != 409 && exception.status() != 422) throw exception;
            var view = page(actor, id, form).addObject("error", exception.getMessage());
            view.setStatus(HttpStatusCode.valueOf(exception.status()));
            return view;
        }
    }

    @GetMapping("/me/profile/photo")
    ResponseEntity<byte[]> selfPhoto(@AuthenticationPrincipal TnaPrincipal actor) {
        return image(personnel.selfPhoto(actor));
    }

    @PostMapping("/admin/employees/{id}/personal-details")
    ModelAndView updateDetails(@AuthenticationPrincipal TnaPrincipal actor, @PathVariable UUID id,
            @ModelAttribute("personalForm") EmployeePersonalDetailsData.Form form, BindingResult binding) {
        if (binding.hasErrors()) throw new OrgException(400, "날짜·버전·인정경력의 입력 형식을 확인해 주세요.");
        try {
            details.update(actor, id, form);
            return detailsRedirect(id);
        } catch (OrgException exception) {
            if (exception.status() != 409 && exception.status() != 422) throw exception;
            var current = details.get(actor, id);
            var submitted = new EmployeePersonalDetailsData.View(id, current.version(), form, current.currentForm(),
                    current.entries(), current.appointments(), current.entryForm());
            var view = page(actor, id, null).addObject("personalDetails", submitted).addObject("error", exception.getMessage());
            view.setStatus(HttpStatusCode.valueOf(exception.status()));
            return view;
        }
    }

    @PostMapping("/admin/employees/{id}/personal-entries")
    ModelAndView appendEntry(@AuthenticationPrincipal TnaPrincipal actor, @PathVariable UUID id,
            @ModelAttribute("entryForm") EmployeePersonalDetailsData.EntryForm form, BindingResult binding) {
        if (binding.hasErrors()) throw new OrgException(400, "이력 날짜·식별자·버전의 입력 형식을 확인해 주세요.");
        try {
            details.append(actor, id, form);
            return detailsRedirect(id);
        } catch (OrgException exception) {
            if (exception.status() != 409 && exception.status() != 422) throw exception;
            var current = details.get(actor, id);
            var submitted = new EmployeePersonalDetailsData.View(id, current.version(), current.form(), current.currentForm(),
                    current.entries(), current.appointments(), form);
            var view = page(actor, id, null).addObject("personalDetails", submitted).addObject("error", exception.getMessage());
            view.setStatus(HttpStatusCode.valueOf(exception.status()));
            return view;
        }
    }

    private ModelAndView detailsRedirect(UUID id) {
        var redirect = new RedirectView("/admin/employees/" + id + "/personnel?saved", true);
        redirect.setStatusCode(HttpStatus.SEE_OTHER);
        return new ModelAndView(redirect);
    }

    @GetMapping("/admin/employees/{id}/photo")
    ResponseEntity<byte[]> photo(@AuthenticationPrincipal TnaPrincipal actor, @PathVariable UUID id) {
        return image(personnel.photo(actor, id));
    }

    private ModelAndView page(TnaPrincipal actor, UUID id, Form submitted) {
        var current = personnel.get(actor, id);
        var data = submitted == null ? current : new Personnel(id, submitted, current.statusHistory(),
                current.version(), current.currentForm());
        return new ModelAndView("org/personnel").addObject("personnel", data).addObject("today", personnel.today())
                .addObject("personalDetails", details.get(actor, id));
    }

    private ResponseEntity<byte[]> image(EmployeePhoto.Photo photo) {
        return ResponseEntity.ok().header("Cache-Control", "no-store")
                .header("X-Content-Type-Options", "nosniff")
                .contentType(MediaType.parseMediaType(photo.contentType())).body(photo.bytes());
    }
}
