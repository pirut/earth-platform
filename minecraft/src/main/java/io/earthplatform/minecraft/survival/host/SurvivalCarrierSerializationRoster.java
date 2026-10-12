package io.earthplatform.minecraft.survival.host;

import io.earthplatform.minecraft.mixin.SurvivalCarrierChunkMapAccess;
import io.earthplatform.minecraft.survival.world.SurvivalRefusal;
import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import java.util.concurrent.CompletableFuture;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkResult;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ImposterProtoChunk;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;

/**
 * Save-only tombstone ownership, separate from cubic gameplay and packet owners. This admits an
 * already quiescent physical native scaffold; it neither loads chunks nor authenticates promotion.
 * A new holder, changed ready future, changed chunk, or changed generation status requires a new
 * lifecycle design, not a coordinate match. There is intentionally no release/re-admit operation.
 */
public final class SurvivalCarrierSerializationRoster {
    public static final int RADIUS = 16, MAX_HOLDERS = 1089, MAX_FULL = 64;
    public static final int MAX_METADATA_BYTES = 160 * 1024;
    // Conservative charged metadata, not an assertion about exact JVM object size. Native holder,
    // future and chunk graphs are retained EXISTING scaffold owners, separately bounded below.
    private static final java.util.List<ChunkStatus> STATUSES = ChunkStatus.getStatusList();
    private static final int FIXED_METADATA_BYTES = 4096 + MAX_HOLDERS * Long.BYTES;
    private static final int BYTES_PER_LINEAGE = 128;
    public record PhysicalAdmission(int nativeHolderOwners, int nativeFullOwners, int metadataBytes) {
        public PhysicalAdmission {
            if (nativeHolderOwners < 1 || nativeHolderOwners > MAX_HOLDERS
                    || nativeFullOwners < 0 || nativeFullOwners > MAX_FULL
                    || metadataBytes != chargedMetadataBytes(nativeHolderOwners))
                throw new SurvivalRefusal("Invalid pre-admitted native scaffold/serializer metadata budget");
        }
    }
    public record Usage(int nativeHolderOwners, int nativeFullOwners, int chargedMetadataBytes,
            int maximumNativeHolderOwners, int maximumNativeFullOwners, int maximumMetadataBytes) {}
    private record Lineage(ChunkHolder holder, ChunkAccess readyChunk, ChunkStatus started,
            ChunkStatus selected, ChunkStatus persisted, CompletableFuture<ChunkResult<ChunkAccess>> readyFuture) {}
    private final SurvivalLevelBinding binding;
    private final ServerLevel level;
    private final SurvivalCarrierChunkMapAccess maps;
    private final int centerX, centerZ;
    private final PhysicalAdmission admission;
    private final Lineage[] entries;
    private boolean failed;

