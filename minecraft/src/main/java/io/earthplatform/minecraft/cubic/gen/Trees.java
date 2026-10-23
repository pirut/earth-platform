package io.earthplatform.minecraft.cubic.gen;

import io.earthplatform.minecraft.cubic.Cubic;
import static io.earthplatform.minecraft.cubic.gen.SurfaceGenerator.DRY;

import com.google.gson.JsonElement;
import com.mojang.serialization.JsonOps;
import io.earthplatform.minecraft.cubic.gen.SurfaceGenerator.Surface;
import io.earthplatform.minecraft.cubic.gen.SurfaceGenerator.Tree;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.IntBinaryOperator;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.valueproviders.IntProvider;
import net.minecraft.world.level.block.HugeMushroomBlock;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.levelgen.placement.PlacementModifier;
import com.google.gson.JsonObject;
import net.minecraft.resources.ResourceKey;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.GenerationStep;
import net.minecraft.world.level.levelgen.feature.ConfiguredFeature;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.configurations.FeatureConfiguration;
import net.minecraft.world.level.levelgen.feature.configurations.RandomBooleanFeatureConfiguration;
import net.minecraft.world.level.levelgen.feature.configurations.RandomFeatureConfiguration;
import net.minecraft.world.level.levelgen.feature.configurations.RootSystemConfiguration;
import net.minecraft.world.level.levelgen.feature.configurations.TreeConfiguration;
import net.minecraft.world.level.levelgen.feature.treedecorators.BeehiveDecorator;
import net.minecraft.world.level.levelgen.feature.treedecorators.TreeDecorator;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;

/**
 * Minecraft's own trees, grown by vanilla's tree features: each biome's trees as vanilla picks
 * them (jungle trees, bushes and giant jungle trees in a jungle; oak, fancy oak and birch in a
 * forest; spruce and pine in a taiga), at a density from the land cover. Each tree kind is grown
 * a few dozen times on flat ground into {@link TreeShape}s, which are then placed per column from
 * a hash, so every cube a tree reaches gets the same tree. Giant sequoias grow in the groves the
 * generator marks.
 */
final class Trees {
    /** How far a tree reaches sideways from its origin, and the tallest vanilla tree (a giant jungle tree) above it. */
    static final int REACH = 16, TALLEST = 34;
    /** Shapes kept per vanilla tree feature. */
    private static final int VARIANTS = 24;
    /** Sequoia placement cells: at most one per cell, in a grove. */
    private static final int GIANT_CELL = 28;

    /**
     * Each family's tree features by the vanilla biome feature that grows them; a biome's own
     * trees are used when the cover asks for its family, else the family's home biome's.
     */
    private static final Map<String, Tree> FAMILY = Map.ofEntries(
            Map.entry("trees_birch_and_oak", Tree.OAK), Map.entry("trees_flower_forest", Tree.OAK), Map.entry("trees_plains", Tree.OAK),
            Map.entry("trees_meadow", Tree.OAK), Map.entry("trees_swamp", Tree.OAK), Map.entry("trees_badlands", Tree.OAK),
            Map.entry("trees_windswept_hills", Tree.OAK), Map.entry("trees_windswept_forest", Tree.OAK),
            Map.entry("trees_birch", Tree.BIRCH), Map.entry("birch_tall", Tree.BIRCH),
            Map.entry("trees_taiga", Tree.SPRUCE), Map.entry("trees_old_growth_pine_taiga", Tree.SPRUCE),
            Map.entry("trees_old_growth_spruce_taiga", Tree.SPRUCE), Map.entry("trees_grove", Tree.SPRUCE), Map.entry("trees_snowy", Tree.SPRUCE),
            Map.entry("trees_jungle", Tree.JUNGLE), Map.entry("trees_sparse_jungle", Tree.JUNGLE), Map.entry("bamboo_vegetation", Tree.JUNGLE),
            Map.entry("trees_savanna", Tree.ACACIA), Map.entry("trees_windswept_savanna", Tree.ACACIA),
            Map.entry("dark_forest_vegetation", Tree.DARK_OAK), Map.entry("trees_cherry", Tree.CHERRY), Map.entry("trees_mangrove", Tree.MANGROVE));
    private static final Map<Tree, ResourceKey<Biome>> HOME = new EnumMap<>(Map.of(
            Tree.OAK, Biomes.PLAINS, Tree.BIRCH, Biomes.BIRCH_FOREST, Tree.SPRUCE, Biomes.TAIGA, Tree.JUNGLE, Biomes.JUNGLE,
            Tree.ACACIA, Biomes.SAVANNA, Tree.MANGROVE, Biomes.MANGROVE_SWAMP, Tree.DARK_OAK, Biomes.DARK_FOREST, Tree.CHERRY, Biomes.CHERRY_GROVE));

