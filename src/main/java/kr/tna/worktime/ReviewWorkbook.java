package kr.tna.worktime;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Minimal review-only OOXML workbook; all user-provided strings are text cells. */
public final class ReviewWorkbook {
    private ReviewWorkbook() { }
    public static byte[] create(List<List<Object>> rows) {
        if(rows==null||rows.size()>1_048_576)throw new IllegalArgumentException("Invalid worksheet row count");
        var sheet=new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?><worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"><sheetData>");
        int rowNumber=0;
        for(var row:rows) {
            if(row==null||row.size()>16_384)throw new IllegalArgumentException("Invalid worksheet column count");
            sheet.append("<row r=\"").append(++rowNumber).append("\">");int column=0;
            for(Object value:row) {
                String reference=column(++column)+rowNumber;
                sheet.append("<c r=\"").append(reference).append("\"");
                if(value instanceof Long||value instanceof Integer||value instanceof Short||value instanceof Byte
                        || value instanceof Double decimal && Double.isFinite(decimal)) {
                    sheet.append(" t=\"n\"><v>").append(value).append("</v></c>");
                } else {
                    if(value!=null&&!(value instanceof String))throw new IllegalArgumentException("Only text and finite numeric worksheet values are supported");
                    String text=value==null?"미확정":(String)value;
                    sheet.append(" t=\"inlineStr\"><is><t xml:space=\"preserve\">").append(xml(text)).append("</t></is></c>");
                }
            }
            sheet.append("</row>");
        }
        sheet.append("</sheetData></worksheet>");
        var output=new ByteArrayOutputStream();
        try(var zip=new ZipOutputStream(output,StandardCharsets.UTF_8)) {
            entry(zip,"[Content_Types].xml","""
                <?xml version="1.0" encoding="UTF-8"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/><Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/></Types>
                """);
            entry(zip,"_rels/.rels","""
                <?xml version="1.0" encoding="UTF-8"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/></Relationships>
                """);
            entry(zip,"xl/workbook.xml","""
                <?xml version="1.0" encoding="UTF-8"?><workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets><sheet name="근태 검토" sheetId="1" r:id="rId1"/></sheets></workbook>
                """);
            entry(zip,"xl/_rels/workbook.xml.rels","""
                <?xml version="1.0" encoding="UTF-8"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/></Relationships>
                """);
            entry(zip,"xl/worksheets/sheet1.xml",sheet.toString());
        }catch(IOException error){throw new UncheckedIOException(error);}
        return output.toByteArray();
    }
    private static void entry(ZipOutputStream zip,String name,String content) throws IOException {
        var entry=new ZipEntry(name);entry.setTime(0);zip.putNextEntry(entry);zip.write(content.getBytes(StandardCharsets.UTF_8));zip.closeEntry();
    }
    private static String column(int value) {
        var result=new StringBuilder();while(value>0){value--;result.append((char)('A'+value%26));value/=26;}return result.reverse().toString();
    }
    private static String xml(String value) {
        if(value.codePointCount(0,value.length())>32_767||value.codePoints().anyMatch(c->!(c==9||c==10||c==13
                ||c>=0x20&&c<=0xd7ff||c>=0xe000&&c<=0xfffd||c>=0x10000&&c<=0x10ffff)))throw new IllegalArgumentException("Invalid XML cell text");
        return value.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;").replace("'","&apos;");
    }
}
