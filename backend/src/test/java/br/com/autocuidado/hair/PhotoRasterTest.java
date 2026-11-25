package br.com.autocuidado.hair;

import br.com.autocuidado.media.PhotoRaster;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.zip.CRC32;
import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PhotoRasterTest {
    @Test
    void checksRasterContentAndSizeBeforeDecoding() throws Exception {
        assertThrows(PhotoRaster.Rejected.class, () -> PhotoRaster.normalize(photo("texto".getBytes(StandardCharsets.UTF_8))));
        assertThrows(PhotoRaster.Rejected.class, () -> PhotoRaster.normalize(photo(new byte[PhotoRaster.MAX_BYTES + 1])));
        assertThrows(PhotoRaster.Rejected.class, () -> PhotoRaster.normalize(photo(raster(15, 10, "GIF"))));
        assertThrows(PhotoRaster.Rejected.class, () -> PhotoRaster.normalize(photo(raster(4097, 1, "PNG"))));

        byte[] oversized = raster(1, 1, "PNG");
        ByteBuffer.wrap(oversized).putInt(16, 3500).putInt(20, 3500);
        var crc = new CRC32();
        crc.update(oversized, 12, 17);
        ByteBuffer.wrap(oversized).putInt(29, (int) crc.getValue());
        assertThrows(PhotoRaster.Rejected.class, () -> PhotoRaster.normalize(photo(oversized)));
    }

    @Test
    void rotatesExifPhotoAndDiscardsItsMetadata() throws Exception {
        byte[] jpeg = raster(24, 12, "JPEG");
        byte[] exif = HexFormat.of().parseHex("4578696600004d4d002a00000008000101120003000000010006000000000000");
        var tagged = new ByteArrayOutputStream();
        tagged.write(jpeg, 0, 2);
        tagged.write(new byte[] {(byte) 0xff, (byte) 0xe1, 0, (byte) (exif.length + 2)});
        tagged.write(exif);
        tagged.write(jpeg, 2, jpeg.length - 2);
        byte[] normalized = PhotoRaster.normalize(photo(tagged.toByteArray()));
        var image = ImageIO.read(new ByteArrayInputStream(normalized));
        assertEquals(12, image.getWidth());
        assertEquals(24, image.getHeight());
        assertFalse(new String(normalized, StandardCharsets.ISO_8859_1).contains("Exif\0\0"));
    }

    private static MockMultipartFile photo(byte[] data) {
        return new MockMultipartFile("photo", "test.jpg", "image/jpeg", data);
    }

    static byte[] raster(int width, int height, String format) throws Exception {
        var bytes = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB), format, bytes);
        return bytes.toByteArray();
    }
}
