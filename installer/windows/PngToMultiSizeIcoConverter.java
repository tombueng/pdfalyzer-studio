import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.util.List;
import java.util.ArrayList;

public class PngToMultiSizeIcoConverter {
    private static final int[] SIZES = {16, 32, 48, 64, 128, 256};

    public static void main(String[] args) throws IOException {
        if (args.length != 2) {
            System.out.println("Usage: java PngToMultiSizeIcoConverter <input.png> <output.ico>");
            return;
        }
        BufferedImage src = ImageIO.read(new File(args[0]));
        List<byte[]> pngImages = new ArrayList<>();
        for (int size : SIZES) {
            BufferedImage scaled = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = scaled.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g.drawImage(src, 0, 0, size, size, null);
            g.dispose();
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ImageIO.write(scaled, "png", baos);
            pngImages.add(baos.toByteArray());
        }

        try (FileOutputStream out = new FileOutputStream(args[1])) {
            // ICO header
            out.write(new byte[]{0, 0, 1, 0}); // reserved, type
            out.write((byte) SIZES.length);    // number of images
            out.write(0); // pad

            int offset = 6 + 16 * SIZES.length;
            for (int i = 0; i < SIZES.length; i++) {
                int size = SIZES[i];
                byte[] pngData = pngImages.get(i);
                out.write(size == 256 ? 0 : size); // width
                out.write(size == 256 ? 0 : size); // height
                out.write(0); // colors
                out.write(0); // reserved
                out.write(1); // color planes
                out.write(0);
                out.write(32); // bits per pixel
                out.write(0);
                out.write(intToBytesLE(pngData.length)); // image data size
                out.write(intToBytesLE(offset));         // offset
                offset += pngData.length;
            }
            for (byte[] pngData : pngImages) {
                out.write(pngData);
            }
        }
        System.out.println("ICO file written: " + args[1]);
    }

    private static byte[] intToBytesLE(int value) {
        return new byte[]{
                (byte) (value & 0xFF),
                (byte) ((value >> 8) & 0xFF),
                (byte) ((value >> 16) & 0xFF),
                (byte) ((value >> 24) & 0xFF)
        };
    }
}
