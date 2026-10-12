package io.earthplatform.geography;

import java.io.*;
import java.util.Arrays;

/** Immutable EGP1 float32 raster. NaN denotes no data; neither NaN nor infinity is filled automatically. */
public final class RasterTile {
    public static final int MAGIC = 0x45475031;
    private final TileSpec spec;
    private final float[] heights;
    public RasterTile(TileSpec spec, float[] heights) {
        if (heights.length != (long) spec.width() * spec.height()) throw new IllegalArgumentException("Wrong cell count");
        this.spec = spec; this.heights = heights.clone();
    }
    public TileSpec spec() { return spec; }
    public long payloadBytes() { return (long) heights.length * Float.BYTES; }
    public double sample(GeoPoint point) {
        if (!spec.covers(point)) throw new MissingTerrainException("Point outside tile " + spec.id());
        double x = Math.clamp(spec.sampleX(point), 0, spec.width() - 1), y = Math.clamp(spec.sampleY(point), 0, spec.height() - 1);
        int x0 = (int) StrictMath.floor(x), y0 = (int) StrictMath.floor(y);
        int x1 = Math.min(x0 + 1, spec.width() - 1), y1 = Math.min(y0 + 1, spec.height() - 1);
        double dx = x - x0, dy = y - y0;
        // Ignore zero-weight no-data neighbours at exact sample centres and grid lines.
        double sum = 0;
        int[] indices = {y0 * spec.width() + x0, y0 * spec.width() + x1, y1 * spec.width() + x0, y1 * spec.width() + x1};
        double[] weights = {(1-dx)*(1-dy), dx*(1-dy), (1-dx)*dy, dx*dy};
        for (int i = 0; i < 4; i++) if (weights[i] > 0) {
            float value = heights[indices[i]];
            if (!Float.isFinite(value)) throw new MissingTerrainException("No-data sample in " + spec.id());
            sum += weights[i] * value;
        }
        return sum;
    }
    /**
     * Conservative continuous bilinear support proof for an axis-aligned geographic rectangle.
     * Every raster node in its inclusive interpolation stencil must be finite, including nodes
     * with zero endpoint weight. An interior no-data hole therefore cannot be hidden by finite
     * corners. Wrapped/reversed, out-of-domain or unusually large rectangles fail closed.
     */
    public boolean hasFiniteRectangleSupport(GeoPoint northWest, GeoPoint southEast) {
        double x0=spec.sampleX(northWest),x1=spec.sampleX(southEast);
        double y0=spec.sampleY(northWest),y1=spec.sampleY(southEast);
        if(!Double.isFinite(x0)||!Double.isFinite(x1)||!Double.isFinite(y0)||!Double.isFinite(y1)
                ||x0<0||y0<0||x1<x0||y1<y0||x1>spec.width()-1||y1>spec.height()-1)return false;
        int left=(int)StrictMath.floor(x0),right=(int)StrictMath.ceil(x1);
        int top=(int)StrictMath.floor(y0),bottom=(int)StrictMath.ceil(y1);
        if((long)(right-left+1)*(bottom-top+1)>65536)return false;
        for(int y=top;y<=bottom;y++)for(int x=left;x<=right;x++)
            if(!Float.isFinite(heights[y*spec.width()+x]))return false;
        return true;
    }
    public byte[] encode() throws IOException {
        var bytes = new ByteArrayOutputStream();
        try (var out = new DataOutputStream(bytes)) {
            out.writeInt(MAGIC); out.writeInt(spec.width()); out.writeInt(spec.height());
            out.writeDouble(spec.west()); out.writeDouble(spec.north()); out.writeDouble(spec.stepLon()); out.writeDouble(spec.stepLat());
            for (float v : heights) out.writeFloat(v);
        }
        return bytes.toByteArray();
    }
    public static RasterTile decode(TileSpec expected, byte[] bytes) throws IOException {
        if (bytes.length != expected.encodedBytes()) throw new IOException("Raster byte length mismatch");
        try (var in = new DataInputStream(new ByteArrayInputStream(bytes))) {
            if (in.readInt() != MAGIC || in.readInt() != expected.width() || in.readInt() != expected.height()
                    || Double.compare(in.readDouble(), expected.west()) != 0 || Double.compare(in.readDouble(), expected.north()) != 0
                    || Double.compare(in.readDouble(), expected.stepLon()) != 0 || Double.compare(in.readDouble(), expected.stepLat()) != 0)
                throw new IOException("Raster header disagrees with pinned manifest");
            float[] values = new float[expected.width() * expected.height()];
            for (int i = 0; i < values.length; i++) values[i] = in.readFloat();
            return new RasterTile(expected, values);
        }
    }
}
