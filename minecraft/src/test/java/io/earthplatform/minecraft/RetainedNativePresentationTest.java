package io.earthplatform.minecraft;

import com.mojang.serialization.Lifecycle;
import io.earthplatform.core.CubePos;
import io.earthplatform.core.WorldIdentity;
import io.earthplatform.minecraft.network.EngineCubeSection;
import io.earthplatform.minecraft.network.EngineCubeSnapshot;
import io.earthplatform.minecraft.network.EnginePaletteContext;
import io.earthplatform.streaming.CubeStreamAdmission;
import java.util.AbstractCollection;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.RegistrationInfo;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Real publication/admission capabilities; headless, without claiming native GL execution. */
class RetainedNativePresentationTest {
    private static final WorldIdentity WORLD = new WorldIdentity(42, "retained", "1", "terrain", "native");
    private static final long EPOCH = 7;
    private static EnginePaletteContext palette;

    @BeforeAll static void registries() {
        var registry = new MappedRegistry<Biome>(Registries.BIOME, Lifecycle.stable());
        registry.register(Biomes.PLAINS,
                VanillaRegistries.createLookup().lookupOrThrow(Registries.BIOME).getOrThrow(Biomes.PLAINS).value(),
                RegistrationInfo.BUILT_IN);
        palette = EnginePaletteContext.create(registry.freeze());
    }

    private static EngineCubeSnapshot snapshot(CubeStreamAdmission.Header header) {
        return EngineCubeSnapshot.capture(header.position(), header.world(), header.connectionEpoch(),
                header.leaseGeneration(), header.revision(), new EngineCubeSection(palette));
    }
    private static CubeStreamAdmission.Header header(CubeStreamAdmission.Lease lease, long revision) {
        return new CubeStreamAdmission.Header(WORLD, EPOCH, lease.position(), lease.generation(), revision);
    }
    private static void commit(CubeStreamAdmission gate, CubeStreamAdmission.Header header) {
        var admitted = gate.admit(header);
        assertTrue(admitted.accepted());
        assertEquals(CubeStreamAdmission.Rejection.NONE, gate.commit(admitted.token(), () -> {}));
    }

    private static void await(CountDownLatch latch) {
        try { assertTrue(latch.await(10, TimeUnit.SECONDS)); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt();throw new AssertionError(interrupted); }
    }

    @Test void allocationFreeLeaseIdentityCheckPreservesWorldEpochXYZGenerationAndLoadedState() {
        var gate = new CubeStreamAdmission(WORLD, EPOCH, 2);
        var position = new CubePos(1, -688, 1);
        var lease = gate.replaceInterest(List.of(position)).requested().getFirst();
        var original = header(lease, 3);
        assertFalse(gate.isCurrentLoadedLease(original));
        commit(gate, original);
        assertTrue(gate.isCurrentLoadedLease(original));
        commit(gate, header(lease, 4));
        assertTrue(gate.isCurrentLoadedLease(original));
        assertFalse(gate.isCurrentLoaded(original));
        assertFalse(gate.isCurrentLoadedLease(header(lease, 5)));
        var wrongWorld = new WorldIdentity(43, "retained", "1", "terrain", "native");
        assertFalse(gate.isCurrentLoadedLease(new CubeStreamAdmission.Header(wrongWorld, EPOCH, position, lease.generation(), 3)));
        assertFalse(gate.isCurrentLoadedLease(new CubeStreamAdmission.Header(WORLD, EPOCH + 1, position, lease.generation(), 3)));
        assertFalse(gate.isCurrentLoadedLease(new CubeStreamAdmission.Header(WORLD, EPOCH, position.offset(0, 1, 0), lease.generation(), 3)));
        assertFalse(gate.isCurrentLoadedLease(new CubeStreamAdmission.Header(WORLD, EPOCH, position, lease.generation() + 1, 3)));
        gate.replaceInterest(List.of());
        var replacement = gate.replaceInterest(List.of(position)).requested().getFirst();
        commit(gate, header(replacement, 4));
        assertFalse(gate.isCurrentLoadedLease(original));
        gate.close();
        assertFalse(gate.isCurrentLoadedLease(header(replacement, 4)));
    }
}
