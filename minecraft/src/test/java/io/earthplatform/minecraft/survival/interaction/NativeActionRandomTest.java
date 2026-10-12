package io.earthplatform.minecraft.survival.interaction;

import io.earthplatform.core.WorldIdentity;
import io.earthplatform.minecraft.survival.world.NativeSurvivalWorld;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import net.minecraft.data.registries.VanillaRegistries;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import static org.junit.jupiter.api.Assertions.*;

class NativeActionRandomTest {
    private static final WorldIdentity WORLD = new WorldIdentity(39, "geographic", "generator-v1", "source-digest", "palette-digest");
    private static final NativeSurvivalWorld.ActionIdentity ACTION = new NativeSurvivalWorld.ActionIdentity(new UUID(1, 2), new UUID(3, 4), 5);

    @Test void exactIdentityProducesOneReproducibleExplicitlySeededNativeStream() {
        var first = new NativeActionRandom(WORLD, ACTION); var second = new NativeActionRandom(WORLD, ACTION);
        assertEquals(first.seedEffect(), second.seedEffect()); assertSame(first.source(), first.source());
        for (int i = 0; i < 100; i++) assertEquals(first.source().nextLong(), second.source().nextLong());
        var seed = first.seedEffect();
        assertEquals(WORLD, seed.world()); assertEquals(ACTION.ownerEpoch(), seed.ownerEpoch());
        assertEquals(ACTION.actor(), seed.actor()); assertEquals(ACTION.sequence(), seed.sequence());
        assertEquals(seed, NativeEffectCodec.decode(NativeEffectCodec.encode(seed), VanillaRegistries.createLookup()));
    }

    @Test void everyWorldFieldAndFullOwnerActorSequenceChangesTheBoundSeed() {
        long initial = NativeActionRandom.derive(WORLD, ACTION);
        var seeds = new HashSet<Long>(); seeds.add(initial);
        for (WorldIdentity changed : List.of(
                new WorldIdentity(40, WORLD.generatorId(), WORLD.generatorVersion(), WORLD.datasetVersion(), WORLD.blockPaletteVersion()),
                new WorldIdentity(39, "other-generator", WORLD.generatorVersion(), WORLD.datasetVersion(), WORLD.blockPaletteVersion()),
                new WorldIdentity(39, WORLD.generatorId(), "other-version", WORLD.datasetVersion(), WORLD.blockPaletteVersion()),
                new WorldIdentity(39, WORLD.generatorId(), WORLD.generatorVersion(), "other-source", WORLD.blockPaletteVersion()),
                new WorldIdentity(39, WORLD.generatorId(), WORLD.generatorVersion(), WORLD.datasetVersion(), "other-palette")))
            assertTrue(seeds.add(NativeActionRandom.derive(changed, ACTION)));
        for (NativeSurvivalWorld.ActionIdentity changed : List.of(
                new NativeSurvivalWorld.ActionIdentity(new UUID(9, 2), ACTION.actor(), 5),
                new NativeSurvivalWorld.ActionIdentity(new UUID(1, 9), ACTION.actor(), 5),
                new NativeSurvivalWorld.ActionIdentity(ACTION.ownerEpoch(), new UUID(9, 4), 5),
                new NativeSurvivalWorld.ActionIdentity(ACTION.ownerEpoch(), new UUID(3, 9), 5),
                new NativeSurvivalWorld.ActionIdentity(ACTION.ownerEpoch(), ACTION.actor(), 6)))
            assertTrue(seeds.add(NativeActionRandom.derive(WORLD, changed)));
    }

    @Test void compiledFactoryHasNoLevelGlobalClockOrUnseededRandomEntrypoint() throws Exception {
        var node = new ClassNode();
        try (var input = NativeActionRandom.class.getResourceAsStream("NativeActionRandom.class")) {
            new ClassReader(input).accept(node, 0);
        }
        int explicitFactories = 0;
        for (var method : node.methods) for (var instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode call) {
                assertFalse(call.owner.startsWith("net/minecraft/world/level/"), call.owner);
                assertFalse(call.owner.startsWith("net/minecraft/server/level/"), call.owner);
                assertFalse(call.owner.equals("java/lang/System"), call.name);
                if (call.owner.equals("net/minecraft/util/RandomSource")) {
                    assertEquals("create", call.name); assertEquals("(J)Lnet/minecraft/util/RandomSource;", call.desc);
                    explicitFactories++;
                }
            }
        }
        assertEquals(1, explicitFactories);
    }
}
