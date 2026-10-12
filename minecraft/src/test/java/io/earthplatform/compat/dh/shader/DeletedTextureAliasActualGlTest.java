package io.earthplatform.compat.dh.shader;

import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import io.earthplatform.minecraft.scene.NativeShaderTextureAliases;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL45;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Real-driver negative control and production alias-helper regression. Uses an
 * isolated surfaceless EGL pbuffer, never the Minecraft window/context or callback.
 * This proves the GL lifetime mechanism; transformed mod/reload coverage is separate.
 * Context availability is required: this suite fails rather than silently skipping.
 */
class DeletedTextureAliasActualGlTest {
    @Test void aDeletedNameReallyProducesTheObservedDirectStateAccessError() {
        try (var context = new EglContext()) {
            int deleted = GL45.glCreateTextures(GL11.GL_TEXTURE_2D);
            assertTrue(GL11.glIsTexture(deleted));
            int[] rawSlots = new int[NativeShaderTextureAliases.SLOT_COUNT];
            rawSlots[0] = deleted;
            GL11.glDeleteTextures(deleted);
            assertFalse(GL11.glIsTexture(deleted));
            StrictGlErrors.check("actualGl.negative.beforeBind");
            GL45.glBindTextureUnit(0, rawSlots[0]);
            var failure = assertThrows(StrictGlErrors.Failure.class,
                    () -> StrictGlErrors.check("actualGl.negative.deletedNameBind"));
            assertEquals(GL11.GL_INVALID_OPERATION, failure.errorCode());
            assertEquals(java.util.List.of(GL11.GL_INVALID_OPERATION), failure.errorCodes());
            assertTrue(failure.errorCollectionExhausted());
            System.out.println("ACTUAL_GL_NEGATIVE_CONTROL deletedName=" + deleted
                    + " firstError=1282 exactPhase=" + failure.phase());
            // Only this deliberate negative-control assertion consumes the expected
            // error. Production always throws/fences at the same strict boundary.
        }
    }

    @Test void exactPreDeleteAliasClearingPreventsErrorAndPreservesLiveNamesAndBindings() {
        try (var context = new EglContext()) {
            Object level = new Object();
            int deleted = GL45.glCreateTextures(GL11.GL_TEXTURE_2D);
            int live = GL45.glCreateTextures(GL11.GL_TEXTURE_2D);
            try {
                int[] rawSlots = new int[NativeShaderTextureAliases.SLOT_COUNT];
                rawSlots[0] = deleted; rawSlots[3] = deleted; rawSlots[1] = live;
                for (int high : new int[]{31, 32, 63, 64, 127}) rawSlots[high] = deleted;
                rawSlots[126] = live;
                GL45.glBindTextureUnit(7, live);
                int bindingBefore = binding(7);
                try (var scope = NativeShaderTextureAliases.begin(level, () -> {})) {
                    NativeShaderTextureAliases.beforeDelete(level, rawSlots, deleted);
                    assertEquals(0, rawSlots[0]); assertEquals(0, rawSlots[3]);
                    assertEquals(live, rawSlots[1]); assertEquals(live, rawSlots[126]);
                    for (int high : new int[]{31, 32, 63, 64, 127}) assertEquals(0, rawSlots[high]);
                    assertTrue(GL11.glIsTexture(deleted), "The CPU helper cannot delete the object");
                    assertEquals(bindingBefore, binding(7));
                    GL11.glDeleteTextures(deleted);
                    assertFalse(GL11.glIsTexture(deleted));
                    GL45.glBindTextureUnit(0, rawSlots[0]);
                    GL45.glBindTextureUnit(1, rawSlots[1]);
                    StrictGlErrors.check("actualGl.corrected.singleDeleteBind");
                    assertTrue(GL11.glIsTexture(live));
                    assertEquals(live, binding(1));
                    assertEquals(7, NativeShaderTextureAliases.evidence().aliasesCleared());
                }
                System.out.println("ACTUAL_GL_PREVENTION exactAliases=7 logicalSlots=128 highSlots=31,32,63,64,127 unmatchedLive=" + live + " error=0");
            } finally {
                GL11.glDeleteTextures(deleted); GL11.glDeleteTextures(live);
            }
        }
    }

