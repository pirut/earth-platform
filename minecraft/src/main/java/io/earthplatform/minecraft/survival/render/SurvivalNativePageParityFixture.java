package io.earthplatform.minecraft.survival.render;

import com.mojang.blaze3d.systems.RenderSystem;
import io.earthplatform.core.CancellationToken;
import io.earthplatform.core.CubePos;
import io.earthplatform.core.ImmutableCube;
import io.earthplatform.core.WorldIdentity;
import io.earthplatform.minecraft.enginebridge.NativeCubeCodec;
import io.earthplatform.minecraft.network.EngineCubeSnapshot;
import io.earthplatform.minecraft.network.EnginePaletteContext;
import io.earthplatform.minecraft.renderprobe.RenderPublicationGuard;
import io.earthplatform.minecraft.scene.SceneCube;
import io.earthplatform.minecraft.scene.SceneNeighborhood;
import io.earthplatform.minecraft.scene.SceneRenderEnvelope;
import io.earthplatform.minecraft.survival.source.MarinVisualCubeSource;
import io.earthplatform.minecraft.visual.NativeDistanceCpuBudget;
import io.earthplatform.streaming.CubeStreamAdmission;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

/**
 * INTERNAL opt-in client diagnostic data, never gameplay admission or a complete-overlay capability.
 * Synthetic layouts invoke real registered models, including genuine cutout models outside the
 * intentionally narrow survival simulation palette. Source-page mode uses actual generated/edited
 * cube values but does not attest that the supplied edit map is complete. No launch is installed.
 */
public final class SurvivalNativePageParityFixture implements AutoCloseable {
    public enum Kind { SPARSE_STATES, FULL_PAGE_STATES, CUTOUT_ROLLOVER, SOURCE_PAGE }
    private static final AtomicLong EPOCHS = new AtomicLong();
    private final Thread owner = Thread.currentThread();
    private final Kind kind;
    private final SurvivalDistancePagePlan.Page page;
    private final EnginePaletteContext palette;
    private final WorldIdentity world;
    private final long epoch;
    private final CubeStreamAdmission admission;
    private final RenderPublicationGuard guard;
    private final Map<CubePos, CubeStreamAdmission.Lease> leases = new TreeMap<>();
    private final Map<CubePos, SceneCube> cubes = new TreeMap<>();
    private final Map<CubePos, ImmutableCube> blocks = new TreeMap<>();
    private NativeCubeCodec codec;
    private NativeDistanceCpuBudget.Reservation reservation;
    private boolean closed;

