package io.earthplatform.minecraft.survival.source;

import io.earthplatform.core.WorldIdentity;
import io.earthplatform.geography.WorldManifest;
import io.earthplatform.geography.survival.MarinSurvivalDefinition;
import io.earthplatform.minecraft.survival.host.SurvivalLevelBinding;
import io.earthplatform.minecraft.survival.world.SurvivalRefusal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.levelgen.structure.StructureStart;

/**
 * Explicit procedural gameplay environment, not observed Marin ecology. Its distinct source and
 * generator identity is persisted in WorldIdentity and the checkpoint binding. Prior terrain-only
 * saves cannot acquire this policy merely by opening a new read scope. No carrier biome, structure
 * section, heightmap, light or weather field is consulted to answer these two queries.
 */
public final class MarinPlayerEnvironmentV1 {
    public static final String VERSION = "marin-player-environment-v1";
    public static final String PROFILE = "marin-player-environment-v1";
    public static final String SAVE_NAMESPACE = "earthplatform-marin-player-environment-v1";
    public static final String IDENTITY = WorldManifest.sha256((VERSION + "\nterrain="
            + MarinSurvivalDefinition.DEFINITION_IDENTITY
            + "\nbiome=procedural-uniform-minecraft:forest;not-surveyed;native-BiomeManager-eight-corner-zoom"
            + "\nquart=full-signed-XYZ;zoom-seed=BiomeManager.obfuscateSeed;seed=" + MarinSurvivalDefinition.SEED
            + "\nstructures=none-generated-in-bounded-playable-domain;no-imported-structure-starts"
            + "\nno-light-weather-mobs-fluids-or-death-authority").getBytes(StandardCharsets.UTF_8));
    private final SurvivalLevelBinding binding;
    private final Object registries;
    private final Registry<Biome> biomes;
    private final Holder<Biome> forest;
    private final BiomeManager nativeManager, manager;
    private final Runnable guard;
    private final List<QuartRead> reads = new ArrayList<>();
    private int structureQueries;
    public record QuartRead(int x, int y, int z) {}

    public static WorldIdentity worldIdentity(String palette) {
        return new WorldIdentity(MarinSurvivalDefinition.SEED, PROFILE, VERSION, IDENTITY, palette);
    }
    public MarinPlayerEnvironmentV1(SurvivalLevelBinding binding, Runnable guard) {
        this.binding = Objects.requireNonNull(binding); this.guard = Objects.requireNonNull(guard);
        requireWorld();
        registries = binding.level().registryAccess();
        biomes = binding.level().registryAccess().registryOrThrow(Registries.BIOME);
        forest = biomes.getHolderOrThrow(Biomes.FOREST);
        if (forest.getClass() != Holder.Reference.class || forest.value().getClass() != Biome.class)
            throw refused("Exact loaded native forest biome required");
        // Preserve the actual native level's zoom seed and eight-corner selection implementation.
        nativeManager = binding.level().getBiomeManager();
        requireZoomSeed(nativeManager);
        manager = nativeManager.withDifferentSource(this::noiseBiome);
    }
    private void requireWorld() {
        binding.checkOpen();
        if (!binding.world().world().equals(worldIdentity(binding.world().world().blockPaletteVersion())))
            throw refused("Player environment policy is absent from persisted world identity");
    }
    public void revalidate() {
        guard.run(); requireWorld();
        if(nativeManager!=binding.level().getBiomeManager())throw refused("Native BiomeManager generation changed");
        requireZoomSeed(nativeManager);requireZoomSeed(manager);
        if (registries != binding.level().registryAccess()
                || biomes != binding.level().registryAccess().registryOrThrow(Registries.BIOME)
                || forest != biomes.getHolderOrThrow(Biomes.FOREST))
            throw refused("Player environment registry/biome holder generation changed");
    }
    public Holder<Biome> biome(BlockPos pos) {
        revalidate(); requireDomain(pos.getX(), pos.getY(), pos.getZ(), 1);
        return manager.getBiome(pos);
    }
    public Holder<Biome> noiseBiome(int quartX, int quartY, int quartZ) {
        revalidate();
        // Multiplication is wide, never packed, truncated, clamped or collapsed to X/Z.
        requireDomain((long)quartX * 4, (long)quartY * 4, (long)quartZ * 4, 4);
        if (reads.size() >= 1024) throw refused("Bounded environment quart-query budget exceeded");
        reads.add(new QuartRead(quartX, quartY, quartZ));
        return forest;
    }
    public StructureStart noGeneratedStructure(BlockPos pos) {
        revalidate(); requireDomain(pos.getX(), pos.getY(), pos.getZ(), 1);
        if (++structureQueries > 128) throw refused("Bounded no-structure query budget exceeded");
        return StructureStart.INVALID_START;
    }
    private static void requireDomain(long x,long y,long z,int span) {
        var envelope = MarinSurvivalDefinition.ENVELOPE;
        if (x < envelope.minTravelX() || x + span > envelope.maxTravelXExclusive()
                || z < envelope.minTravelZ() || z + span > envelope.maxTravelZExclusive()
                || y < MarinSurvivalDefinition.MIN_BLOCK_Y
                || y + span > MarinSurvivalDefinition.MAX_BLOCK_Y_EXCLUSIVE)
            throw refused("Unknown full-coordinate procedural environment sample");
    }
    private static void requireZoomSeed(BiomeManager manager) {
        if(manager.getClass()!=BiomeManager.class)throw refused("Custom native BiomeManager implementation");
        try {
            var field=BiomeManager.class.getDeclaredField("biomeZoomSeed");
            if(field.getType()!=long.class||!field.trySetAccessible()
                    ||field.getLong(manager)!=BiomeManager.obfuscateSeed(MarinSurvivalDefinition.SEED))
                throw refused("Native BiomeManager zoom seed differs from persisted gameplay environment policy");
        }catch(ReflectiveOperationException|SecurityException failure) {
            var refusal=refused("Cannot inspect native BiomeManager zoom seed");refusal.initCause(failure);throw refusal;
        }
    }
    public List<QuartRead> quartReads() { return List.copyOf(reads); }
    public int structureQueries() { return structureQueries; }
    private static SurvivalRefusal refused(String message) { return new SurvivalRefusal(message); }
}
