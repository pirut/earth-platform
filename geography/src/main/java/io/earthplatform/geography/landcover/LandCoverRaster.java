package io.earthplatform.geography.landcover;

import io.earthplatform.geography.GeoPoint;
import java.io.*;
import java.util.Objects;

/** Immutable ELC1 categorical raster. West/north describe pixel AREA edges, unlike elevation EGP1. */
public final class LandCoverRaster {
    public static final int MAGIC = 0x454c4331;
    public static final int HEADER_BYTES = 44;
    public static final int MAX_CELLS = 16 * 1024 * 1024;
    private final String id;
    private final int width, height;
    private final double west, north, stepLon, stepLat;
    private final byte[] values;

    public LandCoverRaster(String id, int width, int height, double west, double north,
                           double stepLon, double stepLat, byte[] values) {
        this.id = Objects.requireNonNull(id);
        if (id.isBlank() || width <= 0 || height <= 0 || (long) width * height > MAX_CELLS
                || values.length != (long) width * height || !Double.isFinite(west)
                || west < -180 || west >= 180 || !Double.isFinite(north) || north > 90 || north < -90
                || !Double.isFinite(stepLon) || !Double.isFinite(stepLat) || stepLon <= 0 || stepLat <= 0
                || !Double.isFinite(width * stepLon) || width * stepLon > 360
                || !Double.isFinite(height * stepLat) || north - height * stepLat < -90)
            throw new IllegalArgumentException("Invalid categorical raster geometry");
        for (byte value : values) LandCoverClass.fromCode(Byte.toUnsignedInt(value));
        this.width = width; this.height = height; this.west = west; this.north = north;
        this.stepLon = stepLon; this.stepLat = stepLat; this.values = values.clone();
    }
    public String id() { return id; }
    public int width() { return width; }
    public int height() { return height; }
    public double west() { return west; }
    public double north() { return north; }
    public double stepLon() { return stepLon; }
    public double stepLat() { return stepLat; }
    public int payloadBytes() { return values.length; }
    private double unwrappedLongitude(GeoPoint point) {
        return point.longitude() < west ? point.longitude() + 360 : point.longitude();
    }
    public boolean covers(GeoPoint point) {
        double longitude = unwrappedLongitude(point);
        return longitude < west + width * stepLon && point.latitude() <= north && point.latitude() > north - height * stepLat;
    }
    /** Containing source pixel; no averaging, interpolation, randomization, or invented subpixel detail. */
    public LandCoverSample sample(GeoPoint point) {
        if (!covers(point)) return LandCoverSample.outsideCoverage();
        int x = Math.min(width - 1, (int) StrictMath.floor((unwrappedLongitude(point) - west) / stepLon));
        int y = Math.min(height - 1, (int) StrictMath.floor((north - point.latitude()) / stepLat));
        LandCoverClass result = LandCoverClass.fromCode(Byte.toUnsignedInt(values[y * width + x]));
        return new LandCoverSample(result, result == LandCoverClass.UNKNOWN
                ? LandCoverSample.Status.SOURCE_NO_DATA : LandCoverSample.Status.MAPPED, id);
    }
    public byte[] encode() throws IOException {
        var bytes = new ByteArrayOutputStream(HEADER_BYTES + values.length);
        try (var out = new DataOutputStream(bytes)) {
            out.writeInt(MAGIC); out.writeInt(width); out.writeInt(height);
            out.writeDouble(west); out.writeDouble(north); out.writeDouble(stepLon); out.writeDouble(stepLat);
            out.write(values);
        }
        return bytes.toByteArray();
    }
    public static LandCoverRaster decode(String id, byte[] bytes) throws IOException {
        if (bytes.length < HEADER_BYTES || bytes.length > HEADER_BYTES + MAX_CELLS)
            throw new IOException("Invalid ELC1 byte length");
        try (var in = new DataInputStream(new ByteArrayInputStream(bytes))) {
            if (in.readInt() != MAGIC) throw new IOException("Invalid ELC1 magic");
            int width = in.readInt(), height = in.readInt();
            if (width <= 0 || height <= 0 || (long) width * height != bytes.length - HEADER_BYTES)
                throw new IOException("Invalid ELC1 dimensions or trailing/truncated bytes");
            double west = in.readDouble(), north = in.readDouble(), dx = in.readDouble(), dy = in.readDouble();
            byte[] values = in.readNBytes(bytes.length - HEADER_BYTES);
            return new LandCoverRaster(id, width, height, west, north, dx, dy, values);
        } catch (IllegalArgumentException e) { throw new IOException("Invalid ELC1 data", e); }
    }
}
