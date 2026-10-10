package io.earthplatform.minecraft;

/** Temporary engine window. Canonical Earth metres never pass through packed BlockPos. */
public record VerticalWindow(int originY, int height) {
    public VerticalWindow {
        if ((originY & 15) != 0 || height <= 0 || (height & 15) != 0 || height > 384) {
            throw new IllegalArgumentException("Window must be section-aligned and at most 384 blocks tall");
        }
        Math.addExact(originY, height);
    }

    public long toEarthY(int engineY) {
        if (engineY < 0 || engineY >= height) throw new IllegalArgumentException("Outside active window");
        return (long) originY + engineY;
    }

    public int toEngineY(long earthY) {
        long local = earthY - originY;
        if (local < 0 || local >= height) throw new IllegalArgumentException("Earth Y is outside active window");
        return (int) local;
    }

    public int minCubeY() { return Math.floorDiv(originY, 16); }
    public int cubeCount() { return height / 16; }
}
