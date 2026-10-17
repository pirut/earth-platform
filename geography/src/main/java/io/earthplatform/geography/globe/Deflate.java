package io.earthplatform.geography.globe;

import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/**
 * Inflates zlib-wrapped Deflate data, as the TIFF files of the land cover, Copernicus, surface
 * water and climate sources hold their blocks and rows. Each caller checks the length it gets
 * back and words its own errors.
 */
final class Deflate {
    private Deflate() {}

    /**
     * Inflates {@code raw} into {@code out} until {@code out} is full, the data ends, or it needs
     * more input or a dictionary, and returns how many bytes were written.
     */
    static int inflate(byte[] raw, byte[] out) throws DataFormatException {
        Inflater inflater = new Inflater();
        try {
            inflater.setInput(raw);
            int size = out.length, done = 0;
            while (done < size && !inflater.finished()) {
                int n = inflater.inflate(out, done, size - done);
                if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) break;
                done += n;
            }
            return done;
        } finally {
            inflater.end();
        }
    }
}
