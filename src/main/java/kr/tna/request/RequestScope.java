package kr.tna.request;

import kr.tna.org.OrgException;

public enum RequestScope {
    LEAVE, CORRECTION;

    public static RequestScope parse(String value) {
        if (value == null) return null;
        try {
            return valueOf(value);
        } catch (IllegalArgumentException ex) {
            throw new OrgException(400, "신청 조회 유형을 확인해 주세요.");
        }
    }
}
