package io.earthplatform.minecraft.coregate;

import com.mojang.serialization.Lifecycle;
import io.earthplatform.core.CancellationToken;
import io.earthplatform.core.CubePos;
import io.earthplatform.core.CubeTickets;
import io.earthplatform.minecraft.enginebridge.AnalyticNativeCubeProvider;
import io.earthplatform.minecraft.enginebridge.NativeCollisionMotionFence;
import io.earthplatform.minecraft.enginebridge.NativeCollisionLevelAccess;
import io.earthplatform.minecraft.enginebridge.NativeCollisionView;
import io.earthplatform.minecraft.enginebridge.NativeCubeCodec;
import io.earthplatform.minecraft.network.EngineCubeSnapshot;
import io.earthplatform.minecraft.network.EnginePaletteContext;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipFile;
import net.minecraft.core.BlockPos;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.RegistrationInfo;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.CollisionGetter;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.BooleanOp;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import static org.junit.jupiter.api.Assertions.*;

/** Independent integration review. Descriptor checks are not a live transformed-client proof. */
class CoreCollisionIntegrationReviewTest {
    private static EnginePaletteContext palette;

    @BeforeAll static void registries() {
        var registry = new MappedRegistry<Biome>(Registries.BIOME, Lifecycle.stable());
        registry.register(Biomes.PLAINS, VanillaRegistries.createLookup().lookupOrThrow(Registries.BIOME)
                .getOrThrow(Biomes.PLAINS).value(), RegistrationInfo.BUILT_IN);
        palette = EnginePaletteContext.create(registry.freeze());
    }

    @Test void finiteCollisionFixtureIsExactlyFlatOutsideThePinnedCentralCube() {
        var nativeProvider = new AnalyticNativeCubeProvider(0, AnalyticNativeCubeProvider.Config.boundedCollision(128), palette);
        var flatProvider = new AnalyticNativeCubeProvider(0, AnalyticNativeCubeProvider.Config.flat(128), palette);
        assertNotEquals(nativeProvider.identity(), flatProvider.identity(), "The distinct generator identity must isolate durable storage");
        int different = 0;
        for (var pos : CubeTickets.box(new CubePos(0, 8, 0), 2, 2, 125)) {
            var actual = nativeProvider.load(pos, new CancellationToken()).join();
            var flat = flatProvider.load(pos, new CancellationToken()).join();
            for (int i = 0; i < CubePos.VOLUME; i++) {
                if (actual.get(i) != flat.get(i)) {
                    assertEquals(new CubePos(0, 8, 0), pos, "Far flat geometry must be exact outside actual native coverage");
                    different++;
                }
            }
        }
        assertEquals(65, different, "35 roof cells, 6 support cells, 24 wall cells");
    }

    @Test void nativeFixtureHasRealAirUnderItsRoofAndAnIndependentWall() {
        var provider = new AnalyticNativeCubeProvider(0, AnalyticNativeCubeProvider.Config.boundedCollision(128), palette);
        var cube = provider.load(new CubePos(0, 8, 0), new CancellationToken()).join();
        int air = provider.config().airState(), solid = provider.config().solidState();
        assertEquals(solid, cube.get(CubePos.index(4, 0, 5)));
        assertEquals(air, cube.get(CubePos.index(4, 1, 5)));
        assertEquals(air, cube.get(CubePos.index(4, 3, 5)));
        assertEquals(solid, cube.get(CubePos.index(4, 4, 5)));
        assertEquals(air, cube.get(CubePos.index(4, 5, 5)));
        assertEquals(solid, cube.get(CubePos.index(9, 3, 5)));
        assertEquals(air, cube.get(CubePos.index(9, 4, 5)));
    }

