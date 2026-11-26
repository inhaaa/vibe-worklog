package kr.tna.notification;

import java.util.Optional;
import java.util.UUID;

/** 결재 이력 1건 → 알림 받는 사람과 구분 (설계 §2). 받는 사람이 처리한 본인이면 알리지 않는다. */
public final class NotificationRules {
    public record Target(UUID recipient, String kind) { }
    private NotificationRules() { }

    public static Optional<Target> target(String eventKind, UUID actorEmployee, UUID nextApprover, UUID requestTarget) {
        Target target = switch (eventKind) {
            case "SUBMITTED", "STEP_APPROVED", "REASSIGNED" -> nextApprover == null ? null : new Target(nextApprover, "APPROVAL_REQUESTED");
            case "APPROVED", "REJECTED" -> new Target(requestTarget, eventKind);
            default -> null;
        };
        return Optional.ofNullable(target).filter(t -> t.recipient() != null && !t.recipient().equals(actorEmployee));
    }
}
