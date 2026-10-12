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
public final class MarinPlayerEnvironmentV2 {
    public static final String VERSION = "marin-player-environment-v2";
    public static final String PROFILE = "marin-player-environment-v2";
    public static final String SAVE_NAMESPACE = "earthplatform-marin-player-environment-v2";
    public static final long TERRAIN_SEED = MarinSurvivalDefinition.SEED;
    public static final long REGISTERED_NATIVE_BIOME_SEED = 0L;
    /** Data-only identity; neither v1 saves nor another native biome seed can acquire this policy. */
    public static String sourceIdentity(String palette,long nativeBiomeSeed) {
        Objects.requireNonNull(palette);
        if(palette.isBlank()||palette.length()>1024)throw new IllegalArgumentException("Bounded explicit palette identity required");
        return WorldManifest.sha256((VERSION+"\nterrain-source="+MarinSurvivalDefinition.DEFINITION_IDENTITY
                +"\nterrain-generator="+MarinSurvivalDefinition.GENERATOR_IDENTITY
                +"\nterrain-tree-seed="+TERRAIN_SEED
                +"\nnative-world-options-biome-seed="+nativeBiomeSeed
                +"\npalette-length="+palette.length()+"\npalette="+palette
                +"\nbiome=procedural-uniform-minecraft:forest;not-surveyed;native-BiomeManager-eight-corner-zoom"
                +"\nquart=full-signed-XYZ;zoom-seed=BiomeManager.obfuscateSeed(actual-native-world-options-biome-seed)"
                +"\nstructures=none-generated-in-bounded-playable-domain;no-imported-structure-starts"
                +"\nno-light-weather-mobs-fluids-or-death-authority").getBytes(StandardCharsets.UTF_8));
    }
    private final SurvivalLevelBinding binding;
    private final Object registries;
    private final Registry<Biome> biomes;
    private final Holder<Biome> forest;
    private final BiomeManager nativeManager, manager;
    private final Runnable guard;
    private final long nativeBiomeSeed;
    private final List<QuartRead> reads = new ArrayList<>();
    private int structureQueries;
    public record QuartRead(int x, int y, int z) {}

