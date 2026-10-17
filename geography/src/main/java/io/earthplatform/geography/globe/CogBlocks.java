package io.earthplatform.geography.globe;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Decodes the blocks of a tiled float GeoTIFF for {@link FloatCogs}: TIFF LZW, then the
 * floating-point predictor where the file uses it.
 */
final class CogBlocks {
    private CogBlocks() {}

    /**
     * A block's floats from its bytes, LZW-compressed or not, undoing the floating-point predictor
     * (3) row by row if it is used. The bytes given are left as they are.
     */
    static float[] decode(byte[] raw, int block, boolean bigEndian, int predictor, boolean compressed) throws IOException {
        int rowBytes = block * 4;
        if (!compressed && raw.length != rowBytes * block) throw new IOException("Uncompressed block of " + raw.length + " bytes");
        byte[] bytes = compressed ? lzw(raw, rowBytes * block) : predictor == 1 ? raw : raw.clone();
        float[] out = new float[block * block];
        if (predictor == 1) {
            ByteBuffer.wrap(bytes).order(bigEndian ? ByteOrder.BIG_ENDIAN : ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(out);
            return out;
        }
        for (int row = 0; row < block; row++) {
            int start = row * rowBytes;
            for (int i = start + 1; i < start + rowBytes; i++) bytes[i] += bytes[i - 1];
            for (int i = 0; i < block; i++) {
                // Byte planes run from the most significant byte, whatever the file's byte order.
                int bits = (bytes[start + i] & 0xFF) << 24 | (bytes[start + block + i] & 0xFF) << 16
                        | (bytes[start + 2 * block + i] & 0xFF) << 8 | (bytes[start + 3 * block + i] & 0xFF);
                out[row * block + i] = Float.intBitsToFloat(bits);
            }
        }
        return out;
    }

    /**
     * TIFF LZW: 9 to 12-bit codes, most significant bit first, widths changing one code early. Each
     * code's string already lies in the output where it was first written, so a code is copied from
     * there rather than walked back through its prefixes, and codes are cut from three bytes at once.
     */
    static byte[] lzw(byte[] in, int size) throws IOException {
        byte[] out = new byte[size];
        // Where each code's string starts in the output, and its length; codes below 256 are their own byte.
        int[] offset = new int[4096], length = new int[4096];
        int done = 0, next = 258, width = 9, old = -1, oldStart = 0;
        long bitPos = 0, bitEnd = (long) in.length * 8;
        int last = in.length - 1;
        while (true) {
            if (bitPos + width > bitEnd) break;
            int at = (int) (bitPos >>> 3), shift = (int) (bitPos & 7);
            int window = (in[at] & 0xFF) << 16 | (at < last ? (in[at + 1] & 0xFF) << 8 : 0) | (at + 1 < last ? in[at + 2] & 0xFF : 0);
            int code = window >>> (24 - shift - width) & (1 << width) - 1;
            bitPos += width;
            if (code == 257) break;
            if (code == 256) {
                next = 258;
                width = 9;
                old = -1;
                continue;
            }
            int start = done;
            if (old == -1) {
                if (code > 255) throw new IOException("Bad LZW code after clear");
                if (done >= size) throw new IOException("LZW block overruns its size");
                out[done++] = (byte) code;
            } else if (code < 256) {
                if (done >= size) throw new IOException("LZW block overruns its size");
                out[done++] = (byte) code;
            } else if (code < next) {
                int n = length[code];
                if (done + n > size) throw new IOException("LZW block overruns its size");
                System.arraycopy(out, offset[code], out, done, n);
                done += n;
            } else if (code == next) {
                // The code being defined: the previous string and its own first byte.
                int n = (old < 256 ? 1 : length[old]) + 1;
                if (done + n > size) throw new IOException("LZW block overruns its size");
                System.arraycopy(out, oldStart, out, done, n - 1);
                out[done + n - 1] = out[oldStart];
                done += n;
            } else {
                throw new IOException("Bad LZW code " + code);
            }
            if (old != -1 && next < 4096) {
                // The previous string plus this one's first byte, which follows it in the output.
                offset[next] = oldStart;
                length[next] = (old < 256 ? 1 : length[old]) + 1;
                next++;
                if (next + 1 >= 1 << width && width < 12) width++;
            }
            old = code;
            oldStart = start;
        }
        if (done != size) throw new IOException("Short LZW block: " + done + " of " + size + " bytes");
        return out;
    }
}
