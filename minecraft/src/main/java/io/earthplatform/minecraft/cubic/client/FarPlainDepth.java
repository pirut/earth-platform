package io.earthplatform.minecraft.cubic.client;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import io.earthplatform.minecraft.EarthPlatformMod;
import io.earthplatform.minecraft.cubic.Cubic;
import java.nio.FloatBuffer;
import javax.annotation.Nullable;
import org.joml.Matrix4d;
import org.joml.Matrix4f;
import org.joml.Vector4d;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL13C;
import org.lwjgl.opengl.GL14C;
import org.lwjgl.opengl.GL20C;
import org.lwjgl.opengl.GL30C;
import org.lwjgl.system.MemoryStack;

/**
 * Far terrain without a shader pack, drawn in a depth of its own: 32-bit float, with a near plane
 * as far out as the view allows (see {@link #near}). The game's depth, with its 0.05-block near plane,
 * gives a whole depth step to 30 blocks at 5 km and 480 at 20 km, so two far faces a block or ten
 * apart land on one depth and fight each other, most visibly zoomed in; a near plane hundreds of
 * times farther gives steps hundreds of times finer, with no {@code ARB_clip_control} (macOS stops at
 * OpenGL 4.1). The colour goes straight into the game's target, and once far terrain has drawn its
 * depth is merged into the game's, for the water and clouds after it. Every frame without a shader
 * pack or the camera under a fluid, unless {@code far.plainDepth} is off, and only where it is safe
 * (see {@link #begin}); anywhere it is not, far terrain draws in the game's depth as before. The
 * layered clouds clip against {@link #texture}. Render thread only; one per far renderer.
 */
final class FarPlainDepth {
    /**
     * The least near plane, in blocks, worth the extra copy and merge: nearer than this the depth is hardly finer than
     * the game's, so far terrain stays in the game's.
     */
    static final float NEAR = 8;
    /**
     * How much of the nearest view depth far terrain can show at the near plane may take, as a margin for what
     * {@link #near} does not count: the camera's bob moves the eye up to a block off the view's origin, and a column
     * is cut to the whole block.
     */
    static final float SAFETY = 0.8F;
    /** The game's depth: 24 bits, so a step is 2^-24 of the range. */
    static final double STEP = 1.0 / (1 << 24);
    private int texture, framebuffer, vao, merge, width, height, drawnFrom;
    private int gameUnit, farUnit, farInverse, gameMatrix, gameSteps;
    private boolean failed;
    private final Matrix4f game = new Matrix4f(), far = new Matrix4f(), inverse = new Matrix4f();

    /**
     * The nearest any far terrain on screen can be from the eye, in blocks. Far terrain nearer than the game's
     * blocks' reach is cut away under them, so it starts at the reach sideways; up and down the blocks reach only
     * {@link Cubic#VERTICAL_RADIUS} cubes, so the ground below a flyer past that, within the reach sideways, shows.
     */
    static float nearest(float blocksReach) { return Math.min(blocksReach, Cubic.VERTICAL_RADIUS * 16.0F); }

    /**
     * How deep, in the projection's own depth (its clip {@code w}), a point at one block from the eye lies at its
     * least anywhere on screen. {@code w} is the view depth the game's own depth is made from, for any projection the
     * game folds its view bobbing, hurt tilt and nausea distortion into: not the distance along one fixed axis. It is
     * smallest along the screen's corners (the set of directions in view is a convex cone, and the share of a
     * direction that counts as depth is largest at the cone's middle and falls away from it), so those four rays tell
     * it for any field of view and aspect. NaN for a projection where it is not positive and finite.
     */
    static double depthShare(Matrix4f projection) {
        Matrix4d unproject = new Matrix4d(projection).invert();
        double least = Double.POSITIVE_INFINITY;
        for (int corner = 0; corner < 4; corner++) {
            double x = (corner & 1) == 0 ? -1 : 1, y = (corner & 2) == 0 ? -1 : 1;
            Vector4d from = unproject.transform(new Vector4d(x, y, -1, 1)), to = unproject.transform(new Vector4d(x, y, 1, 1));
            double dx = to.x / to.w - from.x / from.w, dy = to.y / to.w - from.y / from.w, dz = to.z / to.w - from.z / from.w;
            double length = Math.sqrt(dx * dx + dy * dy + dz * dz);
            double share = (projection.m03() * dx + projection.m13() * dy + projection.m23() * dz) / length;
            if (!(share > 0) || Double.isInfinite(share)) return Double.NaN;
            least = Math.min(least, share);
        }
        return least;
    }

