package io.earthplatform.minecraft.cubic.gen;

import static io.earthplatform.minecraft.cubic.gen.SurfaceGenerator.*;

import io.earthplatform.minecraft.cubic.gen.SurfaceGenerator.Surface;
import io.earthplatform.minecraft.cubic.gen.SurfaceGenerator.Tree;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.BambooStalkBlock;
import net.minecraft.world.level.block.BeehiveBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CocoaBlock;
import net.minecraft.world.level.block.DoublePlantBlock;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.MangrovePropaguleBlock;
import net.minecraft.world.level.block.PinkPetalsBlock;
import net.minecraft.world.level.block.SeaPickleBlock;
import net.minecraft.world.level.block.SnowyDirtBlock;
import net.minecraft.world.level.block.SweetBerryBushBlock;
import net.minecraft.world.level.block.VineBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BambooLeaves;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.chunk.LevelChunkSection;

/**
 * What grows on a {@link SurfaceGenerator}'s ground, as vanilla grows it: grass, ferns, flowers,
 * mushrooms and crops by biome, sugar cane by water, bamboo, cacti and dead bushes, snow on cold
 * ground; every overworld tree, with cocoa, vines, propagules and bee nests; and in water, lily
 * pads, seagrass, kelp, sea pickles and coral reefs, with ice on frozen water. Everything is
 * decided per column or tree cell from a hash, so each cube is decorated on its own and agrees
 * with its neighbours.
 */
final class Vegetation {
    /** Tree cell size, how far a tree reaches from its cell's trunk, and how high above the ground a tree or a plant can reach. */
    static final int CELL = 7, REACH = 5, TALLEST = 16, TALLEST_PLANT = 15;

    /** Receives a tree's blocks; {@code force} marks trunk blocks that replace whatever is there. */
    interface TreeSink { void put(int x, int y, int z, BlockState state, boolean force); }

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

    Vegetation(SurfaceGenerator gen) { this.gen = gen; }