    /**
     * Tree attempts per 16x16 columns where the cover is all trees of a family, after vanilla's
     * own counts less the attempts vanilla loses to trees already standing there.
     */
    static int full(Tree kind) {
        return switch (kind) {
            case JUNGLE -> 24;
            case DARK_OAK, MANGROVE -> 12;
            case CHERRY -> 8;
            case AZALEA -> 6;
            default -> 9;
        };
    }
    private static final int MOST = 24;

    /** A tree standing at (x, y, z), its origin the block above the ground, turned {@code turn} quarters, snowed on where it is cold. */
    private record Planted(int x, int y, int z, TreeShape shape, int turn, long seed, boolean snow) {
        void place(int x0, int y0, int z0, int x1, int y1, int z1, IntBinaryOperator ground, TreeShape.Sink sink) {
            shape.place(x, y, z, turn, seed, snow, x0, y0, z0, x1, y1, z1, ground, sink);
        }
    }

    /** A vanilla random selector's trees: the first entry whose chance passes, else the default; null entries grow no tree. */
    private record Pick(float[] chances, ConfiguredFeature<?, ?>[] trees, @Nullable ConfiguredFeature<?, ?> otherwise) {
        @Nullable ConfiguredFeature<?, ?> choose(RandomSource random) {
            for (int i = 0; i < chances.length; i++) if (random.nextFloat() < chances[i]) return trees[i];
            return otherwise;
        }
    }
    private record Own(Tree family, Pick pick) {}

    private static final Planted[] NONE = new Planted[0];
    private final SurfaceGenerator gen;
    private final IntBinaryOperator ground;
    private final ConcurrentHashMap<Long, Planted[]> chunks = new ConcurrentHashMap<>(), giants = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Holder<Biome>, Optional<Own>> owns = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Tree, Pick> homes = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<ConfiguredFeature<?, ?>, TreeShape[]> shapes = new ConcurrentHashMap<>();

    Trees(SurfaceGenerator gen) {
        this.gen = gen;
        this.ground = gen::surfaceY;
    }

    /** How high above the ground the trees around this cube column can reach. */
    int tallest(int cx, int cz) { return gen.giants((cx << 4) + 8, (cz << 4) + 8) ? Sequoia.TALLEST : TALLEST; }

    /**
     * Writes every tree block in the box (x0..x1, y0..y1, z0..z1): ordinary trees first, then
     * sequoias, whose trunks win where the two meet. With {@code giantsOnly}, sequoias only.
     */
    void place(int x0, int y0, int z0, int x1, int y1, int z1, boolean giantsOnly, TreeShape.Sink sink) {
        place(x0, y0, z0, x1, y1, z1, giantsOnly, false, sink);
    }

    /**
     * As {@link #place(int, int, int, int, int, int, boolean, TreeShape.Sink)}; with {@code clear},
     * trees rooted where a structure clears the ground around its buildings are left out whole,
     * rather than cut to stumps as the ground is cleared.
     */
    void place(int x0, int y0, int z0, int x1, int y1, int z1, boolean giantsOnly, boolean clear, TreeShape.Sink sink) {
        int cx0 = Math.floorDiv(x0 - REACH, 16), cx1 = Math.floorDiv(x1 + REACH, 16);
        int cz0 = Math.floorDiv(z0 - REACH, 16), cz1 = Math.floorDiv(z1 + REACH, 16);
        if (!giantsOnly) for (int cz = cz0; cz <= cz1; cz++) for (int cx = cx0; cx <= cx1; cx++)
            for (Planted tree : chunk(cx, cz)) {
                if (clear && gen.cleared(tree.x, tree.z)) continue;
                tree.place(x0, y0, z0, x1, y1, z1, ground, sink);
            }
        for (int cz = cz0; cz <= cz1; cz++) for (int cx = cx0; cx <= cx1; cx++) {
            if (!gen.giants((cx << 4) + 8, (cz << 4) + 8)) continue;
            for (Planted tree : giants(cx, cz)) tree.place(x0, y0, z0, x1, y1, z1, ground, sink);
        }
    }

