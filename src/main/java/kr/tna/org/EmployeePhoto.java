package kr.tna.org;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Base64;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.MemoryCacheImageInputStream;

/** Bounded pixel-only decoding: uploaded metadata and trailing bytes are never stored. */
public final class EmployeePhoto {
    public static final int MAX_BYTES = 128 * 1024;
    public static final int MAX_BASE64_LENGTH = 4 * ((MAX_BYTES + 2) / 3);
    private static final int MAX_DIMENSION = 2048;
    private static final long MAX_PIXELS = 4_000_000;

    private EmployeePhoto() { }

    public record Photo(String contentType, byte[] bytes) { }

    public static Photo decode(String data) {
        if (data == null || data.isEmpty() || data.length() > MAX_BASE64_LENGTH + 32) throw invalid();
        String declared = null;
        String encoded = data;
        if (data.startsWith("data:")) {
            if (data.startsWith("data:image/png;base64,")) declared = "png";
            else if (data.startsWith("data:image/jpeg;base64,")) declared = "jpeg";
            else throw invalid();
            encoded = data.substring(data.indexOf(',') + 1);
        }
        if (encoded.isEmpty() || encoded.length() > MAX_BASE64_LENGTH) throw invalid();
        byte[] source;
        try { source = Base64.getDecoder().decode(encoded); }
        catch (IllegalArgumentException malformed) { throw invalid(); }
        if (source.length == 0 || source.length > MAX_BYTES) throw invalid();
        String format;
        if (source.length >= 8 && source[0] == (byte) 0x89 && source[1] == 'P' && source[2] == 'N'
                && source[3] == 'G' && source[4] == 13 && source[5] == 10 && source[6] == 26 && source[7] == 10) format = "png";
        else if (source.length >= 3 && source[0] == (byte) 0xff && source[1] == (byte) 0xd8
                && source[2] == (byte) 0xff) format = "jpeg";
        else throw invalid();
        if (declared != null && !declared.equals(format)) throw invalid();
        ImageReader reader = ImageIO.getImageReadersByFormatName(format).next();
        try (var input = new MemoryCacheImageInputStream(new ByteArrayInputStream(source))) {
            reader.setInput(input, true, true);
            int width = reader.getWidth(0), height = reader.getHeight(0);
            if (width < 1 || height < 1 || width > MAX_DIMENSION || height > MAX_DIMENSION
                    || (long) width * height > MAX_PIXELS) throw invalid();
            BufferedImage decoded = reader.read(0);
            if (decoded == null || decoded.getWidth() != width || decoded.getHeight() != height) throw invalid();
            var output = new ByteArrayOutputStream();
            if (!ImageIO.write(decoded, format, output) || output.size() > MAX_BYTES) throw invalid();
            return new Photo("image/" + format, output.toByteArray());
        } catch (IOException | IllegalArgumentException malformed) {
            throw invalid();
        } finally { reader.dispose(); }
    }

    private static OrgException invalid() {
        return OrgException.invalid("사진은 128KiB 이하 PNG/JPEG 이미지로, 가로·세로 2048px 및 400만 픽셀 이내여야 합니다.");
    }
}
