package io.earthplatform.minecraft.coregate;

import io.earthplatform.core.CubePos;
import io.earthplatform.core.WorldIdentity;
import io.earthplatform.minecraft.enginebridge.RollingCubeActor.CubeLease;
import io.earthplatform.minecraft.enginebridge.RollingCubeActor.CubeVersion;
import io.earthplatform.minecraft.enginebridge.RollingCubeActor.StageMetadata;
import io.earthplatform.minecraft.enginebridge.RollingCubeActor.StageReady;
import io.earthplatform.minecraft.enginebridge.RollingCubePlan;
import io.earthplatform.minecraft.enginebridge.ReplacementCertificate;
import io.earthplatform.minecraft.enginebridge.ReplacementSetCertificate;
import io.earthplatform.minecraft.enginebridge.ReplacementQueryAttestation;
import io.earthplatform.minecraft.enginebridge.NativeReplacementCapture;
import io.earthplatform.minecraft.enginebridge.ReplacementWorkspaces;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.TreeSet;
import net.minecraft.network.FriendlyByteBuf;

/**
 * Bounded rolling-stage bodies. World, connection epoch, profile and packet kind belong to the
 * outer envelope. Readers consume precisely one body; the enclosing handler must reject trailing
 * bytes. There are no native buffers or unbounded collections retained by these helpers.
 */
public final class RollingGateWire {
    public static final int WIRE_VERSION = 3;
    public static final int MAX_OWNERS = RollingCubePlan.MAX_COMMITTED_OWNERS;
    public static final int MAX_UNION = RollingCubePlan.MAX_STAGED_OWNERS;
    public static final int DIGEST_LENGTH = 64;
    private RollingGateWire() {}

    public static void writeMetadata(FriendlyByteBuf b, StageMetadata m) {
        validateMetadata(m);
        b.writeVarInt(wireVersion(m.world())); b.writeLong(m.generation()); b.writeBoolean(m.initial()); b.writeBoolean(m.currentPinned()); b.writeBoolean(m.candidatePinned());
        CoreGatePayload.position(b, m.currentFocus()); CoreGatePayload.position(b, m.pinCenter()); CoreGatePayload.position(b, m.currentCenter());
        CoreGatePayload.position(b, m.candidateCenter());
        writeLeases(b, m.currentLeases()); writeLeases(b, m.candidateLeases());
        writePositions(b, m.newPositions()); writePositions(b, m.retiredPositions());
        writePositions(b, m.renderTargets());
    }

    public static StageMetadata readMetadata(FriendlyByteBuf b, WorldIdentity world, long epoch) {
        requireVersion(b,world);
        if(b.readableBytes()<59)throw new IllegalArgumentException("Truncated rolling metadata header");
        long generation = b.readLong();
        int initial = b.readUnsignedByte();
        if (initial > 1) throw new IllegalArgumentException("Noncanonical rolling initial flag");
        boolean currentPinned = flag(b), candidatePinned = flag(b);
        CubePos focus = CoreGatePayload.position(b), pin = CoreGatePayload.position(b), current = CoreGatePayload.position(b), candidate = CoreGatePayload.position(b);
        StageMetadata result = new StageMetadata(world, epoch, generation, initial == 1, pin, current, candidate,
                readLeases(b), readLeases(b), readPositions(b, MAX_OWNERS), readPositions(b, MAX_OWNERS),
                readPositions(b, RollingCubePlan.WINDOW_TARGETS), currentPinned, candidatePinned, focus);
        validateMetadata(result);
        return result;
    }

    public static void writeReady(FriendlyByteBuf b, StageReady ready) {
        validateReady(ready);
        b.writeVarInt(wireVersion(ready.world())); b.writeLong(ready.generation()); CoreGatePayload.position(b, ready.candidateCenter());
        b.writeVarInt(ready.versions().size());
        for (CubeVersion version : ready.versions()) {
            CoreGatePayload.position(b, version.position()); b.writeLong(version.leaseGeneration()); b.writeLong(version.revision());
        }
        b.writeBoolean(ready.pinned());writeCertificate(b,ready.replacementCertificate());
        writeReplacementSet(b,ready.activeFocus(),ready.replacementSet(),ready.replacementCaptures());
        if(RollingArenaWire.arenaWorld(ready.world()))RollingArenaWire.write(b,ready.arenaCertificate(),ready.arenaCapture());
        if(RollingEarthReplacementWire.earthWorld(ready.world()))RollingEarthReplacementWire.write(b,ready.replacementCertificate(),ready.earthReplacementCapture());
    }