    // Where trees stand ----------------------------------------------------------------------------

    /** The ordinary trees whose origins are in a chunk column. */
    private Planted[] chunk(int cx, int cz) {
        long key = it.unimi.dsi.fastutil.HashCommon.mix((long) cx << 32 | cz & 0xFFFFFFFFL);
        Planted[] cached = chunks.get(key);
        if (cached != null) return cached;
        List<Planted> trees = new ArrayList<>();
        for (int dz = 0; dz < 16; dz++) for (int dx = 0; dx < 16; dx++) {
            int x = (cx << 4) + dx, z = (cz << 4) + dz;
            long r = gen.mix(x, 0x54524545L, z);
            // Chances in 65536ths: cover percent x attempts per chunk x 256 / 100, so most columns stop here.
            int roll = (int) (r & 0xFFFF);
            if (roll >= 150 * MOST * 256 / 100) continue;
            int h = gen.surfaceY(x, z), water = gen.waterY(x, z);
            Surface surface = gen.coverAt(x, z, h, water);
            if (surface.trees() <= 0) continue;
            Tree kind = surface.tree((int) Math.floorMod(r >>> 16, 10));
            if (roll >= surface.trees() * full(kind) * 256 / 100) continue;
            if (!fits(surface, kind, h, water)) continue;
            ConfiguredFeature<?, ?> feature = pick(surface.biome(), kind).choose(RandomSource.create(r));
            if (feature == null) continue;
            TreeShape[] grown = shapes(feature);
            if (grown.length == 0) continue;
            trees.add(new Planted(x, h + 1, z, grown[(int) Math.floorMod(r >>> 24, grown.length)], (int) (r >>> 34 & 3), r, snowy(surface)));
        }
        if (mostAdded() > 0) for (int dz = 0; dz < 16; dz++) for (int dx = 0; dx < 16; dx++) {
            Planted tree = added((cx << 4) + dx, (cz << 4) + dz);
            if (tree != null) trees.add(tree);
        }
        Planted[] made = trees.isEmpty() ? NONE : trees.toArray(Planted[]::new);
        if (chunks.size() > 32768) chunks.clear();
        chunks.putIfAbsent(key, made);
        return made;
    }

    /** Whether snow falls where a tree stands, as on the ground around it. */
    static boolean snowy(Surface surface) { return surface.biome().value().getBaseTemperature() < 0.15f; }

    /**
     * Whether a tree of this kind can stand on a column: on soil, out of water but for mangroves
     * (up to 5 deep) and swamp oaks (up to 2), as vanilla places them.
     */
    private static boolean fits(Surface surface, Tree kind, int h, int water) {
        int depth = water == DRY ? 0 : water - h;
        int deepest = kind == Tree.MANGROVE ? 5 : surface.biome().is(Biomes.SWAMP) ? 2 : 0;
        return depth <= deepest && surface.top().is(BlockTags.DIRT);
    }