    public static int chargedMetadataBytes(int holders) {
        if (holders < 1 || holders > MAX_HOLDERS) throw new SurvivalRefusal("Native holder budget exceeded");
        int bytes = Math.addExact(FIXED_METADATA_BYTES, Math.multiplyExact(holders, BYTES_PER_LINEAGE));
        if (bytes > MAX_METADATA_BYTES) throw new SurvivalRefusal("Serializer roster metadata budget exceeded");
        return bytes;
    }
    static SurvivalCarrierSerializationRoster admit(SurvivalLevelBinding binding, ServerLevel level,
            int centerX, int centerZ, PhysicalAdmission admission) {
        binding.checkOpen();
        if (binding.level() != level) throw new SurvivalRefusal("Foreign serialization Level");
        var maps = (SurvivalCarrierChunkMapAccess) level.getChunkSource().chunkMap;
        // First pass contains no roster/map/list copies. Count and validate all three native views
        // before allocating the fixed positional table or any Lineage object.
        int holders = 0, full = 0;
        for (int view = 0; view < 3; view++) {
            var map = view(maps, view);
            if (map.size() > MAX_HOLDERS) throw new SurvivalRefusal("Native holder view exceeds physical admission");
            for (var item : map.long2ObjectEntrySet()) {
                var holder = item.getValue();
                if (holder == null || holder.getClass() != ChunkHolder.class || item.getLongKey() != holder.getPos().toLong())
                    throw new SurvivalRefusal("Unknown native holder implementation or position");
                index(holder.getPos(), centerX, centerZ);
                boolean seen = false;
                for (int earlier = 0; earlier < view; earlier++) {
                    var previous = view(maps, earlier).get(item.getLongKey());
                    if (previous != null && previous != holder) throw new SurvivalRefusal("Native views disagree on holder identity");
                    seen |= previous != null;
                }
                if (seen) continue;
                if (++holders > MAX_HOLDERS) throw new SurvivalRefusal("Native holder union exceeds physical admission");
                var chunk = requireReady(holder);
                requireChunkClass(chunk);
                if (chunk instanceof LevelChunk || chunk instanceof ImposterProtoChunk) {
                    if (++full > MAX_FULL) throw new SurvivalRefusal("Native full owners exceed physical admission");
                    binding.requireCarrier(fullChunk(chunk));
                }
                SurvivalCarrierTransport.verifySerializationEmpty(level, chunk);
            }
        }
        if (holders != admission.nativeHolderOwners() || full != admission.nativeFullOwners())
            throw new SurvivalRefusal("Actual native owner census differs from caller physical admission");
        return new SurvivalCarrierSerializationRoster(binding, level, maps, centerX, centerZ, admission);
    }
    private SurvivalCarrierSerializationRoster(SurvivalLevelBinding binding, ServerLevel level,
            SurvivalCarrierChunkMapAccess maps, int centerX, int centerZ, PhysicalAdmission admission) {
        this.binding = binding; this.level = level; this.maps = maps;
        this.centerX = centerX; this.centerZ = centerZ; this.admission = admission;
        this.entries = new Lineage[MAX_HOLDERS];
        int count = 0;
        for (int view = 0; view < 3; view++) for (var holder : view(maps, view).values()) {
            int index = index(holder.getPos(), centerX, centerZ);
            if (entries[index] != null) {
                if (entries[index].holder() != holder) throw new SurvivalRefusal("Native holder changed during sealing");
                continue;
            }
            var access = (SurvivalCarrierGenerationLineageAccess) holder;
            var chunk = requireReady(holder);
            var started = access.earthplatform$carrierStartedStatus();
            var selected = selectedStatus(holder, started);
            var future = access.earthplatform$carrierFuture(selected);
            if (future == null || future.getNow(null) == null || future.getNow(null).orElse(null) != chunk)
                throw new SurvivalRefusal("Native ready future changed during sealing");
            entries[index] = new Lineage(holder, chunk, started, selected, chunk.getPersistedStatus(), future); count++;
        }
        if (count != admission.nativeHolderOwners()) throw new SurvivalRefusal("Native roster changed during sealing");
    }
    public Usage usage() {
        binding.checkCarrierTransportState();
        return new Usage(admission.nativeHolderOwners(), admission.nativeFullOwners(), admission.metadataBytes(),
                MAX_HOLDERS, MAX_FULL, MAX_METADATA_BYTES);
    }
    void requireWrite(ServerLevel exactLevel, ChunkAccess chunk) {
        binding.checkCarrierTransportState();
        if (failed) throw new SurvivalRefusal("Carrier serialization lineage is permanently fenced after refusal");
        try {
            if (exactLevel != level || SurvivalLevelBinding.find(exactLevel) != binding
                    || level.getChunkSource().chunkMap != maps)
                throw new SurvivalRefusal("Foreign native serializer Level/binding");
            // Native unload may already have removed the saved holder from all views. Retained
            // lineage still owns that exact object; any NEW holder in a live view is refused.
            for (int view = 0; view < 3; view++) {
                var map = view(maps, view);
                if (map.size() > MAX_HOLDERS) throw new SurvivalRefusal("Native holder view grew beyond admission");
                for (var item : map.long2ObjectEntrySet()) {
                    var holder = item.getValue();
                    if (holder == null || item.getLongKey() != holder.getPos().toLong())
                        throw new SurvivalRefusal("Malformed native holder view");
                    var entry = entries[index(holder.getPos(), centerX, centerZ)];
                    if (entry == null || entry.holder() != holder) throw new SurvivalRefusal("Unknown/replaced native holder");
                }
            }
            var entry = entries[index(chunk.getPos(), centerX, centerZ)];
            if (entry == null) throw new SurvivalRefusal("No pre-admitted native serialization lineage");
            var holder = entry.holder();
            var access = (SurvivalCarrierGenerationLineageAccess) holder;
            if (!holder.getPos().equals(chunk.getPos()) || requireReady(holder) != entry.readyChunk()
                    || entry.readyChunk().getPersistedStatus() != entry.persisted()
                    || access.earthplatform$carrierStartedStatus() != entry.started()
                    || access.earthplatform$carrierFuture(entry.selected()) != entry.readyFuture()
                    || entry.readyFuture().getNow(null).orElse(null) != entry.readyChunk())
                throw new SurvivalRefusal("Native current-ready holder/future/chunk lineage changed");
            boolean exact = chunk == entry.readyChunk();
            // Native may unwrap its already-pinned wrapper. Never admit a freshly manufactured
            // wrapper just because it wraps a known full owner at the same coordinate.
            if (!exact && entry.readyChunk() instanceof ImposterProtoChunk wrapper)
                exact = chunk == wrapper.getWrapped();
            if (!exact) throw new SurvivalRefusal("Serializer chunk is not the exact admitted ready identity");
            if (entry.readyChunk() instanceof LevelChunk || entry.readyChunk() instanceof ImposterProtoChunk)
                binding.requireCarrier(fullChunk(entry.readyChunk()));
            SurvivalCarrierTransport.verifySerializationEmpty(level, entry.readyChunk());
        } catch (RuntimeException | Error failure) {
            failed = true; // No retry/re-admission can hide an observed replacement or ABA attempt.
            throw failure;
        }
    }
    private static ChunkAccess requireReady(ChunkHolder holder) {
        if (!holder.isReadyForSaving() || holder.getGenerationRefCount() != 0)
            throw new SurvivalRefusal("Native holder is not ready for serialization");
        var access = (SurvivalCarrierGenerationLineageAccess) holder;
        var started = access.earthplatform$carrierStartedStatus();
        if (started == null) throw new SurvivalRefusal("Native holder has no started generation lineage");
        // Do not join/pump a future or demote exceptional completion to absence.
        for (var status : STATUSES) {
            var future = access.earthplatform$carrierFuture(status);
            if (future != null) {
                if (!future.isDone()) throw new SurvivalRefusal("Native generation future remains pending");
                future.getNow(null); // Exceptional/cancelled completion propagates.
            }
        }
        var chunk = holder.getLatestChunk();
        if (chunk == null || !holder.getPos().equals(chunk.getPos())) throw new SurvivalRefusal("Native ready chunk missing or foreign");
        return chunk;
    }
    private static ChunkStatus selectedStatus(ChunkHolder holder, ChunkStatus started) {
        return holder.getChunkIfPresentUnchecked(started) != null ? started : started.getParent();
    }
    private static LevelChunk fullChunk(ChunkAccess chunk) {
        return chunk instanceof ImposterProtoChunk wrapper ? wrapper.getWrapped() : (LevelChunk) chunk;
    }
    private static void requireChunkClass(ChunkAccess chunk) {
        if (chunk.getClass() != ProtoChunk.class && chunk.getClass() != LevelChunk.class && chunk.getClass() != ImposterProtoChunk.class)
            throw new SurvivalRefusal("Unknown native carrier serialization implementation");
    }
    private static int index(ChunkPos pos, int centerX, int centerZ) {
        long x = (long) pos.x - centerX + RADIUS, z = (long) pos.z - centerZ + RADIUS;
        if (x < 0 || x > 2 * RADIUS || z < 0 || z > 2 * RADIUS)
            throw new SurvivalRefusal("Native serialization owner escaped admitted 33x33 halo");
        return (int) (x * 33 + z);
    }
    private static Long2ObjectLinkedOpenHashMap<ChunkHolder> view(SurvivalCarrierChunkMapAccess maps, int index) {
        return switch (index) {
            case 0 -> maps.earthplatform$carrierUpdating();
            case 1 -> maps.earthplatform$carrierVisible();
            case 2 -> maps.earthplatform$carrierPendingUnloads();
            default -> throw new AssertionError(index);
        };
    }
}
