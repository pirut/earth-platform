package io.earthplatform.minecraft.coregate;

import io.earthplatform.core.CubePos;
import io.earthplatform.core.WorldIdentity;
import io.earthplatform.minecraft.enginebridge.NativeCubeCodec;
import io.earthplatform.minecraft.enginebridge.NativeEarthCubeProvider;
import io.earthplatform.minecraft.enginebridge.RollingCubePlan;
import io.earthplatform.minecraft.enginebridge.RollingEditPolicy;
import io.earthplatform.minecraft.network.EnginePaletteContext;
import io.earthplatform.minecraft.lod.DhLodLayout;
import io.earthplatform.minecraft.lod.DhAnalyticFlatPolicy;
import io.earthplatform.minecraft.enginebridge.ReplacementWorkspaces;
import io.netty.buffer.Unpooled;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Immutable admission policy for one bounded rolling host. The 3D provider remains separate
 * from any optional heightfield sampler. Decoding this descriptor never opens source files,
 * constructs SceneTerrain, samples terrain, or changes a provider's unknown-coverage policy.
 * An Earth descriptor is structural evidence only: the host must separately compare the
 * actual prepared, wrapped provider identity before admitting cubes or far summaries.
 */
public final class RollingSessionDescriptor {
    public static final int WIRE_VERSION = 5;
    public static final int INDEXED_WIRE_VERSION = 6;
    public static final int ARENA_WIRE_VERSION = 7;
    public static final int RESUME_WIRE_VERSION = 8;
    public static final String ARENA = "arena", ARENA_PROFILE_ID = "arena-v1";
    public static final int MAX_WIRE_BYTES = 4096;
    public static final String VERSION = "rolling-session-descriptor-v6";
    // Source and durable-store identity are independent of presentation-policy evolution.
    private static final int LEGACY_WIRE_VERSION=5;
    private static final String LEGACY_PRESENTATION_VERSION="rolling-session-descriptor-v5";
    private static final String LEGACY_IDENTITY_VERSION = "rolling-session-descriptor-v1";
    private static final int LEGACY_STORE_FAR_RADIUS = 512;
    public static final String ANALYTIC = "analytic", EARTH = "earth";
    public static final String EARTH_PROFILE_ID = "earth";
    public static final String ALPINE_PROFILE_ID = "everest-v1";
    public static final int EARTH_BODY_RADIUS = 128;
    public static final int EARTH_MIN_CENTER_Y = -686, EARTH_MAX_CENTER_Y = 551;
    public static final int EARTH_BODY_MIN_Y = -10976, EARTH_BODY_MAX_Y = 8832;
    private static final int MAX_ID = 128, MAX_KIND = 16, MAX_PROFILE_ID = 16, MAX_LAYOUT_ID = 32, MAX_LAYOUT_IDENTITY = 1024;

    /** Inclusive full-body boundaries, not feet-only permission. */
    public record BodyEnvelope(long minX, long maxX, int minY, int maxY, long minZ, long maxZ) {
        public BodyEnvelope {
            if (minX >= maxX || minY >= maxY || minZ >= maxZ)
                throw new IllegalArgumentException("Empty body envelope");
        }
    }
    /** Inclusive center ranges. Every permitted center has complete radius-two dependencies. */
    public record WindowEnvelope(int minX, int maxX, int minY, int maxY, int minZ, int maxZ) {
        public WindowEnvelope {
            if (minX > maxX || minY > maxY || minZ > maxZ)
                throw new IllegalArgumentException("Empty window envelope");
        }
    }

    private final WorldIdentity world;
    private final String profileKind, profileId, paletteFingerprint, sourceFingerprint, fingerprint, storeFingerprint;
    private final DhLodLayout lodLayout;
    private final RollingEditPolicy editPolicy;
    private final CubePos pinCenter, initialCenter;
    private final Vec3 entry;
    private final int anchorY;
    private final long fixedFarCenterX, fixedFarCenterZ;
    private final boolean naturalRelief, twoWorkspaces, adjacentWorkspaces, indexedWorkspaces, arenaMode;
    private final BodyEnvelope bodyEnvelope;
    private final WindowEnvelope windowEnvelope;

    private RollingSessionDescriptor(WorldIdentity world, String profileKind, String profileId,
            String paletteFingerprint, CubePos pinCenter, CubePos initialCenter, Vec3 entry,
            int anchorY, long fixedFarCenterX, long fixedFarCenterZ, boolean naturalRelief, DhLodLayout lodLayout, RollingEditPolicy editPolicy,
            BodyEnvelope bodyEnvelope, WindowEnvelope windowEnvelope, boolean twoWorkspaces, boolean adjacentWorkspaces) {
        this(world,profileKind,profileId,paletteFingerprint,pinCenter,initialCenter,entry,anchorY,fixedFarCenterX,fixedFarCenterZ,naturalRelief,lodLayout,editPolicy,bodyEnvelope,windowEnvelope,twoWorkspaces,adjacentWorkspaces,false);
    }

