package io.earthplatform.minecraft.cubic.compat;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.earthplatform.minecraft.cubic.CubeWorld;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ModCompatMixinsTest {
    private static Set<String> names(JsonArray array) {
        Set<String> result = new HashSet<>();
        for (JsonElement element : array) result.add(element.getAsString());
        return result;
    }

    @Test void compatMixinsRegisteredInExpectedSections() throws Exception {
        try (var input = getClass().getClassLoader().getResourceAsStream("earthplatform.cubic.mixins.json")) {
            assertNotNull(input);
            JsonObject root = JsonParser.parseString(new String(input.readAllBytes(), StandardCharsets.UTF_8))
                    .getAsJsonObject();
            Set<String> common = names(root.getAsJsonArray("mixins"));
            Set<String> client = names(root.getAsJsonArray("client"));

            for (String m : List.of("compat.ImmersiveVehiclesWorldMixin", "compat.ImmersiveVehiclesEntityMixin")) {
                assertTrue(common.contains(m), "Expected common mixin " + m);
                assertFalse(client.contains(m), "Common mixin must not also be in client: " + m);
            }
            for (String m : List.of("compat.RagdollifiedTickMixin", "compat.BloodyBitsSpatterMixin",
                    "compat.WakesCubicHeightMixin")) {
                assertTrue(client.contains(m), "Expected client mixin " + m);
                assertFalse(common.contains(m), "Client mixin must not be in common: " + m);
            }
        }
    }

    @Test void optionalMixinsGatesAbsentModTargets() {
        OptionalMixins plugin = new OptionalMixins();
        String pkg = "io.earthplatform.minecraft.cubic.mixin.compat.";
        assertFalse(plugin.shouldApplyMixin("com.raiiiden.ragdollified.client.ClientRagdollManager", pkg + "RagdollifiedTickMixin"));
        assertFalse(plugin.shouldApplyMixin("com.cravencraft.bloodybits.client.particle.spatter.BloodSpatterParticle", pkg + "BloodyBitsSpatterMixin"));
        assertFalse(plugin.shouldApplyMixin("com.leclowndu93150.wakes.simulation.WakeHandler", pkg + "WakesCubicHeightMixin"));
        assertFalse(plugin.shouldApplyMixin("mcinterface1211.WrapperWorld", pkg + "ImmersiveVehiclesWorldMixin"));
        assertFalse(plugin.shouldApplyMixin("mcinterface1211.BuilderEntityExisting", pkg + "ImmersiveVehiclesEntityMixin"));
        assertTrue(plugin.shouldApplyMixin("net.minecraft.world.level.Level", "io.earthplatform.minecraft.cubic.mixin.CubicLevelMixin"));
    }

    @Test void bloodyBitsOriginRebaseEliminatesFloat32Cancellation() {
        Vec3 camera = new Vec3(12_500_000.375, 64.25, -8_400_000.625);
        BlockPos block = new BlockPos(12_500_001, 64, -8_400_002);

        // Unshifted float32 loses sub-block precision at 12.5M blocks.
        float rawX = (float) block.getX() - (float) camera.x;
        double exactX = block.getX() - camera.x;
        assertTrue(Math.abs(rawX - exactX) > 0.2, "Sanity check: unshifted float32 should quantize at 12.5M");

        int ox = BloodyBitsCompat.originX(camera);
        int oy = BloodyBitsCompat.originY(camera);
        int oz = BloodyBitsCompat.originZ(camera);
        assertEquals(12_500_000, ox);
        assertEquals(64, oy);
        assertEquals(-8_400_001, oz);
        assertFalse(BloodyBitsCompat.isZeroOrigin(ox, oy, oz));

        Vec3 shiftedCam = camera.subtract(ox, oy, oz);
        BlockPos shiftedPos = block.offset(-ox, -oy, -oz);
        float rebasedX = (float) shiftedPos.getX() - (float) shiftedCam.x;
        float rebasedY = (float) shiftedPos.getY() - (float) shiftedCam.y;
        float rebasedZ = (float) shiftedPos.getZ() - (float) shiftedCam.z;

        assertEquals(block.getX() - camera.x, rebasedX, 1e-5);
        assertEquals(block.getY() - camera.y, rebasedY, 1e-5);
        assertEquals(block.getZ() - camera.z, rebasedZ, 1e-5);
        assertTrue(BloodyBitsCompat.isZeroOrigin(0, 0, 0));
    }

    private record FakeNode(int y, boolean valid) {}

    private static final class FakeTree {
        final int y;
        final List<FakeNode> nodes = new ArrayList<>();
        boolean alive = true;
        int recolored;
        int pruned;
        int cleaned;
        Runnable onSolve;

        FakeTree(int y) { this.y = y; }
    }

    private static final class FakeSplash {}

    @Test void wakesSparseLayersHandlesArbitraryCubicElevations() {
        AtomicInteger created = new AtomicInteger();
        WakesCompat.Ops<FakeTree, FakeNode> ops = new WakesCompat.Ops<>() {
            @Override public FakeTree newTree(int y) { created.incrementAndGet(); return new FakeTree(y); }
            @Override public Queue<FakeNode> newQueue() { return new ArrayDeque<>(); }
            @Override public int nodeY(FakeNode node) { return node.y(); }
            @Override public boolean validPos(FakeNode node, Level world) { return node.valid(); }
            @Override public void tickAdvance(FakeTree tree, Object handler) {}
            @Override public boolean tickSolve(FakeTree tree, Object handler) {
                if (tree.onSolve != null) {
                    Runnable action = tree.onSolve;
                    tree.onSolve = null;
                    action.run();
                }
                return tree.alive;
            }
            @Override public void insertNode(FakeTree tree, FakeNode node) { tree.nodes.add(node); }
            @SuppressWarnings("unchecked")
            @Override public void query(FakeTree tree, Frustum frustum, ArrayList<?> out, Class<?> type) {
                ((ArrayList<Object>) out).add(tree);
            }
            @Override public void recolor(FakeTree tree) { tree.recolored++; }
            @Override public void prune(FakeTree tree) { tree.pruned++; }
            @Override public void cleanupArea(FakeTree tree, int minX, int minZ, int maxX, int maxZ) { tree.cleaned++; }
            @Override public boolean isSplashPlaneClass(Class<?> type) { return type == FakeSplash.class; }
        };

        var layers = new WakesCompat.SparseLayers<>(ops);

        // Out-of-range and resolution-reset inserts are ignored.
        layers.insert(new FakeNode(CubeWorld.MIN_Y - 1, true), null, false);
        layers.insert(new FakeNode(CubeWorld.MAX_Y + 1, true), null, false);
        layers.insert(new FakeNode(62, true), null, true);
        assertEquals(0, layers.activeLevels());

        // Real Earth water elevations: Dead Sea (-400), ocean (62), mountain lake (4800).
        layers.insert(new FakeNode(-400, true), null, false);
        layers.insert(new FakeNode(62, true), null, false);
        layers.insert(new FakeNode(4800, true), null, false);
        assertEquals(3, layers.activeLevels());
        assertTrue(layers.hasLevel(-400));
        assertTrue(layers.hasLevel(62));
        assertTrue(layers.hasLevel(4800));

        // Query returns all active sparse trees, but skips splash plane queries.
        ArrayList<Object> visible = new ArrayList<>();
        layers.queryVisible(null, visible, FakeTree.class);
        assertEquals(3, visible.size());
        ArrayList<Object> splashes = new ArrayList<>();
        layers.queryVisible(null, splashes, FakeSplash.class);
        assertTrue(splashes.isEmpty());

        // Re-entrant insert during tickSolve (flood fill) must not throw CME.
        FakeTree seaLevelTree = (FakeTree) visible.stream()
                .filter(t -> ((FakeTree) t).y == 62).findFirst().orElseThrow();
        seaLevelTree.onSolve = () -> layers.insert(new FakeNode(63, true), null, false);
        layers.tick(new Object(), false);
        assertEquals(4, layers.activeLevels());
        assertEquals(1, seaLevelTree.nodes.size());

        // Recolor and chunk cleanup visit every active layer.
        layers.recolorWakes();
        layers.cleanupChunk(0, 0, 16, 16);
        assertEquals(1, seaLevelTree.recolored);
        assertEquals(1, seaLevelTree.cleaned);

        // Dead tree with no pending inserts is reclaimed on tick.
        seaLevelTree.alive = false;
        layers.tick(new Object(), false);
        assertFalse(layers.hasLevel(62));
        assertEquals(3, layers.activeLevels());

        // Resolution-reset tick drops pending nodes; reset prunes and clears all layers.
        layers.insert(new FakeNode(62, true), null, false);
        layers.tick(new Object(), true);
        layers.reset();
        assertEquals(0, layers.activeLevels());
        assertEquals(4, created.get() - 1); // 4 unique trees (-400, 62, 4800, 63) + recreated 62 = 5
    }

    @Test void immersiveVehiclesAtmosphereDensityRealisticAcrossEarth() {
        double seaLevel = 63.0;
        // Broken 16-block carrier collapses density to < 2% just 200 m above sea level.
        assertTrue(ImmersiveVehiclesCompat.airDensity(263.0, seaLevel, 16L) < 0.02);

        // With ATMOSPHERE_HEIGHT = 4352 (half-density height = 8500 m):
        assertEquals(1.225, ImmersiveVehiclesCompat.airDensity(seaLevel, seaLevel, ImmersiveVehiclesCompat.ATMOSPHERE_HEIGHT), 1e-9);
        double andes = ImmersiveVehiclesCompat.airDensity(seaLevel + 5000.0, seaLevel, ImmersiveVehiclesCompat.ATMOSPHERE_HEIGHT);
        double everest = ImmersiveVehiclesCompat.airDensity(seaLevel + 8849.0, seaLevel, ImmersiveVehiclesCompat.ATMOSPHERE_HEIGHT);
        assertTrue(andes > 0.75 && andes < 0.90, "Expected realistic 5000 m density, got " + andes);
        assertTrue(everest > 0.50 && everest < 0.65, "Expected flyable Everest density, got " + everest);
    }

    @Test void immersiveVehiclesGroundScanStopsAtUnloadedSkyTop() {
        CubeWorld cubes = new CubeWorld(null) {
            @Override protected int baselineTop(int x, int z) { return 200; }
            @Override public void blockChanged(BlockPos pos) {}
        };
        assertTrue(ImmersiveVehiclesCompat.isEmptyForGroundScan(cubes, new BlockPos(10, 500, 10), true));
        assertFalse(ImmersiveVehiclesCompat.isEmptyForGroundScan(cubes, new BlockPos(10, 200, 10), true));
        assertFalse(ImmersiveVehiclesCompat.isEmptyForGroundScan(cubes, new BlockPos(10, 199, 10), true));
    }
}
