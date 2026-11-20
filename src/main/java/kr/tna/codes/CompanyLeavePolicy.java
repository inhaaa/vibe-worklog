package kr.tna.codes;

/** Policy combinations that HR can publish and employees can use. */
public final class CompanyLeavePolicy {
    public static final String RULE = "COMPANY_LEAVE";
    private CompanyLeavePolicy() { }

    public static boolean supports(String unit, String payKind, String deductionRule, String recognitionRule) {
        return RULE.equals(recognitionRule)
                && ("DAY".equals(unit) || "DAY_HALF".equals(unit))
                && ("PAID".equals(payKind) || "UNPAID".equals(payKind))
                && ("NONE".equals(deductionRule) || "LEAVE_DAY".equals(deductionRule));
    }
}
