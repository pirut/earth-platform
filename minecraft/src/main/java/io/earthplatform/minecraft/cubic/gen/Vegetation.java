package io.earthplatform.minecraft.cubic.gen;

import static io.earthplatform.minecraft.cubic.gen.SurfaceGenerator.*;

import io.earthplatform.minecraft.cubic.gen.SurfaceGenerator.Surface;
import io.earthplatform.minecraft.cubic.gen.SurfaceGenerator.Tree;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.BambooStalkBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoublePlantBlock;
import net.minecraft.world.level.block.PinkPetalsBlock;
import net.minecraft.world.level.block.SeaPickleBlock;
import net.minecraft.world.level.block.SnowyDirtBlock;
import net.minecraft.world.level.block.SweetBerryBushBlock;
import net.minecraft.world.level.block.entity.BeehiveBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BambooLeaves;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.chunk.LevelChunkSection;

/**
 * What grows on a {@link SurfaceGenerator}'s ground, as vanilla grows it: grass, ferns, flowers,
 * mushrooms and crops by biome, sugar cane by water, bamboo, cacti and dead bushes, snow on cold
 * ground; Minecraft's own trees ({@link Trees}); and in water, lily pads, seagrass, kelp, sea
 * pickles and coral reefs, with ice on frozen water. Everything is decided per column or tree
 * from a hash, so each cube is decorated on its own and agrees with its neighbours.
 */
final class Vegetation {
    /** How high above the ground a plant (bamboo) can reach. */
    static final int TALLEST_PLANT = 15;

    private static BlockState state(Block block) { return block.defaultBlockState(); }
    private static final BlockState CACTUS = state(Blocks.CACTUS), SUGAR_CANE = state(Blocks.SUGAR_CANE), ICE = state(Blocks.ICE),
            RED_SAND = state(Blocks.RED_SAND), PODZOL = state(Blocks.PODZOL), MYCELIUM = state(Blocks.MYCELIUM),
            COARSE_DIRT = state(Blocks.COARSE_DIRT), MOSS = state(Blocks.MOSS_BLOCK), SEAGRASS = state(Blocks.SEAGRASS),
            KELP = state(Blocks.KELP), KELP_PLANT = state(Blocks.KELP_PLANT);
    private static final BlockState[] DEFAULT_FLOWERS = flowers(Blocks.POPPY, Blocks.DANDELION, Blocks.CORNFLOWER, Blocks.AZURE_BLUET),
            PLAINS_FLOWERS = flowers(Blocks.DANDELION, Blocks.POPPY, Blocks.AZURE_BLUET, Blocks.OXEYE_DAISY, Blocks.CORNFLOWER,
                    Blocks.RED_TULIP, Blocks.ORANGE_TULIP, Blocks.WHITE_TULIP, Blocks.PINK_TULIP),
            MEADOW_FLOWERS = flowers(Blocks.ALLIUM, Blocks.AZURE_BLUET, Blocks.CORNFLOWER, Blocks.OXEYE_DAISY, Blocks.DANDELION, Blocks.POPPY),
            FOREST_FLOWERS = flowers(Blocks.DANDELION, Blocks.POPPY, Blocks.LILY_OF_THE_VALLEY, Blocks.LILAC, Blocks.ROSE_BUSH, Blocks.PEONY),
            ALL_FLOWERS = flowers(Blocks.DANDELION, Blocks.POPPY, Blocks.ALLIUM, Blocks.AZURE_BLUET, Blocks.RED_TULIP, Blocks.ORANGE_TULIP,
                    Blocks.WHITE_TULIP, Blocks.PINK_TULIP, Blocks.OXEYE_DAISY, Blocks.CORNFLOWER, Blocks.LILY_OF_THE_VALLEY,
                    Blocks.LILAC, Blocks.ROSE_BUSH, Blocks.PEONY),
            CORAL_BLOCKS = flowers(Blocks.TUBE_CORAL_BLOCK, Blocks.BRAIN_CORAL_BLOCK, Blocks.BUBBLE_CORAL_BLOCK, Blocks.FIRE_CORAL_BLOCK, Blocks.HORN_CORAL_BLOCK),
            CORALS = flowers(Blocks.TUBE_CORAL, Blocks.BRAIN_CORAL, Blocks.BUBBLE_CORAL, Blocks.FIRE_CORAL, Blocks.HORN_CORAL),
            CORAL_FANS = flowers(Blocks.TUBE_CORAL_FAN, Blocks.BRAIN_CORAL_FAN, Blocks.BUBBLE_CORAL_FAN, Blocks.FIRE_CORAL_FAN, Blocks.HORN_CORAL_FAN);
    private static BlockState[] flowers(Block... blocks) {
        BlockState[] states = new BlockState[blocks.length];
        for (int i = 0; i < blocks.length; i++) states[i] = blocks[i].defaultBlockState();
        return states;
    }
    private static final Direction[] SIDES = {Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST};