    private RollingSessionDescriptor(WorldIdentity world, String profileKind, String profileId,
            String paletteFingerprint, CubePos pinCenter, CubePos initialCenter, Vec3 entry,
            int anchorY, long fixedFarCenterX, long fixedFarCenterZ, boolean naturalRelief, DhLodLayout lodLayout, RollingEditPolicy editPolicy,
            BodyEnvelope bodyEnvelope, WindowEnvelope windowEnvelope, boolean twoWorkspaces, boolean adjacentWorkspaces, boolean indexedWorkspaces) {
        this(world,profileKind,profileId,paletteFingerprint,pinCenter,initialCenter,entry,anchorY,fixedFarCenterX,fixedFarCenterZ,naturalRelief,lodLayout,editPolicy,bodyEnvelope,windowEnvelope,twoWorkspaces,adjacentWorkspaces,indexedWorkspaces,false);
    }

    private RollingSessionDescriptor(WorldIdentity world, String profileKind, String profileId,
            String paletteFingerprint, CubePos pinCenter, CubePos initialCenter, Vec3 entry,
            int anchorY, long fixedFarCenterX, long fixedFarCenterZ, boolean naturalRelief, DhLodLayout lodLayout, RollingEditPolicy editPolicy,
            BodyEnvelope bodyEnvelope, WindowEnvelope windowEnvelope, boolean twoWorkspaces, boolean adjacentWorkspaces, boolean indexedWorkspaces, boolean arenaMode) {
        this.arenaMode=arenaMode;
        if(arenaMode&&(twoWorkspaces||adjacentWorkspaces||indexedWorkspaces||naturalRelief||!ARENA.equals(profileKind)))
            throw new IllegalArgumentException("Arena capability cannot be combined with legacy profiles");
        this.world = boundedWorld(world);
        this.profileKind = boundedString(profileKind, MAX_KIND);
        this.profileId = boundedString(profileId, MAX_PROFILE_ID);
        this.paletteFingerprint = requireDigest(paletteFingerprint);
        this.pinCenter = Objects.requireNonNull(pinCenter);
        this.initialCenter = Objects.requireNonNull(initialCenter);
        this.entry = Objects.requireNonNull(entry);
        this.anchorY = anchorY;
        this.fixedFarCenterX = fixedFarCenterX;
        this.fixedFarCenterZ = fixedFarCenterZ;
        this.naturalRelief = naturalRelief;
        this.twoWorkspaces=twoWorkspaces;
        this.adjacentWorkspaces=adjacentWorkspaces;
        this.indexedWorkspaces=indexedWorkspaces;
        if(indexedWorkspaces&&!adjacentWorkspaces)throw new IllegalArgumentException("Indexed gate requires the accepted two adjacent workspaces");
        if(adjacentWorkspaces&&!twoWorkspaces)throw new IllegalArgumentException("Adjacent mode requires the complete two-workspace capability");
        this.lodLayout = Objects.requireNonNull(lodLayout);
        this.editPolicy = Objects.requireNonNull(editPolicy);
        if (editPolicy != RollingEditPolicy.INTERIOR_V1 && !ANALYTIC.equals(profileKind) && !arenaMode)
            throw new IllegalArgumentException("Boundary editing requires the analytic replacement profile");
        if(twoWorkspaces&&(!ANALYTIC.equals(profileKind)||naturalRelief||editPolicy!=RollingEditPolicy.FULL_CUBE_V1))
            throw new IllegalArgumentException("Two workspaces require explicit full-cube analytic authority");
        this.bodyEnvelope = Objects.requireNonNull(bodyEnvelope);
        this.windowEnvelope = Objects.requireNonNull(windowEnvelope);
        this.sourceFingerprint = sourceDigest(world);
        this.fingerprint = sha256(canonicalBytes(false));
        this.storeFingerprint = sha256(canonicalBytes(true));
    }

    /** New fixed source/store contract; never reinterpret an older analytic workspace save. */
    public static RollingSessionDescriptor arena(EnginePaletteContext palette) {
        var source=io.earthplatform.minecraft.enginebridge.ArenaTerrainSource.FIXED;
        var provider=new io.earthplatform.minecraft.enginebridge.NativeArenaCubeProvider(source,palette);
        var origin=io.earthplatform.minecraft.enginebridge.ArenaDefinition.FIXED.origin();
        int ground=Math.toIntExact(source.surfaceY(8,12));
        var result=new RollingSessionDescriptor(provider.identity(),ARENA,ARENA_PROFILE_ID,palette.fingerprint(),origin,CubePos.fromBlock(8,ground+1,12),
                new Vec3(8.5,ground+1.01,12.5),ground,0,0,false,DhLodLayout.ARENA_4096,RollingEditPolicy.FULL_CUBE_V1,
                new BodyEnvelope(-256,256,96,256,-256,256),new WindowEnvelope(-16,15,6,15,-16,15),false,false,false,true);
        result.validateGeometry();return result;
    }