    @Test void realArrayDeletionClearsBothNamesWithoutMutatingTheDeletionArgument() {
        try (var context = new EglContext()) {
            Object level = new Object();
            int a = GL45.glCreateTextures(GL11.GL_TEXTURE_2D);
            int b = GL45.glCreateTextures(GL11.GL_TEXTURE_2D);
            int live = GL45.glCreateTextures(GL11.GL_TEXTURE_2D);
            try {
                int[] rawSlots = new int[NativeShaderTextureAliases.SLOT_COUNT];
                rawSlots[0] = a; rawSlots[1] = b; rawSlots[11] = a; rawSlots[5] = live;
                for (int high : new int[]{31, 63, 127}) rawSlots[high] = a;
                for (int high : new int[]{32, 64}) rawSlots[high] = b;
                rawSlots[126] = live;
                int[] deletes = {a, 0, b, a}; int[] original = deletes.clone();
                try (var scope = NativeShaderTextureAliases.begin(level, () -> {})) {
                    NativeShaderTextureAliases.beforeDelete(level, rawSlots, deletes);
                    assertArrayEquals(original, deletes);
                    assertEquals(0, rawSlots[0]); assertEquals(0, rawSlots[1]); assertEquals(0, rawSlots[11]);
                    assertEquals(live, rawSlots[5]); assertEquals(live, rawSlots[126]);
                    for (int high : new int[]{31, 32, 63, 64, 127}) assertEquals(0, rawSlots[high]);
                    GL11.glDeleteTextures(deletes);
                    // Logical cache capacity is not the number of hardware units.
                    for (int i = 0; i < 12; i++) GL45.glBindTextureUnit(i, rawSlots[i]);
                    StrictGlErrors.check("actualGl.corrected.arrayDeleteBind");
                    assertFalse(GL11.glIsTexture(a)); assertFalse(GL11.glIsTexture(b));
                    assertTrue(GL11.glIsTexture(live));
                    assertEquals(8, NativeShaderTextureAliases.evidence().aliasesCleared());
                }
            } finally {
                GL11.glDeleteTextures(a); GL11.glDeleteTextures(b); GL11.glDeleteTextures(live);
            }
        }
    }

    @Test void completionCannotRevisitNamesOrClearLaterLiveTextureAssignments() {
        try (var context = new EglContext()) {
            Object level = new Object(); CompletableFuture<Void> completion = new CompletableFuture<>();
            int deleted = GL45.glCreateTextures(GL11.GL_TEXTURE_2D);
            int replacement = 0;
            var scope = NativeShaderTextureAliases.begin(level, () -> {});
            try {
                scope.retainUntil(completion);
                int[] rawSlots = new int[NativeShaderTextureAliases.SLOT_COUNT]; rawSlots[0] = deleted;
                NativeShaderTextureAliases.beforeDelete(level, rawSlots, deleted);
                GL11.glDeleteTextures(deleted);
                replacement = GL45.glCreateTextures(GL11.GL_TEXTURE_2D);
                rawSlots[0] = replacement; rawSlots[127] = replacement;
                completion.complete(null);
                assertTrue(scope.closed()); assertFalse(NativeShaderTextureAliases.active());
                assertEquals(replacement, rawSlots[0]); assertEquals(replacement, rawSlots[127]);
                // Even an ordinary-world deletion notification cannot inherit this
                // completed scope. The real deletion is intentionally not issued here.
                NativeShaderTextureAliases.beforeDelete(level, rawSlots, replacement);
                assertEquals(replacement, rawSlots[0]); assertEquals(replacement, rawSlots[127]);
                GL45.glBindTextureUnit(0, rawSlots[0]);
                StrictGlErrors.check("actualGl.completedScope.liveReplacementBind");
                System.out.println("ACTUAL_GL_REPLACEMENT old=" + deleted + " new=" + replacement
                        + " numericReuseObserved=" + (deleted == replacement)
                        + " completionLeftNewAssignment=true");
                // Driver ID allocation is not deterministic. Exact same-number ABA
                // is separately mandatory in pure helper tests, regardless of this receipt.
            } finally {
                completion.completeExceptionally(new IllegalStateException("fixture cleanup"));
                GL11.glDeleteTextures(deleted);
                if (replacement != 0) GL11.glDeleteTextures(replacement);
            }
        }
    }

