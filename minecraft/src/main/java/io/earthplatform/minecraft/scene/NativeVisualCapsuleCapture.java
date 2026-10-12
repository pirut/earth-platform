package io.earthplatform.minecraft.scene;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexFormat;
import io.earthplatform.minecraft.mixin.SceneBufferBuilderAccessor;
import io.earthplatform.minecraft.mixin.SceneWeightedBakedModelAccessor;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.client.renderer.block.ModelBlockRenderer;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.client.resources.model.SimpleBakedModel;
import net.minecraft.client.resources.model.WeightedBakedModel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.ColorResolver;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.util.RandomSource;
import net.neoforged.neoforge.client.model.data.ModelData;
import net.neoforged.neoforge.client.model.lighting.LightPipelineAwareModelBlockRenderer;
import net.neoforged.neoforge.common.NeoForgeConfig;
import net.neoforged.neoforge.common.world.AuxiliaryLightManager;
import org.lwjgl.system.MemoryUtil;

/**
 * Render-owner-only capture through the installed, transformed renderBatched and BufferBuilder path.
 * Capture is sliced by callback count/time; no incomplete corpus can escape. This class is never a worker input.
 */
public final class NativeVisualCapsuleCapture implements AutoCloseable {
    public static final int CAPTURE_CASES = 8 * 6 * 256;
    /** Exhaustively replay every case with each admitted solid, then a mixed/outside-ring neighborhood. */
    public static final int VERIFICATION_CASES = CAPTURE_CASES * 6;
    public enum Mode { EXHAUSTIVE_QUALIFICATION, CAPTURE_WITH_PRIOR_QUALIFICATION }
    /** Minted only after the installed exhaustive replay succeeds; cannot be supplied as an arbitrary pass boolean. */
    public static final class Qualification {
        private final String resource, model, appearance;
        private Qualification(String resource, String model, String appearance) { this.resource=resource;this.model=model;this.appearance=appearance; }
        private void require(String resource,String model,String appearance) {
            if (!this.resource.equals(resource)||!this.model.equals(model)||!this.appearance.equals(appearance))
                throw new IllegalStateException("Prior native qualification does not match this exact resource/model/appearance policy");
        }
    }
    public record Step(int callbacks, int completed, int total, long elapsedNanos, long maxCallbackNanos, boolean complete) {}
    public record Sealed(NativeVisualCapsule capsule, Lease lease, Evidence evidence, Qualification qualification) {}
    public record Evidence(int capturedCases, int verifiedCases, long admissionNanos, long totalNanos, long maxSliceNanos,
                           long maxCallbackNanos, long observedGetterCalls, String schema, boolean renderingLevel) {}
    public static final class Lease {
        private final NativeVisualCapsuleCapture owner;
        private final NativeVisualCapsule.Identity identity;
        private Lease(NativeVisualCapsuleCapture owner, NativeVisualCapsule.Identity identity) { this.owner = owner; this.identity = identity; }
        public NativeVisualCapsule.Identity capsuleIdentity() { requireCurrent(); return identity; }
        public void requireCurrent() { owner.requireCurrent(); }
        public SceneNativeShaderContract.Capture shaderContract() { requireCurrent(); return owner.shader; }
        public VertexFormat format() { requireCurrent(); return (VertexFormat) owner.shader.values().format(); }
        public long retainedMetadataBytes() { return 16_384L + owner.shader.metadataBytes(); }
        public void revoke() { owner.close(); }
    }
    private static final BlockState[] PALETTE = { Blocks.AIR.defaultBlockState(), Blocks.STONE.defaultBlockState(),
            Blocks.DIORITE.defaultBlockState(), Blocks.ANDESITE.defaultBlockState(), Blocks.TUFF.defaultBlockState(), Blocks.SNOW_BLOCK.defaultBlockState() };
    private static final String[] TEXTURES = { "air", "stone", "diorite", "andesite", "tuff", "snow" };
    private final Mode mode;
    private final Qualification priorQualification;
    private final Thread owner = Thread.currentThread();
    private final SceneNativeShaderContract.Capture shader;
    private final BlockRenderDispatcher renderer;
    private final SceneAppearanceSignature appearance;
    private final Object resourceManager;
    private final boolean renderingLevel;
    private final BakedModel[] roots = new BakedModel[6], variants = new BakedModel[8];
    private final BlockPos[][] positions = new BlockPos[7][8];
    private final String resourceDigest, modelDigest, appearanceDigest, schema;
    private byte[] table;
    private int completed;
    private long admissionNanos, totalNanos, maxSliceNanos, maxCallbackNanos, getterCalls;
    private boolean closed, failed, sealed, stepping;