    private final SurfaceGenerator gen;
    private final Trees trees;

    Vegetation(SurfaceGenerator gen) {
        this.gen = gen;
        this.trees = new Trees(gen);
    }

    /** Plants, water life and trees for the cube at (x0, y0, z0), from its column's heights, water and cover (with a one-block border). */
    void decorate(LevelChunkSection section, int x0, int y0, int z0, int[] height, int[] water, Surface[] surface) {
        for (int lz = 0; lz < 16; lz++) for (int lx = 0; lx < 16; lx++) {
            int gi = (lz + 1) * 18 + lx + 1, h = height[gi];
            Surface s = surface[lz * 16 + lx];
            if (water[gi] != DRY) {
                // Frozen water is iced over, however deep: its bed may lie cubes below.
                if (water[gi] > h && gen.iced(s, x0 + lx, z0 + lz)) {
                    if (water[gi] >= y0 && water[gi] <= y0 + 15) replaceWater(section, lx, water[gi] - y0, lz, ICE);
                } else if (h + 1 <= y0 + 15 && water[gi] + 1 >= y0) underwater(section, x0 + lx, y0, z0 + lz, h, water[gi], s);
            } else if (h + 1 + TALLEST_PLANT >= y0 && h <= y0 + 15) ground(section, x0 + lx, y0, z0 + lz, gi, height, water, s);
        }
        trees.place(x0, y0, z0, x0 + 15, y0 + 15, z0 + 15, false, (x, y, z, state, kind) -> put(section, x - x0, y - y0, z - z0, state, kind));
    }

    /** How high above the ground the trees around this cube column can reach. */
    int tallest(int cx, int cz) { return trees.tallest(cx, cz); }

    /** Writes the trees in a box, for far terrain. */
    void trees(int x0, int y0, int z0, int x1, int y1, int z1, boolean giantsOnly, TreeShape.Sink sink) {
        trees.place(x0, y0, z0, x1, y1, z1, giantsOnly, sink);
    }

    /**
     * Block entity tags for the bee nests in a generated cube whose lowest corner is (x0, y0, z0),
     * each holding two or three bees, as vanilla's beehive decorator stocks a nest.
     */
    static void bees(LevelChunkSection section, int x0, int y0, int z0, List<CompoundTag> blockEntities) {
        if (!section.maybeHas(state -> state.is(Blocks.BEE_NEST))) return;
        String id = BlockEntityType.getKey(BlockEntityType.BEEHIVE).toString();
        for (int i = 0; i < 4096; i++) {
            int lx = i & 15, lz = i >> 4 & 15, ly = i >> 8;
            if (!section.getBlockState(lx, ly, lz).is(Blocks.BEE_NEST)) continue;
            int x = x0 + lx, y = y0 + ly, z = z0 + lz;
            RandomSource random = RandomSource.create(BlockPos.asLong(x, y, z) * 0x9E3779B97F4A7C15L);
            List<BeehiveBlockEntity.Occupant> bees = new ArrayList<>();
            for (int b = 2 + random.nextInt(2); b > 0; b--) bees.add(BeehiveBlockEntity.Occupant.create(random.nextInt(599)));
            CompoundTag tag = new CompoundTag();
            tag.putString("id", id);
            tag.putInt("x", x);
            tag.putInt("y", y);
            tag.putInt("z", z);
            tag.put("bees", BeehiveBlockEntity.Occupant.LIST_CODEC.encodeStart(NbtOps.INSTANCE, bees).getOrThrow());
            blockEntities.add(tag);
        }
    }

