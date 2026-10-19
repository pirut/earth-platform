package io.earthplatform.minecraft.cubic.client;

/** Opaque ranges submitted in this view, cleared before every frame and draw pass. */
final class FarDrawCount {
    private int ranges;

    void begin() { ranges = 0; }
    void submitted(int count) { ranges = count; }
    int ranges() { return ranges; }
}