    /**
     * The sequoias whose origins are in a chunk column: in a grove, one in about a third of the
     * 28-block cells (one per 2,000 or so square metres, as in the denser real groves), rooted at
     * the lowest ground under its trunk.
     */
    private Planted[] giants(int cx, int cz) {
        long key = it.unimi.dsi.fastutil.HashCommon.mix((long) cx << 32 | cz & 0xFFFFFFFFL);
        Planted[] cached = giants.get(key);
        if (cached != null) return cached;
        List<Planted> trees = new ArrayList<>();
        int x0 = cx << 4, z0 = cz << 4;
        for (int gz = Math.floorDiv(z0, GIANT_CELL); gz <= Math.floorDiv(z0 + 15, GIANT_CELL); gz++)
            for (int gx = Math.floorDiv(x0, GIANT_CELL); gx <= Math.floorDiv(x0 + 15, GIANT_CELL); gx++) {
                long r = gen.mix(gx, 0x53455155L, gz);
                int x = gx * GIANT_CELL + 4 + (int) Math.floorMod(r, GIANT_CELL - 8);
                int z = gz * GIANT_CELL + 4 + (int) Math.floorMod(r >>> 12, GIANT_CELL - 8);
                if (x >> 4 != cx || z >> 4 != cz || Math.floorMod(r >>> 24, 100) >= 35) continue;
                int h = gen.surfaceY(x, z), water = gen.waterY(x, z);
                if (water != DRY) continue;
                Surface surface = gen.coverAt(x, z, h, water);
                if (surface.broadleaf() != Tree.SEQUOIA || surface.trees() <= 0 || !surface.top().is(BlockTags.DIRT)) continue;
                int foot = h;
                for (int i = 0; i < 8; i++) {
                    double angle = i * Math.PI / 4;
                    foot = Math.min(foot, gen.surfaceY(x + (int) Math.round(4 * Math.cos(angle)), z + (int) Math.round(4 * Math.sin(angle))));
                }
                if (h - foot > 6) continue;
                TreeShape[] grown = Sequoia.shapes();
                trees.add(new Planted(x, foot + 1, z, grown[(int) Math.floorMod(r >>> 32, grown.length)], (int) (r >>> 50 & 3), r, snowy(surface)));
            }
        Planted[] made = trees.isEmpty() ? NONE : trees.toArray(Planted[]::new);
        if (giants.size() > 32768) giants.clear();
        giants.putIfAbsent(key, made);
        return made;
    }

    // Trees other mods add -----------------------------------------------------------------------

    /**
     * A tree another mod adds to a biome's vegetation (a walnut in the plains, a palm on the beach),
     * with the attempts per chunk, the deepest water and the ground its own placement asks for.
     */
    private record Added(ConfiguredFeature<?, ?> feature, float perChunk, int deepest, @Nullable BlockState survives, BlockState soil) {}
    /** A biome's added trees; {@code scaled} where the biome has vanilla trees, whose density the land cover sets, so theirs follows it too. */
    private record Additions(Added[] trees, float perChunk, boolean scaled) {}
    private record Rooting(BlockState survives, BlockState top) {}
    private static final Additions NO_ADDITIONS = new Additions(new Added[0], 0, false);
    /** Ground added trees are tried on when their saplings name what they grow on, in order. */
    private static final BlockState[] SOILS = {Blocks.GRASS_BLOCK.defaultBlockState(), Blocks.SAND.defaultBlockState(), Blocks.RED_SAND.defaultBlockState(),
            Blocks.MUD.defaultBlockState(), Blocks.PODZOL.defaultBlockState(), Blocks.MOSS_BLOCK.defaultBlockState(), Blocks.MYCELIUM.defaultBlockState(),
            Blocks.SNOW_BLOCK.defaultBlockState(), Blocks.GRAVEL.defaultBlockState(), Blocks.STONE.defaultBlockState()};
    private final ConcurrentHashMap<Holder<Biome>, Additions> additions = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Rooting, Boolean> rootings = new ConcurrentHashMap<>();
    private volatile float mostAdded = -1;

    /** The most added-tree attempts per chunk in any biome, at the densest cover; 0 where no mod adds trees. */
    private float mostAdded() {
        float most = mostAdded;
        if (most >= 0) return most;
        most = 0;
        for (Holder<Biome> biome : gen.biomeIds) {
            Additions here = additions(biome);
            most = Math.max(most, here.perChunk * (here.scaled ? 1.5f : 1));
        }
        if (most > 0) Cubic.LOGGER.info("Earth trees: other mods add trees to {} biomes", additions.values().stream().filter(a -> a.trees.length > 0).count());
        return mostAdded = Math.min(most, 64);
    }

