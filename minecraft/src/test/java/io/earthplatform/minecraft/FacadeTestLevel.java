package io.earthplatform.minecraft;

import net.minecraft.core.*;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.*;
import net.minecraft.util.profiling.InactiveProfiler;
import net.minecraft.world.TickRateManager;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.flag.FeatureFlagSet;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.item.alchemy.PotionBrewing;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.level.*;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkSource;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.entity.LevelEntityGetter;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.saveddata.maps.*;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.scores.Scoreboard;
import net.minecraft.world.ticks.LevelTickAccess;

/** Real Level superclass initialization, but only a height/registry fixture, never a live world. */
class FacadeTestLevel extends Level {
    private final int minY, height;
    FacadeTestLevel(RegistryAccess registry, Holder<DimensionType> type, int minY, int height) {
        super(null, ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION,
                ResourceLocation.fromNamespaceAndPath("earthplatform", "headless_facade_fixture")),
                registry, type, () -> InactiveProfiler.INSTANCE, false, false, 0L, 32);
        this.minY=minY; this.height=height;
    }
    @Override public int getMinBuildHeight() { return minY; }
    @Override public int getHeight() { return height; }
    private static UnsupportedOperationException unsupported() { return new UnsupportedOperationException("Not a live level: Gate 0 constructor/access fixture only"); }
    @Override public void sendBlockUpdated(BlockPos p, BlockState a, BlockState b, int flags) { throw unsupported(); }
    @Override public void playSeededSound(Player p,double x,double y,double z,Holder<SoundEvent> s,SoundSource source,float v,float pitch,long seed) { throw unsupported(); }
    @Override public void playSeededSound(Player p,Entity e,Holder<SoundEvent> s,SoundSource source,float v,float pitch,long seed) { throw unsupported(); }
    @Override public String gatherChunkSourceStats() { throw unsupported(); }
    @Override public Entity getEntity(int id) { throw unsupported(); }
    @Override public TickRateManager tickRateManager() { throw unsupported(); }
    @Override public MapItemSavedData getMapData(MapId id) { throw unsupported(); }
    @Override public void setMapData(MapId id,MapItemSavedData data) { throw unsupported(); }
    @Override public MapId getFreeMapId() { throw unsupported(); }
    @Override public void destroyBlockProgress(int id,BlockPos p,int progress) { throw unsupported(); }
    @Override public Scoreboard getScoreboard() { throw unsupported(); }
    @Override public RecipeManager getRecipeManager() { throw unsupported(); }
    @Override protected LevelEntityGetter<Entity> getEntities() { throw unsupported(); }
    @Override public PotionBrewing potionBrewing() { throw unsupported(); }
    @Override public void setDayTimeFraction(float value) { throw unsupported(); }
    @Override public float getDayTimeFraction() { throw unsupported(); }
    @Override public float getDayTimePerTick() { throw unsupported(); }
    @Override public void setDayTimePerTick(float value) { throw unsupported(); }
    @Override public LevelTickAccess<Block> getBlockTicks() { throw unsupported(); }
    @Override public LevelTickAccess<Fluid> getFluidTicks() { throw unsupported(); }
    @Override public ChunkSource getChunkSource() { throw unsupported(); }
    @Override public void addParticle(ParticleOptions p,double x,double y,double z,double a,double b,double c) { throw unsupported(); }
    @Override public void levelEvent(Player p,int event,BlockPos pos,int data) { throw unsupported(); }
    @Override public void gameEvent(Holder<GameEvent> event,Vec3 p,GameEvent.Context context) { throw unsupported(); }
    @Override public Holder<Biome> getUncachedNoiseBiome(int x,int y,int z) { throw unsupported(); }
    @Override public FeatureFlagSet enabledFeatures() { return FeatureFlags.DEFAULT_FLAGS; }
    @Override public float getShade(Direction direction,boolean shade) { throw unsupported(); }
    @Override public int getBlockTint(BlockPos pos,ColorResolver resolver) { throw unsupported(); }
    @Override public java.util.List<? extends Player> players() { throw unsupported(); }
}
