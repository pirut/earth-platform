package io.earthplatform.minecraft.cubic.gen;

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue;
import java.util.Random;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CocoaBlock;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.minecraft.world.level.block.VineBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Pre-baked procedural regional tree shapes grown from pure vanilla blocks, giving Earth's climates
 * distinct silhouettes (palms, emergent rainforest canopies, narrow boreal pines, Mediterranean
 * cypresses and umbrella stone pines, savanna baobabs and flat-top acacias, and taller spreading
 * oaks and birches). Each species bakes {@value #VARIANTS} deterministic {@link TreeShape}s that stay
 * within {@link Trees#REACH} and {@link Trees#TALLEST}, with accurate leaf distances so crowns never
 * decay unprompted yet still decay naturally when felled.
 */
public final class RegionalTrees {
    /** Regional tree forms selected by climate, ecoregion and coast rules. */
    public enum Species {
        PALM,
        RAINFOREST_CANOPY,
        BOREAL_PINE,
        CYPRESS,
        STONE_PINE,
        BAOBAB,
        FLAT_ACACIA,
        BROADLEAF_OAK,
        TALL_BIRCH
    }

    static final int VARIANTS = 8;

    private static final BlockState JUNGLE_LOG = Blocks.JUNGLE_LOG.defaultBlockState();
    private static final BlockState JUNGLE_WOOD = Blocks.JUNGLE_WOOD.defaultBlockState();
    private static final BlockState JUNGLE_LEAVES = Blocks.JUNGLE_LEAVES.defaultBlockState();
    private static final BlockState SPRUCE_LOG = Blocks.SPRUCE_LOG.defaultBlockState();
    private static final BlockState SPRUCE_WOOD = Blocks.SPRUCE_WOOD.defaultBlockState();
    private static final BlockState SPRUCE_LEAVES = Blocks.SPRUCE_LEAVES.defaultBlockState();
    private static final BlockState DARK_OAK_LOG = Blocks.DARK_OAK_LOG.defaultBlockState();
    private static final BlockState DARK_OAK_WOOD = Blocks.DARK_OAK_WOOD.defaultBlockState();
    private static final BlockState DARK_OAK_LEAVES = Blocks.DARK_OAK_LEAVES.defaultBlockState();
    private static final BlockState OAK_LOG = Blocks.OAK_LOG.defaultBlockState();
    private static final BlockState OAK_WOOD = Blocks.OAK_WOOD.defaultBlockState();
    private static final BlockState OAK_LEAVES = Blocks.OAK_LEAVES.defaultBlockState();
    private static final BlockState ACACIA_LOG = Blocks.ACACIA_LOG.defaultBlockState();
    private static final BlockState ACACIA_WOOD = Blocks.ACACIA_WOOD.defaultBlockState();
    private static final BlockState ACACIA_LEAVES = Blocks.ACACIA_LEAVES.defaultBlockState();
    private static final BlockState BIRCH_LOG = Blocks.BIRCH_LOG.defaultBlockState();
    private static final BlockState BIRCH_WOOD = Blocks.BIRCH_WOOD.defaultBlockState();
    private static final BlockState BIRCH_LEAVES = Blocks.BIRCH_LEAVES.defaultBlockState();
    private static final BlockState VINE = Blocks.VINE.defaultBlockState();
    private static final BlockState COCOA = Blocks.COCOA.defaultBlockState().setValue(CocoaBlock.AGE, 2);
    private static final Direction[] HORIZONTAL = {Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST};

    private static final TreeShape[][] CACHE = new TreeShape[Species.values().length][];

    private final Long2ObjectOpenHashMap<BlockState> blocks = new Long2ObjectOpenHashMap<>();
    private final Random random;
    private final int salt;

    private RegionalTrees(long seed) {
        this.random = new Random(seed);
        this.salt = (int) (seed >>> 20);
    }

    /** A species' pre-baked variants, grown once on first use. */
    static TreeShape[] shapes(Species species) {
        int id = species.ordinal();
        TreeShape[] made = CACHE[id];
        if (made == null) synchronized (CACHE) {
            made = CACHE[id];
            if (made == null) {
                made = new TreeShape[VARIANTS];
                for (int i = 0; i < VARIANTS; i++)
                    made[i] = grow(0x524547494F4EL * (id + 1) + i * 0x9E3779B97F4A7C15L, species, i);
                CACHE[id] = made;
            }
        }
        return made;
    }

    /** Grows one deterministic variant of a regional species. */
    static TreeShape grow(long seed, Species species) {
        return grow(seed, species, (int) Math.floorMod(seed, VARIANTS));
    }

    private static TreeShape grow(long seed, Species species, int variant) {
        RegionalTrees tree = new RegionalTrees(seed);
        switch (species) {
            case PALM -> tree.palm();
            case RAINFOREST_CANOPY -> tree.rainforestCanopy();
            case BOREAL_PINE -> tree.borealPine();
            case CYPRESS -> tree.cypress();
            case STONE_PINE -> tree.stonePine();
            case BAOBAB -> tree.baobab();
            case FLAT_ACACIA -> tree.flatAcacia();
            case BROADLEAF_OAK -> tree.broadleafOak(variant >= VARIANTS - 2);
            case TALL_BIRCH -> tree.tallBirch();
        }
        tree.blocks.keySet().removeIf(key ->
                Math.abs(BlockPos.getX(key)) > Trees.REACH
                        || Math.abs(BlockPos.getZ(key)) > Trees.REACH
                        || BlockPos.getY(key) < 0
                        || BlockPos.getY(key) >= Trees.TALLEST);
        leafDistances(tree.blocks);
        if (species == Species.RAINFOREST_CANOPY) tree.hangVines();
        return new TreeShape(tree.blocks, species == Species.BROADLEAF_OAK || species == Species.TALL_BIRCH ? 0.02f : 0f);
    }

    // Species generators --------------------------------------------------------------------------

    /** Curved tropical palm (8-15 blocks) with radiating arched fronds and coconuts under the crown. */
    private void palm() {
        int height = 8 + random.nextInt(8);
        double leanAngle = random.nextDouble() * Math.PI * 2;
        double lean = 1.6 + random.nextDouble() * 2.2;
        double cos = Math.cos(leanAngle), sin = Math.sin(leanAngle);
        int px = 0, pz = 0;
        for (int y = 0; y < height; y++) {
            double u = y / (double) Math.max(1, height - 1);
            int x = y < 2 ? 0 : (int) Math.round(lean * u * u * cos);
            int z = y < 2 ? 0 : (int) Math.round(lean * u * u * sin);
            if (x != px || z != pz) {
                if (x != px && z != pz) {
                    putWood(px, y, pz, JUNGLE_WOOD);
                    putWood(x, y, pz, JUNGLE_WOOD);
                } else {
                    putWood(px, y, pz, JUNGLE_WOOD);
                }
            }
            putWood(x, y, z, x == px && z == pz ? JUNGLE_LOG : JUNGLE_WOOD);
            px = x;
            pz = z;
        }
        int tx = px, ty = height - 1, tz = pz;
        // Ensure top crown stem is an upright jungle log for cocoa attachment and crown hub.
        putWood(tx, ty, tz, JUNGLE_LOG);
        putWood(tx, ty - 1, tz, JUNGLE_LOG);
        putLeaf(tx, ty + 1, tz, JUNGLE_LEAVES);
        for (Direction d : HORIZONTAL) {
            putLeaf(tx + d.getStepX(), ty + 1, tz + d.getStepZ(), JUNGLE_LEAVES);
            putLeaf(tx + d.getStepX(), ty, tz + d.getStepZ(), JUNGLE_LEAVES);
        }
        // Radiating arched fronds, 6-connected so every frond tip stays well within leaf distance 6.
        int fronds = 6 + random.nextInt(2);
        double start = random.nextDouble() * Math.PI * 2;
        for (int i = 0; i < fronds; i++) {
            double angle = start + i * Math.PI * 2 / fronds + (random.nextDouble() - 0.5) * 0.25;
            double fc = Math.cos(angle), fs = Math.sin(angle);
            int reach = 4 + random.nextInt(2);
            int lx = tx, ly = ty + 1, lz = tz;
            for (int s = 1; s <= reach; s++) {
                int nx = tx + (int) Math.round(s * fc);
                int nz = tz + (int) Math.round(s * fs);
                int ny = s <= 2 ? ty + 1 : s == 3 ? ty : ty - 1;
                connectLeaf(lx, ly, lz, nx, ny, nz, JUNGLE_LEAVES);
                // Slight side leaflets in the middle of the arch for natural frond width.
                if (s == 2) {
                    int sx = (int) Math.round(-fs), sz = (int) Math.round(fc);
                    if (random.nextBoolean()) putLeaf(nx + sx, ny, nz + sz, JUNGLE_LEAVES);
                }
                lx = nx;
                ly = ny;
                lz = nz;
            }
        }
        // Mature coconuts tucked under the crown heart on open faces of the top log.
        int coconuts = 2 + random.nextInt(2);
        int offset = random.nextInt(4);
        for (int i = 0; i < 4 && coconuts > 0; i++) {
            Direction d = HORIZONTAL[(offset + i) & 3];
            long pos = BlockPos.asLong(tx + d.getStepX(), ty - 1, tz + d.getStepZ());
            if (!blocks.containsKey(pos)) {
                blocks.put(pos, COCOA.setValue(CocoaBlock.FACING, d.getOpposite()));
                coconuts--;
            }
        }
    }

    /** Tall buttressed emergent rainforest tree (16-26 blocks) with multi-tiered umbrella crown and vines. */
    private void rainforestCanopy() {
        int height = 16 + random.nextInt(11);
        // 2x2 sturdy trunk core.
        for (int y = 0; y < height - 2; y++) {
            putWood(0, y, 0, JUNGLE_LOG);
            putWood(1, y, 0, JUNGLE_LOG);
            putWood(0, y, 1, JUNGLE_LOG);
            putWood(1, y, 1, JUNGLE_LOG);
        }
        // Flared buttress roots around the lower trunk.
        int roots = 4 + random.nextInt(2);
        double rootStart = random.nextDouble() * Math.PI * 2;
        for (int i = 0; i < roots; i++) {
            double a = rootStart + i * Math.PI * 2 / roots + (random.nextDouble() - 0.5) * 0.4;
            double reach = 2.4 + random.nextDouble() * 1.4;
            int rootHeight = 3 + random.nextInt(3);
            for (int y = 0; y < rootHeight; y++) {
                double r = reach * (1 - y / (double) rootHeight);
                int steps = (int) Math.ceil(r * 2);
                for (int s = 1; s <= steps; s++) {
                    double d = r * s / steps;
                    int x = (int) Math.round(0.5 + d * Math.cos(a));
                    int z = (int) Math.round(0.5 + d * Math.sin(a));
                    putWood(x, y, z, JUNGLE_WOOD);
                }
            }
        }
        // Mid-canopy secondary boughs for layered rainforest depth.
        int midY = (int) (height * (0.64 + random.nextDouble() * 0.08));
        int midCount = 3 + random.nextInt(2);
        double midAngle = random.nextDouble() * Math.PI * 2;
        for (int i = 0; i < midCount; i++) {
            double a = midAngle + i * Math.PI * 2 / midCount + (random.nextDouble() - 0.5) * 0.45;
            double reach = 4.2 + random.nextDouble() * 1.8;
            int endX = (int) Math.round(0.5 + reach * Math.cos(a));
            int endZ = (int) Math.round(0.5 + reach * Math.sin(a));
            int endY = midY + 1 + random.nextInt(2);
            limb(0, midY, 0, endX, endY, endZ, JUNGLE_WOOD);
            pad(endX, endY + 1, endZ, 2.8 + random.nextDouble() * 0.7, 1.5, 0.55, JUNGLE_LEAVES);
        }
        // Main emergent umbrella crown: spreading upswept boughs with broad flattened foliage domes.
        int crownY = height - 4;
        int boughs = 5 + random.nextInt(3);
        double topAngle = midAngle + 0.5;
        for (int i = 0; i < boughs; i++) {
            double a = topAngle + i * Math.PI * 2 / boughs + (random.nextDouble() - 0.5) * 0.35;
            double reach = 5.5 + random.nextDouble() * 3.0;
            int endX = (int) Math.round(0.5 + reach * Math.cos(a));
            int endZ = (int) Math.round(0.5 + reach * Math.sin(a));
            int endY = height - 2 + random.nextInt(3);
            limb(0, crownY, 0, endX, endY, endZ, JUNGLE_WOOD);
            pad(endX, endY + 1, endZ, 3.3 + random.nextDouble() * 0.9, 1.8, 0.55, JUNGLE_LEAVES);
            // Mid-bough foliage pad so the umbrella forms a continuous canopy.
            int mx = (int) Math.round(0.5 + reach * 0.52 * Math.cos(a));
            int mz = (int) Math.round(0.5 + reach * 0.52 * Math.sin(a));
            pad(mx, endY + 1, mz, 2.7 + random.nextDouble() * 0.6, 1.6, 0.5, JUNGLE_LEAVES);
        }
        // Central emergent cap over the trunk hub.
        putWood(0, height - 2, 0, JUNGLE_WOOD);
        putWood(1, height - 2, 1, JUNGLE_WOOD);
        pad(0.5, height, 0.5, 4.0 + random.nextDouble() * 0.8, 2.1, 0.55, JUNGLE_LEAVES);
    }

    /** Narrow spire-shaped boreal spruce/pine (12-22 blocks) with drooping tiered whorls and needle tip. */
    private void borealPine() {
        int height = 12 + random.nextInt(11);
        for (int y = 0; y < height - 2; y++) putWood(0, y, 0, SPRUCE_LOG);
        // Subtle root flare on the tallest boreal pines.
        if (height >= 17) {
            for (Direction d : HORIZONTAL) {
                if (random.nextInt(3) != 0) putWood(d.getStepX(), 0, d.getStepZ(), SPRUCE_WOOD);
            }
        }
        int crownBase = Math.max(3, (int) Math.round(height * (0.22 + random.nextDouble() * 0.10)));
        double maxRadius = 2.8 + (height - 12) * 0.14 + random.nextDouble() * 0.6;
        double phase = random.nextDouble() * Math.PI * 2;
        for (int y = crownBase; y <= height - 3; y++) {
            double u = (y - crownBase) / (double) Math.max(1, height - 3 - crownBase);
            // Tiered whorls: fuller every second or third block, tucked narrower between whorls.
            boolean major = (y - crownBase) % 2 == 0;
            double tier = major ? 1.0 : 0.62;
            double r = Math.max(1.0, maxRadius * Math.pow(1 - u, 0.85) * tier);
            int arms = major ? (r > 2.6 ? 6 : 4) : 4;
            if (major && r >= 2.8) {
                // Short interior branch stubs keep wider lower whorls solidly connected to wood.
                for (Direction d : HORIZONTAL) {
                    if (random.nextInt(3) != 0) putWood(d.getStepX(), y, d.getStepZ(), SPRUCE_WOOD);
                }
            }
            int reach = (int) Math.ceil(r + 0.5);
            for (int dz = -reach; dz <= reach; dz++) for (int dx = -reach; dx <= reach; dx++) {
                if (dx == 0 && dz == 0) continue;
                double dist = Math.hypot(dx, dz);
                double angle = Math.atan2(dz, dx);
                double lobe = 0.82 + 0.25 * Math.cos(arms * angle + phase + y * 1.1);
                if (dist > r * lobe) continue;
                putLeaf(dx, y, dz, SPRUCE_LEAVES);
                // Droop outer needle tips slightly downward on major whorls.
                if (major && dist >= r * 0.65 && y > crownBase && random.nextInt(3) != 0)
                    putLeaf(dx, y - 1, dz, SPRUCE_LEAVES);
            }
        }
        // Crisp needle spire tip with no bare log visible from above.
        for (Direction d : HORIZONTAL) putLeaf(d.getStepX(), height - 2, d.getStepZ(), SPRUCE_LEAVES);
        putLeaf(0, height - 2, 0, SPRUCE_LEAVES);
        putLeaf(0, height - 1, 0, SPRUCE_LEAVES);
        putLeaf(0, height, 0, SPRUCE_LEAVES);
    }

    /** Slender columnar Mediterranean cypress (8-14 blocks) tapering to a candle-flame point. */
    private void cypress() {
        int height = 8 + random.nextInt(7);
        int topLog = height - 3;
        for (int y = 0; y <= topLog; y++) putWood(0, y, 0, DARK_OAK_LOG);
        int crownBase = 1 + (height >= 12 ? 1 : 0);
        for (int y = crownBase; y < height; y++) {
            double u = (y - crownBase) / (double) Math.max(1, height - 1 - crownBase);
            // Flame profile: full in the lower third, narrowing steadily to a pointed spire tip.
            double r = u < 0.25 ? 1.2 + u * 1.4 : 1.55 * Math.pow(Math.max(0, 1 - (u - 0.25) / 0.75), 0.75);
            int reach = (int) Math.ceil(r);
            for (int dz = -reach; dz <= reach; dz++) for (int dx = -reach; dx <= reach; dx++) {
                double dist = Math.hypot(dx, dz);
                if (dist > r + 0.15) continue;
                // Slight natural flutter on outer corners so it reads organic, not boxy.
                if (Math.abs(dx) == 1 && Math.abs(dz) == 1 && u > 0.55 && random.nextBoolean()) continue;
                putLeaf(dx, y, dz, SPRUCE_LEAVES);
            }
        }
        putLeaf(0, height - 1, 0, SPRUCE_LEAVES);
        putLeaf(0, height, 0, SPRUCE_LEAVES);
    }

    /** Mediterranean umbrella / stone pine (11-17 blocks) with bare stem and wide flat-domed crown. */
    private void stonePine() {
        int height = 11 + random.nextInt(7);
        int forkY = (int) Math.round(height * (0.58 + random.nextDouble() * 0.08));
        double swayAngle = random.nextDouble() * Math.PI * 2;
        double sway = random.nextDouble() * 1.1;
        int px = 0, pz = 0;
        for (int y = 0; y <= forkY; y++) {
            double u = y / (double) Math.max(1, forkY);
            int x = (int) Math.round(sway * u * Math.cos(swayAngle));
            int z = (int) Math.round(sway * u * Math.sin(swayAngle));
            if (x != px || z != pz) putWood(px, y, pz, SPRUCE_WOOD);
            putWood(x, y, z, x == px && z == pz ? SPRUCE_LOG : SPRUCE_WOOD);
            px = x;
            pz = z;
        }
        // Occasional small shed branch stub below the umbrella crown.
        if (random.nextBoolean()) {
            Direction stub = HORIZONTAL[random.nextInt(4)];
            putWood(px + stub.getStepX(), Math.max(3, forkY - 2), pz + stub.getStepZ(), SPRUCE_WOOD);
        }
        // Radiating upswept limbs forming the ribs of the umbrella crown.
        int limbs = 4 + random.nextInt(2);
        double start = random.nextDouble() * Math.PI * 2;
        int canopyY = height - 2;
        double umbrellaRadius = 4.2 + random.nextDouble() * 1.6;
        for (int i = 0; i < limbs; i++) {
            double a = start + i * Math.PI * 2 / limbs + (random.nextDouble() - 0.5) * 0.35;
            double reach = umbrellaRadius * (0.68 + random.nextDouble() * 0.16);
            int ex = px + (int) Math.round(reach * Math.cos(a));
            int ez = pz + (int) Math.round(reach * Math.sin(a));
            limb(px, forkY, pz, ex, canopyY - 1, ez, SPRUCE_WOOD);
            pad(ex, canopyY, ez, 2.4 + random.nextDouble() * 0.5, 1.4, 0.35, SPRUCE_LEAVES);
        }
        // Central umbrella dome tying all limbs into one wide, flat-bottomed pine canopy.
        limb(px, forkY, pz, px, canopyY, pz, SPRUCE_WOOD);
        pad(px, canopyY + 0.4, pz, umbrellaRadius * 0.92, 1.9, 0.3, SPRUCE_LEAVES);
    }

    /** Savanna baobab (10-15 blocks) with thick bottle-shaped silvery trunk and stubby crown branches. */
    private void baobab() {
        int height = 10 + random.nextInt(6);
        int shoulder = height - 4;
        double baseRadius = 2.1 + random.nextDouble() * 0.6;
        double phase = random.nextDouble() * Math.PI * 2;
        // Bottle-shaped trunk: broad at foot, subtly swollen in lower-mid bole, tapering at crown shoulder.
        for (int y = 0; y <= shoulder; y++) {
            double u = y / (double) Math.max(1, shoulder);
            double r = baseRadius * (1.04 + 0.08 * Math.sin(u * Math.PI) - 0.24 * u * u);
            int reach = (int) Math.ceil(r + 0.5);
            for (int dz = -reach; dz <= reach; dz++) for (int dx = -reach; dx <= reach; dx++) {
                double d = Math.hypot(dx, dz);
                double flute = 0.18 * Math.sin(5 * Math.atan2(dz, dx) + phase);
                if (d <= r + flute) {
                    boolean core = d < r - 0.85;
                    putWood(dx, y, dz, core ? ACACIA_LOG : ACACIA_WOOD);
                }
            }
        }
        // Stubby root-like crown limbs fanning outward and upward from the top of the bottle trunk.
        int limbs = 5 + random.nextInt(3);
        double start = random.nextDouble() * Math.PI * 2;
        for (int i = 0; i < limbs; i++) {
            double a = start + i * Math.PI * 2 / limbs + (random.nextDouble() - 0.5) * 0.4;
            double reach = 3.2 + random.nextDouble() * 2.2;
            int ex = (int) Math.round(reach * Math.cos(a));
            int ez = (int) Math.round(reach * Math.sin(a));
            int ey = shoulder + 2 + random.nextInt(3);
            int sx = (int) Math.round(Math.cos(a));
            int sz = (int) Math.round(Math.sin(a));
            limb(sx, shoulder, sz, ex, ey, ez, ACACIA_WOOD);
            // Sparse flattened tufts of foliage perched at branch ends.
            pad(ex, ey + 1, ez, 2.0 + random.nextDouble() * 0.6, 1.2, 0.4, ACACIA_LEAVES);
        }
        // Small central crown tuft.
        limb(0, shoulder, 0, 0, shoulder + 2, 0, ACACIA_WOOD);
        pad(0, shoulder + 3, 0, 2.2 + random.nextDouble() * 0.5, 1.2, 0.4, ACACIA_LEAVES);
    }

    /** Savanna flat-top umbrella acacia (8-13 blocks) with sweeping multi-forked limbs and table-flat pads. */
    private void flatAcacia() {
        int height = 8 + random.nextInt(6);
        int forkY = 3 + random.nextInt(3);
        double leanAngle = random.nextDouble() * Math.PI * 2;
        int fx = (int) Math.round(Math.cos(leanAngle) * (0.6 + random.nextDouble() * 0.8));
        int fz = (int) Math.round(Math.sin(leanAngle) * (0.6 + random.nextDouble() * 0.8));
        limb(0, 0, 0, fx, forkY, fz, ACACIA_WOOD);
        putWood(0, 0, 0, ACACIA_LOG);
        putWood(0, 1, 0, ACACIA_LOG);

        // Main wide sweeping branch to the top flat-table umbrella.
        int forks = 3 + random.nextInt(2);
        double start = leanAngle + (random.nextDouble() - 0.5) * 0.6;
        for (int i = 0; i < forks; i++) {
            double a = start + i * Math.PI * 2 / forks + (random.nextDouble() - 0.5) * 0.4;
            boolean main = i == 0;
            double reach = main ? 2.4 + random.nextDouble() * 1.4 : 3.4 + random.nextDouble() * 2.0;
            int ex = fx + (int) Math.round(reach * Math.cos(a));
            int ez = fz + (int) Math.round(reach * Math.sin(a));
            int ey = main ? height - 1 : height - 2 - random.nextInt(2);
            limb(fx, forkY, fz, ex, ey, ez, ACACIA_WOOD);
            // Sub-strut just below the wide main pad so distant edge leaves stay within decay distance 6.
            if (main) {
                double padR = 4.2 + random.nextDouble() * 1.3;
                for (int s = 0; s < 3; s++) {
                    double sa = a + (s - 1) * 2.1;
                    limb(ex, ey - 1, ez, ex + (int) Math.round(2.0 * Math.cos(sa)), ey, ez + (int) Math.round(2.0 * Math.sin(sa)), ACACIA_WOOD);
                }
                flatTable(ex, ey + 1, ez, padR, ACACIA_LEAVES);
            } else {
                flatTable(ex, ey + 1, ez, 2.6 + random.nextDouble() * 1.1, ACACIA_LEAVES);
            }
        }
    }

    /** Larger spreading temperate broadleaf oak / dark-oak (10-16 blocks) with gnarled limbs and billowing crown. */
    private void broadleafOak(boolean dark) {
        BlockState log = dark ? DARK_OAK_LOG : OAK_LOG;
        BlockState wood = dark ? DARK_OAK_WOOD : OAK_WOOD;
        BlockState leaves = dark ? DARK_OAK_LEAVES : OAK_LEAVES;

        int height = 10 + random.nextInt(7);
        int crownBase = Math.max(4, (int) Math.round(height * (0.36 + random.nextDouble() * 0.08)));
        // Sturdy trunk with root flare on mature trees.
        for (int y = 0; y <= crownBase + 2; y++) putWood(0, y, 0, log);
        if (height >= 12) {
            for (Direction d : HORIZONTAL) {
                if (random.nextInt(4) != 0) putWood(d.getStepX(), 0, d.getStepZ(), wood);
                if (height >= 14 && random.nextInt(3) == 0) putWood(d.getStepX(), 1, d.getStepZ(), wood);
            }
        }
        // Spreading main boughs forming a full, multi-lobed broadleaf crown.
        int boughs = 4 + random.nextInt(3);
        double start = random.nextDouble() * Math.PI * 2;
        for (int i = 0; i < boughs; i++) {
            double a = start + i * Math.PI * 2 / boughs + (random.nextDouble() - 0.5) * 0.45;
            double reach = 3.2 + random.nextDouble() * 2.3;
            int sy = crownBase + random.nextInt(3);
            int ex = (int) Math.round(reach * Math.cos(a));
            int ez = (int) Math.round(reach * Math.sin(a));
            int ey = Math.min(height - 3, sy + 2 + random.nextInt(3));
            limb(0, sy, 0, ex, ey, ez, wood);
            pad(ex, ey + 1, ez, 2.5 + random.nextDouble() * 0.9, 2.1, 0.72, leaves);
        }
        // Upper leader branches and full rounded crown dome.
        int topY = height - 3;
        limb(0, crownBase + 1, 0, 0, topY, 0, wood);
        for (int i = 0; i < 2; i++) {
            double a = start + 1.1 + i * Math.PI + (random.nextDouble() - 0.5) * 0.6;
            int ex = (int) Math.round((1.8 + random.nextDouble()) * Math.cos(a));
            int ez = (int) Math.round((1.8 + random.nextDouble()) * Math.sin(a));
            limb(0, topY - 1, 0, ex, topY + 1, ez, wood);
            pad(ex, topY + 1, ez, 2.4 + random.nextDouble() * 0.6, 1.9, 0.65, leaves);
        }
        pad(0, topY + 1.5, 0, 2.9 + random.nextDouble() * 0.7, 2.2, 0.65, leaves);
    }

    /** Slender multi-branch tall birch / aspen (10-16 blocks) with airy upright-oval crown. */
    private void tallBirch() {
        int height = 10 + random.nextInt(7);
        int crownBase = Math.max(4, (int) Math.round(height * (0.38 + random.nextDouble() * 0.10)));
        double swayAngle = random.nextDouble() * Math.PI * 2;
        double sway = random.nextBoolean() ? 0.9 : 0.0;
        int px = 0, pz = 0;
        for (int y = 0; y < height - 2; y++) {
            double u = y / (double) Math.max(1, height - 3);
            int x = y < 3 ? 0 : (int) Math.round(sway * u * Math.cos(swayAngle));
            int z = y < 3 ? 0 : (int) Math.round(sway * u * Math.sin(swayAngle));
            if (x != px || z != pz) putWood(px, y, pz, BIRCH_WOOD);
            putWood(x, y, z, x == px && z == pz ? BIRCH_LOG : BIRCH_WOOD);
            px = x;
            pz = z;
        }
        // Steeply ascending slender side limbs with light clustered foliage.
        int branches = 3 + random.nextInt(3);
        double start = random.nextDouble() * Math.PI * 2;
        for (int i = 0; i < branches; i++) {
            double a = start + i * Math.PI * 2 / branches + (random.nextDouble() - 0.5) * 0.5;
            int sy = crownBase + (int) ((height - 5 - crownBase) * (i / (double) branches));
            double reach = 1.8 + random.nextDouble() * 1.4;
            int ex = px + (int) Math.round(reach * Math.cos(a));
            int ez = pz + (int) Math.round(reach * Math.sin(a));
            int ey = Math.min(height - 3, sy + 2 + random.nextInt(2));
            limb(px, sy, pz, ex, ey, ez, BIRCH_WOOD);
            pad(ex, ey + 0.5, ez, 1.9 + random.nextDouble() * 0.5, 1.7, 0.7, BIRCH_LEAVES);
        }
        // Tapered airy oval upper crown.
        pad(px, height - 3, pz, 2.2 + random.nextDouble() * 0.5, 2.4, 0.7, BIRCH_LEAVES);
        putLeaf(px, height - 1, pz, BIRCH_LEAVES);
        putLeaf(px, height, pz, BIRCH_LEAVES);
    }

    // Geometry & foliage helpers ------------------------------------------------------------------

    private void putWood(int x, int y, int z, BlockState wood) {
        if (y >= 0 && y < Trees.TALLEST && Math.abs(x) <= Trees.REACH && Math.abs(z) <= Trees.REACH)
            blocks.put(BlockPos.asLong(x, y, z), wood);
    }

    private void putLeaf(int x, int y, int z, BlockState leaf) {
        if (y >= 1 && y < Trees.TALLEST && Math.abs(x) <= Trees.REACH && Math.abs(z) <= Trees.REACH)
            blocks.putIfAbsent(BlockPos.asLong(x, y, z), leaf);
    }

    /** 6-connected leaf segment so arching palm fronds never break diagonally. */
    private void connectLeaf(int x0, int y0, int z0, int x1, int y1, int z1, BlockState leaf) {
        int x = x0, y = y0, z = z0;
        while (x != x1 || y != y1 || z != z1) {
            if (y != y1) y += Integer.compare(y1, y);
            else if (Math.abs(x1 - x) >= Math.abs(z1 - z)) x += Integer.compare(x1, x);
            else z += Integer.compare(z1, z);
            putLeaf(x, y, z, leaf);
        }
    }

    /** 6-connected wooden limb from (x0, y0, z0) to (x1, y1, z1), bridging diagonal steps orthogonally. */
    private void limb(int x0, int y0, int z0, int x1, int y1, int z1, BlockState wood) {
        int steps = Math.max(1, (int) Math.ceil(Math.sqrt((x1 - x0) * (x1 - x0) + (y1 - y0) * (y1 - y0) + (z1 - z0) * (z1 - z0)) * 2.5));
        int px = x0, py = y0, pz = z0;
        putWood(px, py, pz, wood);
        for (int i = 1; i <= steps; i++) {
            double t = i / (double) steps;
            int nx = (int) Math.round(x0 + (x1 - x0) * t);
            int ny = (int) Math.round(y0 + (y1 - y0) * t);
            int nz = (int) Math.round(z0 + (z1 - z0) * t);
            while (px != nx || py != ny || pz != nz) {
                if (py != ny) py += Integer.compare(ny, py);
                else if (px != nx) px += Integer.compare(nx, px);
                else pz += Integer.compare(nz, pz);
                putWood(px, py, pz, wood);
            }
        }
    }

    /** Noisy flattened ellipsoid foliage clump around (cx, cy, cz). */
    private void pad(double cx, double cy, double cz, double wide, double high, double under, BlockState leaves) {
        double below = Math.max(0.7, high * under);
        int x0 = (int) Math.round(cx), y0 = (int) Math.round(cy), z0 = (int) Math.round(cz);
        int rx = (int) Math.ceil(wide + 1), up = (int) Math.ceil(high + 1), dn = (int) Math.ceil(below + 1);
        for (int dy = -dn; dy <= up; dy++) for (int dz = -rx; dz <= rx; dz++) for (int dx = -rx; dx <= rx; dx++) {
            int x = x0 + dx, y = y0 + dy, z = z0 + dz;
            if (y < 1) continue;
            double kx = (x - cx) / wide, kz = (z - cz) / wide, ky = (y - cy) / (y >= cy ? high : below);
            double n = noise(x / 2.4, y / 2.4, z / 2.4);
            if (kx * kx + kz * kz + ky * ky + 0.24 * n <= 1.0) putLeaf(x, y, z, leaves);
        }
    }

    /** Crisp razor-flat savanna umbrella foliage sheet: wide bottom tier with a smaller central cap. */
    private void flatTable(int cx, int cy, int cz, double radius, BlockState leaves) {
        int r = (int) Math.ceil(radius + 1);
        for (int dz = -r; dz <= r; dz++) for (int dx = -r; dx <= r; dx++) {
            double d = Math.hypot(dx, dz);
            double edge = radius + 0.45 * noise((cx + dx) / 2.3, cy / 2.3, (cz + dz) / 2.3);
            if (d <= edge) putLeaf(cx + dx, cy, cz + dz, leaves);
            if (d <= radius * 0.62 + 0.35 * noise((cx + dx) / 2.1, (cy + 1) / 2.1, (cz + dz) / 2.1))
                putLeaf(cx + dx, cy + 1, cz + dz, leaves);
        }
    }

    /** Hangs natural vine curtains from outer leaves of an emergent rainforest canopy. */
    private void hangVines() {
        Long2ObjectOpenHashMap<BlockState> vines = new Long2ObjectOpenHashMap<>();
        int count = 0;
        for (var entry : blocks.long2ObjectEntrySet()) {
            if (count >= 12) break;
            if (!(entry.getValue().getBlock() instanceof LeavesBlock)) continue;
            long key = entry.getLongKey();
            int x = BlockPos.getX(key), y = BlockPos.getY(key), z = BlockPos.getZ(key);
            if (y < 7) continue;
            for (Direction d : HORIZONTAL) {
                int vx = x + d.getStepX(), vz = z + d.getStepZ();
                if (Math.abs(vx) > Trees.REACH || Math.abs(vz) > Trees.REACH) continue;
                if (blocks.containsKey(BlockPos.asLong(vx, y, vz)) || vines.containsKey(BlockPos.asLong(vx, y, vz))) continue;
                if (random.nextInt(14) != 0) continue;
                BlockState state = VINE.setValue(VineBlock.getPropertyForFace(d.getOpposite()), true);
                int length = 2 + random.nextInt(5);
                for (int k = 0; k < length && y - k >= 2; k++) {
                    long pos = BlockPos.asLong(vx, y - k, vz);
                    if (blocks.containsKey(pos) || vines.containsKey(pos)) break;
                    vines.put(pos, state);
                }
                count++;
                break;
            }
        }
        blocks.putAll(vines);
    }

    /** Sets each leaf's BFS distance (1..6) from logs/wood and removes any unreachable leaf. */
    private static void leafDistances(Long2ObjectOpenHashMap<BlockState> blocks) {
        Long2IntOpenHashMap distance = new Long2IntOpenHashMap();
        LongArrayFIFOQueue queue = new LongArrayFIFOQueue();
        for (var entry : blocks.long2ObjectEntrySet()) {
            if (entry.getValue().getBlock() instanceof RotatedPillarBlock && BlockPos.getY(entry.getLongKey()) >= 0) {
                distance.put(entry.getLongKey(), 0);
                queue.enqueue(entry.getLongKey());
            }
        }
        while (!queue.isEmpty()) {
            long at = queue.dequeueLong();
            int next = distance.get(at) + 1;
            if (next > 6) continue;
            for (Direction side : Direction.values()) {
                long n = BlockPos.offset(at, side);
                BlockState there = blocks.get(n);
                if (there == null || !(there.getBlock() instanceof LeavesBlock) || distance.containsKey(n)) continue;
                distance.put(n, next);
                queue.enqueue(n);
            }
        }
        var entries = blocks.long2ObjectEntrySet().fastIterator();
        while (entries.hasNext()) {
            var entry = entries.next();
            if (!(entry.getValue().getBlock() instanceof LeavesBlock)) continue;
            int d = distance.getOrDefault(entry.getLongKey(), 7);
            if (d > 6) entries.remove();
            else entry.setValue(entry.getValue().setValue(LeavesBlock.DISTANCE, d).setValue(LeavesBlock.PERSISTENT, false));
        }
    }

    /** Smooth value noise in [-1, 1] for organic foliage billowing. */
    private double noise(double x, double y, double z) {
        int x0 = (int) Math.floor(x), y0 = (int) Math.floor(y), z0 = (int) Math.floor(z);
        double fx = x - x0, fy = y - y0, fz = z - z0, value = 0;
        fx = fx * fx * (3 - 2 * fx);
        fy = fy * fy * (3 - 2 * fy);
        fz = fz * fz * (3 - 2 * fz);
        for (int i = 0; i < 8; i++) {
            int dx = i & 1, dy = i >> 1 & 1, dz = i >> 2;
            value += (dx == 1 ? fx : 1 - fx) * (dy == 1 ? fy : 1 - fy) * (dz == 1 ? fz : 1 - fz) * corner(x0 + dx, y0 + dy, z0 + dz);
        }
        return value;
    }

    private double corner(int x, int y, int z) {
        int n = x * 374761393 + y * 668265263 + z * 0x7FFFFFFF + salt * 1274126177;
        n = (n ^ n >>> 13) * 1274126177;
        return ((n ^ n >>> 16) & 0xFFFF) / 32767.5 - 1;
    }
}
