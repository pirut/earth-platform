package io.earthplatform.minecraft;

import io.earthplatform.core.CubePos;
import net.minecraft.core.SectionPos;
import net.minecraft.network.FriendlyByteBuf;

/** Engine-facing full-height identity: section key plus 12-bit local offset, never BlockPos.asLong(). */
public record EarthBlockAddress(long sectionKey, int localIndex) {
    public EarthBlockAddress {
        if (localIndex < 0 || localIndex >= CubePos.VOLUME) throw new IllegalArgumentException("Local cube offset must be 0..4095");
    }
    public static EarthBlockAddress of(long x, long y, long z) {
        CubePos cube = CubePos.fromBlock(x, y, z);
        long key = SectionPos.asLong(cube.x(), cube.y(), cube.z());
        if (SectionPos.x(key) != cube.x() || SectionPos.y(key) != cube.y() || SectionPos.z(key) != cube.z()) {
            throw new IllegalArgumentException("Coordinates exceed section-key range");
        }
        return new EarthBlockAddress(key, CubePos.index(CubePos.local(x), CubePos.local(y), CubePos.local(z)));
    }
    public long x() { return (long) SectionPos.x(sectionKey) * 16 + (localIndex & 15); }
    public long z() { return (long) SectionPos.z(sectionKey) * 16 + ((localIndex >>> 4) & 15); }
    public long y() { return (long) SectionPos.y(sectionKey) * 16 + ((localIndex >>> 8) & 15); }
    public void write(FriendlyByteBuf buffer) { buffer.writeLong(sectionKey); buffer.writeShort(localIndex); }
    public static EarthBlockAddress read(FriendlyByteBuf buffer) { return new EarthBlockAddress(buffer.readLong(), buffer.readUnsignedShort()); }
}
