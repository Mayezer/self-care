package br.com.autocuidado.media;

import java.awt.Color;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import javax.imageio.ImageIO;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.MemoryCacheImageInputStream;
import javax.imageio.stream.MemoryCacheImageOutputStream;

import org.springframework.web.multipart.MultipartFile;

public final class PhotoRaster {
    public static final int MAX_BYTES = 5 * 1024 * 1024;
    private static final int MAX_INPUT_EDGE = 4096;
    private static final int MAX_INPUT_PIXELS = 12_000_000;
    private static final int MAX_OUTPUT_EDGE = 1600;

    private PhotoRaster() {}

    public static byte[] normalize(MultipartFile photo) {
        return normalize(photo, MAX_OUTPUT_EDGE);
    }

    public static byte[] normalize(MultipartFile photo, int maxOutputEdge) {
        if (maxOutputEdge < 1 || maxOutputEdge > MAX_OUTPUT_EDGE) throw new IllegalArgumentException("Invalid output size");
        if (photo.isEmpty() || photo.getSize() > MAX_BYTES) {
            throw new Rejected("Escolha uma foto JPEG ou PNG de até 5 MiB.");
        }
        try (var bytes = photo.getInputStream(); var input = new MemoryCacheImageInputStream(bytes)) {
            var readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) throw unsupported();
            var reader = readers.next();
            try {
                String format = reader.getFormatName();
                boolean jpeg = "JPEG".equalsIgnoreCase(format);
                if (!jpeg && !"PNG".equalsIgnoreCase(format)) throw unsupported();
                int orientation = jpeg ? jpegOrientation(input) : 1;
                input.seek(0);
                reader.setInput(input, true, true);
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                if (width <= 0 || height <= 0 || width > MAX_INPUT_EDGE || height > MAX_INPUT_EDGE
                        || (long) width * height > MAX_INPUT_PIXELS) {
                    throw new Rejected("A foto deve ter até 4.096 pixels por lado e 12 megapixels.");
                }
                reader.addIIOReadWarningListener((source, warning) -> { throw unsupported(); });
                int sample = Math.max(1, Math.max(width, height) / maxOutputEdge);
                var params = reader.getDefaultReadParam();
                params.setSourceSubsampling(sample, sample, 0, 0);
                return encode(reader.read(0, params), orientation, maxOutputEdge);
            } finally {
                reader.dispose();
            }
        } catch (IOException | IllegalArgumentException exception) {
            throw unsupported();
        }
    }

    private static byte[] encode(BufferedImage image, int orientation, int maxOutputEdge) throws IOException {
        int width = image.getWidth();
        int height = image.getHeight();
        int orientedWidth = orientation >= 5 ? height : width;
        int orientedHeight = orientation >= 5 ? width : height;
        double scale = Math.min(1, (double) maxOutputEdge / Math.max(orientedWidth, orientedHeight));
        int outWidth = Math.max(1, (int) Math.round(orientedWidth * scale));
        int outHeight = Math.max(1, (int) Math.round(orientedHeight * scale));
        var normalized = new BufferedImage(outWidth, outHeight, BufferedImage.TYPE_INT_RGB);
        var graphics = normalized.createGraphics();
        try {
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, outWidth, outHeight);
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            graphics.scale((double) outWidth / orientedWidth, (double) outHeight / orientedHeight);
            graphics.drawImage(image, orient(orientation, width, height), null);
        } finally {
            graphics.dispose();
        }
        var bytes = new ByteArrayOutputStream();
        try (var output = new MemoryCacheImageOutputStream(bytes)) {
            ImageIO.write(normalized, "JPEG", output);
        }
        return bytes.toByteArray();
    }

    private static AffineTransform orient(int orientation, int width, int height) {
        return switch (orientation) {
            case 2 -> new AffineTransform(-1, 0, 0, 1, width, 0);
            case 3 -> new AffineTransform(-1, 0, 0, -1, width, height);
            case 4 -> new AffineTransform(1, 0, 0, -1, 0, height);
            case 5 -> new AffineTransform(0, 1, 1, 0, 0, 0);
            case 6 -> new AffineTransform(0, 1, -1, 0, height, 0);
            case 7 -> new AffineTransform(0, -1, -1, 0, height, width);
            case 8 -> new AffineTransform(0, -1, 1, 0, 0, width);
            default -> new AffineTransform();
        };
    }

    // Preserve the orientation of a phone photo without copying its EXIF block into the output.
    private static int jpegOrientation(ImageInputStream input) throws IOException {
        input.seek(0);
        if (input.readUnsignedShort() != 0xffd8) return 1;
        while (input.read() == 0xff) {
            int marker = input.readUnsignedByte();
            while (marker == 0xff) marker = input.readUnsignedByte();
            if (marker == 0xda || marker == 0xd9) return 1;
            int length = input.readUnsignedShort() - 2;
            if (length < 0) return 1;
            if (marker != 0xe1) {
                input.skipBytes(length);
                continue;
            }
            byte[] data = new byte[length];
            input.readFully(data);
            int orientation = exifOrientation(data);
            if (orientation != 0) return orientation;
        }
        return 1;
    }

    private static int exifOrientation(byte[] data) {
        if (data.length < 14 || data[0] != 'E' || data[1] != 'x' || data[2] != 'i' || data[3] != 'f'
                || data[4] != 0 || data[5] != 0) return 0;
        var tiff = ByteBuffer.wrap(data, 6, data.length - 6).slice();
        int order = tiff.getShort(0);
        if (order == 0x4949) tiff.order(ByteOrder.LITTLE_ENDIAN);
        else if (order != 0x4d4d) return 0;
        if (tiff.getShort(2) != 42) return 0;
        int offset = tiff.getInt(4);
        if (offset < 8 || offset > tiff.limit() - 2) return 0;
        int entries = Short.toUnsignedInt(tiff.getShort(offset));
        for (int entry = offset + 2, index = 0; index < entries && entry <= tiff.limit() - 12; entry += 12, index++) {
            if (tiff.getShort(entry) == 0x0112 && tiff.getShort(entry + 2) == 3 && tiff.getInt(entry + 4) == 1) {
                int value = tiff.getShort(entry + 8);
                return value >= 1 && value <= 8 ? value : 0;
            }
        }
        return 0;
    }

    private static Rejected unsupported() {
        return new Rejected("O arquivo deve ser uma foto JPEG ou PNG válida.");
    }

    public static final class Rejected extends RuntimeException {
        public Rejected(String message) { super(message); }
    }
}