    /** Preserves the exact existing fixture world identity, coordinates and save directory. */
    public static RollingSessionDescriptor analytic(CoreGateProfile profile, EnginePaletteContext palette) {
        return analytic(profile, palette, RollingEditPolicy.INTERIOR_V1);
    }
    /** Admission policy changes the session digest, never terrain or portable-store identity. */
    public static RollingSessionDescriptor analytic(CoreGateProfile profile, EnginePaletteContext palette, RollingEditPolicy editPolicy) {
        return analytic(profile,palette,editPolicy,false);
    }
    public static RollingSessionDescriptor analytic(CoreGateProfile profile, EnginePaletteContext palette, RollingEditPolicy editPolicy, boolean twoWorkspaces) {
        return analytic(profile,palette,editPolicy,twoWorkspaces,false);
    }
    /** Explicit face-adjacent topology; never inferred from terrain or a global property after construction. */
    public static RollingSessionDescriptor analytic(CoreGateProfile profile, EnginePaletteContext palette, RollingEditPolicy editPolicy, boolean twoWorkspaces, boolean adjacentWorkspaces) {
        return analytic(profile,palette,editPolicy,twoWorkspaces,adjacentWorkspaces,false);
    }
    /** Explicit paged-index authority, with unchanged two live regions and source identity. */
    public static RollingSessionDescriptor analytic(CoreGateProfile profile, EnginePaletteContext palette, RollingEditPolicy editPolicy, boolean twoWorkspaces, boolean adjacentWorkspaces, boolean indexedWorkspaces) {
        Objects.requireNonNull(profile); Objects.requireNonNull(palette);
        var result = new RollingSessionDescriptor(RollingGateFixture.provider(palette, profile).identity(),
                ANALYTIC, profile.id(), palette.fingerprint(), profile.center(), profile.center(),
                profile.entry(), profile.anchorY(), 0, 0, false, DhLodLayout.LONG_4096, editPolicy,
                new BodyEnvelope(-512, 512, CoreGateProfile.GAMEPLAY_MIN_Y,
                        CoreGateProfile.GAMEPLAY_MAX_Y_EXCLUSIVE, -512, 512),
                new WindowEnvelope(-32, 31, RollingCubePlan.MIN_WINDOW_CENTER_Y,
                        RollingCubePlan.MAX_WINDOW_CENTER_Y, -32, 31),twoWorkspaces,adjacentWorkspaces,indexedWorkspaces);
        result.validateGeometry();
        return result;
    }

    /**
     * The ground coordinate must already have been sampled by the bootstrap worker. It pins
     * the one original editable cube; the entry feet may legitimately lie in the next Y cube.
     * The prepared provider must have captured naturalRelief=true before this factory is used.
     */
    public static RollingSessionDescriptor earth(WorldIdentity wrappedSourceWorld, EnginePaletteContext palette,
            long groundX, int groundY, long groundZ) {
        Objects.requireNonNull(palette);
        validateEarthWorld(wrappedSourceWorld, palette);
        // Check before arithmetic or narrowing. Keep the fixed far footprint inside exact native X/Z.
        requireFarCenter(groundX, DhLodLayout.FIXED_512); requireFarCenter(groundZ, DhLodLayout.FIXED_512);
        if (groundY < -11000 || groundY > 8849)
            throw new IllegalArgumentException("Earth anchor outside source height domain");
        CubePos pin = CubePos.fromBlock(groundX, groundY, groundZ);
        BodyEnvelope body = new BodyEnvelope(groundX - EARTH_BODY_RADIUS, groundX + EARTH_BODY_RADIUS,
                EARTH_BODY_MIN_Y, EARTH_BODY_MAX_Y, groundZ - EARTH_BODY_RADIUS, groundZ + EARTH_BODY_RADIUS);
        WindowEnvelope windows = new WindowEnvelope(CubePos.section(body.minX()), CubePos.section(body.maxX() - 1),
                EARTH_MIN_CENTER_Y, EARTH_MAX_CENTER_Y, CubePos.section(body.minZ()), CubePos.section(body.maxZ() - 1));
        var result = new RollingSessionDescriptor(wrappedSourceWorld, EARTH, EARTH_PROFILE_ID,
                palette.fingerprint(), pin, pin, new Vec3(groundX + .5, groundY + 3.0, groundZ + .5),
                groundY, groundX, groundZ, true, DhLodLayout.FIXED_512, RollingEditPolicy.INTERIOR_V1, body, windows,false,false);
        result.validateGeometry();
        return result;
    }