    private static int binding(int unit) {
        int previous = GL11.glGetInteger(org.lwjgl.opengl.GL13.GL_ACTIVE_TEXTURE);
        org.lwjgl.opengl.GL13.glActiveTexture(org.lwjgl.opengl.GL13.GL_TEXTURE0 + unit);
        try { return GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D); }
        finally { org.lwjgl.opengl.GL13.glActiveTexture(previous); }
    }

    private interface Egl extends Library {
        Egl API = Native.load("EGL", Egl.class);
        Pointer eglGetPlatformDisplay(int platform, Pointer nativeDisplay, Pointer attributes);
        boolean eglInitialize(Pointer display, int[] major, int[] minor);
        boolean eglBindAPI(int api);
        boolean eglChooseConfig(Pointer display, int[] attributes, Pointer[] configs, int capacity, int[] count);
        Pointer eglCreatePbufferSurface(Pointer display, Pointer config, int[] attributes);
        Pointer eglCreateContext(Pointer display, Pointer config, Pointer shared, int[] attributes);
        boolean eglMakeCurrent(Pointer display, Pointer draw, Pointer read, Pointer context);
        Pointer eglGetCurrentContext();
        boolean eglDestroyContext(Pointer display, Pointer context);
        boolean eglDestroySurface(Pointer display, Pointer surface);
        boolean eglTerminate(Pointer display);
        boolean eglReleaseThread();
        int eglGetError();
    }

    private static final class EglContext implements AutoCloseable {
        private final Egl egl = Egl.API;
        private Pointer display, surface, context;
        private boolean initialized, current;
        private EglContext() {
            assertEquals(128, NativeShaderTextureAliases.SLOT_COUNT, "Pinned Iris transformed logical domain");
            assertNull(egl.eglGetCurrentContext(), "Fixture must not displace an existing EGL context");
            try {
                display = egl.eglGetPlatformDisplay(0x31DD /* EGL_PLATFORM_SURFACELESS_MESA */, null, null);
                assertNotNull(display, "Surfaceless EGL display required");
                require(egl.eglInitialize(display, new int[1], new int[1]), "initialize"); initialized = true;
                require(egl.eglBindAPI(0x30A2 /* EGL_OPENGL_API */), "bind OpenGL API");
                Pointer[] configs = new Pointer[1]; int[] count = new int[1];
                require(egl.eglChooseConfig(display, new int[]{0x3033, 1, 0x3040, 8,
                        0x3024, 8, 0x3023, 8, 0x3022, 8, 0x3038}, configs, 1, count), "choose pbuffer config");
                assertEquals(1, count[0], "OpenGL pbuffer config required");
                surface = egl.eglCreatePbufferSurface(display, configs[0], new int[]{0x3057, 16, 0x3056, 16, 0x3038});
                assertNotNull(surface, "EGL pbuffer allocation failed, error=" + Integer.toHexString(egl.eglGetError()));
                context = egl.eglCreateContext(display, configs[0], null,
                        new int[]{0x3098, 4, 0x30FB, 5, 0x30FD, 1, 0x3038});
                assertNotNull(context, "OpenGL4.5 core context required, error=" + Integer.toHexString(egl.eglGetError()));
                require(egl.eglMakeCurrent(display, surface, surface, context), "make current"); current = true;
                assertTrue(GL.createCapabilities().OpenGL45, "Actual DSA context required");
                StrictGlErrors.check("actualGl.context.entry");
                System.out.println("ACTUAL_GL_CONTEXT version=" + GL11.glGetString(GL11.GL_VERSION)
                        + " renderer=" + GL11.glGetString(GL11.GL_RENDERER)
                        + " isolated=true callbackInstalled=false");
            } catch (RuntimeException | Error failure) {
                try { close(); } catch (RuntimeException | Error cleanup) { if (cleanup != failure) failure.addSuppressed(cleanup); }
                throw failure;
            }
        }
        private void require(boolean success, String operation) {
            if (!success) throw new AssertionError("EGL " + operation + " failed: 0x" + Integer.toHexString(egl.eglGetError()));
        }
        @Override public void close() {
            Throwable failure = null;
            if (current) {
                // Check the final fixture reads/deletes too, before destroying the
                // only context in which an unexpected error can be attributed.
                failure = attempt(failure, () -> StrictGlErrors.check("actualGl.context.exit"));
                failure = attempt(failure, () -> GL.setCapabilities(null));
                failure = attempt(failure, () -> require(egl.eglMakeCurrent(display, null, null, null), "release current"));
                current = false;
            }
            if (context != null) {
                failure = attempt(failure, () -> require(egl.eglDestroyContext(display, context), "destroy context"));
                context = null;
            }
            if (surface != null) {
                failure = attempt(failure, () -> require(egl.eglDestroySurface(display, surface), "destroy surface"));
                surface = null;
            }
            if (initialized) {
                failure = attempt(failure, () -> require(egl.eglTerminate(display), "terminate"));
                initialized = false;
            }
            failure = attempt(failure, () -> require(egl.eglReleaseThread(), "release thread"));
            if (failure instanceof RuntimeException error) throw error;
            if (failure instanceof Error error) throw error;
        }
        private static Throwable attempt(Throwable primary, Runnable cleanup) {
            try { cleanup.run(); }
            catch (RuntimeException | Error next) {
                if (primary == null) return next;
                if (primary != next) primary.addSuppressed(next);
            }
            return primary;
        }
    }
}