    /** The added tree whose origin is a column, if one stands there. */
    @Nullable private Planted added(int x, int z) {
        long r = gen.mix(x, 0x4D4F4453L, z);
        int roll = (int) (r & 0xFFFF);
        // Chances in 65536ths: attempts per chunk x 256, as for vanilla's trees.
        if (roll >= mostAdded() * 256) return null;
        int h = gen.surfaceY(x, z), water = gen.waterY(x, z);
        Surface surface = gen.coverAt(x, z, h, water);
        Additions here = additions(surface.biome());
        float scale = here.scaled ? surface.trees() / 100f : 1, at = 0;
        for (Added tree : here.trees) {
            at += tree.perChunk * scale * 256;
            if (roll >= at) continue;
            int depth = water == DRY ? 0 : water - h;
            if (depth > tree.deepest || !roots(tree, surface.top())) return null;
            TreeShape[] grown = shapes(tree.feature, tree.soil);
            if (grown.length == 0) return null;
            return new Planted(x, h + 1, z, grown[(int) Math.floorMod(r >>> 24, grown.length)], (int) (r >>> 34 & 3), r, snowy(surface));
        }
        return null;
    }

    /** Whether an added tree takes root in a column's top: where its sapling would survive, else on soil as vanilla's trees. */
    private boolean roots(Added tree, BlockState top) {
        if (tree.survives == null) return top.is(BlockTags.DIRT);
        return rootings.computeIfAbsent(new Rooting(tree.survives, top), k -> survives(k.survives(), k.top()));
    }

    private boolean survives(BlockState sapling, BlockState top) {
        try {
            return sapling.canSurvive(new TreeLevel(top, under(top), gen.biomeIds.byIdOrThrow(0), RandomSource.create(0)), BlockPos.ZERO);
        } catch (RuntimeException failure) {
            return false;
        }
    }

    private static BlockState under(BlockState top) {
        return top.is(BlockTags.DIRT) && !top.is(Blocks.MUD) && !top.is(Blocks.MOSS_BLOCK) ? Blocks.DIRT.defaultBlockState() : top;
    }

    /**
     * The trees other mods add to a biome: its vegetation features from outside vanilla that grow
     * a tree, a huge mushroom or a feature of the mod's own that turns out to grow logs or leaves.
     * Biome modifiers (NeoForge's add_features) and data packs both land in the biome's own list.
     */
    private Additions additions(Holder<Biome> biome) {
        Additions known = additions.get(biome);
        if (known != null) return known;
        List<Added> found = new ArrayList<>();
        var steps = biome.value().getGenerationSettings().features();
        int step = GenerationStep.Decoration.VEGETAL_DECORATION.ordinal();
        if (steps.size() > step) for (Holder<PlacedFeature> placed : steps.get(step)) {
            String namespace = placed.unwrapKey().map(k -> k.location().getNamespace()).orElse("");
            if (namespace.equals("minecraft") || namespace.equals(Cubic.MOD_ID)) continue;
            ConfiguredFeature<?, ?> feature = placed.value().feature().value();
            if (feature.getFeatures().noneMatch(Trees::treeLike)) continue;
            Added tree = added(placed.value());
            if (tree != null && tree.perChunk > 0 && shapes(tree.feature, tree.soil).length > 0) found.add(tree);
        }
        float total = 0;
        for (Added tree : found) total += tree.perChunk;
        Additions made = found.isEmpty() ? NO_ADDITIONS : new Additions(found.toArray(Added[]::new), total, own(biome).isPresent());
        Additions raced = additions.putIfAbsent(biome, made);
        return raced != null ? raced : made;
    }

    /** A tree feature, a huge mushroom, or a mod's own kind of feature, which might be a tree. */
    private static boolean treeLike(ConfiguredFeature<?, ?> feature) {
        Feature<?> kind = feature.feature();
        if (kind == Feature.TREE || kind == Feature.HUGE_RED_MUSHROOM || kind == Feature.HUGE_BROWN_MUSHROOM || feature.config() instanceof TreeConfiguration) return true;
        ResourceLocation id = BuiltInRegistries.FEATURE.getKey(kind);
        return id != null && !id.getNamespace().equals("minecraft");
    }

