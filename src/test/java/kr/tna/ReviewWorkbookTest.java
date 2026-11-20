package kr.tna;

import static org.junit.jupiter.api.Assertions.*;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.zip.ZipInputStream;
import javax.xml.parsers.DocumentBuilderFactory;
import kr.tna.worktime.ReviewWorkbook;
import org.junit.jupiter.api.Test;

class ReviewWorkbookTest {
    @Test void halfDayIsANumericExcelCell() throws Exception {
        var sheet = entries(ReviewWorkbook.create(List.of(List.<Object>of(0.5)))).get("xl/worksheets/sheet1.xml");
        assertTrue(sheet.contains("<v>0.5</v>"));
        assertTrue(sheet.contains("t=\"n\""));
    }

    @Test void producesAnOpenXmlWorkbookWhoseUntrustedStringsAreAlwaysText() throws Exception {
        var bytes=ReviewWorkbook.create(List.of(List.<Object>of("사원", "시간(ms)"),List.<Object>of("=HYPERLINK(\"https://invalid.test\",\"합성\")",60_100L)));
        assertTrue(bytes.length>100);var entries=entries(bytes);
        assertTrue(entries.containsKey("[Content_Types].xml"));assertTrue(entries.containsKey("xl/workbook.xml"));
        String sheet=entries.get("xl/worksheets/sheet1.xml");assertNotNull(sheet);assertTrue(sheet.contains("t=\"inlineStr\""));
        assertTrue(sheet.contains("=HYPERLINK"));assertFalse(sheet.contains("<f>"));assertFalse(entries.keySet().stream().anyMatch(n->n.contains("externalLink")));
        assertTrue(sheet.contains("60100"));
        var factory=DocumentBuilderFactory.newInstance();factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl",true);
        for(var entry:entries.entrySet())factory.newDocumentBuilder().parse(new ByteArrayInputStream(entry.getValue().getBytes(StandardCharsets.UTF_8)));
    }
    @Test void nullIsLabeledUnknownAndUnicodeMarkupIsEscaped() throws Exception {
        String sheet=entries(ReviewWorkbook.create(List.of(Arrays.<Object>asList("  합성 <사원> & \"표시\"  ",null,0L)))).get("xl/worksheets/sheet1.xml");
        assertNotNull(sheet);assertTrue(sheet.contains("미확정"));assertTrue(sheet.contains("&lt;사원&gt;"));assertTrue(sheet.contains("&amp;"));assertTrue(sheet.contains("xml:space=\"preserve\""));
    }
    @Test void invalidXmlAndOversizedCellsAreRejectedInsteadOfWritingABrokenFile() {
        assertThrows(IllegalArgumentException.class,()->ReviewWorkbook.create(List.of(List.<Object>of("x"+(char)1+"y"))));
        assertThrows(IllegalArgumentException.class,()->ReviewWorkbook.create(List.of(List.<Object>of("가".repeat(32768)))));
    }
    private HashMap<String,String> entries(byte[] bytes) throws Exception {
        var result=new HashMap<String,String>();try(var zip=new ZipInputStream(new ByteArrayInputStream(bytes),StandardCharsets.UTF_8)) {
            for(var e=zip.getNextEntry();e!=null;e=zip.getNextEntry())result.put(e.getName(),new String(zip.readAllBytes(),StandardCharsets.UTF_8));
        }return result;
    }
}
