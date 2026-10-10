package io.earthplatform.minecraft.network;

import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeResolver;

/** Owns one actual Minecraft LevelChunkSection on one thread. No mutable section escapes. */
public final class EngineCubeSection {
    private final Thread owner = Thread.currentThread();
    private final EnginePaletteContext context;
    private final LevelChunkSection section;
    public EngineCubeSection(EnginePaletteContext context) {
        this.context = context; this.section = new LevelChunkSection(context.biomes());
    }
    private EngineCubeSection(EnginePaletteContext context, LevelChunkSection section) { this.context=context;this.section=section; }
    public static EngineCubeSection uniform(EnginePaletteContext context, BlockState state) {
        if (Block.BLOCK_STATE_REGISTRY.getId(state)<0) throw new IllegalArgumentException("Unregistered block state");
        var blocks=new PalettedContainer<>(Block.BLOCK_STATE_REGISTRY,state,PalettedContainer.Strategy.SECTION_STATES);
        var biomes=new PalettedContainer<>(context.biomes().asHolderIdMap(),context.biomes().getHolderOrThrow(Biomes.PLAINS),PalettedContainer.Strategy.SECTION_BIOMES);
        return new EngineCubeSection(context,new LevelChunkSection(blocks,biomes));
    }
    public BlockState get(int x, int y, int z) { check(x,y,z); return section.getBlockState(x,y,z); }
    public void set(int x, int y, int z, BlockState state) {
        check(x,y,z); if (Block.BLOCK_STATE_REGISTRY.getId(state)<0) throw new IllegalArgumentException("Unregistered block state");
        section.setBlockState(x,y,z,state);
    }
    public void fillBiomes(BiomeResolver resolver) {
        checkOwner();
        section.fillBiomesFromNoise((x,y,z,ignored)->{
            Biome biome=resolver.getNoiseBiome(x,y,z,null).value();
            if(context.biomes().getId(biome)<0) throw new IllegalArgumentException("Unregistered biome");
            return context.biomes().wrapAsHolder(biome);
        },null,0,0,0);
    }
    public Biome biome(int x,int y,int z) {
        checkOwner();if((x|y|z)<0 || x>=4 || y>=4 || z>=4)throw new IndexOutOfBoundsException("Cube-local quart position required");
        return section.getNoiseBiome(x,y,z).value();
    }
    public boolean isEmpty() { checkOwner(); return section.hasOnlyAir(); }
    public String paletteFingerprint() { return context.fingerprint(); }
    private void check(int x,int y,int z) {
        checkOwner(); if ((x|y|z) < 0 || x >= 16 || y >= 16 || z >= 16) throw new IndexOutOfBoundsException("Cube-local position required");
    }
    private void checkOwner() { if (Thread.currentThread() != owner) throw new IllegalStateException("Mutable Minecraft section accessed from non-owner thread"); }
    byte[] encode() {
        checkOwner();
        var buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            section.write(buffer);
            // Pinned 1.21.1 recalcBlockCounts counts a fluid-containing state twice, whereas
            // incremental setBlockState counts it once. Our frame uses one canonical count:
            // the number of nonempty cells, independently recomputed from actual state data.
            int[] nonEmpty = {0};
            section.getStates().count((state, count) -> { if (!state.isEmpty()) nonEmpty[0] += count; });
            buffer.setShort(0, nonEmpty[0]);
            if (buffer.readableBytes() > EngineCubeSnapshot.MAX_SECTION_BYTES) throw new IllegalStateException("Section exceeds bounded wire format");
            byte[] bytes = new byte[buffer.readableBytes()]; buffer.readBytes(bytes); return bytes;
        } finally { buffer.release(); }
    }
    static EngineCubeSection decode(byte[] bytes, EnginePaletteContext context) {
        var buffer = new FriendlyByteBuf(Unpooled.wrappedBuffer(bytes));
        try {
            SectionWireValidator.validate(buffer, context);
            buffer.readerIndex(0);
            EngineCubeSection cube = new EngineCubeSection(context);
            cube.section.read(buffer);
            cube.section.recalcBlockCounts(); // restore tickability as well as client-side nonempty count
            return cube;
        } finally { buffer.release(); }
    }
}
