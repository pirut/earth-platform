package io.earthplatform.geography.globe;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import javax.imageio.ImageIO;

import static io.earthplatform.geography.globe.TerrariumTileSource.TILE_SIZE;

/**
 * Decodes Terrarium elevation tiles from their PNG bytes into metres, row-major north to south:
 * each pixel encodes {@code (R*256 + G + B/256) - 32768} metres. Plain 8-bit RGB and RGBA tiles,
 * as the composite serves nearly all of them, are read straight from their bytes; anything else
 * goes through ImageIO. {@link TerrariumTileSource#encode} writes such tiles.
 */
final class TerrariumPng {
    private TerrariumPng() {}

    static float[] decode(byte[] png) throws IOException {
        float[] plain = decodePlain(png);
        if (plain != null) return plain;
        BufferedImage image;
        try {
            image = ImageIO.read(new ByteArrayInputStream(png));
        } catch (RuntimeException malformed) {
            throw new IOException("Malformed image", malformed);
        }
        if (image == null) throw new IOException("Not a readable image");
        if (image.getWidth() != TILE_SIZE || image.getHeight() != TILE_SIZE) throw new IOException("Unexpected tile size " + image.getWidth() + "x" + image.getHeight());
        float[] metres = new float[TILE_SIZE * TILE_SIZE];
        // Plain 8-bit sRGB tiles, nearly all of them, are read straight from their bytes; getRGB converts pixel by pixel.
        if ((image.getType() == BufferedImage.TYPE_3BYTE_BGR || image.getType() == BufferedImage.TYPE_4BYTE_ABGR)
                && image.getColorModel().getColorSpace().isCS_sRGB()
                && image.getRaster().getDataBuffer() instanceof java.awt.image.DataBufferByte buffer
                && buffer.getNumBanks() == 1 && image.getRaster().getMinX() == 0 && image.getRaster().getMinY() == 0
                && image.getRaster().getSampleModel() instanceof java.awt.image.PixelInterleavedSampleModel model
                && model.getPixelStride() == (image.getType() == BufferedImage.TYPE_3BYTE_BGR ? 3 : 4)
                && model.getScanlineStride() == TILE_SIZE * model.getPixelStride() && buffer.getOffset() == 0
                && image.getRaster().getSampleModelTranslateX() == 0 && image.getRaster().getSampleModelTranslateY() == 0) {
            byte[] bytes = buffer.getData();
            int stride = model.getPixelStride();
            int[] offsets = model.getBandOffsets();
            int red = offsets[0], green = offsets[1], blue = offsets[2];
            for (int i = 0; i < metres.length; i++) {
                int at = i * stride;
                metres[i] = (float) (((bytes[at + red] & 0xFF) * 256 + (bytes[at + green] & 0xFF) + (bytes[at + blue] & 0xFF) / 256.0) - 32768.0);
            }
            return metres;
        }
        int[] argb = image.getRGB(0, 0, TILE_SIZE, TILE_SIZE, null, 0, TILE_SIZE);
        for (int i = 0; i < argb.length; i++) {
            int p = argb[i];
            metres[i] = (float) ((((p >>> 16) & 0xFF) * 256 + ((p >>> 8) & 0xFF) + (p & 0xFF) / 256.0) - 32768.0);
        }
        return metres;
    }

    /**
     * A plain tile's metres read straight from its PNG: 256 x 256, 8-bit RGB or RGBA, not interlaced, with no
     * colour profile, as the composite's tiles are. Null for anything else, which ImageIO reads instead.
     */
    static float[] decodePlain(byte[] png) {
        if (png.length < 33 || (png[0] & 0xFF) != 0x89 || png[1] != 'P' || png[2] != 'N' || png[3] != 'G') return null;
        java.nio.ByteBuffer in = java.nio.ByteBuffer.wrap(png);
        in.position(8);
        int channels = 0;
        java.util.zip.Inflater inflater = new java.util.zip.Inflater();
        try {
            int stride = 0;
            byte[] data = null;
            int filled = 0;
            while (in.remaining() >= 12) {
                int length = in.getInt(), type = in.getInt();
                if (length < 0 || length > in.remaining() - 4) return null;
                int start = in.position();
                if (type == 0x49484452) { // IHDR
                    if (length != 13 || in.getInt() != TILE_SIZE || in.getInt() != TILE_SIZE) return null;
                    int depth = in.get(), colour = in.get(), compression = in.get(), filter = in.get(), interlace = in.get();
                    if (depth != 8 || (colour != 2 && colour != 6) || compression != 0 || filter != 0 || interlace != 0) return null;
                    channels = colour == 2 ? 3 : 4;
                    stride = TILE_SIZE * channels;
                    data = new byte[(stride + 1) * TILE_SIZE];
                } else if (type == 0x49444154) { // IDAT
                    if (data == null) return null;
                    inflater.setInput(png, start, length);
                    while (!inflater.needsInput() && !inflater.finished() && filled < data.length) {
                        int n = inflater.inflate(data, filled, data.length - filled);
                        if (n == 0 && (inflater.needsDictionary() || inflater.needsInput())) break;
                        filled += n;
                    }
                } else if (type == 0x49454E44) { // IEND
                    break;
                } else if (type == 0x69434350 || type == 0x504C5445) { // iCCP, PLTE
                    return null;
                }
                in.position(start + length + 4);
            }
            if (data == null || filled != data.length) return null;
            byte[] previous = new byte[stride], row = new byte[stride];
            float[] metres = new float[TILE_SIZE * TILE_SIZE];
            for (int y = 0; y < TILE_SIZE; y++) {
                int at = y * (stride + 1), filter = data[at];
                System.arraycopy(data, at + 1, row, 0, stride);
                if (!unfilter(filter, row, previous, channels)) return null;
                for (int x = 0, p = 0; x < TILE_SIZE; x++, p += channels)
                    metres[y * TILE_SIZE + x] = (float) (((row[p] & 0xFF) * 256 + (row[p + 1] & 0xFF) + (row[p + 2] & 0xFF) / 256.0) - 32768.0);
                byte[] swap = previous; previous = row; row = swap;
            }
            return metres;
        } catch (java.util.zip.DataFormatException | RuntimeException malformed) {
            return null;
        } finally {
            inflater.end();
        }
    }

    /** Undoes one PNG row filter in place; false for an unknown filter. */
    private static boolean unfilter(int filter, byte[] row, byte[] previous, int bpp) {
        switch (filter) {
            case 0 -> { }
            case 1 -> { for (int i = bpp; i < row.length; i++) row[i] += row[i - bpp]; }
            case 2 -> { for (int i = 0; i < row.length; i++) row[i] += previous[i]; }
            case 3 -> {
                for (int i = 0; i < row.length; i++)
                    row[i] += (byte) (((i >= bpp ? row[i - bpp] & 0xFF : 0) + (previous[i] & 0xFF)) >> 1);
            }
            case 4 -> {
                // With nothing to the left the predictor is the byte above.
                for (int i = 0; i < bpp; i++) row[i] += previous[i];
                for (int i = bpp; i < row.length; i++) {
                    int a = row[i - bpp] & 0xFF, b = previous[i] & 0xFF, c = previous[i - bpp] & 0xFF;
                    int pa = b - c, pb = a - c, pc = pa + pb;
                    pa = pa < 0 ? -pa : pa; pb = pb < 0 ? -pb : pb; pc = pc < 0 ? -pc : pc;
                    row[i] += (byte) (pa <= pb && pa <= pc ? a : pb <= pc ? b : c);
                }
            }
            default -> { return false; }
        }
        return true;
    }
}