    // Ground ---------------------------------------------------------------------------------------

    private void ground(LevelChunkSection section, int x, int y0, int z, int gi, int[] height, int[] water, Surface s) {
        int h = height[gi], lx = x & 15, lz = z & 15;
        BlockState top = s.top();
        Holder<Biome> biome = s.biome();
        long r = gen.mix(x, h, z);
        int roll = (int) Math.floorMod(r, 1000);
        boolean snowy = biome.value().getBaseTemperature() < 0.15f;
        boolean soil = top == GRASS || top == PODZOL || top == MYCELIUM || top == MOSS;
        BlockState plant = null;
        // A stack of one plant: cacti, sugar cane, bamboo.
        BlockState stalk = null;
        int stalkHeight = 0;
        boolean byWater = !snowy && nextToWater(gi, height, water, h);
        if (top == SNOW_BLOCK) plant = SNOW;
        else if (byWater && roll < 150 && (soil || top == SAND || top == RED_SAND || top == DIRT || top == COARSE_DIRT || top == MUD)) {
            stalk = SUGAR_CANE;
            stalkHeight = 2 + (int) Math.floorMod(r >>> 20, 3);
        } else if (top == SAND || top == RED_SAND) {
            // Desert scrub: dead bushes, and cacti on every other column (so no two touch) with no higher ground beside them.
            if (roll < s.grass()) plant = state(Blocks.DEAD_BUSH);
            else if (roll < s.flowers() && ((x | z) & 1) == 0 && height[gi - 1] <= h && height[gi + 1] <= h
                    && height[gi - 18] <= h && height[gi + 18] <= h) {
                stalk = CACTUS;
                stalkHeight = 1 + (int) Math.floorMod(r >>> 20, 3);
            }
        } else if ((top == COARSE_DIRT || top.is(Blocks.LIGHT_GRAY_TERRACOTTA)) && (biome.is(Biomes.SAVANNA) || biome.is(Biomes.WINDSWEPT_SAVANNA)
                || biome.is(Biomes.DESERT) || biome.is(Biomes.BADLANDS))) {
            // Dry scrub on bare dirt: sagebrush and saltbush stand-ins, with a few tufts of grass.
            if (roll < s.grass() / 5) plant = state(Blocks.DEAD_BUSH);
            else if (roll < s.grass() / 3 && top == COARSE_DIRT) plant = state(Blocks.SHORT_GRASS);
        } else if (top.is(Blocks.WATER)) {
            // Marsh pools level with the ground: lily pads.
            if (roll < 90) plant = state(Blocks.LILY_PAD);
        } else if (top == MUD) {
            if (biome.is(Biomes.MANGROVE_SWAMP) && roll < 120) plant = state(Blocks.MOSS_CARPET);
        } else if (soil) {
            boolean shade = s.trees() >= 50 || top == PODZOL || top == MYCELIUM;
            // Crops grow wild in patches: one 16-block square in many has some.
            long region = gen.mix(x >> 4, 0x50415443L, z >> 4);
            int patch = (int) Math.floorMod(r >>> 32, 100);
            boolean jungle = biome.is(Biomes.JUNGLE) || biome.is(Biomes.SPARSE_JUNGLE) || biome.is(Biomes.BAMBOO_JUNGLE);
            boolean taiga = biome.is(Biomes.TAIGA) || biome.is(Biomes.SNOWY_TAIGA) || biome.is(Biomes.OLD_GROWTH_SPRUCE_TAIGA)
                    || biome.is(Biomes.OLD_GROWTH_PINE_TAIGA);
            if (biome.is(Biomes.BAMBOO_JUNGLE) && roll < 300 && top != MYCELIUM) {
                stalk = state(Blocks.BAMBOO);
                stalkHeight = 5 + (int) Math.floorMod(r >>> 20, 10);
            } else if (top == GRASS && jungle && Math.floorMod(region, 10) == 1 && patch < 8) plant = state(Blocks.MELON);
            else if (taiga && Math.floorMod(region, 8) == 2 && patch < 8)
                plant = state(Blocks.SWEET_BERRY_BUSH).setValue(SweetBerryBushBlock.AGE, 1 + (int) Math.floorMod(r >>> 40, 3));
            else if (top == GRASS && !snowy && !jungle && Math.floorMod(region, 90) == 3 && patch < 6) plant = state(Blocks.PUMPKIN);
            else if (roll < s.grass()) {
                boolean fern = s.ferns() && roll % 3 == 0, tall = Math.floorMod(r >>> 24, 6) == 0;
                plant = fern ? state(tall ? Blocks.LARGE_FERN : Blocks.FERN) : state(tall ? Blocks.TALL_GRASS : Blocks.SHORT_GRASS);
            } else if (roll < s.flowers()) plant = flower(biome, x, z, r);
            else if (shade && roll < s.flowers() + (top == MYCELIUM ? 60 : 8))
                plant = state((r >>> 44) % 3 == 0 ? Blocks.RED_MUSHROOM : Blocks.BROWN_MUSHROOM);
            // Cold ground is under snow, with a few plants showing through.
            if (snowy && stalk == null && !(plant != null && plant.is(Blocks.SWEET_BERRY_BUSH)) && roll >= s.grass() / 3) plant = SNOW;
        }
        if (snowy && plant == null && stalk == null && !top.is(Blocks.POWDER_SNOW) && !top.is(Blocks.ICE) && !top.is(Blocks.PACKED_ICE)) plant = SNOW;
        if (plant == SNOW && top.hasProperty(SnowyDirtBlock.SNOWY) && h >= y0 && h <= y0 + 15 && section.getBlockState(lx, h - y0, lz) == top)
            section.setBlockState(lx, h - y0, lz, top.setValue(SnowyDirtBlock.SNOWY, true), false);
        int y = h + 1;
        if (stalk != null) {
            for (int k = 0; k < stalkHeight; k++) {
                BlockState piece = stalk;
                if (stalk.is(Blocks.BAMBOO)) piece = stalk.setValue(BambooStalkBlock.AGE, 1).setValue(BambooStalkBlock.LEAVES,
                        k >= stalkHeight - 2 ? BambooLeaves.LARGE : k == stalkHeight - 3 ? BambooLeaves.SMALL : BambooLeaves.NONE);
                plant(section, lx, y + k - y0, lz, piece);
            }
        } else if (plant != null) {
            if (plant.getBlock() instanceof DoublePlantBlock) {
                // Both halves or neither, as far as this cube can tell.
                if (y + 1 - y0 < 16 && y - y0 >= 0 && !section.getBlockState(lx, y + 1 - y0, lz).isAir()) return;
                plant(section, lx, y - y0, lz, plant);
                plant(section, lx, y + 1 - y0, lz, plant.setValue(DoublePlantBlock.HALF, DoubleBlockHalf.UPPER));
            } else plant(section, lx, y - y0, lz, plant);
        }
    }

