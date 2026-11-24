package kr.tna.org;

import java.time.Instant;

/** 사용자 관리·계정 화면이 같은 순서로 읽는 계정 상태. */
public enum AccountStatus {
    NONE("계정 없음"), ACTIVE("사용 중"), PREVIOUS_INVITATION("이전 초대 대기"),
    BIRTH_DATE_REQUIRED("생년월일 필요"), LOCKED("잠김(5회 실패)"), EXPIRED("기간 만료"), PENDING("최초 접속 대기");

    public static final int MAX_FAILURES = 5;
    private final String label;
    AccountStatus(String label) { this.label = label; }
    public String label() { return label; }

    public static AccountStatus of(boolean exists, boolean active, Instant activationExpiresAt, int failures,
                                   boolean birthDateKnown, Instant now) {
        if (!exists) return NONE;
        if (activationExpiresAt == null) return active ? ACTIVE : PREVIOUS_INVITATION;
        if (!birthDateKnown) return BIRTH_DATE_REQUIRED;
        if (failures >= MAX_FAILURES) return LOCKED;
        if (!activationExpiresAt.isAfter(now)) return EXPIRED;
        return PENDING;
    }
}
