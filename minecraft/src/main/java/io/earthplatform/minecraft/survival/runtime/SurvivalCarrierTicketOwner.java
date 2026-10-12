package io.earthplatform.minecraft.survival.runtime;

import io.earthplatform.geography.survival.MarinSurvivalDefinition;
import io.earthplatform.minecraft.survival.host.SurvivalCarrierSerializationRoster;
import io.earthplatform.minecraft.survival.host.SurvivalCarrierTransport;
import io.earthplatform.minecraft.survival.host.SurvivalLevelBinding;
import io.earthplatform.minecraft.survival.world.SurvivalRefusal;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;

/** Owns only nine exact native forced tickets; never changes native holder maps or promotes a ring.
 * The owner is retained by the runtime BEFORE acquire(). A thrown ticket call retains its attempted
 * position as uncertain ownership. No automatic rollback/release hides post-prefix native work.
 */
final class SurvivalCarrierTicketOwner {
    private final InstalledMarinSessionAdmission.Token admission;
    private final ServerLevel carrier;
    private final int centerX = Math.floorDiv(Math.toIntExact(MarinSurvivalDefinition.ANCHOR_X),16);
    private final int centerZ = Math.floorDiv(Math.toIntExact(MarinSurvivalDefinition.ANCHOR_Z),16);
    private final ArrayList<ChunkPos> attempted = new ArrayList<>(9), owned = new ArrayList<>(9);
    private List<LevelChunk> full = List.of();
    private int holders;
    private boolean acquired, prebindSaved, bound, released;
    private Throwable failure;
    SurvivalCarrierTicketOwner(InstalledMarinSessionAdmission.Token admission) {
        this.admission=admission; carrier=admission.carrier();
    }
    void acquire() {
        requireHealthy(); admission.requireTransportPreparation();
        if (acquired || !attempted.isEmpty() || SurvivalLevelBinding.find(carrier)!=null || !carrier.players().isEmpty())
            throw new SurvivalRefusal("Installed transport preparation was reused or carrier is already owned");
        // Pure all-nine foreign-ticket check precedes the first mutation.
        for (int dx=-1;dx<=1;dx++) for (int dz=-1;dz<=1;dz++)
            if (carrier.getForcedChunks().contains(ChunkPos.asLong(centerX+dx,centerZ+dz)))
                throw new SurvivalRefusal("Installed transport needs fresh ownership of all nine exact tickets");
        try {
            for (int dx=-1;dx<=1;dx++) for (int dz=-1;dz<=1;dz++) {
                ChunkPos position=new ChunkPos(centerX+dx,centerZ+dz); attempted.add(position);
                if (!carrier.setChunkForced(position.x,position.z,true))
                    throw new SurvivalRefusal("Exact native transport ticket was not newly acquired");
                owned.add(position);
            }
            acquired=true;
        } catch (RuntimeException | Error error) { failure=error; throw error; }
    }
    boolean ready() {
        requireHealthy(); admission.requireTransportPreparation();
        if (!acquired || released || bound) throw new SurvivalRefusal("Transport readiness requested outside initial preparation");
        if (carrier.getChunkSource().getLoadedChunksCount()>SurvivalCarrierSerializationRoster.MAX_HOLDERS)
            throw new SurvivalRefusal("Native transport holder budget exceeded");
        for (ChunkPos pos:owned) {
            if (!carrier.getForcedChunks().contains(pos.toLong())) throw new SurvivalRefusal("Owned native transport ticket disappeared");
            if (!carrier.isPositionEntityTicking(new BlockPos(pos.getMinBlockX(),135,pos.getMinBlockZ()))
                    || !carrier.areEntitiesLoaded(pos.toLong()) || carrier.getChunkSource().getChunkNow(pos.x,pos.z)==null) return false;
        }
        return true;
    }
    /** Genuine native save, before binding. Its success is distinct from session writer completion. */
    void saveAndSeal() {
        requireHealthy(); admission.requireTransportPreparation();
        if (!ready() || prebindSaved) throw new SurvivalRefusal("Native pre-bind save is absent, premature or repeated");
        try {
            census(); carrier.save(null,true,false); census();
            if (!physicalWritesSettled()) throw new SurvivalRefusal("Native pre-bind save returned without settled physical holder work");
            prebindSaved=true;
        } catch (RuntimeException | Error error) { failure=error; throw error; }
    }
    void bind(SurvivalLevelBinding exact) {
        requireHealthy(); admission.requireCurrent();
        if (!prebindSaved || bound || exact.level()!=carrier || SurvivalLevelBinding.find(carrier)!=exact)
            throw new SurvivalRefusal("Carrier binding lacks exact pre-save transport provenance");
        census();
        for (LevelChunk chunk:full) exact.admitCarrier(chunk);
        exact.admitCarrierSerialization(centerX,centerZ,new SurvivalCarrierSerializationRoster.PhysicalAdmission(
                holders,full.size(),SurvivalCarrierSerializationRoster.chargedMetadataBytes(holders)));
        bound=true;
    }
    /** Read-only drain evidence. Does not equate tickets/Level closure with session-owner drain. */
    boolean physicalWritesSettled() {
        requireHealthy();
        if (carrier.getChunkSource().getPendingTasksCount()!=0 || carrier.getChunkSource().getLightEngine().hasLightWork()) return false;
        for (int x=centerX-16;x<=centerX+16;x++) for (int z=centerZ-16;z<=centerZ+16;z++) {
            var holder=carrier.getChunkSource().chunkMap.getVisibleChunkIfPresent(ChunkPos.asLong(x,z));
            if (holder==null) continue;
            if (holder.getGenerationRefCount()!=0 || !holder.isReadyForSaving()
                    || holder.getAllFutures().stream().anyMatch(pair->pair.getSecond()!=null && !pair.getSecond().isDone())
                    || holder.getLatestChunk()!=null && holder.getLatestChunk().isUnsaved()) return false;
        }
        return true;
    }
    /** Only final shutdown may request release after all other native/session owners have drained.
     * Same-process RETURNED retains these tickets and the binding for genuine reentry.
     */
    void releaseForFinalStop() {
        requireHealthy(); admission.requireTransportPreparation();
        if (!bound || released || !carrier.players().isEmpty() || !physicalWritesSettled())
            throw new SurvivalRefusal("Exact native carrier work has not drained for final ticket release");
        try {
            for (ChunkPos pos:owned) {
                if (!carrier.getForcedChunks().contains(pos.toLong()) || !carrier.setChunkForced(pos.x,pos.z,false))
                    throw new SurvivalRefusal("Exact owned native ticket changed during final release");
            }
            released=true;
        } catch (RuntimeException | Error error) { failure=error; throw error; }
    }
    private void census() {
        int count=0; var chunks=new ArrayList<LevelChunk>(64);
        for (int x=centerX-16;x<=centerX+16;x++) for (int z=centerZ-16;z<=centerZ+16;z++) {
            if (carrier.getChunkSource().chunkMap.getVisibleChunkIfPresent(ChunkPos.asLong(x,z))!=null) count++;
            LevelChunk chunk=carrier.getChunkSource().getChunkNow(x,z);
            if (chunk!=null) {
                if (chunks.size()>=SurvivalCarrierSerializationRoster.MAX_FULL) throw new SurvivalRefusal("Native full-carrier budget exceeded");
                if (SurvivalLevelBinding.find(carrier)==null) verifyUnboundEmpty(chunk);
                else SurvivalCarrierTransport.verifyEmpty(chunk);
                if (chunk.getAttachmentHolder().hasAttachments()) throw new SurvivalRefusal("Native transport chunk has unadmitted attachments");
                chunks.add(chunk);
            }
        }
        if (count!=carrier.getChunkSource().getLoadedChunksCount() || count>SurvivalCarrierSerializationRoster.MAX_HOLDERS || chunks.isEmpty())
            throw new SurvivalRefusal("Native carrier holders escaped the exact admitted serialization envelope");
        holders=count; full=List.copyOf(chunks);
    }
    private void verifyUnboundEmpty(LevelChunk chunk) {
        if (chunk.getClass()!=LevelChunk.class || chunk.getLevel()!=carrier || SurvivalLevelBinding.find(carrier)!=null)
            throw new SurvivalRefusal("Pre-bind transport emptiness requires its exact original LevelChunk");
        var raw=(io.earthplatform.minecraft.survival.host.SurvivalCarrierChunkAccess)chunk;
        if (raw.earthplatform$carrierHeightOwner()!=carrier || !raw.earthplatform$carrierMetadataEmpty()
                || carrier.getMinBuildHeight()!=-64 || carrier.getHeight()!=384)
            throw new SurvivalRefusal("Pre-bind carrier metadata/height envelope differs");
        var sections=raw.earthplatform$carrierSections();
        if (sections.length!=24) throw new SurvivalRefusal("Pre-bind carrier sections exceed fixed native scaffold");
        for (var section:sections)
            if (section==null || section.getStates().maybeHas(state->state!=net.minecraft.world.level.block.Blocks.AIR.defaultBlockState()))
                throw new SurvivalRefusal("Pre-bind carrier contains terrain");
        for (var heightmap:raw.earthplatform$carrierHeightmaps().values())
            for (long value:heightmap.getRawData()) if (value!=0) throw new SurvivalRefusal("Pre-bind carrier contains terrain height data");
        var ticks=raw.earthplatform$carrierTicks();
        if (!(ticks.blocks() instanceof net.minecraft.world.ticks.TickAccess<?> blocks)
                || !(ticks.fluids() instanceof net.minecraft.world.ticks.TickAccess<?> fluids)
                || blocks.count()!=0 || fluids.count()!=0) throw new SurvivalRefusal("Pre-bind carrier contains scheduled terrain");
    }
    private void requireHealthy() {
        admission.requireIdentity();
        if (failure!=null) throw new SurvivalRefusal("Exact failed native carrier owner is retained: "+failure);
    }
}