    /** New isolated real-source profile. Raster preparation and source-ground checks precede use. */
    public static RollingSessionDescriptor alpine(WorldIdentity wrappedSourceWorld, EnginePaletteContext palette,
            long groundX, int groundY, long groundZ) {
        Objects.requireNonNull(palette);
        boundedWorld(wrappedSourceWorld);
        if (!io.earthplatform.minecraft.enginebridge.NativeAlpineCubeProvider.GENERATOR_ID.equals(wrappedSourceWorld.generatorId())
                || !(io.earthplatform.minecraft.enginebridge.NativeAlpineCubeProvider.GENERATOR_VERSION + "/" + RollingGateFixture.VERSION).equals(wrappedSourceWorld.generatorVersion())
                || wrappedSourceWorld.seed()!=io.earthplatform.geography.earth.EarthAlpineDefinition.SEED
                || !wrappedSourceWorld.datasetVersion().equals(io.earthplatform.geography.earth.EarthAlpineDefinition.DEFINITION_IDENTITY)
                || !NativeCubeCodec.paletteIdentity(palette).equals(wrappedSourceWorld.blockPaletteVersion()))
            throw new IllegalArgumentException("Alpine descriptor requires its exact wrapped source/palette contract");
        if (groundX != io.earthplatform.geography.earth.EarthAlpineDefinition.ANCHOR_X
                || groundZ != io.earthplatform.geography.earth.EarthAlpineDefinition.ANCHOR_Z
                || groundY != io.earthplatform.geography.earth.EarthAlpineDefinition.GROUND_Y || Math.floorMod(groundY,16)<1 || Math.floorMod(groundY,16)>13)
            throw new IllegalArgumentException("Alpine anchor or interior-edit height differs from the finite policy");
        CubePos pin=CubePos.fromBlock(groundX,groundY,groundZ);
        var policy=io.earthplatform.geography.earth.EarthAlpineDefinition.ENVELOPE;
        var body=new BodyEnvelope(policy.minTravelX(),policy.maxTravelXExclusive(),
                io.earthplatform.geography.earth.EarthAlpineDefinition.BODY_MIN_Y,io.earthplatform.geography.earth.EarthAlpineDefinition.BODY_MAX_Y,
                policy.minTravelZ(),policy.maxTravelZExclusive());
        var windows=new WindowEnvelope(policy.minCenterX(),policy.maxCenterXExclusive()-1,policy.minCenterY(),policy.maxCenterYExclusive()-1,
                policy.minCenterZ(),policy.maxCenterZExclusive()-1);
        var result=new RollingSessionDescriptor(wrappedSourceWorld,EARTH,ALPINE_PROFILE_ID,palette.fingerprint(),pin,pin,
                new Vec3(groundX+.5,groundY+1.01,groundZ+.5),groundY,groundX,groundZ,true,DhLodLayout.EARTH_ALPINE_2048,
                RollingEditPolicy.INTERIOR_V1,body,windows,false,false);
        result.validateGeometry();return result;
    }

    /** New presentation pose only. Source, original editable pin, durable directory and envelope stay exact. */
    public RollingSessionDescriptor resumeAt(Vec3 heldFeet) {
        if(!alpineMode())throw new IllegalStateException("Only exact Alpine continuation is supported");
        Objects.requireNonNull(heldFeet);
        var center=RollingCubePlan.centerFromFeet(heldFeet.x,heldFeet.y,heldFeet.z);
        var result=new RollingSessionDescriptor(world,profileKind,profileId,paletteFingerprint,pinCenter,center,heldFeet,
                anchorY,fixedFarCenterX,fixedFarCenterZ,naturalRelief,lodLayout,editPolicy,bodyEnvelope,windowEnvelope,false,false);
        result.validateGeometry();
        if(!storeFingerprint.equals(result.storeFingerprint))throw new IllegalStateException("Continuation changed durable identity");
        return result;
    }
    public boolean initialPinned(){return !arenaMode&&(!alpineMode()||RollingCubePlan.adjacent(pinCenter,initialCenter));}
    public boolean earthResumeBootstrap(){return alpineMode()&&!initialPinned();}
    public boolean resumed(){return alpineMode()&&(!initialCenter.equals(pinCenter)||!entry.equals(new Vec3(fixedFarCenterX+.5,anchorY+1.01,fixedFarCenterZ+.5)));}
    /** Prepared source comparison deliberately excludes only the owner-validated presentation pose. */
    public boolean samePreparedSource(RollingSessionDescriptor prepared){return equals(prepared)||alpineMode()&&prepared!=null&&prepared.alpineMode()&&equals(prepared.resumeAt(entry));}

    private void validateGeometry() {
        if (!arenaMode&&!alpineMode()&&!pinCenter.equals(initialCenter)) throw new IllegalArgumentException("Initial center must be original pin");
        requireFarCenter(fixedFarCenterX, lodLayout); requireFarCenter(fixedFarCenterZ, lodLayout);
        requireAllowedWindow(initialCenter);
        if(twoWorkspaces){for(var workspace:workspaces().positions())requireAllowedWindow(workspace);flatPolicy();}
        // This also proves fixed-pin dependencies and the unchanged source/mesh accounting.
        RollingCubePlan.stationary(pinCenter, initialCenter,initialPinned()).validate();
        if (!containsBody(walkEntryBody())) throw new IllegalArgumentException("Entry body outside bounded session");
    }

    /** Exact signed XYZ/domain validation before a desired/candidate window can reach the actor. */
    public void requireAllowedWindow(CubePos center) {
        Objects.requireNonNull(center);
        RollingCubePlan.validateWindowCenter(center);
        var e = windowEnvelope;
        if (center.x() < e.minX() || center.x() > e.maxX()
                || center.y() < e.minY() || center.y() > e.maxY()
                || center.z() < e.minZ() || center.z() > e.maxZ())
            throw new IllegalArgumentException("Window outside bounded session envelope");
        if (naturalRelief && (((long) center.y() - RollingCubePlan.OWNER_RADIUS) * CubePos.SIZE < CoreGateProfile.GAMEPLAY_MIN_Y
                || ((long) center.y() + RollingCubePlan.OWNER_RADIUS + 1) * CubePos.SIZE > CoreGateProfile.GAMEPLAY_MAX_Y_EXCLUSIVE))
            throw new IllegalArgumentException("Earth window exceeds complete source Y domain");
    }