    /**
     * Reads an added tree's placement: its count and rarity give the attempts per chunk, its water
     * depth filter the deepest water, its would-survive check the sapling that names its ground.
     */
    @Nullable private Added added(PlacedFeature placed) {
        float perChunk = 1;
        int deepest = 0;
        BlockState survives = null;
        RandomSource random = RandomSource.create(0x434F554EL);
        for (PlacementModifier modifier : placed.placement()) {
            JsonElement json = PlacementModifier.CODEC.encodeStart(JsonOps.INSTANCE, modifier).result().orElse(null);
            if (json == null || !json.isJsonObject()) continue;
            JsonObject object = json.getAsJsonObject();
            try {
                switch (object.has("type") ? object.get("type").getAsString() : "") {
                    case "minecraft:count", "minecraft:count_on_every_layer" -> perChunk *= mean(object.get("count"), random);
                    case "minecraft:rarity_filter" -> perChunk /= Math.max(1, object.get("chance").getAsInt());
                    case "minecraft:noise_threshold_count" -> perChunk *= (object.get("below_noise").getAsInt() + object.get("above_noise").getAsInt()) / 2f;
                    case "minecraft:surface_water_depth_filter" -> deepest = object.get("max_water_depth").getAsInt();
                    case "minecraft:block_predicate_filter" -> {
                        BlockState sapling = sapling(object.get("predicate"));
                        if (sapling != null) survives = sapling;
                    }
                    default -> {}
                }
            } catch (RuntimeException unreadable) {
                // A placement this reading does not understand counts as one attempt.
            }
        }
        BlockState soil = SOILS[0];
        if (survives != null) {
            soil = null;
            for (BlockState top : SOILS) if (survives(survives, top)) { soil = top; break; }
            if (soil == null) return null;
        }
        return new Added(placed.feature().value(), perChunk, deepest, survives, soil);
    }

    private static float mean(JsonElement count, RandomSource random) {
        IntProvider provider = IntProvider.CODEC.parse(JsonOps.INSTANCE, count).getOrThrow();
        long sum = 0;
        for (int i = 0; i < 256; i++) sum += provider.sample(random);
        return sum / 256f;
    }

    /** The block a would-survive check tests, anywhere in a block predicate. */
    @Nullable private static BlockState sapling(JsonElement predicate) {
        if (predicate == null) return null;
        if (predicate.isJsonArray()) {
            for (JsonElement each : predicate.getAsJsonArray()) {
                BlockState found = sapling(each);
                if (found != null) return found;
            }
            return null;
        }
        if (!predicate.isJsonObject()) return null;
        JsonObject object = predicate.getAsJsonObject();
        if (object.has("type") && object.get("type").getAsString().equals("minecraft:would_survive") && object.has("state"))
            return BlockState.CODEC.parse(JsonOps.INSTANCE, object.get("state")).result().orElse(null);
        for (var entry : object.entrySet()) {
            BlockState found = sapling(entry.getValue());
            if (found != null) return found;
        }
        return null;
    }

    // Which vanilla trees ------------------------------------------------------------------------

    /** The trees for a kind in a biome: the biome's own if they are that family, else the family's. */
    private Pick pick(Holder<Biome> biome, Tree kind) {
        Tree family = kind == Tree.SEQUOIA ? Tree.SPRUCE : kind;
        Own own = own(biome).orElse(null);
        if (own != null && own.family() == family) return own.pick();
        return homes.computeIfAbsent(family, this::home);
    }

    /** A biome's own vanilla trees, from its vegetation features. */
    private Optional<Own> own(Holder<Biome> biome) {
        Optional<Own> known = owns.get(biome);
        if (known != null) return known;
        Optional<Own> found = Optional.empty();
        var steps = biome.value().getGenerationSettings().features();
        int step = GenerationStep.Decoration.VEGETAL_DECORATION.ordinal();
        if (steps.size() > step) for (Holder<PlacedFeature> placed : steps.get(step)) {
            Tree family = placed.unwrapKey().map(k -> FAMILY.get(k.location().getPath())).orElse(null);
            if (family != null) { found = Optional.of(new Own(family, pick(placed.value().feature()))); break; }
        }
        owns.putIfAbsent(biome, found);
        return found;
    }