    /**
     * Far terrain's near plane, in the projection's own depth: {@link #SAFETY} of the least depth far terrain can show
     * at on screen, {@link #nearest} out along the screen's most sideways ray. Far terrain nearer than the plane
     * clips, so it comes from the view's own projection (the field of view and aspect the game has this frame, a zoom
     * and an ultrawide screen included), never from an assumed one: at 110 degrees on 32:9 the corner of the screen
     * sees a point 199 blocks off at 40 blocks of depth. 0 where there is no safe plane.
     */
    static float near(Matrix4f projection, float blocksReach) {
        double share = depthShare(projection);
        return share > 0 ? (float) (SAFETY * nearest(blocksReach) * share) : 0;
    }

    /**
     * Whether the game's projection is a perspective one that far terrain's depth can be remade for: its clip {@code z}
     * row must be {@code A * (depth) + B} of its clip {@code w} row, as the game's is for a view of any rotation, shear
     * or scale. Not an orthographic one, a reversed one, or one with an oblique near plane, whose depth another mod
     * has bent; far terrain stays in the game's depth for those.
     */
    static boolean remappable(Matrix4f projection) {
        double wx = projection.m03(), wy = projection.m13(), wz = projection.m23();
        double ww = wx * wx + wy * wy + wz * wz;
        if (!(ww > 0)) return false;
        double a = -(projection.m02() * wx + projection.m12() * wy + projection.m22() * wz) / ww;
        // The standard orientation: the near plane at -1 and the far plane at 1, which puts A below -1.
        if (!(a <= -0.99) || !(projection.m32() + a * projection.m33() < 0)) return false;
        double scale = Math.sqrt(ww) * Math.max(1, Math.abs(a));
        return Math.abs(projection.m02() + a * wx) <= 1e-4 * scale && Math.abs(projection.m12() + a * wy) <= 1e-4 * scale
                && Math.abs(projection.m22() + a * wz) <= 1e-4 * scale;
    }

    /**
     * The game's projection with far terrain's own near plane and the far plane the far terrain has: every pixel keeps
     * its place and its ray, and only the depth is remade. Clip {@code w} is the view depth for any projection the game
     * hands us (rotated, sheared and scaled by its bobbing and nausea), so the depth row is made from the original's own
     * {@code w} row, {@code z = -A' w + B'}, rather than by overwriting two entries of the original's: that left the rest
     * of the old depth row in, and with a view tilted a few degrees a point tens of kilometres off was clipped. Null
     * where {@link #remappable} says no, or the near plane is not under the far.
     */
    @Nullable static Matrix4f projection(Matrix4f game, float near) {
        float farPlane = FarRenderer.depthFar();
        if (!remappable(game) || !(near > 0) || !(near < farPlane)) return null;
        double a = ((double) farPlane + near) / ((double) near - farPlane), b = 2.0 * farPlane * near / ((double) near - farPlane);
        return new Matrix4f(game).m02((float) (-a * game.m03())).m12((float) (-a * game.m13())).m22((float) (-a * game.m23()))
                .m32((float) (-a * game.m33() + b));
    }

    /** The game's projection this frame's far terrain was drawn against. */
    Matrix4f gameProjection() { return game; }

    /** The inverse of {@link #projection}, which takes a pixel's own depth back to where it lies (the merge, and a far fragment's test against the blocks). */
    Matrix4f inverse() { return inverse; }

    /** Far terrain's own depth, of this frame's draw. */
    int texture() { return texture; }

    /** The projection {@link #texture}'s depth was drawn with. */
    Matrix4f projection() { return far; }

