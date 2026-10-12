package io.earthplatform.minecraft.survival.host;

import java.util.Map;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.Heightmap;

/** Internal transport access. These are never an authoritative terrain API. */
public interface SurvivalCarrierChunkAccess {
    net.minecraft.world.level.LevelHeightAccessor earthplatform$carrierHeightOwner();
    boolean earthplatform$carrierSerializationMetadataEmpty();
    LevelChunkSection[] earthplatform$carrierSections();
    Map<Heightmap.Types, Heightmap> earthplatform$carrierHeightmaps();
    boolean earthplatform$carrierMetadataEmpty();
    ChunkAccess.TicksToSave earthplatform$carrierTicks();
}
