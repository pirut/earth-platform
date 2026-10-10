package io.earthplatform.minecraft.residency;

import io.earthplatform.core.CubePos;
import io.earthplatform.core.ImmutableCube;
import io.earthplatform.minecraft.network.EngineCubeSection;
import io.earthplatform.minecraft.network.EnginePaletteContext;
import io.earthplatform.minecraft.network.EngineCubeSnapshot;
import io.earthplatform.core.WorldIdentity;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Gate 0 owner only: bounded inert sections, explicit implicit-air knowledge, and lossless in-memory
 * retirement. No server/client residency, notifications, lighting, tick execution or disk persistence.
 * Mutable native sections never escape this owner thread. Retired edits remain owned here until reload.
 */
public final class SparseCubeStorage {
    public static final String OPT_IN = "earthplatform.experimental.sparseFacade";
    public enum Presence { RESIDENT, KNOWN_IMPLICIT_AIR, UNLOADED_OWNED, UNKNOWN }
    private static final Set<BlockState> ALLOWED = Set.of(Blocks.AIR.defaultBlockState(), Blocks.STONE.defaultBlockState(),
            Blocks.DIRT.defaultBlockState(), Blocks.GOLD_BLOCK.defaultBlockState(), Blocks.DIAMOND_BLOCK.defaultBlockState());
    private final Thread owner = Thread.currentThread();
    private final EnginePaletteContext palette;
    private final int minY, height, residentLimit, metadataLimit;
    private final Set<CubePos> implicitAir;
    private final Map<CubePos, Resident> resident = new HashMap<>();
    private final Map<CubePos, Retired> retired = new HashMap<>();
    private static final class Resident {
        final EngineCubeSection section;
        long revision;
        Resident(EngineCubeSection section, long revision) { this.section = section; this.revision = revision; }
    }
    private record Retired(ImmutableCube blocks, long revision) {}

