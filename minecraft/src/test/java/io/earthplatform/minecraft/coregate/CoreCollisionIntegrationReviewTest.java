package io.earthplatform.minecraft.coregate;

import com.mojang.serialization.Lifecycle;
import io.earthplatform.core.CancellationToken;
import io.earthplatform.core.CubePos;
import io.earthplatform.core.CubeTickets;
import io.earthplatform.minecraft.enginebridge.NativeCollisionMotionFence;
import io.earthplatform.minecraft.enginebridge.NativeCollisionLevelAccess;
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
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
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
                    var injectionAt=(AnnotationNode)((List<?>)value(annotation,"at")).getFirst();
                    if(!"RETURN".equals(value(injectionAt,"value")))assertEquals(Boolean.TRUE,value(annotation,"cancellable"));
                    for (Object raw : (List<?>) value(annotation, "method")) {
                        assertEquals(1, target.methods.stream().filter(m -> (m.name + m.desc).equals(raw)).count());
                        count++;
                    }
                }
            }
            assertEquals(entry.getKey().equals("CoreCollisionMotionMixin")?6:1, count);
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

    @Test void connectionPlayerReplacementOccursOnlyAfterTheEntireRespawnCallReturns() throws Exception {
        String listenerName = "net/minecraft/server/network/ServerGamePacketListenerImpl";
        String playerDescriptor = "Lnet/minecraft/server/level/ServerPlayer;";
        var playerList = nativeClass("net/minecraft/server/players/PlayerList");
        var respawn = playerList.methods.stream().filter(m -> m.name.equals("respawn")
                && m.desc.startsWith("(Lnet/minecraft/server/level/ServerPlayer;Z")).findFirst().orElseThrow();
        int event = invocationIndex(respawn, "net/neoforged/neoforge/event/EventHooks", "firePlayerRespawnEvent");
        boolean returnsAfterEvent = false;
        for (var instruction : respawn.instructions) {
            if (instruction.getOpcode() == Opcodes.ARETURN) {
                assertTrue(event < respawn.instructions.indexOf(instruction),
                        "The event executes before control returns to the connection handler");
                returnsAfterEvent = true;
            }
            if (instruction instanceof FieldInsnNode field && field.getOpcode() == Opcodes.PUTFIELD)
                assertFalse(field.owner.equals(listenerName) && field.name.equals("player")
                        && field.desc.equals(playerDescriptor), "PlayerList.respawn does not replace connection.player");
        }
        assertTrue(returnsAfterEvent);

        var listener = nativeClass(listenerName);
        var handler = listener.methods.stream().filter(m -> m.name.equals("handleClientCommand")
                && m.desc.equals("(Lnet/minecraft/network/protocol/game/ServerboundClientCommandPacket;)V"))
                .findFirst().orElseThrow();
        var respawnCalls = calls(handler).stream().filter(i -> i.owner.equals(playerList.name)
                && i.name.equals(respawn.name) && i.desc.equals(respawn.desc)).toList();
        assertEquals(2, respawnCalls.size(), "Both death and End-return branches require this ownership check");
        for (var call : respawnCalls) {
            var next = call.getNext();
            while (next != null && next.getOpcode() < 0) next = next.getNext();
            var assignment = assertInstanceOf(FieldInsnNode.class, next,
                    "The returned replacement is assigned only after PlayerList.respawn has completed");
            assertEquals(Opcodes.PUTFIELD, assignment.getOpcode());
            assertEquals(listenerName, assignment.owner);
            assertEquals("player", assignment.name);
            assertEquals(playerDescriptor, assignment.desc);
            assertTrue(handler.instructions.indexOf(call) < handler.instructions.indexOf(assignment));
        }

        var teleport = listener.methods.stream().filter(m -> m.name.equals("teleport")
                && m.desc.equals("(DDDFFLjava/util/Set;)V")).findFirst().orElseThrow();
        assertTrue(calls(teleport).stream().anyMatch(i -> i.owner.equals("net/minecraft/server/level/ServerPlayer")
                && i.name.equals("absMoveTo")), "Connection teleport moves its player field, not an event's player argument");
        assertTrue(Arrays.stream(teleport.instructions.toArray()).anyMatch(i -> i instanceof FieldInsnNode field
                && field.getOpcode() == Opcodes.GETFIELD && field.owner.equals(listenerName)
                && field.name.equals("player") && field.desc.equals(playerDescriptor)));
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
