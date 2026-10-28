package kr.tna.org;

public class OrgException extends RuntimeException {
    private final int status;
    public OrgException(int status, String message) { super(message); this.status = status; }
    public int status() { return status; }
    public static OrgException invalid(String message) { return new OrgException(422, message); }
    public static OrgException conflict(String message) { return new OrgException(409, message); }
    public static OrgException notFound() { return new OrgException(404, "대상을 찾을 수 없습니다."); }
    public static OrgException forbidden() { return new OrgException(403, "처리할 권한이 없습니다. 다시 로그인해 주세요."); }
}