    public NativeVisualCapsuleCapture(SceneNativeShaderContract.Capture shader) {
        this(shader, Mode.EXHAUSTIVE_QUALIFICATION, null);
    }
    public NativeVisualCapsuleCapture(SceneNativeShaderContract.Capture shader, Mode mode, Qualification priorQualification) {
        this.mode = Objects.requireNonNull(mode); this.priorQualification = priorQualification;
        if (mode == Mode.CAPTURE_WITH_PRIOR_QUALIFICATION && priorQualification == null)
            throw new IllegalArgumentException("Reduced capture requires an actual installed exhaustive qualification");
        long admissionStart=System.nanoTime();
        checkOwner(); this.shader = Objects.requireNonNull(shader); shader.requireCurrent();
        var values = shader.values();
        if (!values.shaders() || values.stride() != NativeVisualCapsule.STRIDE)
            throw new IllegalStateException("Native visual capsules require the pinned active Iris 52-byte terrain backend");
        var capturedAppearance = readAppearance();
        if (capturedAppearance.experimentalLighting()) throw new IllegalStateException("Experimental NeoForge lighting is not qualified for the native face capsule");
        renderer = (BlockRenderDispatcher) capturedAppearance.renderer(); appearance = new SceneAppearanceSignature(capturedAppearance);
        resourceManager = Minecraft.getInstance().getResourceManager();
        renderingLevel = net.irisshaders.iris.vertices.ImmediateState.isRenderingLevel;
        schema = verifySchema((VertexFormat) values.format());
        resourceDigest = NativeVisualDefaultResources.verify(Minecraft.getInstance().getResourceManager());
        qualifyModels(); qualifyCoordinates(); qualifySemantics();
        modelDigest = modelDigest();
        String appearanceText = capturedAppearance.ambientOcclusion() + ":" + values.ambientOcclusionBits() + ":" + values.separateAo()
                + ":" + values.disableDirectionalShading() + ":" + renderingLevel + ":" + schema;
        appearanceDigest = digest(appearanceText);
        if (mode == Mode.CAPTURE_WITH_PRIOR_QUALIFICATION) priorQualification.require(resourceDigest,modelDigest,appearanceDigest);
        // Prime every state's captured ID/type before the first slice so in-place map mutations cannot hide.
        try (var storage = new OracleStorage(NativeVisualCapsule.QUAD_BYTES)) {
            var builder = builder(storage);
            for (int palette = 1; palette < PALETTE.length; palette++) {
                try { shader.beginBlock(builder, PALETTE[palette], 0, 0, 0); }
                finally { shader.endBlock(builder); }
            }
        }
        clearCaches(); requireCurrent();
        table = new byte[NativeVisualCapsule.TABLE_BYTES];
        admissionNanos=Math.max(0,System.nanoTime()-admissionStart);
    }
    public Step step(int maxCallbacks, long maxNanos) {
        checkOwner();
        if (maxCallbacks < 1 || maxNanos < 1 || closed || failed || sealed || stepping) throw new IllegalStateException("Native capsule capture cannot step");
        long start = System.nanoTime(); int count = 0; stepping = true; Throwable failure = null;
        try {
            clearCaches(); requireCurrent(); requireCapturePhase();
            while (count < maxCallbacks && completed < totalCases()) {
                if (count > 0 && System.nanoTime() - start >= maxNanos) break;
                long callbackStart = System.nanoTime();
                captureOrVerify(completed);
                maxCallbackNanos = Math.max(maxCallbackNanos, System.nanoTime() - callbackStart);
                completed++; count++;
            }
        } catch (RuntimeException | Error error) { failed = true; failure = error; }
        finally {
            try { clearCaches(); requireCurrent(); requireCapturePhase(); }
            catch (RuntimeException | Error error) { failed = true; failure = SceneBuildStepper.append(failure, error); }
            stepping = false;
            long elapsed = Math.max(0, System.nanoTime() - start); totalNanos += elapsed; maxSliceNanos = Math.max(maxSliceNanos, elapsed);
        }
        SceneBuildStepper.rethrow(failure);
        return new Step(count, completed, totalCases(), Math.max(0, System.nanoTime() - start), maxCallbackNanos, complete());
    }
    private int totalCases() { return CAPTURE_CASES + (mode == Mode.EXHAUSTIVE_QUALIFICATION ? VERIFICATION_CASES : 0); }
    public Mode mode() { return mode; }
    public boolean complete() { checkOwner(); return !failed && !closed && completed == totalCases(); }
    public int completedCallbacks() { checkOwner(); return completed; }
    public long retainedBytes() { checkOwner(); return (table == null ? 0L : table.length) + 16_384L + shader.metadataBytes(); }
    public Sealed seal() {
        checkOwner(); if (!complete() || sealed || stepping) throw new IllegalStateException("Native capsule is incomplete or already transferred");
        requireCurrent();
        byte[] owned = table; table = null;
        var capsule = new NativeVisualCapsule(shader.values().resourceEpoch(), resourceDigest, modelDigest, appearanceDigest, owned);
        sealed = true;
        return new Sealed(capsule, new Lease(this, capsule.identity()), new Evidence(CAPTURE_CASES, totalCases() - CAPTURE_CASES, admissionNanos, totalNanos, maxSliceNanos, maxCallbackNanos, getterCalls, schema, renderingLevel), mode == Mode.EXHAUSTIVE_QUALIFICATION ? new Qualification(resourceDigest,modelDigest,appearanceDigest) : priorQualification);
    }
    private void captureOrVerify(int index) {
        int pass = index / CAPTURE_CASES, entry = index % CAPTURE_CASES;
        int mask = entry & 255, face = (entry >>> 8) % 6, variant = entry / (6 * 256);
        int palette = variant < 4 ? 1 : variant - 2;
        BlockPos pos = positions[pass][variant];
        SyntheticView view = new SyntheticView(pos, palette, face, mask, pass == 0 ? 1 : Math.min(pass, 5), pass == 6);
        byte[] actual = renderSingle(pos, PALETTE[palette], view, face);
        getterCalls += view.reads; view.requireValid();
        int offset = entry * NativeVisualCapsule.QUAD_BYTES;
        if (pass == 0) System.arraycopy(actual, 0, table, offset, actual.length);
        else for (int i = 0; i < actual.length; i++) if (actual[i] != table[offset + i])
            throw new IllegalStateException("Actual native capsule parity failed at pass=" + pass + ", variant=" + variant + ", face=" + face + ", mask=" + mask + ", byte=" + i);
    }
    private byte[] renderSingle(BlockPos pos, BlockState state, SyntheticView view, int face) {
        // Per-reference cache reset avoids packed-Y aliasing and cross-neighborhood culling-cache reuse.
        clearCaches();
        var random = RandomSource.create(state.getSeed(pos));
        requireSolidLayer(renderer.getBlockModel(state), state, random);
        try (var storage = new OracleStorage(NativeVisualCapsule.QUAD_BYTES)) {
            var builder = builder(storage); MeshData mesh = null;
            try {
                try {
                    shader.beginBlock(builder, state, 0, 0, 0);
                    renderer.renderBatched(state, pos, view, new PoseStack(), builder, true, random, ModelData.EMPTY, RenderType.solid());
                } finally { shader.endBlock(builder); }
                mesh = builder.build();
                if (mesh == null || mesh.drawState().vertexCount() != 4 || mesh.drawState().indexCount() != 6
                        || mesh.drawState().mode() != VertexFormat.Mode.QUADS || mesh.indexBuffer() != null
                        || mesh.vertexBuffer().remaining() != NativeVisualCapsule.QUAD_BYTES)
                    throw new IllegalStateException("Native oracle must emit exactly the intended one complete quad");
                shader.requireFormat(mesh.drawState().format(), NativeVisualCapsule.STRIDE);
                byte[] result = new byte[NativeVisualCapsule.QUAD_BYTES]; mesh.vertexBuffer().get(result);
                verifyOutput(result, face); view.requireValid(); return result;
            } finally { if (mesh != null) mesh.close(); }
        }
    }
    private BufferBuilder builder(OracleStorage storage) {
        var builder = new BufferBuilder(storage, VertexFormat.Mode.QUADS, DefaultVertexFormat.BLOCK);
        if (!(builder instanceof SceneBufferBuilderAccessor actual)) throw new IllegalStateException("Native format accessor is absent");
        shader.requireFormat(actual.earthplatform$format(), actual.earthplatform$vertexSize());
        if (actual.earthplatform$vertexSize() != 52) throw new IllegalStateException("Native stride changed");
        return builder;
    }
    private static void verifyOutput(byte[] result, int face) {
        ByteBuffer data = ByteBuffer.wrap(result).order(ByteOrder.nativeOrder());
        int corners = 0; int[] orderedCorners = new int[4];
        int normalAxis = face < 2 ? 1 : face < 4 ? 2 : 0;
        for (int vertex = 0; vertex < 4; vertex++) {
            int corner = 0;
            for (int axis = 0; axis < 3; axis++) {
                float coordinate = data.getFloat(vertex * 52 + axis * 4);
                if (coordinate != 0 && coordinate != 1) throw new IllegalStateException("Native capsule did not emit a unit-cube face");
                if (axis == normalAxis && coordinate != (face & 1)) throw new IllegalStateException("Native capsule emitted the wrong cull face");
                corner |= (int) coordinate << axis;
            }
            if ((corners & (1 << corner)) != 0) throw new IllegalStateException("Native face repeats a corner");
            corners |= 1 << corner; orderedCorners[vertex] = corner;
            for (int axis = 0; axis < 3; axis++) if (result[vertex * 52 + 28 + axis] != NativeVisualCapsule.normal(face,axis) * 127)
                throw new IllegalStateException("Native face normal does not match its cull direction");
            if (result[vertex * 52 + 51] != (byte) -1) throw new IllegalStateException("Pinned native mid-block sentinel changed");
            // Byte31 is the proven normal padding. OracleStorage zeroes it before native writes; nothing is normalized after capture.
        }
        requireWinding(orderedCorners,face);
    }
    private static void requireWinding(int[] corners,int face) {
        for (int vertex=0;vertex<4;vertex++) if (Integer.bitCount(corners[vertex]^corners[(vertex+1)&3])!=1)
            throw new IllegalStateException("Native quad vertices are not cyclic unit-face corners");
        int[] first=new int[3],second=new int[3];
        for(int axis=0;axis<3;axis++){first[axis]=((corners[1]>>>axis)&1)-((corners[0]>>>axis)&1);second[axis]=((corners[2]>>>axis)&1)-((corners[0]>>>axis)&1);}
        int[] normal={first[1]*second[2]-first[2]*second[1],first[2]*second[0]-first[0]*second[2],first[0]*second[1]-first[1]*second[0]};
        for(int axis=0;axis<3;axis++)if(normal[axis]!=NativeVisualCapsule.normal(face,axis))throw new IllegalStateException("Native quad winding disagrees with its face");
    }
    private void qualifyModels() {
        for (int palette = 1; palette < 6; palette++) {
            BlockState state = PALETTE[palette]; BakedModel model = renderer.getBlockModel(state); roots[palette] = model;
            if (palette == 1) {
                if (model.getClass() != WeightedBakedModel.class || !(model instanceof SceneWeightedBakedModelAccessor weighted)
                        || weighted.earthplatform$totalWeight() != 4 || weighted.earthplatform$children().size() != 4)
                    throw new IllegalStateException("Default stone must have exactly four actual unit-weight model children");
                for (int child = 0; child < 4; child++) {
                    var entry = weighted.earthplatform$children().get(child);
                    if (entry.getWeight().asInt() != 1) throw new IllegalStateException("Unsupported stone model weights");
                    variants[child] = entry.data(); validateModel(state, entry.data(), TEXTURES[palette]);
                }
            } else { variants[palette + 2] = model; validateModel(state, model, TEXTURES[palette]); }
        }
    }
    private void validateModel(BlockState state, BakedModel model, String texture) {
        if (model.getClass() != SimpleBakedModel.class || model.isCustomRenderer() || !model.useAmbientOcclusion()
                || !model.usesBlockLight() || model.getModelData(new SyntheticView(BlockPos.ZERO, 1, 1, 0, 1, false), BlockPos.ZERO, state, ModelData.EMPTY) != ModelData.EMPTY)
            throw new IllegalStateException("Unsupported native model graph or custom model data");
        var random = RandomSource.create(0); requireSolidLayer(model, state, random);
        if (!model.getQuads(state, null, random, ModelData.EMPTY, RenderType.solid()).isEmpty())
            throw new IllegalStateException("Unculled native model quads are not admitted");
        for (Direction direction : Direction.values()) {
            random.setSeed(0); var quads = model.getQuads(state, direction, random, ModelData.EMPTY, RenderType.solid());
            if (quads.size() != 1) throw new IllegalStateException("A default cube requires exactly one quad per cull direction");
            BakedQuad quad = quads.getFirst();
            if (quad.isTinted() || quad.getDirection() != direction || !quad.isShade() || !quad.hasAmbientOcclusion()
                    || quad.getVertices().length != 32 || !quad.getSprite().contents().name().toString().equals("minecraft:block/" + texture))
                throw new IllegalStateException("Unsupported tint, face flags, sprite, or quad layout");
            int[] vertices = quad.getVertices().clone(); int corners = 0; int[] orderedCorners = new int[4];
            int normalAxis = direction.ordinal() < 2 ? 1 : direction.ordinal() < 4 ? 2 : 0;
            for (int vertex = 0; vertex < 4; vertex++) {
                int corner = 0;
                for (int axis = 0; axis < 3; axis++) {
                    float c = Float.intBitsToFloat(vertices[vertex * 8 + axis]);
                    if ((c != 0 && c != 1) || (axis == normalAxis && c != (direction.ordinal() & 1))) throw new IllegalStateException("Partial/nonunit native model");
                    corner |= ((int) c) << axis;
                }
                if ((corners & (1 << corner)) != 0) throw new IllegalStateException("Duplicate native model corner"); corners |= 1 << corner; orderedCorners[vertex]=corner;
                if (vertices[vertex * 8 + 3] != -1 || vertices[vertex * 8 + 6] != 0) throw new IllegalStateException("Native default model contains baked color/light");
                float u=Float.intBitsToFloat(vertices[vertex*8+4]),v=Float.intBitsToFloat(vertices[vertex*8+5]);
                if(!Float.isFinite(u)||!Float.isFinite(v)||u<quad.getSprite().getU0()||u>quad.getSprite().getU1()||v<quad.getSprite().getV0()||v>quad.getSprite().getV1())
                    throw new IllegalStateException("Native atlas UV lies outside the actual admitted sprite");
            }
            requireWinding(orderedCorners,direction.ordinal());
        }
    }
    private static void requireSolidLayer(BakedModel model, BlockState state, RandomSource random) {
        int layers = 0;
        for (var layer : model.getRenderTypes(state, random, ModelData.EMPTY)) { if (layer != RenderType.solid()) throw new IllegalStateException("Non-solid native layer"); layers++; }
        if (layers != 1) throw new IllegalStateException("Missing/duplicate native solid layer");
    }
    private void qualifyCoordinates() {
        int[][] bases = {{0,0,0},{-4096,-4096,-4096},{9700000,8192,-3100000},{-9700000,-8192,3100000},
                {Integer.MAX_VALUE-4096,512,Integer.MIN_VALUE+4096},{-4096,4096,4096},{2048,-11000,-2048}};
        for (int pass = 0; pass < positions.length; pass++) {
            for (int variant = 0; variant < 8; variant++) {
                int[] base = bases[pass];
                for (int delta = 0; delta < 4096; delta++) {
                    BlockPos pos = new BlockPos(base[0]+delta,base[1],base[2]);
                    if (PALETTE[1].getSeed(pos) != NativeVisualCapsule.blockSeed(pos.getX(),pos.getY(),pos.getZ())) throw new IllegalStateException("Native block seed contract changed");
                    var random = RandomSource.create(PALETTE[1].getSeed(pos));
                    int selected = Math.abs((int) random.nextLong()) % 4;
                    if (selected != NativeVisualCapsule.stoneVariant(pos.getX(),pos.getY(),pos.getZ())) throw new IllegalStateException("Native weighted selection differs from pure selector");
                    if (variant >= 4 || selected == variant) { positions[pass][variant] = pos; break; }
                }
                if (positions[pass][variant] == null) throw new IllegalStateException("Unable to select a proven native weighted variant");
            }
        }
    }
    private void qualifySemantics() {
        var view = new SyntheticView(BlockPos.ZERO,1,1,0,1,false); int shadeBits = 0;
        var air = PALETTE[0];
        if (!air.isAir() || air.isViewBlocking(view,BlockPos.ZERO) || air.getLightBlock(view,BlockPos.ZERO) != 0
                || air.isSolidRender(view,BlockPos.ZERO) || air.getLightEmission(view,BlockPos.ZERO) != 0
                || air.emissiveRendering(view,BlockPos.ZERO) || !air.getFluidState().isEmpty())
            throw new IllegalStateException("Native capsule requires exact default air semantics");
        for (int palette = 1; palette < 6; palette++) {
            var state = PALETTE[palette]; int bits = Float.floatToRawIntBits(state.getShadeBrightness(view,BlockPos.ZERO));
            if (palette == 1) shadeBits = bits;
            if (bits != shadeBits || !state.isViewBlocking(view,BlockPos.ZERO) || state.getLightBlock(view,BlockPos.ZERO) != 15
                    || !state.isSolidRender(view,BlockPos.ZERO) || !state.isCollisionShapeFullBlock(view,BlockPos.ZERO)
                    || !state.canOcclude() || state.getLightEmission(view,BlockPos.ZERO) != 0 || state.emissiveRendering(view,BlockPos.ZERO)
                    || state.hasOffsetFunction() || !state.getFluidState().isEmpty()) throw new IllegalStateException("Palette states lack equivalent default full-cube light/occlusion semantics");
            for (int neighbor = 0; neighbor < 6; neighbor++) for (Direction face : Direction.values()) {
                view.centerNeighbor = PALETTE[neighbor]; view.overrideNeighbor = face;
                if (Block.shouldRenderFace(state,view,BlockPos.ZERO,face,BlockPos.ZERO.relative(face)) != (neighbor == 0))
                    throw new IllegalStateException("Unsupported palette-pair face culling");
            }
        }
        view.requireValid();
    }
    private String modelDigest() {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            ByteBuffer word = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN);
            for (int variant = 0; variant < variants.length; variant++) {
                int palette = variant < 4 ? 1 : variant - 2;
                BakedModel model = variants[variant];
                requireSolidLayer(model,PALETTE[palette],RandomSource.create(0));
                digest.update((byte)(model.useAmbientOcclusion()?1:0)); digest.update((byte)(model.usesBlockLight()?1:0));
                digest.update((byte)(model.isCustomRenderer()?1:0));
                if (!model.getQuads(PALETTE[palette],null,RandomSource.create(0),ModelData.EMPTY,RenderType.solid()).isEmpty())
                    throw new IllegalStateException("Native model gained unculled quads after qualification");
                for (Direction face : Direction.values()) {
                    var quads = model.getQuads(PALETTE[palette],face,RandomSource.create(0),ModelData.EMPTY,RenderType.solid());
                    if (quads.size() != 1) throw new IllegalStateException("Native model mutated after qualification");
                    BakedQuad quad = quads.getFirst();
                    digest.update((byte) quad.getDirection().ordinal()); digest.update((byte)(quad.isTinted()?1:0));
                    digest.update((byte)(quad.isShade()?1:0)); digest.update((byte)(quad.hasAmbientOcclusion()?1:0));
                    for (int value : quad.getVertices()) { word.putInt(0,value); digest.update(word.array()); }
                    digest.update(quad.getSprite().contents().name().toString().getBytes(StandardCharsets.UTF_8));
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
    private void requireCurrent() {
        checkOwner();
        try { requireCurrentRaw(); }
        catch (RuntimeException | Error failure) { failed = true; throw failure; }
    }
    private void requireCapturePhase() {
        if (renderingLevel != net.irisshaders.iris.vertices.ImmediateState.isRenderingLevel)
            throw new IllegalStateException("Native render phase changed during capsule capture; schedule every slice in the captured phase");
    }
    private void requireCurrentRaw() {
        checkOwner();
        if (closed || failed) throw new IllegalStateException("Native capsule owner has been revoked");
        shader.requireCurrent();
        if (resourceManager != Minecraft.getInstance().getResourceManager() || !appearance.isCurrent(NativeVisualCapsuleCapture::readAppearance))
            throw new IllegalStateException("Native capsule appearance/resource owner changed");
        for (int palette = 1; palette < 6; palette++) if (renderer.getBlockModel(PALETTE[palette]) != roots[palette]) throw new IllegalStateException("Native baked model generation changed");
        var weighted = (SceneWeightedBakedModelAccessor) roots[1];
        if (weighted.earthplatform$totalWeight() != 4 || weighted.earthplatform$children().size() != 4) throw new IllegalStateException("Stone weighted graph changed");
        for (int child = 0; child < 4; child++) if (weighted.earthplatform$children().get(child).data() != variants[child]
                || weighted.earthplatform$children().get(child).getWeight().asInt() != 1) throw new IllegalStateException("Stone child/weight changed");
        if (modelDigest != null && !modelDigest.equals(modelDigest())) throw new IllegalStateException("Native model bytes mutated in place");
    }
    private void clearCaches() {
        Throwable failure = null;
        try { ModelBlockRenderer.clearCache(); } catch (RuntimeException | Error error) { failure = error; }
        if (renderer.getModelRenderer() instanceof LightPipelineAwareModelBlockRenderer nativeRenderer) {
            try { nativeRenderer.getQuadLighter(false).reset(); } catch (RuntimeException | Error error) { failure = SceneBuildStepper.append(failure,error); }
            try { nativeRenderer.getQuadLighter(true).reset(); } catch (RuntimeException | Error error) { failure = SceneBuildStepper.append(failure,error); }
        }
        SceneBuildStepper.rethrow(failure);
    }
    private static SceneAppearanceSignature.Values readAppearance() {
        RenderSystem.assertOnRenderThread(); var mc = Minecraft.getInstance();
        return new SceneAppearanceSignature.Values(mc.getBlockRenderer(),Minecraft.useAmbientOcclusion(),mc.options.graphicsMode().get(),NeoForgeConfig.CLIENT.experimentalForgeLightPipelineEnabled.get());
    }
    private static String digest(String value) { byte[] bytes = value.getBytes(StandardCharsets.UTF_8); return NativeVisualCapsule.sha256(bytes,bytes.length); }
    private static String verifySchema(VertexFormat format) {
        String[] names = {"Position","Color","UV0","UV2","Normal","mc_Entity","mc_midTexCoord","at_tangent","at_midBlock"};
        int[] offsets = {0,12,16,24,28,32,36,44,48}, sizes = {12,4,8,4,3,4,8,4,3};
        String[] types = {"FLOAT","UBYTE","FLOAT","SHORT","BYTE","SHORT","FLOAT","BYTE","BYTE"};
        if (format.getVertexSize() != 52 || !format.getElementAttributeNames().equals(List.of(names)) || format.getElements().size() != names.length) throw new IllegalStateException("Unsupported actual Iris vertex schema");
        for (int i = 0; i < names.length; i++) {
            var element = format.getElements().get(i);
            if (format.getOffset(element) != offsets[i] || element.byteSize() != sizes[i] || !element.type().name().equals(types[i])) throw new IllegalStateException("Unsupported actual Iris element " + names[i]);
        }
        return format.toString();
    }
    private void checkOwner() {
        if (Thread.currentThread() != owner) throw new IllegalStateException("Native capsule capture requires its original render owner");
        RenderSystem.assertOnRenderThread();
    }
    @Override public void close() {
        checkOwner(); closed = true;
        if (stepping) throw new IllegalStateException("Native capture cancelled during callback; close again when the writer returns");
        clearCaches(); table = null;
    }
    /** Exact bounded zeroed allocation; native callbacks can never grow it or write another quad. */
    private static final class OracleStorage extends ByteBufferBuilder {
        private final int capacity; private int count;
        OracleStorage(int capacity) { super(capacity); this.capacity = capacity; }
        @Override public long reserve(int bytes) {
            if (bytes != 52 || count > capacity - bytes) throw new IllegalStateException("Native oracle exceeded qualified quad capacity");
            long pointer = super.reserve(bytes); count += bytes; MemoryUtil.memSet(pointer,0,bytes); return pointer;
        }
    }
    /** Instrumented finite synthetic view. Unsupported reads poison it even when native code catches the exception. */
    private final class SyntheticView implements BlockAndTintGetter {
        private final BlockPos origin; private final int palette, face, mask, solid; private final boolean mixed;
        private long reads; private boolean poisoned;
        private BlockState centerNeighbor; private Direction overrideNeighbor;
        SyntheticView(BlockPos origin,int palette,int face,int mask,int solid,boolean mixed) { this.origin=origin;this.palette=palette;this.face=face;this.mask=mask;this.solid=solid;this.mixed=mixed; }
        private IllegalStateException unsupported(String detail) { poisoned=true; return new IllegalStateException("Unsupported native capsule getter: " + detail); }
        private void check(BlockPos pos) {
            if (++reads > 4096) throw unsupported("read-count budget");
            long x=(long)pos.getX()-origin.getX(), y=(long)pos.getY()-origin.getY(), z=(long)pos.getZ()-origin.getZ();
            if (x < -1 || y < -1 || z < -1 || x > 1 || y > 1 || z > 1) throw unsupported("coordinate outside 3×3×3");
            // Rendering the other five culled faces may inspect their direct neighbor. Any extra AO/model read
            // outside the selected outward ring (plus center/direct culling cells) disproves this reduction.
            boolean admitted = Math.abs(x)+Math.abs(y)+Math.abs(z) <= 1;
            for (int bit=0;!admitted&&bit<8;bit++) admitted=x==NativeVisualCapsule.ring(face,bit,0)
                    &&y==NativeVisualCapsule.ring(face,bit,1)&&z==NativeVisualCapsule.ring(face,bit,2);
            if (!admitted) throw unsupported("coordinate outside selected AO ring and direct culling cells");
        }
        void requireValid() { if(poisoned) throw new IllegalStateException("Native capsule getter contract was poisoned"); }
        @Override public BlockState getBlockState(BlockPos pos) {
            check(pos); int x=pos.getX()-origin.getX(),y=pos.getY()-origin.getY(),z=pos.getZ()-origin.getZ();
            if(x==0&&y==0&&z==0) return PALETTE[palette];
            if(overrideNeighbor!=null&&x==overrideNeighbor.getStepX()&&y==overrideNeighbor.getStepY()&&z==overrideNeighbor.getStepZ())return centerNeighbor;
            if(x==NativeVisualCapsule.normal(face,0)&&y==NativeVisualCapsule.normal(face,1)&&z==NativeVisualCapsule.normal(face,2))return PALETTE[0];
            for(int bit=0;bit<8;bit++)if(x==NativeVisualCapsule.ring(face,bit,0)&&y==NativeVisualCapsule.ring(face,bit,1)&&z==NativeVisualCapsule.ring(face,bit,2))
                return (mask&(1<<bit))==0?PALETTE[0]:PALETTE[mixed?1+Math.floorMod(bit*7+mask,5):solid];
            if(Math.abs(x)+Math.abs(y)+Math.abs(z)==1)return PALETTE[solid];
            return mixed?PALETTE[Math.floorMod(x*13+y*17+z*23+mask,6)]:PALETTE[solid];
        }
        @Override public FluidState getFluidState(BlockPos pos){return getBlockState(pos).getFluidState();}
        @Override public BlockEntity getBlockEntity(BlockPos pos){getBlockState(pos);throw unsupported("block entity");}
        @Override public ModelData getModelData(BlockPos pos){getBlockState(pos);return ModelData.EMPTY;}
        @Override public AuxiliaryLightManager getAuxLightManager(ChunkPos pos){throw unsupported("auxiliary light");}
        @Override public int getHeight(){reads++;return SceneNeighborhood.HEIGHT;}
        @Override public int getMinBuildHeight(){reads++;return SceneNeighborhood.MIN_Y;}
        @Override public int getMaxLightLevel(){reads++;return 15;}
        @Override public float getShade(Direction direction,boolean shade){reads++;if(direction==null)throw unsupported("null shade");return !shade||shader.values().disableDirectionalShading()?1:switch(direction){case DOWN->.5f;case UP->1;case NORTH,SOUTH->.8f;case WEST,EAST->.6f;};}
        @Override public float getShade(float x,float y,float z,boolean shade){if(!Float.isFinite(x)||!Float.isFinite(y)||!Float.isFinite(z))throw unsupported("nonfinite normal");return getShade(Direction.getNearest(x,y,z),shade);}
        @Override public LevelLightEngine getLightEngine(){throw unsupported("propagated light engine");}
        @Override public int getBrightness(LightLayer layer,BlockPos pos){getBlockState(pos);if(layer==null)throw unsupported("null light");return layer==LightLayer.SKY?15:0;}
        @Override public int getRawBrightness(BlockPos pos,int ambient){getBlockState(pos);return 15-Math.min(15,Math.max(0,ambient));}
        @Override public int getBlockTint(BlockPos pos,ColorResolver resolver){getBlockState(pos);throw unsupported("tint");}
    }
}