    /** Whether a column has water beside it level with its ground, as sugar cane needs. */
    private static boolean nextToWater(int gi, int[] height, int[] water, int h) {
        return wet(gi - 1, height, water, h) || wet(gi + 1, height, water, h) || wet(gi - 18, height, water, h) || wet(gi + 18, height, water, h);
    }
    private static boolean wet(int n, int[] height, int[] water, int h) { return water[n] != DRY && height[n] < h && water[n] >= h; }

    /** The flower a biome grows here; each kind grows in patches a few blocks across. */
    private BlockState flower(Holder<Biome> biome, int x, int z, long r) {
        if (biome.is(Biomes.SWAMP)) return state(Blocks.BLUE_ORCHID);
        if (biome.is(Biomes.CHERRY_GROVE)) return state(Blocks.PINK_PETALS).setValue(PinkPetalsBlock.FACING, SIDES[(int) (r >>> 40 & 3)])
                .setValue(PinkPetalsBlock.AMOUNT, 1 + (int) (r >>> 42 & 3));
        long patch = gen.mix(x >> 3, 0x464C4F57L, z >> 3);
        if (biome.is(Biomes.SUNFLOWER_PLAINS) && Math.floorMod(patch, 10) < 6) return state(Blocks.SUNFLOWER);
        BlockState[] kinds;
        if (biome.is(Biomes.FLOWER_FOREST)) { kinds = ALL_FLOWERS; patch = gen.mix(x >> 2, 0x464C4F57L, z >> 2); }
        else if (biome.is(Biomes.MEADOW)) kinds = MEADOW_FLOWERS;
        else if (biome.is(Biomes.PLAINS) || biome.is(Biomes.SUNFLOWER_PLAINS)) kinds = PLAINS_FLOWERS;
        else if (biome.is(Biomes.FOREST) || biome.is(Biomes.BIRCH_FOREST) || biome.is(Biomes.OLD_GROWTH_BIRCH_FOREST) || biome.is(Biomes.DARK_FOREST)) kinds = FOREST_FLOWERS;
        else kinds = DEFAULT_FLOWERS;
        return kinds[(int) Math.floorMod(patch >>> 8, kinds.length)];
    }