    /**
     * Has far terrain draw in its own depth, in the framebuffer now drawn into, for the game's blocks' reach
     * {@code blocksReach}; false leaves it in the game's depth for this pass. That is so whenever it could not be
     * right: the game's projection is not one the depth can be remade for, the view's nearest far terrain is too
     * near for the plane to be worth it ({@link #NEAR}), or the framebuffer is not one it can copy and draw
     * into at its own size (a mod drawing the world into a larger or multisampled target, or a depth it cannot
     * copy). Only a driver that rejects its own target stops it for good.
     */
    boolean begin(Matrix4f projection, float blocksReach) {
        if (failed) return false;
        RenderSystem.assertOnRenderThread();
        float near = near(projection, blocksReach);
        Matrix4f remade = near >= NEAR ? projection(projection, near) : null;
        if (remade == null) return false;
        drawnFrom = GL11C.glGetInteger(GL30C.GL_DRAW_FRAMEBUFFER_BINDING);
        if (drawnFrom == 0) return false;
        int attachment = GL11C.glGetInteger(GL20C.GL_DRAW_BUFFER0);
        if (attachment < GL30C.GL_COLOR_ATTACHMENT0 || attachment > GL30C.GL_COLOR_ATTACHMENT0 + 15) return false;
        if (GL30C.glGetFramebufferAttachmentParameteri(GL30C.GL_DRAW_FRAMEBUFFER, attachment,
                GL30C.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_TYPE) != GL11C.GL_TEXTURE) return false;
        if (GL30C.glGetFramebufferAttachmentParameteri(GL30C.GL_DRAW_FRAMEBUFFER, attachment,
                GL30C.GL_FRAMEBUFFER_ATTACHMENT_TEXTURE_LEVEL) != 0) return false;
        int colour = GL30C.glGetFramebufferAttachmentParameteri(GL30C.GL_DRAW_FRAMEBUFFER, attachment,
                GL30C.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_NAME);
        // The framebuffer's own size and sampling, not the game's main target's.
        int[] size = FarBlockDepth.boundSize();
        if (size == null) return false;
        int targetWidth = size[0], targetHeight = size[1];
        // The game's blocks, which far terrain tests itself against (see FarShaders#BEHIND_BLOCKS) and is merged with at the end.
        if (!FarBlockDepth.copy(targetWidth, targetHeight)) return false;
        game.set(projection);
        far.set(remade);
        invert(far, inverse);
        int active = GlStateManager._getActiveTexture();
        GlStateManager._activeTexture(GL13C.GL_TEXTURE0);
        int bound = GL11C.glGetInteger(GL11C.GL_TEXTURE_BINDING_2D);
        try {
            if (merge == 0) {
                merge = GlShaders.link(FarLodShaders.FULL_SCREEN, FarLodShaders.MERGE, "far terrain's own depth merge");
                gameUnit = GL20C.glGetUniformLocation(merge, "earth_Game");
                farUnit = GL20C.glGetUniformLocation(merge, "earth_Far");
                farInverse = GL20C.glGetUniformLocation(merge, "earth_FarInverse");
                gameMatrix = GL20C.glGetUniformLocation(merge, "earth_GameProjection");
                gameSteps = GL20C.glGetUniformLocation(merge, "earth_GameSteps");
            }
            if (texture == 0) texture = GL11C.glGenTextures();
            if (framebuffer == 0) framebuffer = GL30C.glGenFramebuffers();
            if (width != targetWidth || height != targetHeight) {
                width = targetWidth;
                height = targetHeight;
                GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, texture);
                GL11C.glTexImage2D(GL11C.GL_TEXTURE_2D, 0, GL30C.GL_DEPTH_COMPONENT32F, width, height, 0,
                        GL11C.GL_DEPTH_COMPONENT, GL11C.GL_FLOAT, (FloatBuffer) null);
                GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_MIN_FILTER, GL11C.GL_NEAREST);
                GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_MAG_FILTER, GL11C.GL_NEAREST);
                GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL14C.GL_TEXTURE_COMPARE_MODE, GL11C.GL_NONE);
            }
            GlStateManager._glBindFramebuffer(GL30C.GL_DRAW_FRAMEBUFFER, framebuffer);
            GL30C.glFramebufferTexture2D(GL30C.GL_DRAW_FRAMEBUFFER, GL30C.GL_COLOR_ATTACHMENT0, GL11C.GL_TEXTURE_2D, colour, 0);
            GL30C.glFramebufferTexture2D(GL30C.GL_DRAW_FRAMEBUFFER, GL30C.GL_DEPTH_ATTACHMENT, GL11C.GL_TEXTURE_2D, texture, 0);
            GL20C.glDrawBuffers(GL30C.GL_COLOR_ATTACHMENT0);
            if (GL30C.glCheckFramebufferStatus(GL30C.GL_DRAW_FRAMEBUFFER) != GL30C.GL_FRAMEBUFFER_COMPLETE)
                throw new IllegalStateException("Far terrain's own depth target is incomplete");
            boolean mask = GL11C.glGetBoolean(GL11C.GL_DEPTH_WRITEMASK), scissor = GL11C.glIsEnabled(GL11C.GL_SCISSOR_TEST);
            GL11C.glDepthMask(true);
            if (scissor) GL11C.glDisable(GL11C.GL_SCISSOR_TEST);
            try (MemoryStack stack = MemoryStack.stackPush()) { GL30C.glClearBufferfv(GL11C.GL_DEPTH, 0, stack.floats(1)); }
            if (scissor) GL11C.glEnable(GL11C.GL_SCISSOR_TEST);
            GL11C.glDepthMask(mask);
            return true;
        } catch (IllegalStateException error) {
            failed = true;
            if (GL11C.glGetInteger(GL30C.GL_DRAW_FRAMEBUFFER_BINDING) == framebuffer)
                GL30C.glFramebufferTexture2D(GL30C.GL_DRAW_FRAMEBUFFER, GL30C.GL_COLOR_ATTACHMENT0, GL11C.GL_TEXTURE_2D, 0, 0);
            GlStateManager._glBindFramebuffer(GL30C.GL_DRAW_FRAMEBUFFER, drawnFrom);
            EarthPlatformMod.LOGGER.warn("Far terrain can't draw in its own depth; it stays in the game's", error);
            return false;
        } finally {
            GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, bound);
            GlStateManager._activeTexture(active);
        }
    }

    /** Merges far terrain's depth into the game's, where it is nearer, and draws on in the game's target again. */
    void end() {
        int program = GL11C.glGetInteger(GL20C.GL_CURRENT_PROGRAM), active = GlStateManager._getActiveTexture();
        int heldVao = GL11C.glGetInteger(GL30C.GL_VERTEX_ARRAY_BINDING), depthFunc = GL11C.glGetInteger(GL11C.GL_DEPTH_FUNC);
        boolean depthTest = GL11C.glIsEnabled(GL11C.GL_DEPTH_TEST), cull = GL11C.glIsEnabled(GL11C.GL_CULL_FACE);
        boolean mask = GL11C.glGetBoolean(GL11C.GL_DEPTH_WRITEMASK);
        boolean[] colours = new boolean[4];
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var bits = stack.malloc(4);
            GL11C.glGetBooleanv(GL11C.GL_COLOR_WRITEMASK, bits);
            for (int i = 0; i < 4; i++) colours[i] = bits.get(i) != 0;
        }
        int[] textures = new int[2];
        for (int i = 0; i < 2; i++) {
            GlStateManager._activeTexture(GL13C.GL_TEXTURE0 + i);
            textures[i] = GL11C.glGetInteger(GL11C.GL_TEXTURE_BINDING_2D);
            GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, i == 0 ? FarBlockDepth.texture() : texture);
        }
        GlStateManager._glBindFramebuffer(GL30C.GL_DRAW_FRAMEBUFFER, drawnFrom);
        try {
            // Every pixel's depth is written, the nearer of the game's and far terrain's; no colour.
            GL11C.glColorMask(false, false, false, false);
            GL11C.glEnable(GL11C.GL_DEPTH_TEST);
            GL11C.glDepthFunc(GL11C.GL_ALWAYS);
            GL11C.glDepthMask(true);
            GL11C.glDisable(GL11C.GL_CULL_FACE);
            GlStateManager._glUseProgram(merge);
            GL20C.glUniform1i(gameUnit, 0);
            GL20C.glUniform1i(farUnit, 1);
            try (MemoryStack stack = MemoryStack.stackPush()) {
                var matrix = stack.mallocFloat(16);
                GL20C.glUniformMatrix4fv(farInverse, false, inverse.get(matrix));
                GL20C.glUniformMatrix4fv(gameMatrix, false, game.get(matrix));
            }
            GL20C.glUniform1f(gameSteps, FarBlockDepth.steps());
            if (vao == 0) vao = GL30C.glGenVertexArrays();
            GL30C.glBindVertexArray(vao);
            GL11C.glDrawArrays(GL11C.GL_TRIANGLES, 0, 3);
        } finally {
            for (int i = 0; i < 2; i++) {
                GlStateManager._activeTexture(GL13C.GL_TEXTURE0 + i);
                GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, textures[i]);
            }
            GlStateManager._activeTexture(active);
            GL30C.glBindVertexArray(heldVao);
            GlStateManager._glUseProgram(program);
            GL11C.glColorMask(colours[0], colours[1], colours[2], colours[3]);
            GL11C.glDepthFunc(depthFunc);
            GL11C.glDepthMask(mask);
            if (depthTest) GL11C.glEnable(GL11C.GL_DEPTH_TEST); else GL11C.glDisable(GL11C.GL_DEPTH_TEST);
            if (cull) GL11C.glEnable(GL11C.GL_CULL_FACE); else GL11C.glDisable(GL11C.GL_CULL_FACE);
            // The colour is the game's: let go of it before a resize or reload deletes it.
            GlStateManager._glBindFramebuffer(GL30C.GL_DRAW_FRAMEBUFFER, framebuffer);
            GL30C.glFramebufferTexture2D(GL30C.GL_DRAW_FRAMEBUFFER, GL30C.GL_COLOR_ATTACHMENT0, GL11C.GL_TEXTURE_2D, 0, 0);
            GlStateManager._glBindFramebuffer(GL30C.GL_DRAW_FRAMEBUFFER, drawnFrom);
        }
    }

    /** The inverse of a projection, worked out in double: its entries span a few orders of magnitude, which a float inverse loses a good part of. */
    static Matrix4f invert(Matrix4f matrix, Matrix4f into) { return into.set(new Matrix4d(matrix).invert()); }

    void close() {
        if (texture != 0) GL11C.glDeleteTextures(texture);
        if (framebuffer != 0) GL30C.glDeleteFramebuffers(framebuffer);
        if (vao != 0) GL30C.glDeleteVertexArrays(vao);
        if (merge != 0) GL20C.glDeleteProgram(merge);
        texture = framebuffer = vao = merge = width = height = 0;
    }
}