    /** Validates both bounded centers and the existing at-most-213 source union, without sampling. */
    public void requireAllowedTransition(CubePos current, CubePos candidate) {
        requireAllowedWindow(current); requireAllowedWindow(candidate);
        RollingCubePlan.create(pinCenter, current, candidate,!arenaMode,!arenaMode).validate();
    }

    public boolean containsBody(AABB body) {
        if (!finitePlayerBody(body)) return false;
        var e = bodyEnvelope;
        return body.minX >= e.minX() && body.maxX <= e.maxX() && body.minY >= e.minY()
                && body.maxY <= e.maxY() && body.minZ >= e.minZ() && body.maxZ <= e.maxZ();
    }

    static boolean finitePlayerBody(AABB body) {
        if (body == null) return false;
        for (double v : new double[]{body.minX, body.minY, body.minZ, body.maxX, body.maxY, body.maxZ})
            if (!Double.isFinite(v)) return false;
        return body.getXsize() > 0 && body.getXsize() <= 1 && body.getYsize() > 0 && body.getYsize() <= 2
                && body.getZsize() > 0 && body.getZsize() <= 1;
    }

    public boolean distantMaskEligible(CubePos position) {
        Objects.requireNonNull(position);
        return position.minBlockY() >= CoreGateProfile.GAMEPLAY_MIN_Y
                && position.minBlockY() + CubePos.SIZE <= CoreGateProfile.GAMEPLAY_MAX_Y_EXCLUSIVE;
    }
    public boolean validatesPreparedWorld(WorldIdentity preparedWrappedWorld) { return world.equals(preparedWrappedWorld); }
    public void requirePreparedWorld(WorldIdentity preparedWrappedWorld) {
        if (!validatesPreparedWorld(preparedWrappedWorld)) throw new IllegalArgumentException("Prepared provider identity mismatch");
    }

    /** Length-bounded self-contained descriptor; enclosing packets still reject trailing fields. */
    public void write(FriendlyByteBuf target) {
        var b = new FriendlyByteBuf(Unpooled.buffer(512, MAX_WIRE_BYTES));
        try {
            writeFields(b);
            b.writeUtf(sourceFingerprint, 64); b.writeUtf(fingerprint, 64);
            target.writeVarInt(resumed()?RESUME_WIRE_VERSION:arenaMode?ARENA_WIRE_VERSION:indexedWorkspaces?INDEXED_WIRE_VERSION:LEGACY_WIRE_VERSION); target.writeVarInt(b.readableBytes()); target.writeBytes(b);
        } finally { b.release(); }
    }

