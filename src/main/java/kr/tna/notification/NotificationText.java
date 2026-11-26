package kr.tna.notification;

import java.net.URI;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

/** 알림 화면과 메일의 문구. 인사 개인정보(생년월일·주소 등)는 넣지 않는다. */
public final class NotificationText {
    private static final DateTimeFormatter SHORT = DateTimeFormatter.ofPattern("MM-dd");
    /** 메일 한 통에 필요한 사실. reason은 반려일 때만 쓴다. */
    public record MailFacts(String kind, String companyName, String requesterName, String summary, String reason, UUID requestId) { }
    private NotificationText() { }

    public static String kindLabel(String kind) {
        return switch (kind) { case "APPROVED" -> "승인"; case "REJECTED" -> "반려"; default -> "결재 요청"; };
    }

    public static String summary(String requestKind, String typeName, LocalDate from, LocalDate to) {
        String leave = typeName == null ? "휴가" : typeName;
        String label = switch (requestKind) {
            case "LEAVE_DAY" -> leave;
            case "CANCEL_LEAVE" -> leave + " 취소";
            case "ATTENDANCE_CORRECTION" -> "출퇴근 정정";
            case "OVERTIME" -> "연장근무";
            case "CANCEL_OVERTIME" -> "연장근무 취소";
            default -> "신청";
        };
        String period = from.equals(to) ? SHORT.format(from) : SHORT.format(from) + "~" + SHORT.format(to);
        return label + " " + period;
    }

    public static NotificationMail.Message mail(String to, MailFacts f, URI origin) {
        boolean request = "APPROVAL_REQUESTED".equals(f.kind());
        String subject = "[WorkLog] " + kindLabel(f.kind()) + " · " + (request ? f.requesterName() + " " : "") + f.summary();
        String headline = switch (f.kind()) {
            case "APPROVED" -> "신청이 최종 승인되었습니다.";
            case "REJECTED" -> "신청이 반려되었습니다.";
            default -> "결재할 신청이 도착했습니다.";
        };
        var body = new StringBuilder(headline).append("\n\n")
                .append("회사: ").append(f.companyName()).append('\n')
                .append("신청자: ").append(f.requesterName()).append('\n')
                .append("신청 내용: ").append(f.summary()).append('\n');
        if ("REJECTED".equals(f.kind()) && f.reason() != null) body.append("반려 사유: ").append(f.reason()).append('\n');
        body.append("\n결재 상세: ").append(origin.resolve("/requests/" + f.requestId())).append('\n');
        return new NotificationMail.Message(to, subject, body.toString());
    }
}
