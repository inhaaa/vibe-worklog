package kr.tna.notification;

import java.net.URI;
import kr.tna.common.auth.TnaPrincipal;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.servlet.ModelAndView;

@Controller
class NotificationController {
    private final NotificationService notifications;

    NotificationController(NotificationService notifications) { this.notifications = notifications; }

    @GetMapping("/me/notifications")
    ModelAndView page(@AuthenticationPrincipal TnaPrincipal actor) {
        return new ModelAndView("notification/list").addObject("notifications", notifications.list(actor));
    }

    @PostMapping("/me/notifications/read-all")
    ResponseEntity<Void> readAll(@AuthenticationPrincipal TnaPrincipal actor) {
        notifications.markAllRead(actor);
        return ResponseEntity.status(HttpStatus.SEE_OTHER).location(URI.create("/me/notifications")).build();
    }
}