    /** Structural-only Earth validation. Never prepares terrain or loads a source file. */
    public static RollingSessionDescriptor read(FriendlyByteBuf source, WorldIdentity expectedWorld, EnginePaletteContext palette) {
        Objects.requireNonNull(expectedWorld); Objects.requireNonNull(palette);
        int wireVersion=source.readVarInt();
        if (wireVersion != RESUME_WIRE_VERSION && wireVersion != ARENA_WIRE_VERSION && wireVersion != INDEXED_WIRE_VERSION && wireVersion != LEGACY_WIRE_VERSION) throw new IllegalArgumentException("Unsupported rolling session version");
        int count = source.readVarInt();
        if (count <= 0 || count > MAX_WIRE_BYTES || count > source.readableBytes())
            throw new IllegalArgumentException("Invalid bounded rolling session length");
        var b = new FriendlyByteBuf(source.readSlice(count));
        WorldIdentity world = CoreGatePayload.world(b);
        if (!world.equals(expectedWorld)) throw new IllegalArgumentException("Rolling session world mismatch");
        String kind = b.readUtf(MAX_KIND), id = b.readUtf(MAX_PROFILE_ID), paletteHash = b.readUtf(64);
        if (!palette.fingerprint().equals(paletteHash)) throw new IllegalArgumentException("Rolling session palette mismatch");
        CubePos pin = CoreGatePayload.position(b), initial = CoreGatePayload.position(b);
        Vec3 entry = new Vec3(b.readDouble(), b.readDouble(), b.readDouble());
        int anchor = b.readInt(); long farX = b.readLong(), farZ = b.readLong();
        int relief = b.readUnsignedByte();
        if (relief > 1) throw new IllegalArgumentException("Noncanonical relief policy");
        String layoutId = b.readUtf(MAX_LAYOUT_ID), layoutIdentity = b.readUtf(MAX_LAYOUT_IDENTITY);
        DhLodLayout layout = null;
        for (var candidate : DhLodLayout.values()) if (candidate.id().equals(layoutId)) layout = candidate;
        if (layout == null || !layout.identity().equals(layoutIdentity))
            throw new IllegalArgumentException("Unknown or altered immutable far layout");
        String editPolicyId = b.readUtf(32);
        RollingEditPolicy editPolicy = null;
        for (var candidate : RollingEditPolicy.values()) if (candidate.id().equals(editPolicyId)) editPolicy = candidate;
        if (editPolicy == null) throw new IllegalArgumentException("Unknown immutable edit admission policy");
        boolean two=readFlag(b),adjacent=readFlag(b),indexed=wireVersion==INDEXED_WIRE_VERSION&&readFlag(b);if(wireVersion==INDEXED_WIRE_VERSION&&!indexed)throw new IllegalArgumentException("Indexed version requires explicit indexed mode");String workspaceIdentity=b.readUtf(64),flatIdentity=b.readUtf(1024);
        BodyEnvelope body = new BodyEnvelope(b.readLong(), b.readLong(), b.readInt(), b.readInt(), b.readLong(), b.readLong());
        WindowEnvelope windows = new WindowEnvelope(b.readInt(), b.readInt(), b.readInt(), b.readInt(), b.readInt(), b.readInt());
        var received = new RollingSessionDescriptor(world, kind, id, paletteHash, pin, initial, entry, anchor,
                farX, farZ, relief == 1, layout, editPolicy, body, windows,two,adjacent,indexed,wireVersion==ARENA_WIRE_VERSION);
        if(!workspaceIdentity.equals(received.workspaceIdentity())||!flatIdentity.equals(received.basePolicyIdentity()))throw new IllegalArgumentException("Workspace or flat-source identity mismatch");
        if (!received.sourceFingerprint.equals(b.readUtf(64)) || !received.fingerprint.equals(b.readUtf(64)) || b.isReadable())
            throw new IllegalArgumentException("Rolling session source/policy fingerprint mismatch");
        if(wireVersion==RESUME_WIRE_VERSION&&(!received.alpineMode()||!received.resumed()))throw new IllegalArgumentException("Resume wire requires a distinct exact Alpine presentation pose");
        final RollingSessionDescriptor canonical;
        if (ARENA.equals(kind)&&ARENA_PROFILE_ID.equals(id)&&wireVersion==ARENA_WIRE_VERSION) canonical=arena(palette);
        else if (ANALYTIC.equals(kind)) canonical = analytic(CoreGateProfile.byId(id), palette, editPolicy,two,adjacent,indexed);
        else if (EARTH.equals(kind) && ALPINE_PROFILE_ID.equals(id)) canonical = wireVersion==RESUME_WIRE_VERSION?alpine(world,palette,farX,anchor,farZ).resumeAt(entry):alpine(world, palette, farX, anchor, farZ);
        else if (EARTH.equals(kind) && EARTH_PROFILE_ID.equals(id)) canonical = earth(world, palette, farX, anchor, farZ);
        else throw new IllegalArgumentException("Unknown rolling session kind/profile");
        if (canonical.lodLayout != received.lodLayout || !canonical.world.equals(world) || !canonical.fingerprint.equals(received.fingerprint))
            throw new IllegalArgumentException("Rolling session policy differs from bounded canonical profile");
        return canonical;
    }

    private void writeFields(FriendlyByteBuf b) {
        CoreGatePayload.world(b, world);
        b.writeUtf(profileKind, MAX_KIND); b.writeUtf(profileId, MAX_PROFILE_ID); b.writeUtf(paletteFingerprint, 64);
        CoreGatePayload.position(b, pinCenter); CoreGatePayload.position(b, initialCenter);
        b.writeDouble(entry.x); b.writeDouble(entry.y); b.writeDouble(entry.z);
        b.writeInt(anchorY); b.writeLong(fixedFarCenterX); b.writeLong(fixedFarCenterZ);
        b.writeBoolean(naturalRelief); b.writeUtf(lodLayout.id(), MAX_LAYOUT_ID); b.writeUtf(lodLayout.identity(), MAX_LAYOUT_IDENTITY); b.writeUtf(editPolicy.id(), 32);
        b.writeBoolean(twoWorkspaces);b.writeBoolean(adjacentWorkspaces);if(indexedWorkspaces)b.writeBoolean(true);b.writeUtf(workspaceIdentity(),64);b.writeUtf(basePolicyIdentity(),1024);
        var e = bodyEnvelope;
        b.writeLong(e.minX()); b.writeLong(e.maxX()); b.writeInt(e.minY()); b.writeInt(e.maxY()); b.writeLong(e.minZ()); b.writeLong(e.maxZ());
        var w = windowEnvelope;
        b.writeInt(w.minX()); b.writeInt(w.maxX()); b.writeInt(w.minY()); b.writeInt(w.maxY()); b.writeInt(w.minZ()); b.writeInt(w.maxZ());
    }

