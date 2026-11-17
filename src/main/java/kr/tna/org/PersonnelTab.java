package kr.tna.org;

import java.util.Arrays;

enum PersonnelTab {
    BASIC("basic"), APPOINTMENTS("appointments"), FAMILY("family"), EDUCATION("education"),
    QUALIFICATION("qualification"), LANGUAGE("language"), CONTACT("contact");

    private final String id;

    PersonnelTab(String id) { this.id = id; }

    String id() { return id; }

    static PersonnelTab parse(String id) {
        return Arrays.stream(values()).filter(tab -> tab.id.equals(id)).findFirst()
                .orElseThrow(() -> new OrgException(400, "인사정보 탭을 확인해 주세요."));
    }
}