    // Water ----------------------------------------------------------------------------------------

    private void underwater(LevelChunkSection section, int x, int y0, int z, int h, int water, Surface s) {
        int depth = water - h, lx = x & 15, lz = z & 15;
        if (depth <= 0) return;
        Holder<Biome> b = s.biome();
        long r = gen.mix(x, water, z);
        int roll = (int) Math.floorMod(r, 1000);
        boolean swampy = b.is(Biomes.SWAMP) || b.is(Biomes.MANGROVE_SWAMP), river = b.is(Biomes.RIVER);
        if (swampy && depth <= 3 && roll < 70 || river && depth <= 2 && roll < 12) plant(section, lx, water + 1 - y0, lz, state(Blocks.LILY_PAD));
        boolean warm = b.is(Biomes.WARM_OCEAN), lukewarm = b.is(Biomes.LUKEWARM_OCEAN) || b.is(Biomes.DEEP_LUKEWARM_OCEAN);
        boolean sea = warm || lukewarm || b.is(Biomes.OCEAN) || b.is(Biomes.DEEP_OCEAN) || b.is(Biomes.COLD_OCEAN) || b.is(Biomes.DEEP_COLD_OCEAN);
        int y = h + 1 - y0;
        // Coral reefs in patches on shallow warm sea beds.
        if (warm && depth >= 3 && depth <= 24 && Math.floorMod(gen.mix(x >> 2, 0x52454546L, z >> 2), 100) < 40 && openWater(x, z, water)) {
            int color = (int) Math.floorMod(gen.mix(x >> 3, 0x434F52L, z >> 3), 5);
            int blocks = Math.min(depth - 2, 1 + (int) Math.floorMod(r >>> 12, 3));
            for (int k = 0; k < blocks; k++) replaceWater(section, lx, y + k, lz, CORAL_BLOCKS[color]);
            if (roll < 700) replaceWater(section, lx, y + blocks, lz, (r >>> 16 & 1) == 0 ? CORALS[color] : CORAL_FANS[color]);
        } else if (sea && !warm && depth >= 5 && depth <= 40 && roll < 60) {
            // Kelp up to just under the surface.
            int length = Math.min(depth - 1, 2 + (int) Math.floorMod(r >>> 20, depth - 2));
            for (int k = 0; k < length; k++) replaceWater(section, lx, y + k, lz, k == length - 1 ? KELP : KELP_PLANT);
        } else if (depth > 60) {
            // No light reaches the deep sea floor: bare sediment, no seagrass.
        } else if ((sea || river || swampy) && depth >= 2 && roll < 360) {
            if (depth >= 3 && roll % 5 == 0) {
                BlockState tall = state(Blocks.TALL_SEAGRASS);
                replaceWater(section, lx, y, lz, tall);
                replaceWater(section, lx, y + 1, lz, tall.setValue(DoublePlantBlock.HALF, DoubleBlockHalf.UPPER));
            } else replaceWater(section, lx, y, lz, SEAGRASS);
        } else if ((warm || lukewarm) && depth >= 2 && roll < 380) {
            replaceWater(section, lx, y, lz, state(Blocks.SEA_PICKLE).setValue(SeaPickleBlock.PICKLES, 1 + (int) (r >>> 20 & 3)));
        }
    }