    /** A family's trees as its home biome grows them; azaleas as the lush caves' rooted azalea grows them. */
    private Pick home(Tree family) {
        if (family == Tree.AZALEA) {
            for (Holder<PlacedFeature> placed : vegetation(Biomes.LUSH_CAVES))
                if (placed.value().feature().value().config() instanceof RootSystemConfiguration roots) return pick(roots.treeFeature.value().feature());
        }
        ResourceKey<Biome> home = HOME.getOrDefault(family, Biomes.PLAINS);
        for (Holder<Biome> biome : gen.biomeIds) {
            if (!biome.is(home)) continue;
            Optional<Own> own = own(biome);
            if (own.isPresent() && own.get().family() == family) return own.get().pick();
        }
        // A mod took the home biome's trees away (Ecologics swaps the plains' oaks for walnuts): any biome still growing them.
        for (Holder<Biome> biome : gen.biomeIds) {
            Optional<Own> own = own(biome);
            if (own.isPresent() && own.get().family() == family) return own.get().pick();
        }
        return new Pick(new float[0], new ConfiguredFeature<?, ?>[0], null);
    }

    private Iterable<Holder<PlacedFeature>> vegetation(ResourceKey<Biome> key) {
        int step = GenerationStep.Decoration.VEGETAL_DECORATION.ordinal();
        for (Holder<Biome> biome : gen.biomeIds) {
            if (!biome.is(key)) continue;
            var steps = biome.value().getGenerationSettings().features();
            if (steps.size() > step) return steps.get(step);
        }
        return List.of();
    }

    private static Pick pick(Holder<ConfiguredFeature<?, ?>> feature) {
        FeatureConfiguration config = feature.value().config();
        if (config instanceof RandomFeatureConfiguration selector) {
            float[] chances = new float[selector.features.size()];
            ConfiguredFeature<?, ?>[] trees = new ConfiguredFeature<?, ?>[chances.length];
            for (int i = 0; i < chances.length; i++) {
                chances[i] = selector.features.get(i).chance;
                trees[i] = tree(selector.features.get(i).feature.value().feature());
            }
            return new Pick(chances, trees, tree(selector.defaultFeature.value().feature()));
        }
        if (config instanceof RandomBooleanFeatureConfiguration either)
            return new Pick(new float[]{0.5f}, new ConfiguredFeature<?, ?>[]{tree(either.featureTrue.value().feature())}, tree(either.featureFalse.value().feature()));
        return new Pick(new float[0], new ConfiguredFeature<?, ?>[0], tree(feature));
    }

    /** The feature if it grows a tree or a huge mushroom, else null (a grass patch in bamboo jungle's list). */
    @Nullable private static ConfiguredFeature<?, ?> tree(Holder<ConfiguredFeature<?, ?>> feature) {
        Feature<?> kind = feature.value().feature();
        return kind == Feature.TREE || kind == Feature.HUGE_RED_MUSHROOM || kind == Feature.HUGE_BROWN_MUSHROOM ? feature.value() : null;
    }

    // Growing shapes -----------------------------------------------------------------------------

    private TreeShape[] shapes(ConfiguredFeature<?, ?> feature) { return shapes(feature, null); }

    /** A feature's shapes, grown the first time: vanilla's on grass (mud for mangroves), another mod's on {@code soil}. */
    private TreeShape[] shapes(ConfiguredFeature<?, ?> feature, @Nullable BlockState soil) {
        TreeShape[] grown = shapes.get(feature);
        if (grown != null) return grown;
        shapes.putIfAbsent(feature, grow(feature, soil));
        return shapes.get(feature);
    }

