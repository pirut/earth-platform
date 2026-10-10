package io.earthplatform.minecraft;

import com.mojang.serialization.Lifecycle;
import io.earthplatform.core.CubePos;
import io.earthplatform.core.WorldIdentity;
import io.earthplatform.minecraft.network.EngineCubeSection;
import io.earthplatform.minecraft.network.EngineCubeSnapshot;
import io.earthplatform.minecraft.network.EnginePaletteContext;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.DecoderException;
import java.util.concurrent.CompletableFuture;
import java.util.zip.CRC32C;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.RegistrationInfo;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Block;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class EngineCubeSnapshotTest {
    private static EnginePaletteContext palette;
    private static final WorldIdentity WORLD = new WorldIdentity(42,"earth","surface-fill-v1","noaa-etopo-2022","materials-v1");
    @BeforeAll static void registries() {
        var registry = new MappedRegistry<Biome>(Registries.BIOME,Lifecycle.stable());
        registry.register(Biomes.PLAINS,VanillaRegistries.createLookup().lookupOrThrow(Registries.BIOME).getOrThrow(Biomes.PLAINS).value(),RegistrationInfo.BUILT_IN);
        palette=EnginePaletteContext.create(registry.freeze());
    }
    private static EngineCubeSection section(String kind) {
        if(kind.equals("rock")) return EngineCubeSection.uniform(palette,Blocks.STONE.defaultBlockState());
        var section=new EngineCubeSection(palette);
        for(int y=0;y<16;y++) for(int z=0;z<16;z++) for(int x=0;x<16;x++) {
            var block = switch(kind) {
                case "rock" -> Blocks.STONE;
                case "mixed" -> y<7 ? Blocks.STONE : y<10 ? Blocks.DIRT : y==10 ? Blocks.GRASS_BLOCK : (x<5 && y<13 ? Blocks.WATER : Blocks.AIR);
                default -> Blocks.AIR;
            };
            section.set(x,y,z,block.defaultBlockState());
        }
        return section;
    }
    private static FriendlyByteBuf encode(EngineCubeSnapshot snapshot) { var b=new FriendlyByteBuf(Unpooled.buffer());snapshot.write(b);return b; }
    private static FriendlyByteBuf malformed(byte[] section,int length) {
        var b=new FriendlyByteBuf(Unpooled.buffer());
        b.writeInt(EngineCubeSnapshot.MAGIC);b.writeVarInt(EngineCubeSnapshot.VERSION);b.writeUtf(EngineCubeSnapshot.SOURCE_VERSION,64);
        b.writeLong(WORLD.seed());b.writeUtf(WORLD.generatorId(),128);b.writeUtf(WORLD.generatorVersion(),128);
        b.writeUtf(WORLD.datasetVersion(),128);b.writeUtf(WORLD.blockPaletteVersion(),128);b.writeUtf(palette.fingerprint(),64);
        b.writeLong(7);b.writeLong(11);b.writeLong(3);b.writeLong(0);b.writeVarInt(length);
        b.writeBytes(section);CRC32C crc=new CRC32C();crc.update(b.nioBuffer(0,b.writerIndex()));b.writeInt((int)crc.getValue());return b;
    }
    @Test void actualMinecraftStatesAndExtremeCubeAddressesRoundTrip() {
        for(int y:new int[]{-688,-1,0,553}) for(String kind:new String[]{"air","rock","mixed"}) {
            var source=section(kind);
            var position=new CubePos(1_252_344,y,-626_173);
            var captured=EngineCubeSnapshot.capture(position,WORLD,7,11,3,source);
            var wire=encode(captured);
            try {
                var decoded=EngineCubeSnapshot.read(wire,WORLD,7,palette);
                assertEquals(position,decoded.position());assertEquals(WORLD,decoded.world());assertEquals(3,decoded.revision());assertEquals(11,decoded.leaseGeneration());
                var restored=decoded.materialize(palette);
                for(int ly=0;ly<16;ly++) for(int z=0;z<16;z++) for(int x=0;x<16;x++) assertEquals(source.get(x,ly,z),restored.get(x,ly,z));
                assertFalse(wire.isReadable());
            } finally {wire.release();}
        }
    }
    @Test void fluidAndWaterloggedStatesKeepCanonicalCountsAcrossRepeatedDecodeEncode() {
        var states=java.util.List.of(Blocks.WATER.defaultBlockState(),Blocks.LAVA.defaultBlockState(),
                Blocks.OAK_SLAB.defaultBlockState().setValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.WATERLOGGED,true));
        for(var state:states) {
            var current=EngineCubeSection.uniform(palette,state);
            for(int generation=0;generation<5;generation++) {
                var snapshot=EngineCubeSnapshot.capture(new CubePos(0,-688,0),WORLD,7,11,generation,current);
                byte[] bytes=snapshot.copySectionBytes();assertEquals(4096,((bytes[0]&255)<<8)|(bytes[1]&255));
                var wire=encode(snapshot);
                try { current=EngineCubeSnapshot.readFrame(wire,WORLD,7,palette).materialize(palette); }
                finally { wire.release(); }
                for(int y=0;y<16;y++)for(int z=0;z<16;z++)for(int x=0;x<16;x++)assertSame(state,current.get(x,y,z));
            }
        }
    }
    @Test void canonicalCountLeavesNativeAirAndTickBookkeepingIntact() throws Exception {
        var nativeField=EngineCubeSection.class.getDeclaredField("section");nativeField.setAccessible(true);
        var states=java.util.List.of(Blocks.AIR.defaultBlockState(),Blocks.STONE.defaultBlockState(),Blocks.DIRT.defaultBlockState(),
                Blocks.GOLD_BLOCK.defaultBlockState(),Blocks.DIAMOND_BLOCK.defaultBlockState(),Blocks.WATER.defaultBlockState(),
                Blocks.LAVA.defaultBlockState(),Blocks.OAK_SLAB.defaultBlockState().setValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.WATERLOGGED,true));
        for(var state:states) {
            var before=EngineCubeSection.uniform(palette,state);
            var nativeBefore=(net.minecraft.world.level.chunk.LevelChunkSection)nativeField.get(before);
            boolean empty=nativeBefore.hasOnlyAir(),blocks=nativeBefore.isRandomlyTickingBlocks(),fluids=nativeBefore.isRandomlyTickingFluids();
            var snapshot=EngineCubeSnapshot.capture(new CubePos(0,-688,0),WORLD,7,11,1,before);
            assertEquals(empty,nativeBefore.hasOnlyAir());assertEquals(blocks,nativeBefore.isRandomlyTickingBlocks());assertEquals(fluids,nativeBefore.isRandomlyTickingFluids());
            var after=snapshot.materialize(palette);var nativeAfter=(net.minecraft.world.level.chunk.LevelChunkSection)nativeField.get(after);
            assertEquals(empty,nativeAfter.hasOnlyAir());assertEquals(blocks,nativeAfter.isRandomlyTickingBlocks());assertEquals(fluids,nativeAfter.isRandomlyTickingFluids());
            assertEquals(state.isEmpty(),after.isEmpty());
        }
    }
    @Test void snapshotIsImmutableAndMutableSectionsEnforceThreadOwnership() {
        var source=section("rock");var snapshot=EngineCubeSnapshot.capture(new CubePos(0,553,0),WORLD,7,11,1,source);
        source.set(0,0,0,Blocks.DIAMOND_BLOCK.defaultBlockState());
        byte[] copy=snapshot.copySectionBytes();java.util.Arrays.fill(copy,(byte)0);
        assertEquals(Blocks.STONE.defaultBlockState(),snapshot.materialize(palette).get(0,0,0));
        var wrongThread=CompletableFuture.supplyAsync(()->assertThrows(IllegalStateException.class,()->source.get(0,0,0))).join();
        assertTrue(wrongThread.getMessage().contains("non-owner"));
        assertDoesNotThrow(()->CompletableFuture.supplyAsync(()->snapshot.materialize(palette).get(0,0,0)).join());
    }
    @Test void globalPaletteRetainsExactBlockStateVariants() {
        var source=new EngineCubeSection(palette);
        for(int y=0;y<16;y++)for(int z=0;z<16;z++)for(int x=0;x<16;x++)source.set(x,y,z,Block.BLOCK_STATE_REGISTRY.byId(((y*16+z)*16+x)%1024));
        var encoded=encode(EngineCubeSnapshot.capture(new CubePos(-1,-688,1),WORLD,7,11,1,source));
        try {
            var restored=EngineCubeSnapshot.read(encoded,WORLD,7,palette).materialize(palette);
            for(int y=0;y<16;y++)for(int z=0;z<16;z++)for(int x=0;x<16;x++)assertEquals(source.get(x,y,z),restored.get(x,y,z));
        }finally{encoded.release();}
    }
    @Test void localAndGlobalBiomePalettesPreserveExactRegistryEntries() {
        var keys=java.util.List.of(Biomes.PLAINS,Biomes.DESERT,Biomes.FOREST,Biomes.OCEAN,Biomes.RIVER,Biomes.BEACH,Biomes.TAIGA,Biomes.SWAMP,Biomes.SAVANNA,Biomes.BADLANDS);
        var lookup=VanillaRegistries.createLookup().lookupOrThrow(Registries.BIOME);
        for(int count:new int[]{3,10}) {
            var registry=new MappedRegistry<Biome>(Registries.BIOME,Lifecycle.stable());
            for(int index=0;index<count;index++)registry.register(keys.get(index),lookup.getOrThrow(keys.get(index)).value(),RegistrationInfo.BUILT_IN);
            var context=EnginePaletteContext.create(registry.freeze());var source=new EngineCubeSection(context);
            source.fillBiomes((x,y,z,unused)->registry.getHolderOrThrow(keys.get(((y*4+z)*4+x)%count)));
            var wire=encode(EngineCubeSnapshot.capture(new CubePos(0,553,0),WORLD,7,11,1,source));
            try {
                var restored=EngineCubeSnapshot.readFrame(wire,WORLD,7,context).materialize(context);
                for(int y=0;y<4;y++)for(int z=0;z<4;z++)for(int x=0;x<4;x++)assertSame(source.biome(x,y,z),restored.biome(x,y,z));
            }finally{wire.release();}
        }
    }
    @Test void rejectsOuterAllocationBombsAndTruncationBeforePayloadAllocation() {
        for(int count:new int[]{-1,0,32769,Integer.MAX_VALUE,32768}) {
            var wire=malformed(new byte[]{1},count);
            try {assertThrows(DecoderException.class,()->EngineCubeSnapshot.read(wire,WORLD,7,palette));} finally {wire.release();}
        }
    }
    @Test void rejectsNestedPaletteBombsUnknownIdsAndInvalidBitWidths() {
        for(int bits:new int[]{1,3,9,32,255}) {
            var section=new FriendlyByteBuf(Unpooled.buffer());
            try {section.writeShort(0);section.writeByte(bits);section.writeVarInt(Integer.MAX_VALUE);
                byte[] bytes=new byte[section.readableBytes()];section.readBytes(bytes);var wire=malformed(bytes,bytes.length);
                try{assertThrows(DecoderException.class,()->EngineCubeSnapshot.read(wire,WORLD,7,palette));}finally{wire.release();}
            }finally{section.release();}
        }
        for(byte[] payload:new byte[][]{
                {0,0,4,(byte)0xff,(byte)0xff,(byte)0xff,(byte)0xff,7},
                {0,0,0,(byte)0xff,(byte)0xff,(byte)0xff,(byte)0xff,7,0},
                {0,0,0,0,1},
                {(byte)0xff,(byte)0xff,0,0,0}
        }) {var wire=malformed(payload,payload.length);try{assertThrows(DecoderException.class,()->EngineCubeSnapshot.read(wire,WORLD,7,palette));}finally{wire.release();}}
    }
    @Test void rejectsWrongWorldEpochRegistrySourceAndCorruptChecksum() {
        var snapshot=EngineCubeSnapshot.capture(new CubePos(0,0,0),WORLD,7,11,3,section("mixed"));
        var wrongWorld=new WorldIdentity(43,"earth","surface-fill-v1","noaa-etopo-2022","materials-v1");
        var b=encode(snapshot);final var wrong=b;try{assertThrows(DecoderException.class,()->EngineCubeSnapshot.read(wrong,wrongWorld,7,palette));}finally{b.release();}
        b=encode(snapshot);final var stale=b;try{assertThrows(DecoderException.class,()->EngineCubeSnapshot.read(stale,WORLD,8,palette));}finally{b.release();}
        b=encode(snapshot);b.setByte(b.writerIndex()-1,b.getByte(b.writerIndex()-1)^1);final var corrupt=b;
        try{assertThrows(DecoderException.class,()->EngineCubeSnapshot.read(corrupt,WORLD,7,palette));}finally{b.release();}
        b=encode(snapshot);b.setByte(5,'X');final var source=b;
        try{assertThrows(DecoderException.class,()->EngineCubeSnapshot.read(source,WORLD,7,palette));}finally{b.release();}
        var other=new EnginePaletteContext(palette.biomes(),"0".repeat(64));b=encode(snapshot);final var mismatch=b;
        try{assertThrows(DecoderException.class,()->EngineCubeSnapshot.read(mismatch,WORLD,7,other));}finally{b.release();}
    }
    @Test void benchmarksBoundedSnapshotEncodeDecode() {
        for(String kind:new String[]{"air","rock","mixed"}) {
            var nativeSection=section(kind);int wireBytes=0;long encode=0,decode=0;
            for(int repeat=0;repeat<250;repeat++) {
                long start=System.nanoTime();var snapshot=EngineCubeSnapshot.capture(new CubePos(0,553,0),WORLD,7,11,3,nativeSection);var buffer=encode(snapshot);
                long middle=System.nanoTime();
                try {wireBytes=buffer.readableBytes();var decoded=EngineCubeSnapshot.read(buffer,WORLD,7,palette);decoded.materialize(palette);
                    if(repeat>=50){encode+=middle-start;decode+=System.nanoTime()-middle;}
                }finally{buffer.release();}
            }
            System.out.println("ENGINE_CUBE_WIRE {\"fixture\":\""+kind+"\",\"wireBytes\":"+wireBytes+",\"meanEncodeMicros\":"+(encode/200/1000.0)+",\"meanValidateDecodeMaterializeMicros\":"+(decode/200/1000.0)+",\"scope\":\"warm single-thread codec microbenchmark; not gameplay performance\"}");
            assertTrue(wireBytes<EngineCubeSnapshot.MAX_SECTION_BYTES+1024);
        }
    }
    @Test void everyTruncatedPrefixOfAValidMixedFrameIsRejected() {
        var frame=encode(EngineCubeSnapshot.capture(new CubePos(0,-688,0),WORLD,7,11,3,section("mixed")));
        try {
            for(int length=0;length<frame.readableBytes();length++) {
                var prefix=new FriendlyByteBuf(frame.copy(0,length));
                try{assertThrows(DecoderException.class,()->EngineCubeSnapshot.readFrame(prefix,WORLD,7,palette));}finally{prefix.release();}
            }
        }finally{frame.release();}
    }
    @Test void positiveLeaseGenerationIsRequiredAndOldWireVersionIsRejected() {
        var cube=section("air");
        for(long token:new long[]{0,-1,Long.MIN_VALUE}) assertThrows(IllegalArgumentException.class,
                ()->EngineCubeSnapshot.capture(new CubePos(0,0,0),WORLD,7,token,3,cube));
        var snapshot=EngineCubeSnapshot.capture(new CubePos(0,0,0),WORLD,7,Long.MAX_VALUE,3,cube);
        var frame=encode(snapshot);
        try{assertEquals(Long.MAX_VALUE,EngineCubeSnapshot.readFrame(frame,WORLD,7,palette).leaseGeneration());}finally{frame.release();}
        var old=encode(snapshot);old.setByte(4,1);
        try{assertThrows(DecoderException.class,()->EngineCubeSnapshot.readFrame(old,WORLD,7,palette));}finally{old.release();}
    }
    @Test void exactFramesRejectTrailingBytesWhileStreamApiConsumesOneAtATime() {
        var snapshot=EngineCubeSnapshot.capture(new CubePos(0,0,0),WORLD,7,11,3,section("air"));
        var concatenated=encode(snapshot);snapshot.write(concatenated);
        try {
            EngineCubeSnapshot.read(concatenated,WORLD,7,palette);assertTrue(concatenated.isReadable());
            EngineCubeSnapshot.read(concatenated,WORLD,7,palette);assertFalse(concatenated.isReadable());
        }finally{concatenated.release();}
        var withTrailing=encode(snapshot);withTrailing.writeByte(0);
        try{assertThrows(DecoderException.class,()->EngineCubeSnapshot.readFrame(withTrailing,WORLD,7,palette));}finally{withTrailing.release();}
    }
    @Test void seededPayloadFuzzNeverEscapesBoundedDecoderContract() {
        var random=new java.util.Random(20261009L);
        for(int attempt=0;attempt<4000;attempt++) {
            byte[] payload=new byte[random.nextInt(129)];random.nextBytes(payload);
            var frame=malformed(payload,payload.length);
            try {
                try {var decoded=EngineCubeSnapshot.readFrame(frame,WORLD,7,palette);assertTrue(decoded.sectionBytes()<=EngineCubeSnapshot.MAX_SECTION_BYTES);decoded.materialize(palette);}
                catch(DecoderException expected) { /* Rejection is the normal result for a random native section. */ }
            }finally{frame.release();}
        }
        // Oversized varints must be normalized to DecoderException rather than leaking a raw runtime error.
        var badVersion=new FriendlyByteBuf(Unpooled.buffer());badVersion.writeInt(EngineCubeSnapshot.MAGIC);for(int i=0;i<6;i++)badVersion.writeByte(0x80);
        try{assertThrows(DecoderException.class,()->EngineCubeSnapshot.readFrame(badVersion,WORLD,7,palette));}finally{badVersion.release();}
    }
}