    /** Versioned, length-delimited binary hashing avoids ambiguous string concatenations. */
    private byte[] canonicalBytes(boolean legacyStoreIdentity) {
        try {
            var bytes = new ByteArrayOutputStream(1024);
            try (var out = new DataOutputStream(bytes)) {
                string(out, arenaMode?(legacyStoreIdentity?"arena-session-store-v1":"arena-session-descriptor-v1"):legacyStoreIdentity ? LEGACY_IDENTITY_VERSION : indexedWorkspaces?VERSION:LEGACY_PRESENTATION_VERSION); world(out, world); string(out, profileKind); string(out, profileId); string(out, paletteFingerprint);
                position(out, pinCenter); position(out, legacyStoreIdentity&&alpineMode()?pinCenter:initialCenter);
                Vec3 identityEntry=legacyStoreIdentity&&alpineMode()?new Vec3(fixedFarCenterX+.5,anchorY+1.01,fixedFarCenterZ+.5):entry;
                out.writeDouble(identityEntry.x); out.writeDouble(identityEntry.y); out.writeDouble(identityEntry.z);
                out.writeInt(anchorY); out.writeLong(fixedFarCenterX); out.writeLong(fixedFarCenterZ);
                out.writeBoolean(naturalRelief);
                if (legacyStoreIdentity&&!arenaMode) out.writeInt(LEGACY_STORE_FAR_RADIUS);
                else { string(out, lodLayout.id()); string(out, lodLayout.identity()); string(out, editPolicy.id());out.writeBoolean(twoWorkspaces);out.writeBoolean(adjacentWorkspaces);if(indexedWorkspaces)out.writeBoolean(true);string(out,workspaceIdentity());string(out,basePolicyIdentity()); }
                var e = bodyEnvelope;
                out.writeLong(e.minX()); out.writeLong(e.maxX()); out.writeInt(e.minY()); out.writeInt(e.maxY()); out.writeLong(e.minZ()); out.writeLong(e.maxZ());
                var w = windowEnvelope;
                out.writeInt(w.minX()); out.writeInt(w.maxX()); out.writeInt(w.minY()); out.writeInt(w.maxY()); out.writeInt(w.minZ()); out.writeInt(w.maxZ());
                string(out, sourceFingerprint);
            }
            return bytes.toByteArray();
        } catch (IOException impossible) { throw new AssertionError(impossible); }
    }

    private static boolean readFlag(FriendlyByteBuf b){int v=b.readUnsignedByte();if(v>1)throw new IllegalArgumentException("Noncanonical workspace flag");return v==1;}