    /**
     * Grows a vanilla tree feature on flat ground (mud for mangroves) until it has {@link #VARIANTS}
     * shapes. Bee nests are left out and added as the shape is placed, at the feature's own odds.
     * Huge mushrooms get a patch of mycelium under them.
     */
    private TreeShape[] grow(ConfiguredFeature<?, ?> feature, @Nullable BlockState soil) {
        boolean mud = feature.config() instanceof TreeConfiguration tree && tree.rootPlacer.isPresent();
        BlockState top = soil != null ? soil : (mud ? Blocks.MUD : Blocks.GRASS_BLOCK).defaultBlockState(), under = soil != null ? under(soil) : (mud ? Blocks.MUD : Blocks.DIRT).defaultBlockState();
        float bees = bees(feature);
        Holder<Biome> anywhere = gen.biomeIds.byIdOrThrow(0);
        List<TreeShape> grown = new ArrayList<>();
        for (int attempt = 0; attempt < VARIANTS * 3 && grown.size() < VARIANTS; attempt++) {
            RandomSource random = RandomSource.create(0x54524545L + attempt * 0x9E3779B97F4A7C15L);
            TreeLevel level = new TreeLevel(top, under, anywhere, random);
            try {
                if (!feature.place(level, null, random, BlockPos.ZERO)) continue;
            } catch (RuntimeException failure) {
                Cubic.LOGGER.warn("Could not grow tree feature {}", feature, failure);
                break;
            }
            Long2ObjectOpenHashMap<BlockState> kept = new Long2ObjectOpenHashMap<>();
            for (var entry : level.blocks.long2ObjectEntrySet()) {
                BlockState state = entry.getValue();
                if (state.is(Blocks.BEE_NEST) || state == level.ground(BlockPos.getY(entry.getLongKey()))) continue;
                kept.put(entry.getLongKey(), state);
            }
            // Huge mushrooms stand on a patch of mycelium, the one place it grows outside mushroom islands.
            if (feature.feature() == Feature.HUGE_RED_MUSHROOM || feature.feature() == Feature.HUGE_BROWN_MUSHROOM)
                for (int dz = -2; dz <= 2; dz++) for (int dx = -2; dx <= 2; dx++)
                    if (Math.abs(dx) + Math.abs(dz) <= 1 || Math.abs(dx) + Math.abs(dz) <= 3 && random.nextBoolean())
                        kept.put(BlockPos.asLong(dx, -1, dz), Blocks.MYCELIUM.defaultBlockState());
            if (kept.isEmpty()) continue;
            // Other mods' trees must fit the reach cubes look for trees in, and must grow a tree, not a patch of flowers.
            if (soil != null && (!fits(kept) || kept.values().stream().noneMatch(Trees::woody))) continue;
            grown.add(new TreeShape(kept, bees));
        }
        return grown.toArray(TreeShape[]::new);
    }

    /** Whether a grown tree stays within {@link #REACH} of its origin and {@link #TALLEST} above it. */
    private static boolean fits(Long2ObjectOpenHashMap<BlockState> blocks) {
        for (long key : blocks.keySet())
            if (Math.abs(BlockPos.getX(key)) > REACH || Math.abs(BlockPos.getZ(key)) > REACH || BlockPos.getY(key) > TALLEST) return false;
        return true;
    }

    private static boolean woody(BlockState state) {
        return state.is(BlockTags.LOGS) || state.is(BlockTags.LEAVES) || state.getBlock() instanceof LeavesBlock || state.getBlock() instanceof HugeMushroomBlock;
    }

    /** The chance of a bee nest a vanilla tree feature gives, from its beehive decorator. */
    private static float bees(ConfiguredFeature<?, ?> feature) {
        if (!(feature.config() instanceof TreeConfiguration tree)) return 0;
        for (TreeDecorator decorator : tree.decorators) {
            if (!(decorator instanceof BeehiveDecorator hive)) continue;
            JsonElement json = BeehiveDecorator.CODEC.codec().encodeStart(JsonOps.INSTANCE, hive).result().orElse(null);
            if (json != null && json.isJsonObject() && json.getAsJsonObject().has("probability")) return json.getAsJsonObject().get("probability").getAsFloat();
        }
        return 0;
    }
}
