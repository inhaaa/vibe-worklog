package kr.tna;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.Base64;
import javax.imageio.ImageIO;
import kr.tna.org.EmployeePhoto;
import kr.tna.org.OrgException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class EmployeePhotoTest {
    @Test
    void acceptsPngAndJpegAndReencodesOnlyPixels() throws Exception {
        for (String format : new String[]{"png", "jpeg"}) {
            byte[] source = image(format);
            byte[] withTrailingText = java.util.Arrays.copyOf(source, source.length + 20);
            System.arraycopy("private metadata".getBytes(), 0, withTrailingText, source.length, 16);
            var photo = EmployeePhoto.decode("data:image/" + format + ";base64,"
                    + Base64.getEncoder().encodeToString(withTrailingText));
            assertEquals("image/" + format, photo.contentType());
            assertEquals(4, ImageIO.read(new java.io.ByteArrayInputStream(photo.bytes())).getWidth());
            assertFalse(new String(photo.bytes(), java.nio.charset.StandardCharsets.ISO_8859_1).contains("private metadata"));
        }
    }

    @Test
    void rejectsUnsupportedMalformedOversizedAndMismatchedInputs() throws Exception {
        for (String data : new String[]{null, "", "<svg></svg>", "data:image/svg+xml;base64,AAAA",
                "!invalid!", "A".repeat(180000), Base64.getEncoder().encodeToString(new byte[128 * 1024 + 1]),
                "data:image/jpeg;base64," + Base64.getEncoder().encodeToString(image("png"))}) {
            assertEquals(422, assertThrows(OrgException.class, () -> EmployeePhoto.decode(data)).status());
        }
    }

    @Test
    void rejectsExcessiveDimensionsBeforeImageDecode() throws Exception {
        byte[] source = image("png");
        ByteBuffer.wrap(source).putInt(16, 20000).putInt(20, 20000);
        assertEquals(422, assertThrows(OrgException.class,
                () -> EmployeePhoto.decode(Base64.getEncoder().encodeToString(source))).status());
    }

    static byte[] image(String format) throws Exception {
        var output = new ByteArrayOutputStream();
        assertTrue(ImageIO.write(new BufferedImage(4, 3, BufferedImage.TYPE_INT_RGB), format, output));
        return output.toByteArray();
    }
}