    /**
     * Characterizes pinned ServerGamePacketListenerImpl's decision AFTER player.move, deliberately
     * assuming the Entity.move injection cancelled it. The actual CollisionGetter iterator is used.
     * A separate packet-entry fence is necessary; a solid unknown view alone cannot freeze position.
     */
    @Test void vanillaPostMoveValidationStillAcceptsSmallMotionInsideTheSameUnavailableCells() {
        var view = fixedFixture();
        view.close();
        var access = new Access(view);
        var before = new AABB(4.2, 131, -1.8, 4.8, 132.8, -1.2);
        var delta = new Vec3(0.05, 0, 0.05);
        assertFalse(view.isCurrent());
        assertFalse(access.noCollision(before));
        assertTrue(vanillaAcceptsAfterCancelledMove(access, before, delta),
                "The all-barrier view does not itself stop the later packet absMoveTo");
    }

    @Test void vanillaPostMoveValidationDiscardsLargeVerticalErrorInsideKnownAir() {
        var view = fixedFixture();
        var access = new Access(view);
        var before = new AABB(4.2, 131, -1.8, 4.8, 132.8, -1.2);
        var delta = new Vec3(0, 8, 0);
        assertFalse(NativeCollisionMotionFence.bounded(before, delta));
        assertTrue(access.noCollision(before));
        assertTrue(access.noCollision(before.move(delta)));
        assertTrue(vanillaAcceptsAfterCancelledMove(access, before, delta),
                "A packet-entry bound must run before vanilla's post-move position acceptance");
    }

    @Test void serverPacketHookHasExactPinnedDescriptorAndRunsAfterThreadHandoffBeforeMovement() throws Exception {
        var target = nativeClass("net/minecraft/server/network/ServerGamePacketListenerImpl");
        var mixin = compiledMixin("CoreCollisionServerMoveMixin");
        var hook = mixin.methods.stream().filter(m -> m.name.equals("earthplatform$packetMotion")).findFirst().orElseThrow();
        var inject = annotation(hook, "Lorg/spongepowered/asm/mixin/injection/Inject;");
        String signature = (String) ((List<?>) value(inject, "method")).getFirst();
        var handler = target.methods.stream().filter(m -> (m.name + m.desc).equals(signature)).findFirst().orElseThrow();
        assertEquals(Boolean.TRUE, value(inject, "cancellable"));
        var at = (AnnotationNode) ((List<?>) value(inject, "at")).getFirst();
        assertEquals("INVOKE", value(at, "value"));
        assertEquals("AFTER", ((String[]) value(at, "shift"))[1]);
        String invocation = (String) value(at, "target");
        var calls = calls(handler);
        var handoffs = calls.stream().filter(i -> ("L" + i.owner + ";" + i.name + i.desc).equals(invocation)).toList();
        assertEquals(1, handoffs.size(), "The injection target must occur exactly once in the pinned engine");
        assertEquals("ensureRunningOnSameThread", handoffs.getFirst().name);
        int handoff = handler.instructions.indexOf(handoffs.getFirst());
        for (var call : calls) if (call.name.equals("move") || call.name.equals("absMoveTo"))
            assertTrue(handoff < handler.instructions.indexOf(call));
        assertTrue(calls(hook).stream().anyMatch(i -> i.name.equals("bounded")), "The packet hook must enforce bounded work");
        assertTrue(calls(hook).stream().anyMatch(i -> i.name.equals("teleport")), "Rejected movement must reconcile authoritative position");
        assertTrue(target.fields.stream().anyMatch(f -> f.name.equals("player") && f.desc.equals("Lnet/minecraft/server/level/ServerPlayer;")));
    }