    private final java.util.concurrent.ConcurrentHashMap<Long, Boolean> openWater = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * Whether water is open sea or a wide lagoon rather than a pool among rocks, judged for a 4 by 4 patch by
     * whether the same water reaches out a hundred and seven hundred blocks in nearly every direction. Reefs grow only there:
     * enclosed pools, such as Ha Long Bay's karst lagoons, are too brackish and still for coral.
     */
    private boolean openWater(int x, int z, int water) {
        long key = it.unimi.dsi.fastutil.HashCommon.mix((long) (x >> 2) << 32 | (z >> 2) & 0xFFFFFFFFL);
        Boolean known = openWater.get(key);
        if (known != null) return known;
        int cx = (x & ~3) + 2, cz = (z & ~3) + 2;
        boolean open = true;
        // Near and far: a lagoon behind barrier islands, such as Lake Worth Lagoon, is wide but has a sand and seagrass floor.
        for (int radius = 100; radius <= 700 && open; radius += 600) {
            int same = 0;
            for (int k = 0; k < 8; k++) {
                double a = k * Math.PI / 4;
                int px = cx + (int) Math.round(Math.cos(a) * radius), pz = cz + (int) Math.round(Math.sin(a) * radius);
                if (gen.waterY(px, pz) == water && gen.surfaceY(px, pz) < water) same++;
            }
            open = same >= 6;
        }
        if (openWater.size() > 1 << 16) openWater.clear();
        openWater.putIfAbsent(key, open);
        return open;
    }

    // Trees ----------------------------------------------------------------------------------------

    /** Leaves standing for a tree kind in far terrain too coarse for its shape. */
    static BlockState leaves(Tree kind) {
        return switch (kind) {
            case SPRUCE, SEQUOIA -> state(Blocks.SPRUCE_LEAVES);
            case BIRCH -> state(Blocks.BIRCH_LEAVES);
            case OAK -> state(Blocks.OAK_LEAVES);
            case JUNGLE -> state(Blocks.JUNGLE_LEAVES);
            case ACACIA -> state(Blocks.ACACIA_LEAVES);
            case MANGROVE -> state(Blocks.MANGROVE_LEAVES);
            case DARK_OAK -> state(Blocks.DARK_OAK_LEAVES);
            case CHERRY -> state(Blocks.CHERRY_LEAVES);
            case AZALEA -> state(Blocks.AZALEA_LEAVES);
        };
    }

    // Placement ------------------------------------------------------------------------------------

    /**
     * A tree block at a section-local position: trunks replace what is there, crowns fill air,
     * plants and water (waterlogged where they can be), and ground blocks replace only ground.
     */
    private static void put(LevelChunkSection section, int lx, int ly, int lz, BlockState state, byte kind) {
        BlockState here = section.getBlockState(lx, ly, lz);
        boolean water = here.is(Blocks.WATER);
        switch (kind) {
            case TreeShape.GROUND -> { if (here.isAir() || !here.getFluidState().isEmpty()) return; }
            case TreeShape.CROWN -> {
                if (!here.isAir() && !here.canBeReplaced()) return;
                if (water && !state.hasProperty(BlockStateProperties.WATERLOGGED)) return;
            }
            default -> {}
        }
        if (state.hasProperty(BlockStateProperties.WATERLOGGED)) state = state.setValue(BlockStateProperties.WATERLOGGED, water);
        section.setBlockState(lx, ly, lz, state, false);
    }

    /** A plant into air at a section-local height, if it is in this section. */
    private static void plant(LevelChunkSection section, int lx, int ly, int lz, BlockState state) {
        if (ly >= 0 && ly < 16 && section.getBlockState(lx, ly, lz).isAir()) section.setBlockState(lx, ly, lz, state, false);
    }

    /** A block in place of water at a section-local height, if it is in this section. */
    private static void replaceWater(LevelChunkSection section, int lx, int ly, int lz, BlockState state) {
        if (ly >= 0 && ly < 16 && section.getBlockState(lx, ly, lz).is(Blocks.WATER)) section.setBlockState(lx, ly, lz, state, false);
    }
}