    public static StageReady readReady(FriendlyByteBuf b, WorldIdentity world, long epoch) {
        requireVersion(b,world);
        long generation = b.readLong(); CubePos center = CoreGatePayload.position(b);
        int count = readCount(b, MAX_OWNERS, 28);
        List<CubeVersion> versions = new ArrayList<>(count);
        for (int i = 0; i < count; i++) versions.add(new CubeVersion(CoreGatePayload.position(b), b.readLong(), b.readLong()));
        boolean pinned=flag(b);
        ReplacementCertificate legacy=readCertificate(b,world,epoch);
        var set=readReplacementSet(b,world,epoch);
        var arena=RollingArenaWire.arenaWorld(world)?RollingArenaWire.read(b,world,epoch):null;
        var earth=RollingEarthReplacementWire.earthWorld(world)?RollingEarthReplacementWire.read(b,legacy):null;
        StageReady result = new StageReady(world, epoch, generation, center, versions, legacy,pinned,set.focus(),set.certificate(),set.captures(),arena==null?null:arena.certificate(),arena==null?null:arena.capture(),earth);
        validateReady(result);
        return result;
    }

    /** Validates exact canonical geometry before admission can mutate any live lease. */
    public static void validateMetadata(StageMetadata m) {
        Objects.requireNonNull(m, "metadata");
        validateWorld(m.world());
        if (m.epoch() < 0 || m.generation() <= 0) throw new IllegalArgumentException("Invalid rolling identity");
        if(RollingArenaWire.arenaWorld(m.world())&&(m.currentPinned()||m.candidatePinned()
                ||!m.pinCenter().equals(io.earthplatform.minecraft.enginebridge.ArenaDefinition.FIXED.origin())
                ||!m.currentFocus().equals(io.earthplatform.minecraft.enginebridge.ArenaDefinition.FIXED.origin())))
            throw new IllegalArgumentException("Every arena stage must preserve the fixed origin and zero native pins");
        if(RollingEarthReplacementWire.earthWorld(m.world())){
            var original=io.earthplatform.core.CubePos.fromBlock(io.earthplatform.geography.earth.EarthAlpineDefinition.ANCHOR_X,
                    io.earthplatform.geography.earth.EarthAlpineDefinition.GROUND_Y,io.earthplatform.geography.earth.EarthAlpineDefinition.ANCHOR_Z);
            if(!original.equals(m.pinCenter())||!original.equals(m.currentFocus())
                    ||m.currentPinned()&&!io.earthplatform.core.CubeTickets.box(m.currentCenter(),1,1,27).contains(original)
                    ||m.candidatePinned()&&!io.earthplatform.core.CubeTickets.box(m.candidateCenter(),1,1,27).contains(original))
                throw new IllegalArgumentException("Earth native pin must remain inside the moving27 targets");
        }
        RollingCubePlan plan;
        if (m.initial()) {
            boolean arena=RollingArenaWire.arenaWorld(m.world()),earth=RollingEarthReplacementWire.earthWorld(m.world());
            if (!m.currentCenter().equals(m.candidateCenter()) || !arena&&!earth&&!m.pinCenter().equals(m.currentCenter())
                    || !m.currentFocus().equals(m.pinCenter()) || m.generation() != 1) throw new IllegalArgumentException("Initial stage must start at the original pin");
            if(arena){if(m.currentPinned()||m.candidatePinned()||!m.pinCenter().equals(io.earthplatform.minecraft.enginebridge.ArenaDefinition.FIXED.origin()))throw new IllegalArgumentException("Arena initial stage cannot retain pins");}
            else if(earth){boolean pinned=RollingCubePlan.adjacent(m.pinCenter(),m.currentCenter());if(m.currentPinned()!=pinned||m.candidatePinned()!=pinned)throw new IllegalArgumentException("Earth resume pin policy mismatch");}
            else if(!m.currentPinned()||!m.candidatePinned())throw new IllegalArgumentException("Initial pin required");
            plan = RollingCubePlan.stationary(m.pinCenter(), m.currentCenter(), m.candidatePinned());
        } else {
            if (m.currentCenter().equals(m.candidateCenter()) && m.currentPinned()==m.candidatePinned() && m.currentFocus().equals(m.pinCenter())) throw new IllegalArgumentException("Rolling candidate must advance or change pin mode");
            plan = RollingCubePlan.create(m.pinCenter(), m.currentCenter(), m.candidateCenter(),m.currentPinned(),m.candidatePinned());
        }
        List<CubePos> current = m.initial() ? List.of() : plan.committedOwners();
        List<CubePos> candidate = m.initial() ? plan.committedOwners() : plan.nextCommittedOwners();
        requireLeasePositions(m.currentLeases(), current);
        requireLeasePositions(m.candidateLeases(), candidate);
        if (!m.newPositions().equals(m.initial() ? candidate : plan.addedOwners())
                || !m.retiredPositions().equals(m.initial() ? List.of() : plan.retiredOwners())
                || !m.renderTargets().equals(m.initial() ? plan.currentTargets() : plan.candidateTargets()))
            throw new IllegalArgumentException("Rolling metadata differs from exact bounded plan");
        TreeSet<CubePos> union = new TreeSet<>(current); union.addAll(candidate);
        if (union.size() > MAX_UNION) throw new IllegalArgumentException("Rolling union exceeds bound");
        var retained = new java.util.HashMap<CubePos, Long>();
        for (CubeLease lease : m.currentLeases()) retained.put(lease.position(), lease.leaseGeneration());
        for (CubeLease lease : m.candidateLeases()) {
            Long previous = retained.get(lease.position());
            if (previous != null && previous.longValue() != lease.leaseGeneration())
                throw new IllegalArgumentException("Retained lease changed across stage");
        }
    }

