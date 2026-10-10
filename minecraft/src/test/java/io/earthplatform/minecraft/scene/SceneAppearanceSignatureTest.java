package io.earthplatform.minecraft.scene;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Pure appearance provenance checks; no Minecraft singleton, GL context, or options mutation. */
class SceneAppearanceSignatureTest {
    private final Object renderer = new Object();
    private final Object graphics = new Object();
    private SceneAppearanceSignature.Values values(boolean ao, boolean experimental) {
        return new SceneAppearanceSignature.Values(renderer, ao, graphics, experimental);
    }
    @Test void unchangedCaptureRemainsCurrentAcrossPrivateAndPublishedOwners() {
        var signature = new SceneAppearanceSignature(values(true, false));
        SceneAppearanceSignature published = signature;
        assertSame(signature, published);
        assertTrue(published.isCurrent(() -> values(true, false)));
        assertTrue(published.isCurrent(() -> values(true, false)));
    }
    @Test void oldAndNewAoCandidatesCannotBothBeCurrentForTheSameLiveAppearance() {
        var old = new SceneAppearanceSignature(values(true, false));
        var replacement = new SceneAppearanceSignature(values(false, false));
        assertFalse(old.isCurrent(() -> values(false, false)));
        assertTrue(replacement.isCurrent(() -> values(false, false)));
        assertTrue(old.isCurrent(() -> values(true, false)));
        assertFalse(replacement.isCurrent(() -> values(true, false)));
    }
    @Test void experimentalPipelineDriftInvalidatesEitherDirection() {
        for (boolean prior : new boolean[] {false, true}) {
            var signature = new SceneAppearanceSignature(values(true, prior));
            assertFalse(signature.isCurrent(() -> values(true, !prior)));
        }
    }
    @Test void rendererIdentityDriftInvalidatesPublishedMeshProvenance() {
        var signature = new SceneAppearanceSignature(values(true, false));
        assertFalse(signature.isCurrent(() -> new SceneAppearanceSignature.Values(new Object(), true, graphics, false)));
    }
    @Test void graphicsIdentityDriftInvalidatesPublishedMeshProvenance() {
        var signature = new SceneAppearanceSignature(values(true, false));
        assertFalse(signature.isCurrent(() -> new SceneAppearanceSignature.Values(renderer, true, new Object(), false)));
    }
    @Test void objectEqualityCannotHideRendererOrGraphicsIdentityDrift() {
        class Equal {
            @Override public boolean equals(Object other) { return other instanceof Equal; }
            @Override public int hashCode() { return 1; }
        }
        var first = new Equal();
        var second = new Equal();
        var signature = new SceneAppearanceSignature(new SceneAppearanceSignature.Values(first, false, first, true));
        assertEquals(first, second);
        assertFalse(signature.isCurrent(() -> new SceneAppearanceSignature.Values(second, false, first, true)));
        assertFalse(signature.isCurrent(() -> new SceneAppearanceSignature.Values(first, false, second, true)));
    }
    @Test void ownerCheckPrecedesAnyLiveAppearanceRead() throws InterruptedException {
        var signature = new SceneAppearanceSignature(values(true, false));
        var reads = new AtomicInteger();
        var error = new AtomicReference<Throwable>();
        Thread worker = new Thread(() -> {
            try { signature.isCurrent(() -> { reads.incrementAndGet(); return values(true, false); }); }
            catch (Throwable failure) { error.set(failure); }
        });
        worker.start(); worker.join();
        assertInstanceOf(IllegalStateException.class, error.get());
        assertEquals(0, reads.get());
        assertTrue(signature.isCurrent(() -> { reads.incrementAndGet(); return values(true, false); }));
        assertEquals(1, reads.get());
    }
    @Test void missingAppearanceIdentitiesFailClosed() {
        assertThrows(NullPointerException.class, () -> new SceneAppearanceSignature(null));
        assertThrows(NullPointerException.class, () -> new SceneAppearanceSignature.Values(null, true, graphics, false));
        assertThrows(NullPointerException.class, () -> new SceneAppearanceSignature.Values(renderer, true, null, false));
        var signature = new SceneAppearanceSignature(values(true, false));
        assertThrows(NullPointerException.class, () -> signature.isCurrent(() -> null));
    }
}