    public static WorldIdentity worldIdentity(String palette,long nativeBiomeSeed) {
        return new WorldIdentity(TERRAIN_SEED, PROFILE, VERSION, sourceIdentity(palette,nativeBiomeSeed), palette);
    }
    public static void requireInstalledPolicy(net.minecraft.server.level.ServerLevel level,WorldIdentity world) {
        Objects.requireNonNull(world);
        if(!world.equals(worldIdentity(world.blockPaletteVersion(),nativeBiomeSeed(level))))
            throw refused("Persisted world lacks this exact v2 terrain/palette/native-biome-seed policy; v1 is not migrated");
    }
    /** Read original native fields only, after exact source qualification; never change a Level or manager seed. */
    public static long nativeBiomeSeed(net.minecraft.server.level.ServerLevel level) {
        if(level==null||level.getClass()!=net.minecraft.server.level.ServerLevel.class)
            throw refused("First v2 policy requires exact native ServerLevel");
        // Separate private installed capability; preserves the unchanged exact GameTest seed-zero route.
        if (level.getServer().getClass().getName().equals("net.minecraft.client.server.IntegratedServer"))
            return io.earthplatform.minecraft.survival.runtime.InstalledMarinSessionAdmission.nativeBiomeSeed(level);
        requirePinnedSeedClosure();
        var server=level.getServer();
        if(server.getClass()!=net.minecraft.gametest.framework.GameTestServer.class||!server.isSameThread())
            throw refused("First v2 policy requires the exact registered GameTestServer owner thread");
        var data=server.getWorldData();
        if(data.getClass()!=net.minecraft.world.level.storage.PrimaryLevelData.class)
            throw refused("Foreign native world-options owner");
        var options=data.worldGenOptions();
        if(options.getClass()!=net.minecraft.world.level.levelgen.WorldOptions.class)
            throw refused("Foreign native world-options implementation");
        long seed=options.seed();
        if(seed!=REGISTERED_NATIVE_BIOME_SEED||level.getSeed()!=seed)
            throw refused("Original registered GameTest native biome seed must remain zero");
        requireZoomSeed(level.getBiomeManager(),seed);
        return seed;
    }
    public MarinPlayerEnvironmentV2(SurvivalLevelBinding binding, Runnable guard) {
        this.binding = Objects.requireNonNull(binding); this.guard = Objects.requireNonNull(guard);
        nativeBiomeSeed=nativeBiomeSeed(binding.level());requireWorld();
        registries = binding.level().registryAccess();
        biomes = binding.level().registryAccess().registryOrThrow(Registries.BIOME);
        forest = biomes.getHolderOrThrow(Biomes.FOREST);
        if (forest.getClass() != Holder.Reference.class || forest.value().getClass() != Biome.class)
            throw refused("Exact loaded native forest biome required");
        // Preserve the actual native level's zoom seed and eight-corner selection implementation.
        nativeManager = binding.level().getBiomeManager();
        requireZoomSeed(nativeManager,nativeBiomeSeed);
        manager = nativeManager.withDifferentSource(this::noiseBiome);
    }
    private void requireWorld() {
        binding.checkOpen();
        if (!binding.world().world().equals(worldIdentity(binding.world().world().blockPaletteVersion(),nativeBiomeSeed)))
            throw refused("Player environment policy is absent from persisted world identity");
    }
    public void revalidate() {
        guard.run(); requireWorld();
        if(nativeBiomeSeed(binding.level())!=nativeBiomeSeed)throw refused("Native biome seed generation changed");
        if(nativeManager!=binding.level().getBiomeManager())throw refused("Native BiomeManager generation changed");
        requireZoomSeed(nativeManager,nativeBiomeSeed);requireZoomSeed(manager,nativeBiomeSeed);
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
    private static void requireZoomSeed(BiomeManager manager,long expectedSeed) {
        if(manager.getClass()!=BiomeManager.class)throw refused("Custom native BiomeManager implementation");
        try {
            var field=BiomeManager.class.getDeclaredField("biomeZoomSeed");
            if(field.getType()!=long.class||!field.trySetAccessible()
                    ||field.getLong(manager)!=BiomeManager.obfuscateSeed(expectedSeed))
                throw refused("Native BiomeManager zoom seed differs from persisted gameplay environment policy");
        }catch(ReflectiveOperationException|SecurityException failure) {
            var refusal=refused("Cannot inspect native BiomeManager zoom seed");refusal.initCause(failure);throw refusal;
        }
    }
    private static void requirePinnedSeedClosure() {
        requireClassBytes(net.minecraft.server.level.ServerLevel.class,"e4e249f6a71830ab4eda69b25ea10cfe3a3e0af5d84a6926016cab18775c4c32");
        requireClassBytes(net.minecraft.world.level.Level.class,"e6447eec7e478face83d0299535e967e87991c549f5ffac8652fb7658b1d75b1");
        requireClassBytes(net.minecraft.server.MinecraftServer.class,"f42f6ff6f3ee1b2b678c222b192fccca995686b882eb99546284e29c683d09e6");
        requireClassBytes(net.minecraft.gametest.framework.GameTestServer.class,"240a7a4968bc0134749d8251c030b16fa67ed3429f7d439d4b4b81102eff5690");
        requireClassBytes(net.minecraft.world.level.storage.PrimaryLevelData.class,"9d0e6ce9eb986cce3d834a58cff32eabf8a00c168b4d31747c47b253bcea9d3e");
        requireClassBytes(net.minecraft.world.level.levelgen.WorldOptions.class,"7abd06dee2be763d1e6da6cb35149d671ffd24d0eb516b00b809a42e61420559");
        requireClassBytes(net.minecraft.world.level.biome.BiomeManager.class,"f0e823411e8040e17962945be197ae2745ac35d8b4d87bd73b650d6c41ee7227");
    }
    /** Private bounded verifier; no host/package authority or visibility is widened. */
    private static void requireClassBytes(Class<?> type,String expected) {
        try(var input=type.getResourceAsStream("/"+type.getName().replace('.','/')+".class")) {
            if(input==null)throw refused("Missing original native seed-policy resource: "+type.getName());
            byte[] bytes=input.readNBytes(1024*1024+1);
            if(bytes.length>1024*1024||!expected.equals(java.util.HexFormat.of().formatHex(
                    java.security.MessageDigest.getInstance("SHA-256").digest(bytes))))
                throw refused("Original native seed-policy resource changed: "+type.getName());
        }catch(java.io.IOException|java.security.NoSuchAlgorithmException failure) {
            var refusal=refused("Cannot verify original native seed-policy resource");refusal.initCause(failure);throw refusal;
        }
    }
    public List<QuartRead> quartReads() { return List.copyOf(reads); }
    public int structureQueries() { return structureQueries; }
    private static SurvivalRefusal refused(String message) { return new SurvivalRefusal(message); }
}