    private SurvivalNativePageParityFixture(Kind kind, SurvivalDistancePagePlan.Page page,
            EnginePaletteContext palette, NativeDistanceCpuBudget budget, String content,
            Function<CubePos, ImmutableCube> values) {
        RenderSystem.assertOnRenderThread();
        this.kind = Objects.requireNonNull(kind); this.page = Objects.requireNonNull(page);
        this.palette = Objects.requireNonNull(palette);
        world = new WorldIdentity(39,"survival-native-page-parity","039-internal",content,NativeCubeCodec.paletteIdentity(palette));
        epoch = EPOCHS.updateAndGet(value -> Math.incrementExact(value));
        var dependencies = page.dependencies();
        admission = new CubeStreamAdmission(world,epoch,SurvivalVisualPage.MAX_DEPENDENCIES);
        guard = new RenderPublicationGuard(admission,SurvivalVisualPage.MAX_DEPENDENCIES);
        // Upper-bound fixture state arrays, encoded payloads, source int arrays, codec and one-cube scratch.
        // This is a diagnostic source owner in the existing SOURCE pool, not another worker pool.
        long bytes = (4L << 20) + dependencies.size() * (EngineCubeSnapshot.MAX_SECTION_BYTES
                + CubePos.VOLUME * 12L + 2048);
        reservation = Objects.requireNonNull(budget).reserve(NativeDistanceCpuBudget.Pool.SOURCE,bytes);
        try {
            codec = new NativeCubeCodec(world,palette);
            for (var lease : admission.replaceInterest(dependencies).requested()) leases.put(lease.position(),lease);
            for (CubePos position : dependencies) install(position,Objects.requireNonNull(values.apply(position)),0);
        } catch (RuntimeException | Error failure) { close(); throw failure; }
    }
    public static SurvivalNativePageParityFixture states(EnginePaletteContext palette, NativeDistanceCpuBudget budget,
            boolean fullPage) {
        var address = SurvivalVisualPage.containing(SurvivalNativeModelFixture.MEMBER);
        long mask = fullPage ? -1L : (1L << 0) | (1L << 1) | (1L << 4) | (1L << 16) | (1L << 63);
        var page = new SurvivalDistancePagePlan.Page(address,mask,0,0);
        return new SurvivalNativePageParityFixture(fullPage ? Kind.FULL_PAGE_STATES : Kind.SPARSE_STATES,
                page,palette,budget,"synthetic-states-at-signed-marin",position -> stateValues(address,position));
    }
    /** Eight real-model members exceed 1 MiB in both layouts without exceeding SceneMesh's per-cube bound. */
    public static SurvivalNativePageParityFixture rollover(EnginePaletteContext palette, NativeDistanceCpuBudget budget) {
        var address = SurvivalVisualPage.containing(SurvivalNativeModelFixture.MEMBER);
        var page = new SurvivalDistancePagePlan.Page(address,0xffL,0,0);
        return new SurvivalNativePageParityFixture(Kind.CUTOUT_ROLLOVER,page,palette,budget,
                "synthetic-native-cutout-rollover",position -> {
                    if (!page.containsMember(position)) return ImmutableCube.uniform(id(Blocks.AIR.defaultBlockState()));
                    int[] values = air();
                    for (int y=0;y<16;y+=2) for (int z=0;z<16;z+=2) for (int x=0;x<16;x+=2)
                        values[CubePos.index(x,y,z)] = id(Blocks.OAK_SAPLING.defaultBlockState());
                    return ImmutableCube.copyOf(values);
                });
    }
    /** Actual generated source plus exact caller-provided overlay values; absence of other edits is not certified. */
    public static SurvivalNativePageParityFixture sourcePage(SurvivalDistancePagePlan.Page page,
            MarinVisualCubeSource source, Map<CubePos, ImmutableCube> diagnosticOverlays, NativeDistanceCpuBudget budget) {
        Objects.requireNonNull(source); Objects.requireNonNull(diagnosticOverlays);
        for (var entry : diagnosticOverlays.entrySet()) {
            if (!page.dependencies().contains(entry.getKey())) throw new IllegalArgumentException("Overlay escapes diagnostic page halo");
            source.palette().validate(entry.getValue());
        }
        var cancellation = new CancellationToken();
        return new SurvivalNativePageParityFixture(Kind.SOURCE_PAGE,page,source.palette().context(),budget,
                source.identity(),position -> diagnosticOverlays.containsKey(position)
                        ? diagnosticOverlays.get(position) : source.generate(position,cancellation));
    }
    private static ImmutableCube stateValues(SurvivalVisualPage page, CubePos position) {
        int[] values = air();
        if (position.equals(page.minimum().offset(-1,0,-1)))
            values[CubePos.index(15,1,15)] = id(Blocks.STONE.defaultBlockState());
        if (position.equals(page.minimum().offset(1,0,0)))
            values[CubePos.index(0,8,8)] = id(Blocks.STONE.defaultBlockState());
        if (page.members().contains(position)) {
            int member = SurvivalPageGeometry.memberIndex(page,position);
            if (member==0 || member==1 || member==4 || member==16 || member==63) {
                var samples = samples();
                for (int i=0;i<samples.size();i++) {
                    int x=2+(i%5)*2,z=2+((i/5)%5)*2,y=4+(i/25)*3;
                    values[CubePos.index(x,y,z)] = id(samples.get(i));
                }
            }
            if (member==0) {
                values[CubePos.index(15,8,8)] = id(Blocks.OAK_LOG.defaultBlockState());
                values[CubePos.index(0,0,0)] = id(Blocks.GRASS_BLOCK.defaultBlockState());
            }
        }
        return ImmutableCube.copyOf(values);
    }
    public static List<BlockState> samples() {
        var result = new ArrayList<BlockState>();
        result.addAll(List.of(Blocks.STONE.defaultBlockState(),Blocks.DIRT.defaultBlockState(),Blocks.GRASS_BLOCK.defaultBlockState(),
                Blocks.COBBLESTONE.defaultBlockState(),Blocks.OAK_PLANKS.defaultBlockState(),Blocks.CRAFTING_TABLE.defaultBlockState()));
        for (var axis : Direction.Axis.values()) result.add(Blocks.OAK_LOG.defaultBlockState().setValue(BlockStateProperties.AXIS,axis));
        for (boolean persistent : new boolean[]{false,true}) for (int distance=1;distance<=7;distance++)
            result.add(Blocks.OAK_LEAVES.defaultBlockState().setValue(BlockStateProperties.PERSISTENT,persistent)
                    .setValue(BlockStateProperties.DISTANCE,distance));
        result.add(Blocks.OAK_SAPLING.defaultBlockState()); result.add(Blocks.SHORT_GRASS.defaultBlockState());
        return List.copyOf(result);
    }
    private static int[] air() { int[] values=new int[CubePos.VOLUME]; Arrays.fill(values,id(Blocks.AIR.defaultBlockState())); return values; }
    private static int id(BlockState state) {
        int id=Block.BLOCK_STATE_REGISTRY.getId(state);
        if (id<0 || Block.BLOCK_STATE_REGISTRY.byId(id)!=state || !state.getFluidState().isEmpty() || state.hasBlockEntity())
            throw new IllegalArgumentException("Unsupported exact native diagnostic state");
        return id;
    }
    private void install(CubePos position, ImmutableCube values, long revision) {
        var snapshot=codec.materialize(position,values,epoch,leases.get(position).generation(),revision);
        var cube=new SceneCube(snapshot,palette,SceneRenderEnvelope.NATIVE,state -> { id(state); return true; });
        var ticket=admission.admit(new CubeStreamAdmission.Header(world,epoch,position,snapshot.leaseGeneration(),revision));
        if (!ticket.accepted()) throw new IllegalStateException("Diagnostic native admission refused: "+ticket.rejection());
        if (admission.commit(ticket.token(),()->{cubes.put(position,cube);blocks.put(position,values);})!=CubeStreamAdmission.Rejection.NONE)
            throw new IllegalStateException("Diagnostic native admission changed before commit");
    }
    public void removeFaceOccluder() { requireSynthetic(); edit(page.address().minimum().offset(1,0,0),0,8,8,Blocks.AIR.defaultBlockState()); }
    public void replaceFaceOccluder() { requireSynthetic(); edit(page.address().minimum().offset(1,0,0),0,8,8,Blocks.STONE.defaultBlockState()); }
    public void removeDiagonalOccluder() { requireSynthetic(); edit(page.address().minimum().offset(-1,0,-1),15,1,15,Blocks.AIR.defaultBlockState()); }
    private void requireSynthetic() {
        checkOpen(); if (kind!=Kind.SPARSE_STATES && kind!=Kind.FULL_PAGE_STATES)
            throw new IllegalStateException("Occluder mutation requires the explicit state fixture");
    }
    private void edit(CubePos position,int x,int y,int z,BlockState state) {
        int[] values=blocks.get(position).toArray(); values[CubePos.index(x,y,z)]=id(state);
        install(position,ImmutableCube.copyOf(values),Math.incrementExact(cubes.get(position).snapshot().revision()));
    }
    public Kind kind() { checkOpen(); return kind; }
    public SurvivalDistancePagePlan.Page page() { checkOpen(); return page; }
    public Map<CubePos,SceneCube> cubes() { checkOpen(); return Map.copyOf(cubes); }
    public RenderPublicationGuard guard() { checkOpen(); return guard; }
    public SceneNeighborhood neighborhood(boolean directional) {
        checkOpen(); return new SceneNeighborhood(cubes,SceneRenderEnvelope.NATIVE,
                SceneNeighborhood.LightingPolicy.SHADER_OUTDOOR_NON_EMISSIVE,directional);
    }
    public long wireBytes() { checkOpen(); return cubes.values().stream().mapToLong(c->c.snapshot().sectionBytes()).sum(); }
    public long sourceReservationBytes() { checkOpen(); return reservation.bytes(); }
    private void checkOpen() { checkOwner(); if(closed)throw new IllegalStateException("Native parity fixture is closed"); }
    private void checkOwner() { if(Thread.currentThread()!=owner)throw new IllegalStateException("Native parity fixture requires its render owner");RenderSystem.assertOnRenderThread(); }
    @Override public void close() {
        checkOwner();if(closed)return;guard.close();admission.close();cubes.clear();blocks.clear();leases.clear();codec=null;
        if(reservation!=null){reservation.releaseAfterPhysicalDrain();reservation=null;}closed=true;
    }
}
