package kr.tna.notification;

/** 결재 알림 메일 전달 방식. Task 4에서 전달 기능을 채운다. */
public class NotificationMail {
    public record Message(String to, String subject, String body) {
        @Override public String toString() { return "NotificationMail.Message[redacted]"; }
    }
}
