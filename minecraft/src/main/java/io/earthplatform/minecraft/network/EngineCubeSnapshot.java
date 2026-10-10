package io.earthplatform.minecraft.network;

import io.earthplatform.core.CubePos;
import io.earthplatform.core.WorldIdentity;
import io.netty.handler.codec.DecoderException;
import net.minecraft.core.SectionPos;
import net.minecraft.network.FriendlyByteBuf;
import java.util.zip.CRC32C;

/** Immutable, bounded wire snapshot of a native section. Not yet registered as an engine streaming packet. */
public final class EngineCubeSnapshot {
    public static final int MAGIC = 0x45504331, VERSION = 2, MAX_SECTION_BYTES = 32768;
    public static final String SOURCE_VERSION = "minecraft-1.21.1/neoforge-21.1.256/cube-snapshot-v2";
    private final CubePos position;
    private final WorldIdentity world;
    private final String palette;
    private final long epoch, leaseGeneration, revision;
    private final byte[] section;
    private EngineCubeSnapshot(CubePos position, WorldIdentity world, String palette, long epoch, long leaseGeneration, long revision, byte[] ownedSection) {
        if (epoch < 0 || revision < 0) throw new IllegalArgumentException("Nonnegative epoch and revision required");
        if (leaseGeneration <= 0) throw new IllegalArgumentException("Positive lease generation required");
        if (world == null || world.generatorId().length()>128 || world.generatorVersion().length()>128
                || world.datasetVersion().length()>128 || world.blockPaletteVersion().length()>128) throw new IllegalArgumentException("World identity exceeds bounded wire schema");
        checkedKey(position);
        this.position=position; this.world=world; this.palette=palette; this.epoch=epoch; this.leaseGeneration=leaseGeneration; this.revision=revision; this.section=ownedSection;
    }
    public static EngineCubeSnapshot capture(CubePos position, WorldIdentity world, long epoch, long leaseGeneration, long revision, EngineCubeSection cube) {
        return new EngineCubeSnapshot(position, world, cube.paletteFingerprint(), epoch, leaseGeneration, revision, cube.encode());
    }
    public CubePos position() { return position; }
    public WorldIdentity world() { return world; }
    public long epoch() { return epoch; }
    public long leaseGeneration() { return leaseGeneration; }
    public long revision() { return revision; }
    public int sectionBytes() { return section.length; }
    public byte[] copySectionBytes() { return section.clone(); }
    public EngineCubeSection materialize(EnginePaletteContext context) {
        if (!palette.equals(context.fingerprint())) throw new DecoderException("Registry palette fingerprint mismatch");
        return EngineCubeSection.decode(section, context);
    }
    public void write(FriendlyByteBuf buffer) {
        int start = buffer.writerIndex();
        buffer.writeInt(MAGIC); buffer.writeVarInt(VERSION); buffer.writeUtf(SOURCE_VERSION, 64);
        buffer.writeLong(world.seed());
        buffer.writeUtf(world.generatorId(),128); buffer.writeUtf(world.generatorVersion(),128);
        buffer.writeUtf(world.datasetVersion(),128); buffer.writeUtf(world.blockPaletteVersion(),128);
        buffer.writeUtf(palette,64); buffer.writeLong(epoch); buffer.writeLong(leaseGeneration); buffer.writeLong(revision);
        buffer.writeLong(checkedKey(position)); buffer.writeVarInt(section.length); buffer.writeBytes(section);
        CRC32C crc = new CRC32C(); crc.update(buffer.nioBuffer(start,buffer.writerIndex()-start)); buffer.writeInt((int)crc.getValue());
    }
    /** Read exactly one framed packet. Network handlers should prefer this to the concatenated-stream API. */
    public static EngineCubeSnapshot readFrame(FriendlyByteBuf buffer, WorldIdentity world, long epoch, EnginePaletteContext context) {
        EngineCubeSnapshot result = read(buffer, world, epoch, context);
        if (buffer.isReadable()) throw new DecoderException("Unexpected bytes after cube snapshot frame");
        return result;
    }
    /** Reads one length-bounded snapshot, leaving any subsequent concatenated frame for the caller. */
    public static EngineCubeSnapshot read(FriendlyByteBuf buffer, WorldIdentity expectedWorld, long expectedEpoch, EnginePaletteContext context) {
        int start = buffer.readerIndex();
        try {
            if (buffer.readInt() != MAGIC || buffer.readVarInt() != VERSION) throw new DecoderException("Unsupported cube protocol");
            if (!SOURCE_VERSION.equals(buffer.readUtf(64))) throw new DecoderException("Minecraft section source version mismatch");
            WorldIdentity world = new WorldIdentity(buffer.readLong(),buffer.readUtf(128),buffer.readUtf(128),buffer.readUtf(128),buffer.readUtf(128));
            if (!world.equals(expectedWorld)) throw new DecoderException("Cube world identity mismatch");
            String palette = buffer.readUtf(64);
            if (!palette.equals(context.fingerprint())) throw new DecoderException("Cube registry palette mismatch");
            long epoch = buffer.readLong(), leaseGeneration = buffer.readLong(), revision = buffer.readLong();
            if (epoch != expectedEpoch || epoch < 0 || revision < 0 || leaseGeneration <= 0) throw new DecoderException("Stale cube epoch, invalid lease generation or negative revision");
            long key = buffer.readLong(); CubePos position = new CubePos(SectionPos.x(key),SectionPos.y(key),SectionPos.z(key));
            int count = buffer.readVarInt();
            if (count <= 0 || count > MAX_SECTION_BYTES || buffer.readableBytes() < 4 || count > buffer.readableBytes()-4) throw new DecoderException("Invalid cube byte length: " + count);
            int expectedCrc = buffer.getInt(buffer.readerIndex()+count); CRC32C crc = new CRC32C();
            crc.update(buffer.nioBuffer(start,buffer.readerIndex()-start+count));
            if ((int)crc.getValue()!=expectedCrc) throw new DecoderException("Cube payload checksum mismatch");
            // Validate nested palette lengths before the owned payload or Minecraft containers allocate.
            var view = new FriendlyByteBuf(buffer.slice(buffer.readerIndex(),count));
            SectionWireValidator.validate(view,context);
            byte[] section = new byte[count]; buffer.readBytes(section); buffer.skipBytes(4);
            return new EngineCubeSnapshot(position,world,palette,epoch,leaseGeneration,revision,section);
        } catch (RuntimeException failure) {
            if (failure instanceof DecoderException malformed) throw malformed;
            throw new DecoderException("Malformed cube snapshot",failure);
        }
    }
    private static long checkedKey(CubePos position) {
        long key=SectionPos.asLong(position.x(),position.y(),position.z());
        if (SectionPos.x(key)!=position.x() || SectionPos.y(key)!=position.y() || SectionPos.z(key)!=position.z()) throw new IllegalArgumentException("Cube exceeds lossless section-key range");
        return key;
    }
}