    /** Structural ready checks; the replica additionally compares the entire exact live manifest. */
    public static void validateReady(StageReady ready) {
        Objects.requireNonNull(ready, "ready");
        validateWorld(ready.world());
        if (ready.epoch() < 0 || ready.generation() <= 0) throw new IllegalArgumentException("Invalid rolling ready identity");
        RollingCubePlan.validateWindowCenter(ready.candidateCenter());
        var certificate=ready.replacementCertificate();
        if(RollingEarthReplacementWire.earthWorld(ready.world()))RollingEarthReplacementWire.require(certificate,ready.earthReplacementCapture());
        else if(ready.earthReplacementCapture()!=null)throw new IllegalArgumentException("Unadmitted Earth replacement capability");
        if(RollingArenaWire.arenaWorld(ready.world())){if(ready.pinned()||certificate!=null||ready.replacementSet()!=null||ready.arenaCertificate()==null||ready.arenaCapture()==null)throw new IllegalArgumentException("Complete unpinned arena readiness required");ready.arenaCertificate().verifyCapture(ready.arenaCapture());}
        else if(ready.arenaCertificate()!=null||ready.arenaCapture()!=null)throw new IllegalArgumentException("Unadmitted arena capability");
        if(!ready.pinned()&&certificate==null&&ready.replacementSet()==null&&ready.arenaCertificate()==null)throw new IllegalArgumentException("Unpinned readiness requires replacement authority");
        if(certificate!=null&&(!certificate.world().equals(ready.world())||certificate.epoch()!=ready.epoch()))throw new IllegalArgumentException("Replacement certificate scope differs from readiness");
        if (ready.versions().size() < RollingCubePlan.WINDOW_OWNERS || ready.versions().size() > MAX_OWNERS)
            throw new IllegalArgumentException("Rolling manifest owner count");
        CubePos previous = null;
        HashSet<Long> generations = new HashSet<>();
        for (CubeVersion version : ready.versions()) {
            Objects.requireNonNull(version);
            if ((previous != null && previous.compareTo(version.position()) >= 0)
                    || !generations.add(version.leaseGeneration()))
                throw new IllegalArgumentException("Manifest must have ordered unique coordinates and leases");
            previous = version.position();
        }
    }

