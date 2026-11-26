package kr.tna.notification;

import static org.junit.jupiter.api.Assertions.*;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class NotificationRulesTest {
    final UUID actor = UUID.randomUUID(), next = UUID.randomUUID(), target = UUID.randomUUID();

    @Test void requestsGoToTheNextApproverAndDecisionsToTheRequester() {
        assertEquals(new NotificationRules.Target(next, "APPROVAL_REQUESTED"), NotificationRules.target("SUBMITTED", actor, next, target).orElseThrow());
        assertEquals(new NotificationRules.Target(next, "APPROVAL_REQUESTED"), NotificationRules.target("STEP_APPROVED", actor, next, target).orElseThrow());
        assertEquals(new NotificationRules.Target(next, "APPROVAL_REQUESTED"), NotificationRules.target("REASSIGNED", actor, next, target).orElseThrow());
        assertEquals(new NotificationRules.Target(target, "APPROVED"), NotificationRules.target("APPROVED", actor, null, target).orElseThrow());
        assertEquals(new NotificationRules.Target(target, "REJECTED"), NotificationRules.target("REJECTED", actor, null, target).orElseThrow());
    }

    @Test void withdrawalsUnknownKindsMissingRecipientsAndSelfActionsAreSilent() {
        assertTrue(NotificationRules.target("WITHDRAWN", actor, null, target).isEmpty());
        assertTrue(NotificationRules.target("SOMETHING", actor, next, target).isEmpty());
        assertTrue(NotificationRules.target("SUBMITTED", actor, null, target).isEmpty());
        assertTrue(NotificationRules.target("APPROVED", target, null, target).isEmpty(), "본인이 처리한 결과는 알리지 않는다");
        assertTrue(NotificationRules.target("REASSIGNED", next, next, target).isEmpty());
    }
}