    private static void validateEarthWorld(WorldIdentity world, EnginePaletteContext palette) {
        boundedWorld(world);
        if (!NativeEarthCubeProvider.GENERATOR_ID.equals(world.generatorId())
                || !(NativeEarthCubeProvider.GENERATOR_VERSION + "/" + RollingGateFixture.VERSION).equals(world.generatorVersion())
                || !world.datasetVersion().matches("[0-9a-f]{64}")
                || !NativeCubeCodec.paletteIdentity(palette).equals(world.blockPaletteVersion()))
            throw new IllegalArgumentException("Earth descriptor requires exact wrapped native source/palette identity");
    }
    private static WorldIdentity boundedWorld(WorldIdentity w) {
        Objects.requireNonNull(w);
        for (String s : new String[]{w.generatorId(), w.generatorVersion(), w.datasetVersion(), w.blockPaletteVersion()}) boundedString(s, MAX_ID);
        return w;
    }
    private static String boundedString(String value, int max) {
        Objects.requireNonNull(value);
        if (value.isBlank() || value.length() > max) throw new IllegalArgumentException("Descriptor string exceeds bounded schema");
        return value;
    }
    private static String requireDigest(String value) {
        if (value == null || !value.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("Canonical SHA-256 required");
        return value;
    }
    private static void requireFarCenter(long block, DhLodLayout layout) {
        long min = (long) RollingCubePlan.NATIVE_XZ_MIN * CubePos.SIZE;
        long maxExclusive = ((long) RollingCubePlan.NATIVE_XZ_MAX + 1) * CubePos.SIZE;
        if (block < Math.addExact(min, layout.radiusBlocks()) || block > Math.subtractExact(maxExclusive, layout.radiusBlocks()))
            throw new IllegalArgumentException("Descriptor far footprint exceeds signed native coordinate domain");
    }
    private static String sourceDigest(WorldIdentity world) {
        try {
            var bytes = new ByteArrayOutputStream(512);
            try (var out = new DataOutputStream(bytes)) { string(out, LEGACY_IDENTITY_VERSION + "/source"); world(out, world); }
            return sha256(bytes.toByteArray());
        } catch (IOException impossible) { throw new AssertionError(impossible); }
    }
    private static String sha256(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
    private static void world(DataOutputStream out, WorldIdentity w) throws IOException {
        out.writeLong(w.seed()); string(out, w.generatorId()); string(out, w.generatorVersion()); string(out, w.datasetVersion()); string(out, w.blockPaletteVersion());
    }
    private static void string(DataOutputStream out, String value) throws IOException {
        byte[] encoded = value.getBytes(StandardCharsets.UTF_8); out.writeInt(encoded.length); out.write(encoded);
    }
    private static void position(DataOutputStream out, CubePos p) throws IOException { out.writeInt(p.x()); out.writeInt(p.y()); out.writeInt(p.z()); }

    @Override public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof RollingSessionDescriptor d)) return false;
        return anchorY == d.anchorY && fixedFarCenterX == d.fixedFarCenterX && fixedFarCenterZ == d.fixedFarCenterZ
                && arenaMode==d.arenaMode && naturalRelief == d.naturalRelief && twoWorkspaces==d.twoWorkspaces && adjacentWorkspaces==d.adjacentWorkspaces && indexedWorkspaces==d.indexedWorkspaces && lodLayout == d.lodLayout && editPolicy == d.editPolicy && world.equals(d.world) && profileKind.equals(d.profileKind)
                && profileId.equals(d.profileId) && paletteFingerprint.equals(d.paletteFingerprint)
                && pinCenter.equals(d.pinCenter) && initialCenter.equals(d.initialCenter) && entry.equals(d.entry)
                && bodyEnvelope.equals(d.bodyEnvelope) && windowEnvelope.equals(d.windowEnvelope)
                && sourceFingerprint.equals(d.sourceFingerprint) && fingerprint.equals(d.fingerprint);
    }
    @Override public int hashCode() { return fingerprint.hashCode(); }
    @Override public String toString() { return profileKind + "/" + profileId + "@" + pinCenter + ":" + fingerprint; }

    public WorldIdentity world() { return world; }
    public WorldIdentity sourceIdentity() { return world; }
    public String sourceFingerprint() { return sourceFingerprint; }
    public String paletteFingerprint() { return paletteFingerprint; }
    public String fingerprint() { return fingerprint; }
    public String profileKind() { return profileKind; }
    public String profileId() { return profileId; }
    public String id() { return profileId; }
    public CubePos pinCenter() { return pinCenter; }
    public CubePos initialCenter() { return initialCenter; }
    public CubePos center() { return pinCenter; }
    public CubePos target() { return pinCenter; }
    public Vec3 entry() { return entry; }
    public int anchorY() { return anchorY; }
    public long fixedFarCenterX() { return fixedFarCenterX; }
    public long fixedFarCenterZ() { return fixedFarCenterZ; }
    /** Radius is immutable for this session; the center follows authoritative native candidates. */
    public int fixedFarRadius() { return lodLayout.radiusBlocks(); }
    public DhLodLayout lodLayout() { return lodLayout; }
    public RollingEditPolicy editPolicy() { return editPolicy; }
    public boolean permitsEdit(long x, long y, long z) { return arenaMode?arenaDefinition().editable(x,y,z):editPolicy.permits(pinCenter, x, y, z); }
    public boolean naturalRelief() { return naturalRelief; }
    public boolean twoWorkspaces(){return twoWorkspaces;}
    public boolean adjacentWorkspaces(){return adjacentWorkspaces;}
    public boolean indexedWorkspaces(){return indexedWorkspaces;}
    public boolean arenaMode(){return arenaMode;}
    public boolean alpineMode(){return EARTH.equals(profileKind)&&ALPINE_PROFILE_ID.equals(profileId);}
    public boolean playerFacing(){return arenaMode||alpineMode();}
    public io.earthplatform.minecraft.enginebridge.ArenaDefinition arenaDefinition(){if(!arenaMode)throw new IllegalStateException("Not an arena descriptor");return io.earthplatform.minecraft.enginebridge.ArenaDefinition.FIXED;}
    public io.earthplatform.minecraft.enginebridge.ArenaTerrainSource arenaSource(){if(!arenaMode)throw new IllegalStateException("Not an arena descriptor");return io.earthplatform.minecraft.enginebridge.ArenaTerrainSource.FIXED;}
    private String workspaceIdentity(){return arenaMode?arenaDefinition().identity():twoWorkspaces?workspaces().identity():"NONE";}
    private String basePolicyIdentity(){return arenaMode?arenaSource().identity():twoWorkspaces?flatPolicy().identity():"NONE";}
    public ReplacementWorkspaces workspaces(){if(!twoWorkspaces)throw new IllegalStateException("Single-workspace descriptor");return adjacentWorkspaces?ReplacementWorkspaces.adjacent(pinCenter):ReplacementWorkspaces.two(pinCenter);}
    public DhAnalyticFlatPolicy flatPolicy(){if(!twoWorkspaces||naturalRelief||!ANALYTIC.equals(profileKind))throw new IllegalStateException("No certified analytic-flat capability");return new DhAnalyticFlatPolicy(world.datasetVersion(),world,anchorY,0xff808080);}
    public boolean permitsEdit(CubePos focus,long x,long y,long z){return arenaMode?arenaDefinition().positions().contains(focus)&&permitsEdit(x,y,z):twoWorkspaces?workspaces().contains(focus)&&editPolicy.permits(focus,x,y,z):pinCenter.equals(focus)&&permitsEdit(x,y,z);}
    public BodyEnvelope bodyEnvelope() { return bodyEnvelope; }
    public WindowEnvelope windowEnvelope() { return windowEnvelope; }
    public AABB walkEntryBody() { return new AABB(entry.x - .3, entry.y, entry.z - .3, entry.x + .3, entry.y + 1.8, entry.z + .3); }
    public String saveDirectory() {
        return alpineMode()?"earthplatform-everest-v1/"+storeFingerprint:arenaMode?"earthplatform-finite-arena-v1/"+storeFingerprint:indexedWorkspaces ? "earthplatform-indexed-workspace-player-gate-v1/"+profileId : adjacentWorkspaces ? "earthplatform-adjacent-workspace-player-gate-v1/"+profileId : twoWorkspaces ? "earthplatform-two-workspace-player-gate-v1/"+profileId : ANALYTIC.equals(profileKind) ? RollingGateFixture.directory(CoreGateProfile.byId(profileId))
                : "earthplatform-earth-player-gate-v1/" + storeFingerprint;
    }
}