    @Test void levelAndEntityCollisionHooksMatchPinnedMethodsAndAreRegistered() throws Exception {
        var pairs = Map.of("CoreCollisionLevelMixin", "net/minecraft/world/level/Level",
                "CoreCollisionMotionMixin", "net/minecraft/world/entity/Entity");
        for (var entry : pairs.entrySet()) {
            var target = nativeClass(entry.getValue());
            var mixin = compiledMixin(entry.getKey());
            int count = 0;
            for (var method : mixin.methods) {
                if (method.visibleAnnotations == null) continue;
                for (var annotation : method.visibleAnnotations) {
                    if (!annotation.desc.equals("Lorg/spongepowered/asm/mixin/injection/Inject;")) continue;
                    assertEquals(Boolean.TRUE, value(annotation, "cancellable"));
                    for (Object raw : (List<?>) value(annotation, "method")) {
                        assertEquals(1, target.methods.stream().filter(m -> (m.name + m.desc).equals(raw)).count());
                        count++;
                    }
                }
            }
            assertEquals(1, count);
        }
        try (var input = getClass().getResourceAsStream("/earthplatform.mixins.json")) {
            assertNotNull(input);
            String config = new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            for (String mixin : List.of("CoreCollisionLevelMixin", "CoreCollisionMotionMixin", "CoreCollisionServerMoveMixin"))
                assertTrue(config.contains("\"" + mixin + "\""), mixin + " must be registered");
        }
    }

    @Test void loadedNativeClassesActuallyContainAllThreeCollisionInjections() {
        assertTrue(NativeCollisionLevelAccess.class.isAssignableFrom(net.minecraft.world.level.Level.class),
                "The test runtime must apply the actual Level mixin");
        assertTrue(Arrays.stream(net.minecraft.world.level.Level.class.getDeclaredMethods())
                .anyMatch(m -> m.getName().contains("earthplatform$collision")));
        assertTrue(Arrays.stream(Entity.class.getDeclaredMethods())
                .anyMatch(m -> m.getName().contains("earthplatform$motion")));
        assertTrue(Arrays.stream(net.minecraft.server.network.ServerGamePacketListenerImpl.class.getDeclaredMethods())
                .anyMatch(m -> m.getName().contains("earthplatform$packetMotion")),
                "Packet descriptor inspection alone cannot establish transformed hook installation");
    }

    @Test void logoutAndOrderlyShutdownRecoveryPrecedePinnedPlayerPersistence() throws Exception {
        var playerList = nativeClass("net/minecraft/server/players/PlayerList");
        var remove = playerList.methods.stream().filter(m -> m.name.equals("remove")
                && m.desc.equals("(Lnet/minecraft/server/level/ServerPlayer;)V")).findFirst().orElseThrow();
        int logout = invocationIndex(remove, "net/neoforged/neoforge/event/EventHooks", "firePlayerLoggedOut");
        int save = invocationIndex(remove, "net/minecraft/server/players/PlayerList", "save");
        assertTrue(logout < save, "Recovery must run before this player's final persisted location and mode");
        var server = nativeClass("net/minecraft/server/MinecraftServer");
        var run = server.methods.stream().filter(m -> m.name.equals("runServer") && m.desc.equals("()V")).findFirst().orElseThrow();
        int stopping = invocationIndex(run, "net/neoforged/neoforge/server/ServerLifecycleHooks", "handleServerStopping");
        int stop = invocationIndex(run, "net/minecraft/server/MinecraftServer", "stopServer");
        assertTrue(stopping < stop, "Orderly shutdown recovery must precede stopServer's saveAll");
        for (var event : Map.of("logout", "net/neoforged/neoforge/event/entity/player/PlayerEvent$PlayerLoggedOutEvent",
                "login", "net/neoforged/neoforge/event/entity/player/PlayerEvent$PlayerLoggedInEvent",
                "stopping", "net/neoforged/neoforge/event/server/ServerStoppingEvent").entrySet()) {
            var handler = CoreGateCommands.class.getDeclaredMethod(event.getKey(), Class.forName(event.getValue().replace('/', '.')));
            assertTrue(handler.isAnnotationPresent(net.neoforged.bus.api.SubscribeEvent.class));
        }
    }

    @Test void closedEpochAndGenerationCannotReopenTheWalkHandshake() {
        var h = new CoreCollisionHandshake(9);
        assertTrue(h.accept(new CoreGateCollisionPayload(9, 2, 3, true), 2, false));
        h.invalidate();
        assertFalse(h.accept(new CoreGateCollisionPayload(8, 2, 999, true), 2, false));
        assertFalse(h.accept(new CoreGateCollisionPayload(9, 2, 3, true), 2, false));
        assertFalse(h.accept(new CoreGateCollisionPayload(9, 3, 4, true), 3, true));
        assertFalse(h.ready());
        assertTrue(h.accept(new CoreGateCollisionPayload(9, 3, 4, true), 3, false));
        assertTrue(h.ready());
    }