    /** Plants, water life and trees for the cube at (x0, y0, z0), from its column's heights, water and cover (with a one-block border). */
    void decorate(LevelChunkSection section, int x0, int y0, int z0, int[] height, int[] water, Surface[] surface) {
        for (int lz = 0; lz < 16; lz++) for (int lx = 0; lx < 16; lx++) {
            int gi = (lz + 1) * 18 + lx + 1, h = height[gi];
            Surface s = surface[lz * 16 + lx];
            if (water[gi] != DRY) {
                if (h + 1 <= y0 + 15 && water[gi] + 1 >= y0) underwater(section, x0 + lx, y0, z0 + lz, h, water[gi], s);
            } else if (h + 1 + TALLEST_PLANT >= y0 && h <= y0 + 15) ground(section, x0 + lx, y0, z0 + lz, gi, height, water, s);
        }
        // Trees from every cell whose crown can reach this cube.
        int minCellX = Math.floorDiv(x0 - REACH, CELL), maxCellX = Math.floorDiv(x0 + 15 + REACH, CELL);
        int minCellZ = Math.floorDiv(z0 - REACH, CELL), maxCellZ = Math.floorDiv(z0 + 15 + REACH, CELL);
        TreeSink sink = (x, y, z, state, force) -> put(section, x0, y0, z0, x, y, z, state, force);
        for (int gz = minCellZ; gz <= maxCellZ; gz++) for (int gx = minCellX; gx <= maxCellX; gx++) tree(gx, gz, y0, y0 + 15, sink);
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
        // Frozen water is iced over.
        if (b.value().getBaseTemperature() < 0.15f) { replaceWater(section, lx, water - y0, lz, ICE); return; }
        boolean swampy = b.is(Biomes.SWAMP) || b.is(Biomes.MANGROVE_SWAMP), river = b.is(Biomes.RIVER);
        if (swampy && depth <= 3 && roll < 70 || river && depth <= 2 && roll < 12) plant(section, lx, water + 1 - y0, lz, state(Blocks.LILY_PAD));
        boolean warm = b.is(Biomes.WARM_OCEAN), lukewarm = b.is(Biomes.LUKEWARM_OCEAN) || b.is(Biomes.DEEP_LUKEWARM_OCEAN);
        boolean sea = warm || lukewarm || b.is(Biomes.OCEAN) || b.is(Biomes.DEEP_OCEAN) || b.is(Biomes.COLD_OCEAN) || b.is(Biomes.DEEP_COLD_OCEAN);
        int y = h + 1 - y0;
        // Coral reefs in patches on shallow warm sea beds.
        if (warm && depth >= 3 && depth <= 24 && Math.floorMod(gen.mix(x >> 2, 0x52454546L, z >> 2), 100) < 40) {
            int color = (int) Math.floorMod(gen.mix(x >> 3, 0x434F52L, z >> 3), 5);
            int blocks = Math.min(depth - 2, 1 + (int) Math.floorMod(r >>> 12, 3));
            for (int k = 0; k < blocks; k++) replaceWater(section, lx, y + k, lz, CORAL_BLOCKS[color]);
            if (roll < 700) replaceWater(section, lx, y + blocks, lz, (r >>> 16 & 1) == 0 ? CORALS[color] : CORAL_FANS[color]);
        } else if (sea && !warm && depth >= 5 && depth <= 40 && roll < 60) {
            // Kelp up to just under the surface.
            int length = Math.min(depth - 1, 2 + (int) Math.floorMod(r >>> 20, depth - 2));
            for (int k = 0; k < length; k++) replaceWater(section, lx, y + k, lz, k == length - 1 ? KELP : KELP_PLANT);
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

    // Trees ----------------------------------------------------------------------------------------

    /** The tree of one placement cell, if it has one reaching heights minY..maxY. */
    void tree(int gx, int gz, int minY, int maxY, TreeSink sink) {
        long r = gen.mix(gx * 7919, 31, gz * 104729);
        int tx = gx * CELL + (int) Math.floorMod(r, CELL - 1), tz = gz * CELL + (int) Math.floorMod(r >>> 8, CELL - 1);
        int h = gen.surfaceY(tx, tz);
        if (h + TALLEST < minY || h - 1 > maxY) return;
        int slope = Math.abs(gen.surfaceY(tx + 1, tz) - gen.surfaceY(tx - 1, tz)) + Math.abs(gen.surfaceY(tx, tz + 1) - gen.surfaceY(tx, tz - 1));
        Surface surface = gen.surface(tx, tz, h, gen.waterY(tx, tz), slope);
        if (Math.floorMod(r >>> 16, 100) >= surface.trees()) return;
        Tree kind = surface.tree((int) Math.floorMod(r >>> 24, 10));
        switch (kind) {
            case JUNGLE -> jungle(tx, tz, h, r, sink);
            case ACACIA -> acacia(tx, tz, h, r, sink);
            // Dark forests have huge mushrooms among the trees.
            case DARK_OAK -> { if (Math.floorMod(r >>> 44, 10) == 0) hugeMushroom(tx, tz, h, r, sink); else darkOak(tx, tz, h, r, sink); }
            case CHERRY -> cherry(tx, tz, h, r, sink);
            case AZALEA -> azalea(tx, tz, h, r, sink);
            default -> classic(kind, tx, tz, h, r, surface.biome(), sink);
        }
    }

    /** Leaves standing for a tree kind in far terrain too coarse for its shape. */
    static BlockState leaves(Tree kind) {
        return switch (kind) {
            case SPRUCE -> state(Blocks.SPRUCE_LEAVES);
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

    private static BlockState leaf(Block block) { return block.defaultBlockState().setValue(LeavesBlock.DISTANCE, 1); }

    /** Oak, birch and spruce, as they have always been, and mangroves on roots with hanging propagules. Bees nest in oak and birch on open ground. */
    private void classic(Tree kind, int tx, int tz, int h, long r, Holder<Biome> biome, TreeSink sink) {
        int trunk = kind == Tree.SPRUCE ? 7 + (int) Math.floorMod(r >>> 30, 4) : 4 + (int) Math.floorMod(r >>> 30, 3);
        BlockState log = state(switch (kind) {
            case BIRCH -> Blocks.BIRCH_LOG; case SPRUCE -> Blocks.SPRUCE_LOG; case MANGROVE -> Blocks.MANGROVE_LOG; default -> Blocks.OAK_LOG; });
        BlockState leaves = leaf(switch (kind) {
            case BIRCH -> Blocks.BIRCH_LEAVES; case SPRUCE -> Blocks.SPRUCE_LEAVES; case MANGROVE -> Blocks.MANGROVE_LEAVES; default -> Blocks.OAK_LEAVES; });
        // Mangroves stand on a tangle of roots above the mud.
        int foot = h;
        if (kind == Tree.MANGROVE) {
            BlockState roots = state(Blocks.MANGROVE_ROOTS);
            for (int dz = -1; dz <= 1; dz++) for (int dx = -1; dx <= 1; dx++) {
                if (dx != 0 && dz != 0) continue;
                sink.put(tx + dx, h + 1, tz + dz, roots, false);
                if (dx != 0 || dz != 0) sink.put(tx + 2 * dx, h + 1, tz + 2 * dz, roots, false);
            }
            foot = h + 1;
        }
        int top = foot + trunk;
        if (kind == Tree.SPRUCE) {
            for (int y = h + 2; y <= top + 1; y++) {
                int fromTop = top + 1 - y;
                int radius = fromTop == 0 ? 0 : Math.min(3, 1 + (fromTop + ((y & 1) == 0 ? 1 : 0)) / 2);
                for (int dz = -radius; dz <= radius; dz++) for (int dx = -radius; dx <= radius; dx++) {
                    if (Math.abs(dx) + Math.abs(dz) > radius + 1 || (radius > 0 && Math.abs(dx) == radius && Math.abs(dz) == radius)) continue;
                    sink.put(tx + dx, y, tz + dz, leaves, false);
                }
            }
        } else {
            for (int y = top - 2; y <= top + 1; y++) {
                int radius = y >= top ? 1 : 2;
                for (int dz = -radius; dz <= radius; dz++) for (int dx = -radius; dx <= radius; dx++) {
                    if (radius == 2 && Math.abs(dx) == 2 && Math.abs(dz) == 2 && Math.floorMod(gen.mix(tx + dx, y, tz + dz), 2) == 0) continue;
                    if (y == top + 1 && Math.abs(dx) == 1 && Math.abs(dz) == 1) continue;
                    sink.put(tx + dx, y, tz + dz, leaves, false);
                }
            }
        }
        for (int y = foot + 1; y <= top; y++) sink.put(tx, y, tz, log, true);
        sink.put(tx, h, tz, kind == Tree.MANGROVE ? state(Blocks.MUDDY_MANGROVE_ROOTS) : DIRT, true);
        if (kind == Tree.MANGROVE) {
            for (Direction side : SIDES) if (Math.floorMod(gen.mix(tx + side.getStepX(), top, tz + side.getStepZ()), 3) == 0)
                sink.put(tx + side.getStepX(), top - 3, tz + side.getStepZ(), state(Blocks.MANGROVE_PROPAGULE)
                        .setValue(MangrovePropaguleBlock.HANGING, true).setValue(MangrovePropaguleBlock.AGE, (int) Math.floorMod(r >>> 50, 5)), false);
        } else if (kind != Tree.SPRUCE) {
            boolean open = biome.is(Biomes.PLAINS) || biome.is(Biomes.SUNFLOWER_PLAINS) || biome.is(Biomes.FLOWER_FOREST) || biome.is(Biomes.MEADOW);
            boolean wood = biome.is(Biomes.FOREST) || biome.is(Biomes.BIRCH_FOREST);
            if (open && Math.floorMod(r >>> 50, 20) == 0 || wood && Math.floorMod(r >>> 50, 60) == 0)
                sink.put(tx, top - 3, tz + 1, state(Blocks.BEE_NEST).setValue(BeehiveBlock.FACING, Direction.SOUTH), false);
        }
    }

    /**
     * A rainforest tree: a tall trunk, a giant one in four on a 2x2 trunk, under a broad flat crown
     * hung with vines, with cocoa on the trunk and a bush beside it for the undergrowth. Trees of
     * different heights layer the canopy as a real rainforest's does.
     */
    private void jungle(int tx, int tz, int h, long r, TreeSink sink) {
        BlockState log = state(Blocks.JUNGLE_LOG), leaves = leaf(Blocks.JUNGLE_LEAVES);
        boolean giant = Math.floorMod(r >>> 40, 4) == 0;
        int width = giant ? 2 : 1, radius = giant ? 4 : 3;
        int top = h + (giant ? 12 + (int) Math.floorMod(r >>> 30, 3) : 7 + (int) Math.floorMod(r >>> 30, 5));
        // Crown centre, doubled so a 2x2 trunk's lies between its logs.
        int cx2 = 2 * tx + width - 1, cz2 = 2 * tz + width - 1;
        for (int y = top - 1; y <= top + 1; y++) {
            int ry = y == top + 1 ? radius - 2 : radius;
            int reach = 2 * ry + width - 1;
            for (int z = tz - ry; z <= tz + ry + width - 1; z++) for (int x = tx - ry; x <= tx + ry + width - 1; x++) {
                int dx = 2 * x - cx2, dz = 2 * z - cz2, d = dx * dx + dz * dz;
                if (d > reach * reach + 2) continue;
                // Ragged edges.
                if (d > (reach - 2) * (reach - 2) && Math.floorMod(gen.mix(x, y, z), 3) == 0) continue;
                sink.put(x, y, z, leaves, false);
                // Vines hang from the crown's lower rim.
                if (y == top - 1) {
                    long v = gen.mix(x, y * 3 + 1, z);
                    if (Math.floorMod(v, 4) != 0) continue;
                    hang(sink, x, y, z, cx2, cz2, reach, 1 + (int) Math.floorMod(v >>> 8, 5));
                }
            }
        }
        for (int y = h + 1; y <= top; y++) for (int dz = 0; dz < width; dz++) for (int dx = 0; dx < width; dx++) sink.put(tx + dx, y, tz + dz, log, true);
        for (int dz = 0; dz < width; dz++) for (int dx = 0; dx < width; dx++) sink.put(tx + dx, h, tz + dz, DIRT, true);
        if (!giant) for (int y = h + 3; y <= top - 3; y++) for (Direction side : SIDES) {
            long c = gen.mix(tx * 3 + side.ordinal(), y, tz);
            if (Math.floorMod(c, 12) == 0) sink.put(tx + side.getStepX(), y, tz + side.getStepZ(), state(Blocks.COCOA)
                    .setValue(CocoaBlock.FACING, side.getOpposite()).setValue(CocoaBlock.AGE, (int) Math.floorMod(c >>> 8, 3)), false);
        }
        // A bush three blocks away on each axis, still in this tree's cell.
        int bx = tx - Math.floorMod(tx, CELL) + (Math.floorMod(tx, CELL) + 3) % (CELL - 1);
        int bz = tz - Math.floorMod(tz, CELL) + (Math.floorMod(tz, CELL) + 3) % (CELL - 1);
        int bh = gen.surfaceY(bx, bz);
        if (Math.abs(bh - h) > 1 || gen.waterY(bx, bz) != DRY) return;
        BlockState bushLeaves = leaf(Blocks.OAK_LEAVES);
        for (int y = bh + 1; y <= bh + 2; y++) {
            int rb = y == bh + 1 ? 2 : 1;
            for (int dz = -rb; dz <= rb; dz++) for (int dx = -rb; dx <= rb; dx++) {
                if (Math.abs(dx) + Math.abs(dz) > rb + (rb == 2 && Math.floorMod(gen.mix(bx + dx, y, bz + dz), 2) == 0 ? 1 : 0)) continue;
                sink.put(bx + dx, y, bz + dz, bushLeaves, false);
            }
        }
        sink.put(bx, bh + 1, bz, log, true);
    }

    /**
     * Vines down the outward side of a crown block whose neighbour that way is outside the crown,
     * attached to the leaves and then to the vine above. The crown is centred on (cx2, cz2) / 2.
     */
    private static void hang(TreeSink sink, int x, int y, int z, int cx2, int cz2, int reach, int len) {
        int dx = 2 * x - cx2, dz = 2 * z - cz2, sx = Integer.signum(dx), sz = Integer.signum(dz);
        boolean alongX = sx != 0 && (sz == 0 || (x + z & 1) == 0);
        int vx = alongX ? x + sx : x, vz = alongX ? z : z + sz;
        int ox = 2 * vx - cx2, oz = 2 * vz - cz2;
        if (ox * ox + oz * oz <= reach * reach + 2) return;
        BooleanProperty face = alongX ? (sx > 0 ? VineBlock.WEST : VineBlock.EAST) : (sz > 0 ? VineBlock.NORTH : VineBlock.SOUTH);
        BlockState vine = state(Blocks.VINE).setValue(face, true);
        for (int k = 0; k < len; k++) sink.put(vx, y - k, vz, vine, false);
    }

    /** A savanna acacia: a trunk that leans after a few blocks under a wide, flat crown. */
    private void acacia(int tx, int tz, int h, long r, TreeSink sink) {
        BlockState log = state(Blocks.ACACIA_LOG), leaves = leaf(Blocks.ACACIA_LEAVES);
        int trunk = 5 + (int) Math.floorMod(r >>> 30, 3), bend = 2 + (int) Math.floorMod(r >>> 34, 2);
        Direction lean = SIDES[(int) Math.floorMod(r >>> 36, 4)];
        int x = tx, z = tz, top = h + trunk;
        for (int y = h + 1; y <= top; y++) {
            if (y > h + bend && y < top && Math.abs(x - tx) + Math.abs(z - tz) < 2) { x += lean.getStepX(); z += lean.getStepZ(); }
            sink.put(x, y, z, log, true);
        }
        sink.put(tx, h, tz, DIRT, true);
        for (int dz = -3; dz <= 3; dz++) for (int dx = -3; dx <= 3; dx++) {
            int a = Math.abs(dx) + Math.abs(dz);
            if (a <= 4 && !(a == 4 && Math.floorMod(gen.mix(x + dx, top, z + dz), 2) == 0)) sink.put(x + dx, top + 1, z + dz, leaves, false);
            if (a <= 1) sink.put(x + dx, top + 2, z + dz, leaves, false);
        }
    }

    /** A dark oak: a short 2x2 trunk under a wide, dense crown that shades the ground. */
    private void darkOak(int tx, int tz, int h, long r, TreeSink sink) {
        BlockState log = state(Blocks.DARK_OAK_LOG), leaves = leaf(Blocks.DARK_OAK_LEAVES);
        int top = h + 6 + (int) Math.floorMod(r >>> 30, 3), cx2 = 2 * tx + 1, cz2 = 2 * tz + 1;
        for (int y = top - 2; y <= top + 1; y++) {
            int ry = y == top - 2 || y == top + 1 ? 2 : 3, reach = 2 * ry + 1;
            for (int z = tz - ry; z <= tz + ry + 1; z++) for (int x = tx - ry; x <= tx + ry + 1; x++) {
                int dx = 2 * x - cx2, dz = 2 * z - cz2, d = dx * dx + dz * dz;
                if (d > reach * reach + 2 || d > (reach - 2) * (reach - 2) && Math.floorMod(gen.mix(x, y, z), 3) == 0) continue;
                sink.put(x, y, z, leaves, false);
            }
        }
        for (int y = h + 1; y <= top; y++) for (int dz = 0; dz < 2; dz++) for (int dx = 0; dx < 2; dx++) sink.put(tx + dx, y, tz + dz, log, true);
        for (int dz = 0; dz < 2; dz++) for (int dx = 0; dx < 2; dx++) sink.put(tx + dx, h, tz + dz, DIRT, true);
    }

    /** A huge brown (flat cap) or red (domed cap) mushroom, on a patch of mycelium. */
    private void hugeMushroom(int tx, int tz, int h, long r, TreeSink sink) {
        boolean red = (r >>> 46 & 1) == 0;
        int top = h + 4 + (int) Math.floorMod(r >>> 30, 3);
        BlockState cap = state(red ? Blocks.RED_MUSHROOM_BLOCK : Blocks.BROWN_MUSHROOM_BLOCK);
        if (red) {
            for (int y = top - 2; y <= top; y++) for (int dz = -2; dz <= 2; dz++) for (int dx = -2; dx <= 2; dx++)
                if (Math.max(Math.abs(dx), Math.abs(dz)) == 2 && Math.abs(dx) + Math.abs(dz) < 4) sink.put(tx + dx, y, tz + dz, cap, false);
            for (int dz = -1; dz <= 1; dz++) for (int dx = -1; dx <= 1; dx++) sink.put(tx + dx, top + 1, tz + dz, cap, false);
        } else {
            for (int dz = -3; dz <= 3; dz++) for (int dx = -3; dx <= 3; dx++)
                if (Math.abs(dx) + Math.abs(dz) < 6) sink.put(tx + dx, top + 1, tz + dz, cap, false);
        }
        for (int y = h + 1; y <= top; y++) sink.put(tx, y, tz, state(Blocks.MUSHROOM_STEM), true);
        for (int dz = -1; dz <= 1; dz++) for (int dx = -1; dx <= 1; dx++)
            if (gen.surfaceY(tx + dx, tz + dz) == h && gen.waterY(tx + dx, tz + dz) == DRY) sink.put(tx + dx, h, tz + dz, MYCELIUM, true);
    }

    /** A cherry tree: a trunk with a branch leaning off it, each under a round pink crown. */
    private void cherry(int tx, int tz, int h, long r, TreeSink sink) {
        BlockState log = state(Blocks.CHERRY_LOG), leaves = leaf(Blocks.CHERRY_LEAVES);
        int top = h + 5 + (int) Math.floorMod(r >>> 30, 2);
        for (int y = h + 1; y <= top; y++) sink.put(tx, y, tz, log, true);
        sink.put(tx, h, tz, DIRT, true);
        blob(sink, tx, top, tz, 2, leaves, r);
        Direction lean = SIDES[(int) Math.floorMod(r >>> 36, 4)];
        int bx = tx, bz = tz, branchTop = h + 4;
        for (int y = h + 3; y <= branchTop; y++) { bx += lean.getStepX(); bz += lean.getStepZ(); sink.put(bx, y, bz, log, true); }
        blob(sink, bx, branchTop, bz, 2, leaves, r >>> 3);
    }

    /** An azalea: a short, crooked oak trunk on rooted dirt under a round crown of plain and flowering azalea leaves. */
    private void azalea(int tx, int tz, int h, long r, TreeSink sink) {
        BlockState log = state(Blocks.OAK_LOG);
        int top = h + 3 + (int) Math.floorMod(r >>> 30, 2), x = tx, z = tz;
        Direction lean = SIDES[(int) Math.floorMod(r >>> 36, 4)];
        for (int y = h + 1; y <= top; y++) {
            if (y == top && (r >>> 38 & 1) == 0) { x += lean.getStepX(); z += lean.getStepZ(); }
            sink.put(x, y, z, log, true);
        }
        sink.put(tx, h, tz, state(Blocks.ROOTED_DIRT), true);
        for (int y = top; y <= top + 2; y++) {
            int radius = y == top + 1 ? 2 : 1;
            for (int dz = -radius; dz <= radius; dz++) for (int dx = -radius; dx <= radius; dx++) {
                long c = gen.mix(x + dx, y, z + dz);
                if (radius == 2 && Math.abs(dx) == 2 && Math.abs(dz) == 2 && (c & 1) == 0) continue;
                sink.put(x + dx, y, z + dz, leaf(Math.floorMod(c >>> 4, 4) == 0 ? Blocks.FLOWERING_AZALEA_LEAVES : Blocks.AZALEA_LEAVES), false);
            }
        }
    }

    /** A rounded clump of leaves about a branch end. */
    private void blob(TreeSink sink, int cx, int cy, int cz, int radius, BlockState leaves, long r) {
        for (int y = cy - 1; y <= cy + radius; y++) {
            int ry = y == cy + radius ? radius - 1 : radius + 1;
            for (int dz = -ry; dz <= ry; dz++) for (int dx = -ry; dx <= ry; dx++) {
                int d = dx * dx + dz * dz;
                if (d > ry * ry + 1 || d > (ry - 1) * (ry - 1) + 1 && Math.floorMod(gen.mix(cx + dx, y, cz + dz) ^ r, 3) == 0) continue;
                sink.put(cx + dx, y, cz + dz, leaves, false);
            }
        }
    }

    // Placement ------------------------------------------------------------------------------------

    private static void put(LevelChunkSection section, int x0, int y0, int z0, int x, int y, int z, BlockState state, boolean force) {
        int lx = x - x0, ly = y - y0, lz = z - z0;
        if (lx < 0 || ly < 0 || lz < 0 || lx > 15 || ly > 15 || lz > 15) return;
        BlockState here = section.getBlockState(lx, ly, lz);
        if (force || here.isAir() || here.canBeReplaced()) section.setBlockState(lx, ly, lz, state, false);
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