    /**
     * SHA-256 over a versioned, length-delimited binary manifest. Includes world, epoch, actor
     * generation, XYZ, leases and revisions. Sorted input is required, never silently normalized.
     */
    public static String manifestDigest(StageReady ready) {
        validateReady(ready);
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(8192);
            try (DataOutputStream out = new DataOutputStream(bytes)) {
                string(out, RollingEarthReplacementWire.earthWorld(ready.world())?"earth-rolling-alpine-manifest-v1":RollingArenaWire.arenaWorld(ready.world())?"earth-rolling-arena-manifest-v1":"earth-rolling-manifest-v3");
                WorldIdentity world = ready.world(); out.writeLong(world.seed());
                string(out, world.generatorId()); string(out, world.generatorVersion());
                string(out, world.datasetVersion()); string(out, world.blockPaletteVersion());
                out.writeLong(ready.epoch()); out.writeLong(ready.generation()); position(out, ready.candidateCenter());
                out.writeInt(ready.versions().size());
                for (CubeVersion version : ready.versions()) {
                    position(out, version.position()); out.writeLong(version.leaseGeneration()); out.writeLong(version.revision());
                }
                out.writeBoolean(ready.pinned());
                string(out,ready.replacementCertificate()==null?"NONE":ready.replacementCertificate().identity());
                out.writeBoolean(ready.replacementSet()!=null);
                if(ready.replacementSet()!=null){position(out,ready.activeFocus());string(out,ready.replacementSet().identity());}
                if(ready.arenaCertificate()!=null)string(out,ready.arenaCertificate().identity());
                if(ready.earthReplacementCapture()!=null)string(out,ready.earthReplacementCapture().contentIdentity());
            }
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()));
        } catch (IOException | NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }

    private static void requireLeasePositions(List<CubeLease> leases, List<CubePos> expected) {
        if (leases.size() != expected.size() || leases.size() > MAX_OWNERS)
            throw new IllegalArgumentException("Rolling lease count differs from plan");
        HashSet<Long> generations = new HashSet<>();
        for (int i = 0; i < leases.size(); i++) {
            CubeLease lease = Objects.requireNonNull(leases.get(i));
            if (!lease.position().equals(expected.get(i)) || !generations.add(lease.leaseGeneration()))
                throw new IllegalArgumentException("Rolling leases must exactly follow canonical plan order");
        }
    }

    static void writeCertificate(FriendlyByteBuf b,ReplacementCertificate c) {
        b.writeBoolean(c!=null);if(c==null)return;
        b.writeLong(c.resourceGeneration());CoreGatePayload.position(b,c.originalCube());
        b.writeUtf(c.storeIdentity(),128);b.writeUtf(c.providerIdentity(),128);b.writeUtf(c.paletteIdentity(),128);
        b.writeUtf(c.materialPolicy(),128);b.writeUtf(c.detailPolicy(),128);b.writeLong(c.generation());
        b.writeVarInt(c.dependencies().size());
        for(var v:c.dependencies()){CoreGatePayload.position(b,v.position());b.writeLong(v.leaseGeneration());b.writeLong(v.revision());}
    }
    static ReplacementCertificate readCertificate(FriendlyByteBuf b,WorldIdentity world,long epoch) {
        if(!flag(b))return null;
        long resources=b.readLong();CubePos original=CoreGatePayload.position(b);
        String store=b.readUtf(128),provider=b.readUtf(128),palette=b.readUtf(128),material=b.readUtf(128),detail=b.readUtf(128);
        long generation=b.readLong();int count=readCount(b,27,28);
        if(count!=27)throw new IllegalArgumentException("Complete replacement halo certificate required");
        List<CubeVersion> versions=new ArrayList<>(27);
        for(int i=0;i<count;i++)versions.add(new CubeVersion(CoreGatePayload.position(b),b.readLong(),b.readLong()));
        return new ReplacementCertificate(world,epoch,resources,original,store,provider,palette,material,detail,generation,versions);
    }
    record SetBody(CubePos focus,ReplacementSetCertificate certificate,List<NativeReplacementCapture> captures) {}
    static void writeReplacementSet(FriendlyByteBuf b,CubePos focus,ReplacementSetCertificate c,List<NativeReplacementCapture> captures){
        b.writeByte(c==null?0:c.queryAttestation()==null?1:2);if(c==null){if(focus!=null||!captures.isEmpty())throw new IllegalArgumentException("Set values without complete authority");return;}
        if(!c.workspaces().contains(focus)||captures.size()!=2)throw new IllegalArgumentException("Exact complete replacement set required");
        c.verifyCaptures(captures);
        CoreGatePayload.position(b,focus);b.writeLong(c.resourceGeneration());b.writeUtf(c.storeIdentity(),128);b.writeUtf(c.indexIdentity(),64);
        b.writeUtf(c.providerIdentity(),128);b.writeUtf(c.paletteIdentity(),64);b.writeUtf(c.materialPolicy(),128);b.writeUtf(c.detailPolicy(),128);b.writeLong(c.generation());if(c.queryAttestation()!=null)writeQueryAttestation(b,c.queryAttestation());
        b.writeVarInt(2);
        for(int i=0;i<2;i++){
            var region=c.regions().get(i);var capture=captures.get(i);region.verifyCapture(capture);
            CoreGatePayload.position(b,region.position());b.writeVarInt(27);
            for(var v:region.dependencies()){CoreGatePayload.position(b,v.position());b.writeLong(v.leaseGeneration());b.writeLong(v.revision());}
            b.writeUtf(region.captureIdentity(),64);b.writeByteArray(capture.clonedBytes());
        }
    }
    static SetBody readReplacementSet(FriendlyByteBuf b,WorldIdentity world,long epoch){
        int setKind=b.readUnsignedByte();if(setKind==0)return new SetBody(null,null,List.of());if(setKind>2)throw new IllegalArgumentException("Unknown replacement-set body kind");
        CubePos focus=CoreGatePayload.position(b);long resources=b.readLong();String store=b.readUtf(128),index=b.readUtf(64),provider=b.readUtf(128),palette=b.readUtf(64),material=b.readUtf(128),detail=b.readUtf(128);long generation=b.readLong();var query=setKind==2?readQueryAttestation(b):null;if(setKind==2&&query==null)throw new IllegalArgumentException("Indexed body lacks COMPLETE query");
        if(b.readVarInt()!=2)throw new IllegalArgumentException("Exactly two replacement regions required");
        var regions=new ArrayList<ReplacementSetCertificate.Region>(2);var captures=new ArrayList<NativeReplacementCapture>(2);
        for(int i=0;i<2;i++){
            CubePos position=CoreGatePayload.position(b);if(readCount(b,27,28)!=27)throw new IllegalArgumentException("Exact27 set dependencies required");
            var versions=new ArrayList<CubeVersion>(27);for(int j=0;j<27;j++)versions.add(new CubeVersion(CoreGatePayload.position(b),b.readLong(),b.readLong()));
            var region=new ReplacementSetCertificate.Region(position,versions,b.readUtf(64));
            var capture=new NativeReplacementCapture(position,b.readByteArray(NativeReplacementCapture.MAX_CELLS));region.verifyCapture(capture);regions.add(region);captures.add(capture);
        }
        var set=new ReplacementSetCertificate(world,epoch,resources,store,index,provider,palette,material,detail,generation,regions,query);
        set.verifyCaptures(captures);
        if(!set.workspaces().contains(focus))throw new IllegalArgumentException("Unknown active workspace");
        return new SetBody(focus,set,List.copyOf(captures));
    }

    static void writeQueryAttestation(FriendlyByteBuf b,ReplacementQueryAttestation q){
        b.writeBoolean(q!=null);if(q==null)return;
        b.writeUtf(q.storeIdentity(),128);b.writeUtf(q.indexIdentity(),64);b.writeLong(q.snapshotGeneration());b.writeUtf(q.snapshotRootIdentity(),64);
        b.writeLong(q.minX());b.writeLong(q.minY());b.writeLong(q.minZ());b.writeLong(q.maxX());b.writeLong(q.maxY());b.writeLong(q.maxZ());
        b.writeUtf(q.layoutIdentity(),1024);b.writeUtf(q.policyIdentity(),64);b.writeUtf(q.resultDigest(),64);b.writeVarInt(q.resultCount());
    }
    static ReplacementQueryAttestation readQueryAttestation(FriendlyByteBuf b){
        if(!flag(b))return null;
        String store=b.readUtf(128),index=b.readUtf(64);long generation=b.readLong();String root=b.readUtf(64);
        long x0=b.readLong(),y0=b.readLong(),z0=b.readLong(),x1=b.readLong(),y1=b.readLong(),z1=b.readLong();
        String layout=b.readUtf(1024),policy=b.readUtf(64),result=b.readUtf(64);int count=b.readVarInt();
        return new ReplacementQueryAttestation(store,index,generation,root,x0,y0,z0,x1,y1,z1,layout,policy,result,count);
    }

    private static boolean flag(FriendlyByteBuf b){int value=b.readUnsignedByte();if(value>1)throw new IllegalArgumentException("Noncanonical boolean");return value==1;}

    private static int wireVersion(WorldIdentity world){return RollingEarthReplacementWire.earthWorld(world)?5:RollingArenaWire.arenaWorld(world)?4:WIRE_VERSION;}
    private static void requireVersion(FriendlyByteBuf b,WorldIdentity world) {
        if (b.readVarInt() != wireVersion(world)) throw new IllegalArgumentException("Unsupported rolling body version");
    }
    private static void validateWorld(WorldIdentity world) {
        Objects.requireNonNull(world);
        if (world.generatorId().length() > 128 || world.generatorVersion().length() > 128
                || world.datasetVersion().length() > 128 || world.blockPaletteVersion().length() > 128)
            throw new IllegalArgumentException("World identity exceeds bounded rolling schema");
    }
    private static void writeLeases(FriendlyByteBuf b, List<CubeLease> leases) {
        b.writeVarInt(leases.size());
        for (CubeLease lease : leases) { CoreGatePayload.position(b, lease.position()); b.writeLong(lease.leaseGeneration()); }
    }
    private static List<CubeLease> readLeases(FriendlyByteBuf b) {
        int count = readCount(b, MAX_OWNERS, 20); List<CubeLease> leases = new ArrayList<>(count);
        for (int i = 0; i < count; i++) leases.add(new CubeLease(CoreGatePayload.position(b), b.readLong()));
        return leases;
    }
    private static void writePositions(FriendlyByteBuf b, List<CubePos> positions) {
        b.writeVarInt(positions.size()); for (CubePos p : positions) CoreGatePayload.position(b, p);
    }
    private static List<CubePos> readPositions(FriendlyByteBuf b, int maximum) {
        int count = readCount(b, maximum, 12); List<CubePos> positions = new ArrayList<>(count);
        for (int i = 0; i < count; i++) positions.add(CoreGatePayload.position(b));
        return positions;
    }
    private static int readCount(FriendlyByteBuf b, int maximum, int bytesPerEntry) {
        int count = b.readVarInt();
        if (count < 0 || count > maximum || count > b.readableBytes() / bytesPerEntry)
            throw new IllegalArgumentException("Invalid bounded rolling list length");
        return count;
    }
    private static void string(DataOutputStream out, String value) throws IOException {
        byte[] encoded = value.getBytes(StandardCharsets.UTF_8); out.writeInt(encoded.length); out.write(encoded);
    }
    private static void position(DataOutputStream out, CubePos p) throws IOException {
        out.writeInt(p.x()); out.writeInt(p.y()); out.writeInt(p.z());
    }
}