    private static NativeCollisionView fixedFixture() {
        var provider = new AnalyticNativeCubeProvider(0, AnalyticNativeCubeProvider.Config.boundedCollision(128), palette);
        var codec = new NativeCubeCodec(provider.identity(), palette);
        Map<CubePos, EngineCubeSnapshot> snapshots = new HashMap<>();
        for (var pos : CubeTickets.box(new CubePos(0, 8, 0), 2, 2, 125))
            snapshots.put(pos, codec.materialize(pos, provider.load(pos, new CancellationToken()).join(), 1, 1, 0));
        return new NativeCollisionView(provider.identity(), 1, palette, snapshots, () -> true);
    }

    private static boolean vanillaAcceptsAfterCancelledMove(CollisionGetter access, AABB before, Vec3 request) {
        // Pinned 1.21.1: d7 > -0.5 || d7 < 0.5 sets every finite vertical discrepancy to zero.
        boolean movedWrongly = request.x * request.x + request.z * request.z > 0.0625;
        if (movedWrongly && access.noCollision(before)) return false;
        var priorShape = Shapes.create(before.deflate(1.0E-5F));
        for (var shape : access.getCollisions(null, before.move(request).deflate(1.0E-5F)))
            if (!Shapes.joinIsNotEmpty(shape, priorShape, BooleanOp.AND)) return false;
        return true;
    }

    private record Access(NativeCollisionView view) implements CollisionGetter {
        @Override public BlockGetter getChunkForCollisions(int x, int z) { return view; }
        @Override public BlockState getBlockState(BlockPos pos) { return view.getBlockState(pos); }
        @Override public FluidState getFluidState(BlockPos pos) { return view.getFluidState(pos); }
        @Override public BlockEntity getBlockEntity(BlockPos pos) { return view.getBlockEntity(pos); }
        @Override public int getHeight() { return view.getHeight(); }
        @Override public int getMinBuildHeight() { return view.getMinBuildHeight(); }
        @Override public WorldBorder getWorldBorder() { return new WorldBorder(); }
        @Override public List<VoxelShape> getEntityCollisions(Entity entity, AABB box) { return List.of(); }
    }

    private static ClassNode nativeClass(String name) throws Exception {
        try (var jar = new ZipFile(System.getProperty("earthplatform.test.minecraftJar"));
             var input = jar.getInputStream(jar.getEntry(name + ".class"))) {
            var node = new ClassNode(); new ClassReader(input).accept(node, 0); return node;
        }
    }
    private static ClassNode compiledMixin(String name) throws Exception {
        try (var input = CoreCollisionIntegrationReviewTest.class.getResourceAsStream("/io/earthplatform/minecraft/mixin/" + name + ".class")) {
            assertNotNull(input); var node = new ClassNode(); new ClassReader(input).accept(node, 0); return node;
        }
    }
    private static AnnotationNode annotation(MethodNode method, String descriptor) {
        return method.visibleAnnotations.stream().filter(a -> a.desc.equals(descriptor)).findFirst().orElseThrow();
    }
    private static Object value(AnnotationNode annotation, String key) {
        for (int i = 0; i < annotation.values.size(); i += 2)
            if (annotation.values.get(i).equals(key)) return annotation.values.get(i + 1);
        throw new AssertionError("Missing annotation field: " + key);
    }
    private static List<MethodInsnNode> calls(MethodNode method) {
        var result = new ArrayList<MethodInsnNode>();
        for (var instruction : method.instructions) if (instruction instanceof MethodInsnNode call) result.add(call);
        return result;
    }
    private static int invocationIndex(MethodNode method, String owner, String name) {
        var call = calls(method).stream().filter(i -> i.owner.equals(owner) && i.name.equals(name)).findFirst().orElseThrow();
        return method.instructions.indexOf(call);
    }
}
