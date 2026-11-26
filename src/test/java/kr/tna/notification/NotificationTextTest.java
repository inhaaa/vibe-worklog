package kr.tna.notification;

import static org.junit.jupiter.api.Assertions.*;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class NotificationTextTest {
    final LocalDate d1 = LocalDate.of(2026, 12, 1), d2 = LocalDate.of(2026, 12, 2);

    @Test void requestSummaryUsesTheTypeNameAndShortPeriod() {
        assertEquals("연차 12-01~12-02", NotificationText.summary("LEAVE_DAY", "연차", d1, d2));
        assertEquals("휴가 12-01", NotificationText.summary("LEAVE_DAY", null, d1, d1));
        assertEquals("연차 취소 12-01", NotificationText.summary("CANCEL_LEAVE", "연차", d1, d1));
        assertEquals("출퇴근 정정 12-01", NotificationText.summary("ATTENDANCE_CORRECTION", null, d1, d1));
        assertEquals("연장근무 12-01", NotificationText.summary("OVERTIME", null, d1, d1));
        assertEquals("연장근무 취소 12-01", NotificationText.summary("CANCEL_OVERTIME", null, d1, d1));
        assertEquals("결재 요청", NotificationText.kindLabel("APPROVAL_REQUESTED"));
        assertEquals("승인", NotificationText.kindLabel("APPROVED"));
        assertEquals("반려", NotificationText.kindLabel("REJECTED"));
    }

    @Test void mailSubjectNamesTheRequesterOnlyForRequestsAndBodyCarriesRejectionReason() {
        var request = new NotificationText.MailFacts("APPROVAL_REQUESTED", "합성 회사", "홍길동", "연차 12-01~12-02", null,
                java.util.UUID.fromString("00000000-0000-0000-0000-000000000001"));
        var mail = NotificationText.mail("person@example.test", request, java.net.URI.create("http://127.0.0.1:18080"));
        assertEquals("[WorkLog] 결재 요청 · 홍길동 연차 12-01~12-02", mail.subject());
        assertTrue(mail.body().contains("http://127.0.0.1:18080/requests/00000000-0000-0000-0000-000000000001"));
        assertFalse(mail.body().contains("반려 사유"));
        var rejected = new NotificationText.MailFacts("REJECTED", "합성 회사", "홍길동", "연차 12-01", "일정 중복",
                java.util.UUID.fromString("00000000-0000-0000-0000-000000000002"));
        var rejection = NotificationText.mail("person@example.test", rejected, java.net.URI.create("https://worklog.example"));
        assertEquals("[WorkLog] 반려 · 연차 12-01", rejection.subject());
        assertTrue(rejection.body().contains("반려 사유: 일정 중복"));
        assertTrue(rejection.body().contains("합성 회사"));
        assertEquals("NotificationMail.Message[redacted]", rejection.toString());
    }
}