    public SparseCubeStorage(EnginePaletteContext palette, int minY, int height, int residentLimit,
            int metadataLimit, Set<CubePos> explicitImplicitAir) {
        if (!Boolean.getBoolean(OPT_IN)) throw new SparseFacadeException("explicit sparseFacade opt-in is required");
        if (minY % 16 != 0 || height <= 0 || height % 16 != 0 || (long) minY + height > Integer.MAX_VALUE
                || residentLimit < 1 || metadataLimit < residentLimit || metadataLimit > 65536) {
            throw new IllegalArgumentException("Aligned bounded height and positive bounded ownership limits required");
        }
        this.palette = Objects.requireNonNull(palette); this.minY = minY; this.height = height;
        this.residentLimit = residentLimit; this.metadataLimit = metadataLimit;
        if (explicitImplicitAir.size() > metadataLimit) throw new IllegalArgumentException("Implicit knowledge exceeds metadata limit");
        this.implicitAir = Set.copyOf(explicitImplicitAir);
        this.implicitAir.forEach(this::checkPosition);
    }
    public int minY() { return minY; }
    public int height() { return height; }
    public int residentCount() { checkOwner(); return resident.size(); }
    public int retiredCount() { checkOwner(); return retired.size(); }
    public Presence presence(CubePos position) {
        checkOwner(); checkPosition(position);
        if (resident.containsKey(position)) return Presence.RESIDENT;
        if (retired.containsKey(position)) return Presence.UNLOADED_OWNED;
        return implicitAir.contains(position) ? Presence.KNOWN_IMPLICIT_AIR : Presence.UNKNOWN;
    }
    /** Explicit load, never called as a side effect of reading or writing an absent cube. */
    public void loadUniform(CubePos position, BlockState state) {
        checkOwner(); checkPosition(position); checkState(state);
        if (resident.containsKey(position) || retired.containsKey(position)) throw new SparseFacadeException("refusing replacement of owned cube " + position);
        checkCapacity(position);
        resident.put(position, new Resident(EngineCubeSection.uniform(palette, state), 0));
    }
    public void materializeImplicitAir(CubePos position) {
        if (presence(position) != Presence.KNOWN_IMPLICIT_AIR) throw new SparseFacadeException("explicit immutable air knowledge required at " + position);
        loadUniform(position, Blocks.AIR.defaultBlockState());
    }
    public BlockState get(ChunkPos facade, BlockPos position) {
        CubePos cube = checkedBlock(facade, position);
        Resident value = resident.get(cube);
        if (value != null) return value.section.get(position.getX() & 15, position.getY() & 15, position.getZ() & 15);
        if (!retired.containsKey(cube) && implicitAir.contains(cube)) return Blocks.AIR.defaultBlockState();
        throw new SparseFacadeException("unknown or unloaded owned space at " + position + "; request/reload its cube, never substitute air");
    }
    /** Returns the previous state, or null for an unchanged native LevelChunk mutation. */
    public BlockState set(ChunkPos facade, BlockPos position, BlockState state, boolean moving) {
        CubePos cube = checkedBlock(facade, position); checkState(state);
        if (moving) throw new SparseFacadeException("moving-block side effects are unsupported");
        Resident value = resident.get(cube);
        if (value == null) throw new SparseFacadeException("mutation requires explicitly resident cube " + cube);
        int x = position.getX() & 15, y = position.getY() & 15, z = position.getZ() & 15;
        BlockState old = value.section.get(x, y, z);
        if (old == state) return null;
        long next;
        try { next = Math.incrementExact(value.revision); }
        catch (ArithmeticException exhausted) { throw new SparseFacadeException("revision exhausted; mutation rejected before changing blocks"); }
        value.section.set(x, y, z, state); value.revision = next;
        return old;
    }
    public long revision(CubePos position) {
        checkOwner(); checkPosition(position);
        Resident value = resident.get(position);
        if (value != null) return value.revision;
        Retired old = retired.get(position);
        if (old != null) return old.revision();
        throw new SparseFacadeException("no owned revision for " + position);
    }
    /** Retains every block and the revision in a bounded immutable snapshot; this is not a disk save. */
    public void retire(CubePos position) {
        checkOwner(); checkPosition(position);
        Resident value = resident.get(position);
        if (value == null) throw new SparseFacadeException("cannot retire nonresident cube " + position);
        int[] states = new int[CubePos.VOLUME];
        for (int y = 0; y < 16; y++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
            states[CubePos.index(x, y, z)] = Block.BLOCK_STATE_REGISTRY.getId(value.section.get(x, y, z));
        }
        retired.put(position, new Retired(ImmutableCube.copyOf(states), value.revision));
        resident.remove(position);
    }
    public void reload(CubePos position) {
        checkOwner(); checkPosition(position);
        Retired value = retired.get(position);
        if (value == null) throw new SparseFacadeException("no retained snapshot for " + position);
        if (resident.size() >= residentLimit) throw new SparseFacadeException("resident budget exhausted");
        EngineCubeSection section = new EngineCubeSection(palette);
        for (int y = 0; y < 16; y++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
            section.set(x, y, z, Block.BLOCK_STATE_REGISTRY.byId(value.blocks().get(x, y, z)));
        }
        resident.put(position, new Resident(section, value.revision())); retired.remove(position);
    }
    /** Immutable snapshot capture on the native owner's thread. */
    public EngineCubeSnapshot capture(CubePos position, WorldIdentity world, long epoch, long leaseGeneration) {
        checkOwner(); checkPosition(position);
        Resident value = resident.get(position);
        if (value == null) throw new SparseFacadeException("snapshot capture requires a resident cube");
        return EngineCubeSnapshot.capture(position, world, epoch, leaseGeneration, value.revision, value.section);
    }
    /** Caller must first admit world/epoch/lease metadata and commit this operation on the owner thread. */
    public void installAdmittedSnapshot(EngineCubeSnapshot snapshot) {
        checkOwner(); checkPosition(snapshot.position());
        Resident old = resident.get(snapshot.position()); Retired retained = retired.get(snapshot.position());
        if (old != null && snapshot.revision() <= old.revision || retained != null && snapshot.revision() < retained.revision()) {
            throw new SparseFacadeException("snapshot would overwrite an equal/newer resident revision or newer retained edit");
        }
        if (old == null && resident.size() >= residentLimit) throw new SparseFacadeException("resident budget exhausted before snapshot materialization");
        if (old == null && retained == null) checkCapacity(snapshot.position());
        EngineCubeSection section = snapshot.materialize(palette);
        for (int y = 0; y < 16; y++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) checkState(section.get(x,y,z));
        resident.put(snapshot.position(), new Resident(section, snapshot.revision())); retired.remove(snapshot.position());
    }
    public void checkOwner() {
        if (Thread.currentThread() != owner) throw new SparseFacadeException("mutable ownership accessed from a non-owner thread");
    }
    private CubePos checkedBlock(ChunkPos facade, BlockPos position) {
        checkOwner();
        if ((position.getX() >> 4) != facade.x || (position.getZ() >> 4) != facade.z) throw new SparseFacadeException("block does not belong to this column view");
        CubePos cube = CubePos.fromBlock(position.getX(), position.getY(), position.getZ()); checkPosition(cube); return cube;
    }
    private void checkPosition(CubePos position) {
        Objects.requireNonNull(position);
        long key = SectionPos.asLong(position.x(), position.y(), position.z());
        if (SectionPos.x(key) != position.x() || SectionPos.y(key) != position.y() || SectionPos.z(key) != position.z()) {
            throw new SparseFacadeException("cube outside lossless section-key range: " + position);
        }
        if (position.minBlockY() < minY || position.minBlockY() >= (long) minY + height) throw new SparseFacadeException("cube outside declared fixture height: " + position);
    }
    private static void checkState(BlockState state) {
        if (state == null || !ALLOWED.contains(state)) throw new SparseFacadeException("unsupported Gate 0 state; only explicit inert vanilla allowlist is enabled");
    }
    private void checkCapacity(CubePos position) {
        if (resident.size() >= residentLimit) throw new SparseFacadeException("resident budget exhausted");
        int used = implicitAir.size();
        for (CubePos pos : resident.keySet()) if (!implicitAir.contains(pos)) used++;
        for (CubePos pos : retired.keySet()) if (!implicitAir.contains(pos)) used++;
        if (!implicitAir.contains(position) && used >= metadataLimit) throw new SparseFacadeException("ownership metadata budget exhausted");
    }
}
