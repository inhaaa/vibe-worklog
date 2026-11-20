package kr.tna.org;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.zip.ZipInputStream;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PersonnelExportServiceTest {
    @Test
    void writesUntrustedNamesAndLeadingZeroNumbersAsTextInTheAllowlistedColumns() throws Exception {
        LocalDate asOf = LocalDate.of(2026, 11, 17);
        var row = new PersonnelExportService.Row(UUID.randomUUID(), "00012", "=2+3", asOf, null,
                true, "DEV", "<개발>", "주간", "선임", "팀원", "개발");
        byte[] xlsx = PersonnelExportService.workbook(asOf, List.of(row));
        String sheet = "";
        try (var zip = new ZipInputStream(new ByteArrayInputStream(xlsx))) {
            for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                if (entry.getName().equals("xl/worksheets/sheet1.xml")) {
                    sheet = new String(zip.readAllBytes(), StandardCharsets.UTF_8);
                }
            }
        }
        assertThat(sheet).contains("2026-11-17", "00012", "=2+3", "&lt;개발&gt;", "선임")
                .doesNotContain("<f", "생년월일", "연락처", "주소", "사진");
    }
}
