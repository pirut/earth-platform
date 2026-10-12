package io.earthplatform.minecraft.survival.host;

import java.util.Collection;
import java.util.Map;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.ticks.TickAccess;
import io.earthplatform.minecraft.survival.world.SurvivalRefusal;

/**
 * Exact packet/save call-site capability. A host explicitly admits each empty carrier identity;
 * every use rechecks emptiness. No ThreadLocal exemption, height rebase or native terrain payload.
 */
public final class SurvivalCarrierTransport {
    private static final java.util.List<net.minecraft.world.level.chunk.status.ChunkStatus> NATIVE_STATUSES =
            net.minecraft.world.level.chunk.status.ChunkStatus.getStatusList();
    private SurvivalCarrierTransport() {}

    /** Serializer HEAD gate, before native attachment/structure/entity writes or section reads. */
    public static void validateWrite(net.minecraft.server.level.ServerLevel level, ChunkAccess chunk) {
        var binding = SurvivalLevelBinding.find(level);
        var access = (SurvivalCarrierChunkAccess) chunk;
        if (binding == null) {
            if (access.earthplatform$carrierHeightOwner() instanceof net.minecraft.world.level.Level owner
                    && SurvivalLevelBinding.find(owner) != null)
                throw new SurvivalRefusal("Bound carrier cannot serialize through a foreign ordinary Level");
            return;
        }
        binding.requireCarrierSerialization(level, chunk);
    }
    private static SurvivalCarrierChunkAccess serializerAccess(net.minecraft.server.level.ServerLevel level,
            ChunkAccess receiver, ChunkAccess original) {
        if (receiver != original) throw new SurvivalRefusal("Serializer receiver escaped its exact write scope");
        validateWrite(level, original);
        var payload = original instanceof net.minecraft.world.level.chunk.ImposterProtoChunk wrapper
                ? wrapper.getWrapped() : original;
        return (SurvivalCarrierChunkAccess) payload;
    }
    public static LevelChunkSection[] serializationSections(net.minecraft.server.level.ServerLevel level,
            ChunkAccess receiver, ChunkAccess original) {
        if (SurvivalLevelBinding.find(level) == null) {
            validateWrite(level, original);
            if (receiver != original) throw new SurvivalRefusal("Foreign serializer section receiver");
            return receiver.getSections();
        }
        return serializerAccess(level, receiver, original).earthplatform$carrierSections().clone();
    }
    public static Collection<Map.Entry<Heightmap.Types,Heightmap>> serializationHeightmaps(
            net.minecraft.server.level.ServerLevel level, ChunkAccess receiver, ChunkAccess original) {
        if (SurvivalLevelBinding.find(level) == null) {
            validateWrite(level, original);
            if (receiver != original) throw new SurvivalRefusal("Foreign serializer heightmap receiver");
            return receiver.getHeightmaps();
        }
        serializerAccess(level, receiver, original);
        // Imposter delegates sections/ticks, but inherits its OWN heightmap collection. Preserve
        // that native writer behavior; wrapper and wrapped raw metadata were both verified above.
        return Map.copyOf(((SurvivalCarrierChunkAccess) original).earthplatform$carrierHeightmaps()).entrySet();
    }
    public static ChunkAccess.TicksToSave serializationTicks(net.minecraft.server.level.ServerLevel level,
            ChunkAccess receiver, ChunkAccess original) {
        if (SurvivalLevelBinding.find(level) == null) {
            validateWrite(level, original);
            if (receiver != original) throw new SurvivalRefusal("Foreign serializer tick receiver");
            return receiver.getTicksForSerialization();
        }
        return serializerAccess(level, receiver, original).earthplatform$carrierTicks();
    }
    static void verifySerializationEmpty(net.minecraft.server.level.ServerLevel level, ChunkAccess chunk) {
        var binding = SurvivalLevelBinding.find(level);
        if (binding == null) throw new SurvivalRefusal("Save scaffold requires its exact bound Level");
        binding.checkCarrierTransportState();
        if (chunk.getClass() != net.minecraft.world.level.chunk.ProtoChunk.class
                && chunk.getClass() != LevelChunk.class
                && chunk.getClass() != net.minecraft.world.level.chunk.ImposterProtoChunk.class)
            throw new SurvivalRefusal("Unknown serialization scaffold implementation");
        var access = (SurvivalCarrierChunkAccess) chunk;
        if (access.earthplatform$carrierHeightOwner() != level)
            throw new SurvivalRefusal("Foreign raw carrier height/Level owner");
        if (!access.earthplatform$carrierSerializationMetadataEmpty() || chunk.hasAttachments())
            throw new SurvivalRefusal("Carrier contains native block/structure/upgrade/blending/attachment payload");
        if (chunk instanceof net.minecraft.world.level.chunk.ProtoChunk
                && !((SurvivalCarrierProtoAccess) chunk).earthplatform$carrierProtoPayloadEmpty())
            throw new SurvivalRefusal("Carrier contains proto entities/carving/retrogen payload");
        if (chunk instanceof LevelChunk full) {
            if (full.getLevel() != level) throw new SurvivalRefusal("Foreign LevelChunk save owner");
            var rawFull = (SurvivalCarrierLevelAccess) full;
            var auxiliary = rawFull.earthplatform$carrierAuxLight();
            if (!rawFull.earthplatform$carrierPostLoadEmpty() || auxiliary == null
                    || auxiliary.getClass() != net.neoforged.neoforge.common.world.LevelChunkAuxiliaryLightManager.class)
                throw new SurvivalRefusal("Carrier retains unadmitted post-load or auxiliary-light owner");
            var rawAuxiliary = (io.earthplatform.minecraft.mixin.SurvivalCarrierAuxLightAccess) (Object) auxiliary;
            if (rawAuxiliary.earthplatform$carrierAuxOwner() != full || !rawAuxiliary.earthplatform$carrierAuxLights().isEmpty())
                throw new SurvivalRefusal("Carrier has foreign/nonempty auxiliary-light metadata");
        }
        // These are the EXISTING carrier dimension bounds, not the cubic terrain envelope.
        if (level.getMinBuildHeight() != -64 || level.getHeight() != 384)
            throw new SurvivalRefusal("Unadmitted native serialization height envelope");
        var sections = access.earthplatform$carrierSections();
        if (sections.length != 24) throw new SurvivalRefusal("Unadmitted carrier section metadata count");
        for (var section : sections) {
            if (section == null || section.getStates().maybeHas(state -> state != Blocks.AIR.defaultBlockState()))
                throw new SurvivalRefusal("Native save scaffold contains terrain");
        }
        var maps = access.earthplatform$carrierHeightmaps();
        if (maps.size() > Heightmap.Types.values().length) throw new SurvivalRefusal("Carrier heightmap bound exceeded");
        for (var map : maps.values()) {
            long[] values = map.getRawData();
            if (values.length > 64) throw new SurvivalRefusal("Carrier heightmap storage bound exceeded");
            for (long value : values) if (value != 0) throw new SurvivalRefusal("Carrier contains terrain heightmap data");
        }
        var ticks = access.earthplatform$carrierTicks();
        if (!(ticks.blocks() instanceof TickAccess<?> blocks) || !(ticks.fluids() instanceof TickAccess<?> fluids)
                || blocks.count() != 0 || fluids.count() != 0)
            throw new SurvivalRefusal("Carrier contains native terrain ticks");
        if (!NATIVE_STATUSES.contains(chunk.getPersistedStatus())) throw new SurvivalRefusal("Unknown native save status");
        var light = level.getChunkSource().getLightEngine();
        if (light.getMinLightSection() != -5 || light.getMaxLightSection() != 21)
            throw new SurvivalRefusal("Native light serialization envelope changed");
        // Keep native status, biomes, isLightOn, and actual sky/block DataLayers. The pinned native
        // writer visits exactly 26 light sections, at most 2*26*2048 native light bytes per chunk.
        // No fabricated empty light/status metadata and no changes to native dirty/save lifecycle.
        if (chunk instanceof net.minecraft.world.level.chunk.ImposterProtoChunk wrapper) {
            // The wrapper owns its native heightmap collection (and raw proto scaffold), while
            // native sections/ticks delegate to this exact full owner. Validate BOTH payloads.
            binding.requireCarrier(wrapper.getWrapped());
            verifySerializationEmpty(level, wrapper.getWrapped());
        }
    }
    public static void verifyEmpty(LevelChunk chunk) {
        var binding = SurvivalLevelBinding.find(chunk.getLevel());
        if (binding == null) throw new SurvivalRefusal("Carrier requires its bound survival Level");
        binding.checkCarrierTransportState();
        var access=(SurvivalCarrierChunkAccess)chunk;
        if (!access.earthplatform$carrierMetadataEmpty()) throw new SurvivalRefusal("Carrier owns nonempty block metadata");
        LevelChunkSection[] sections=access.earthplatform$carrierSections();
        if(sections.length!=chunk.getLevel().getSectionsCount()) throw new SurvivalRefusal("Carrier height metadata mismatch");
        for(var section:sections) if(section==null || section.getStates().maybeHas(state->state!=Blocks.AIR.defaultBlockState()))
            throw new SurvivalRefusal("Carrier contains terrain; refusing transport as an empty scaffold");
        for(var map:access.earthplatform$carrierHeightmaps().values()) for(long value:map.getRawData())
            if(value!=0) throw new SurvivalRefusal("Carrier contains a terrain heightmap");
        var ticks=access.earthplatform$carrierTicks();
        if(!(ticks.blocks() instanceof TickAccess<?> blocks) || !(ticks.fluids() instanceof TickAccess<?> fluids)
                || blocks.count()!=0 || fluids.count()!=0) throw new SurvivalRefusal("Carrier contains native terrain ticks");
    }
    private static SurvivalCarrierChunkAccess admitted(ChunkAccess chunk) {
        if(!(chunk instanceof LevelChunk levelChunk)) throw new SurvivalRefusal("Only exact admitted LevelChunk carriers may serialize");
        var binding=SurvivalLevelBinding.find(levelChunk.getLevel());
        binding.requireCarrier(levelChunk);
        verifyEmpty(levelChunk);
        return (SurvivalCarrierChunkAccess)chunk;
    }
    public static LevelChunkSection[] sections(ChunkAccess chunk) {
        if(chunk instanceof LevelChunk levelChunk && SurvivalLevelBinding.find(levelChunk.getLevel())!=null)
            return admitted(chunk).earthplatform$carrierSections().clone();
        return chunk.getSections();
    }
    public static Collection<Map.Entry<Heightmap.Types,Heightmap>> heightmaps(ChunkAccess chunk) {
        if(chunk instanceof LevelChunk levelChunk && SurvivalLevelBinding.find(levelChunk.getLevel())!=null)
            return Map.copyOf(admitted(chunk).earthplatform$carrierHeightmaps()).entrySet();
        return chunk.getHeightmaps();
    }
    public static ChunkAccess.TicksToSave ticks(ChunkAccess chunk) {
        if(chunk instanceof LevelChunk levelChunk && SurvivalLevelBinding.find(levelChunk.getLevel())!=null)
            return admitted(chunk).earthplatform$carrierTicks();
        return chunk.getTicksForSerialization();
    }
}
