package io.earthplatform.geography.earth;

import io.earthplatform.core.CubePos;
import io.earthplatform.geography.*;
import io.earthplatform.geography.landcover.*;
import io.earthplatform.geography.variety.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/**
 * Immutable, bounded real-Earth definition for candidate 030. Only load performs I/O.
 * Original EGM2008 heights, measured land-cover labels, synthetic relief and cosmetic
 * material choices are separate values. No shelf, water surface, vegetation or height offset.
 */
public final class EarthAlpineDefinition {
    public static final String VERSION = "earth-alpine-retained-030-v1";
    public static final long SEED = 20221009L;
    public static final String SOURCE_IDENTITY = "43f04c62b1d64e34d483aee38c3d77ed74503a21aa3b0e3b5669736e9b3f7fb2";
    public static final String DETAIL_IDENTITY = "463b5b1a3762fd3bc6fde03aa6e4e494f466e38c903b7d3eb364fe19bf5f4d29";
    public static final String MATERIAL_POLICY_IDENTITY = "7d097a26035415c298570265a79c2e3970c95acb15c2f2d2147096e09e59b4c8";
    public static final String DEFINITION_IDENTITY = "5c180d8b046dee2ef5e41536efd4d8b65f72d197bb36295b5e04eb05a0b05444";
    public static final String MATERIAL_POLICY = "conservative-high-alpine-opaque-v1";
    public static final int SOURCE_HALO = 8, MAX_FOOTPRINT = 128;
    public static final int FAR_RADIUS = 2048, FAR_ALIGNMENT = 128, FAR_SNAP = 128;
    public static final int TRAVEL_WIDTH = 512, NATIVE_RADIUS_CUBES = 2;
    public static final long MIN_BLOCK_Y = -11008, MAX_BLOCK_Y_EXCLUSIVE = 8864;
    public static final long ANCHOR_X = 9678074, ANCHOR_Z = -3114278;
    public static final int BODY_RADIUS = 256, BODY_MIN_Y = -10976, BODY_MAX_Y = 8832;
    public static final long GROUND_Y = 7314;
    public static final float ENTRY_YAW = 133.03987f, ENTRY_PITCH = -33.55703f;
    public static final String PROFILE_ID = "everest-v1", SAVE_NAMESPACE = "earthplatform-everest-v1";
    public static final Anchor ANCHOR = new Anchor(ANCHOR_X,ANCHOR_Z,GROUND_Y,GROUND_Y+1,ENTRY_YAW,ENTRY_PITCH,
            new Block(ANCHOR_X,GROUND_Y,ANCHOR_Z+2),new Block(ANCHOR_X+1,GROUND_Y+1,ANCHOR_Z+2));
    public static final Envelope ENVELOPE = createEnvelope();
    private static final String EGP_SHA = "597f435d639ea71f35d7b716b6a5f6b846e7dd22a8b8b13e7b12482e90927bca";
    private static final String COVER_SHA = "5276fb2adcf1c570e8328c126bc03a7c757cef2b8553f85a5a107b4a2608fdc7";
    private static final EquirectangularProjection PROJECTION = new EquirectangularProjection(new GeoPoint(0,0),0);
    private final OfflineTerrain terrain;
    private final OfflineLandCover landCover;
    private final LandCoverRaster coverageRaster;
    private final AlpineSurfaceRelief detail;
    private final Envelope envelope;
    private final Anchor anchor;
    private final String sourceIdentity, detailIdentity, materialPolicyIdentity, identity;

    public enum Material {
        AIR(0xFF000000), STONE(0xFF8A8A8A), ANDESITE(0xFF999A99), TUFF(0xFF777A69), SNOW_BLOCK(0xFFF1F5F6);
        private final int argb;
        Material(int argb) { this.argb = argb; }
        /** Full-alpha deterministic preview colour. AIR remains an occupancy decision, not rendered black. */
        public int argb() { return argb; }
    }
    public enum MaterialReceipt { SOURCE_SNOW_SLOPE_SHELTER, SOURCE_BARE_COSMETIC_ROCK, UNRESOLVED_MAPPED_CLASS_CONSERVATIVE_ROCK }
    public record Block(long x,long y,long z) {}
    public record Anchor(long x,long z,long surfaceY,double feetY,float yaw,float pitch,
                         Block digTarget,Block placementTarget) {
        public CubePos center() { return CubePos.fromBlock(x,(long)StrictMath.floor(feetY),z); }
        public double centreX() { return x+0.5; }
        public double centreZ() { return z+0.5; }
    }
    /** All horizontal bounds are half-open; center bounds are cube coordinates. */
    public record Envelope(int minCenterX,int maxCenterXExclusive,int minCenterZ,int maxCenterZExclusive,
                           int minCenterY,int maxCenterYExclusive,long minColumnX,long maxColumnXExclusive,
                           long minColumnZ,long maxColumnZExclusive) {
        public long minTravelX() { return (long)minCenterX*16; }
        public long maxTravelXExclusive() { return (long)maxCenterXExclusive*16; }
        public long minTravelZ() { return (long)minCenterZ*16; }
        public long maxTravelZExclusive() { return (long)maxCenterZExclusive*16; }
        public boolean containsColumn(long x,long z) {
            return x>=minColumnX && x<maxColumnXExclusive && z>=minColumnZ && z<maxColumnZExclusive;
        }
        public boolean permitsCenter(CubePos p) {
            return p!=null && p.x()>=minCenterX && p.x()<maxCenterXExclusive && p.z()>=minCenterZ && p.z()<maxCenterZExclusive
                    && p.y()>=minCenterY && p.y()<maxCenterYExclusive;
        }
    }
    public record Column(double sourceMetres,double proceduralDeltaMetres,double generatedMetres,long surfaceY,
                         LandCoverSample sourceClassification,LandCoverSurfacePolicy.Selection appearance,
                         double eastGradient,double southGradient,double shelter,
                         Material top,Material subsurface,Material deep,MaterialReceipt materialReceipt) {
        public Column {
            Objects.requireNonNull(sourceClassification); Objects.requireNonNull(appearance);
            Objects.requireNonNull(top); Objects.requireNonNull(subsurface); Objects.requireNonNull(deep); Objects.requireNonNull(materialReceipt);
            if (!Double.isFinite(sourceMetres)||!Double.isFinite(proceduralDeltaMetres)||!Double.isFinite(generatedMetres)
                    ||Math.abs(proceduralDeltaMetres)>2 || sourceClassification.status()!=LandCoverSample.Status.MAPPED
                    || generatedMetres!=sourceMetres+proceduralDeltaMetres || surfaceY!=(long)StrictMath.floor(generatedMetres)
                    ||top==Material.AIR||subsurface==Material.AIR||deep!=Material.STONE)
                throw new IllegalArgumentException("Invalid bounded source/detail/material column");
        }
        public Material materialAt(long y) {
            return y>surfaceY?Material.AIR:y==surfaceY?top:y>=surfaceY-3?subsurface:deep;
        }
        public int argb() { return top.argb(); }
    }

    /** Loads only pinned bounded local packs; returns only after the complete moving profile is proved. */
    public static EarthAlpineDefinition load(Path geographyRoot) throws IOException {
        Objects.requireNonNull(geographyRoot);
        return loadPaths(geographyRoot.resolve("data/copernicus-everest-expanded-030/world.properties"),
                geographyRoot.resolve("data/worldcover-2021-proof/landcover.properties"),
                geographyRoot.resolve("data/worldcover-2021-proof/everest/landcover.elc"));
    }
    /** Setup adapter may extract the named bounded classpath resources and call this once. */
    public static EarthAlpineDefinition loadPaths(Path terrainManifest,Path landCoverManifest,Path everestRaster) throws IOException {
        var terrain=OfflineTerrain.load(terrainManifest);
        var tiles=terrain.manifest().tiles();
        if(terrain.manifest().seed()!=SEED || tiles.size()!=1 || !tiles.getFirst().sha256().equals(EGP_SHA) || tiles.getFirst().width()!=528 || tiles.getFirst().height()!=1024
                ||!terrain.manifest().provenance().verticalDatum().contains("EPSG:3855"))
            throw new IOException("Definition requires the verified retained Copernicus pack");
        var landCover=OfflineLandCover.load(landCoverManifest);
        Path path=everestRaster;
        if(Files.size(path)!=LandCoverRaster.HEADER_BYTES+1024*1024)throw new IOException("Unexpected Everest cover dimensions");
        byte[] raw;
        try(var in=Files.newInputStream(path)){raw=in.readNBytes(LandCoverRaster.HEADER_BYTES+1024*1024+1);}
        if(!WorldManifest.sha256(raw).equals(COVER_SHA))throw new IOException("Definition requires pinned Everest WorldCover cells");
        return new EarthAlpineDefinition(terrain,landCover,LandCoverRaster.decode("everest",raw));
    }
    private EarthAlpineDefinition(OfflineTerrain terrain,OfflineLandCover landCover,LandCoverRaster coverageRaster) throws IOException {
        this.terrain=terrain;this.landCover=landCover;this.coverageRaster=coverageRaster;
        // New explicit region, retaining the existing versioned relief algorithm and summit protection.
        detail=new AlpineSurfaceRelief(new MicrodetailProfile(0x4541525448524944L,2,
                List.of(new MicrodetailProfile.Region(new GeoPoint(27.976,86.94),4096,512)),
                List.of(new MicrodetailProfile.PreserveZone(new GeoPoint(27.9881,86.925),256,128))));
        detailIdentity=detail.identity();
        sourceIdentity=hash("source-copernicus-worldcover-global-v1\n"+terrain.manifest().fingerprint()+"\n"+landCover.identity()
                +"\n"+COVER_SHA+"\nprojection=equirectangular-v1;origin=0,0;standardParallel=0;block-centres;one-projected-metre"
                +"\nsource-height=EGM2008-metres;scale=1;offset=0;gradient=cardinal8m-east-ground-corrected;shelter=clamp(concavity/2,0,1)");
        StringBuilder palette=new StringBuilder();for(var value:Material.values())palette.append(value.name()).append('=').append(Integer.toHexString(value.argb())).append(';');
        materialPolicyIdentity=hash(MATERIAL_POLICY+"\n"+LandCoverSurfacePolicy.DEFAULT.identity()+"\n"+palette
                +"\nsnow70=slope-shelter;bare60=variant-rock;other-mapped=unresolved-conservative-variant-rock;unknown=reject"
                +"\nno-vegetation;no-fluid;no-lake;top=one-layer;subsurface=three-rock-layers;deep=stone;air-above");
        envelope=ENVELOPE;
        long minX=envelope.minColumnX(),maxX=envelope.maxColumnXExclusive(),minZ=envelope.minColumnZ(),maxZ=envelope.maxColumnZExclusive();
        // Exhaustive rectangle proof at bounded tiles also checks every source classification, not just corners.
        for(long z=minZ;z<maxZ;z+=MAX_FOOTPRINT)for(long x=minX;x<maxX;x+=MAX_FOOTPRINT)
            if(!knownFootprint(x,z,Math.min(x+MAX_FOOTPRINT,maxX),Math.min(z+MAX_FOOTPRINT,maxZ)))
                throw new IOException("Moving Earth profile has unsupported source/appearance at "+x+","+z);
        Column start=column(ANCHOR_X,ANCHOR_Z);
        long floor=start.surfaceY();
        // No spawn shelf: support and body clearance are verified against unchanged generated columns.
        for(long z=ANCHOR_Z-1;z<=ANCHOR_Z+1;z++)for(long x=ANCHOR_X-1;x<=ANCHOR_X+1;x++)
            if(column(x,z).surfaceY()!=floor)throw new IOException("Pinned natural anchor no longer has a clear level 3x3 support patch");
        Block dig=new Block(ANCHOR_X, floor, ANCHOR_Z+2),place=new Block(ANCHOR_X+1,floor+1,ANCHOR_Z+2);
        if(column(dig.x(),dig.z()).materialAt(dig.y())==Material.AIR || column(place.x(),place.z()).materialAt(place.y())!=Material.AIR
                ||column(place.x(),place.z()).materialAt(place.y()-1)==Material.AIR || !interior(dig)||!interior(place))
            throw new IOException("Natural dig/placement targets no longer satisfy interior supported cells");
        var summit=PROJECTION.toProjected(new GeoPoint(27.989166666666666,86.92555555555556));
        double dx=summit.x()-(ANCHOR_X+0.5),dz=summit.z()-(ANCHOR_Z+0.5);
        float yaw=(float)StrictMath.toDegrees(StrictMath.atan2(-dx,dz));
        float pitch=(float)-StrictMath.toDegrees(StrictMath.atan2(8737.7880859375-(floor+1+1.62),StrictMath.hypot(dx,dz)));
        anchor=new Anchor(ANCHOR_X,ANCHOR_Z,floor,floor+1,yaw,pitch,dig,place);
        if(!anchor.equals(ANCHOR))throw new IOException("Prepared natural anchor differs from pure descriptor policy");
        identity=hash(VERSION+"\n"+sourceIdentity+"\n"+detailIdentity+"\n"+materialPolicyIdentity+"\n"+envelope+"\n"+anchor
                +"\nfarRadius="+FAR_RADIUS+";alignment="+FAR_ALIGNMENT+";snap="+FAR_SNAP+";travelWidth="+TRAVEL_WIDTH
                +";sourceHalo="+SOURCE_HALO+";maxFootprint="+MAX_FOOTPRINT+";nativeRadiusCubes="+NATIVE_RADIUS_CUBES
                +";blockY="+MIN_BLOCK_Y+":"+MAX_BLOCK_Y_EXCLUSIVE);
        if(!SOURCE_IDENTITY.equals(sourceIdentity)||!DETAIL_IDENTITY.equals(detailIdentity)
                ||!MATERIAL_POLICY_IDENTITY.equals(materialPolicyIdentity)||!DEFINITION_IDENTITY.equals(identity))
            throw new IOException("Prepared Earth definition differs from the descriptor's pinned policy/source identity");
    }
    private static boolean interior(Block p) { return CubePos.local(p.x())>=1&&CubePos.local(p.x())<=14&&CubePos.local(p.y())>=1&&CubePos.local(p.y())<=14&&CubePos.local(p.z())>=1&&CubePos.local(p.z())<=14; }
    private static Envelope createEnvelope() {
        int cx=CubePos.section(ANCHOR_X),cz=CubePos.section(ANCHOR_Z);
        long minX=snapCubeCenter(cx-16)-FAR_RADIUS,maxX=snapCubeCenter(cx+15)+FAR_RADIUS;
        long minZ=snapCubeCenter(cz-16)-FAR_RADIUS,maxZ=snapCubeCenter(cz+15)+FAR_RADIUS;
        return new Envelope(cx-16,cx+16,cz-16,cz+16,BODY_MIN_Y/16,BODY_MAX_Y/16,minX,maxX,minZ,maxZ);
    }
    /** Nearest 128 lattice point; ties resolve toward positive infinity, exactly matching publication. */
    public static long snap(long bodyBlock) { return Math.multiplyExact(Math.floorDiv(Math.addExact(bodyBlock,FAR_SNAP/2),FAR_SNAP),FAR_SNAP); }
    public static long snapCubeCenter(int cube) { return snap((long)cube*16+8); }
    public void validatePreparedGround(long x,long groundY,long z) {
        if(x!=ANCHOR_X||z!=ANCHOR_Z||groundY!=anchor.surfaceY())throw new IllegalArgumentException("Earth descriptor does not match prepared natural anchor");
    }
    public Anchor anchor(){return anchor;}
    public Envelope envelope(){return envelope;}
    public EquirectangularProjection projection(){return PROJECTION;}
    public String identity(){return identity;}
    public String sourceIdentity(){return sourceIdentity;}
    public String detailIdentity(){return detailIdentity;}
    public String materialPolicyIdentity(){return materialPolicyIdentity;}
    public String appearancePolicyIdentity(){return LandCoverSurfacePolicy.DEFAULT.identity();}
    public MicrodetailProfile detailProfile(){return detail.profile();}
    public WorldManifest sourceManifest(){return terrain.manifest();}
    public String landCoverIdentity(){return landCover.identity();}
    public boolean permitsCenter(CubePos center){return envelope.permitsCenter(center);}
    public void validateCenter(CubePos center){if(!permitsCenter(center))throw new IllegalArgumentException("Outside bounded Earth travel/Y profile");}
    public boolean permitsFarCenter(long x,long z){
        return Math.floorMod(x,FAR_ALIGNMENT)==0&&Math.floorMod(z,FAR_ALIGNMENT)==0
                &&x>=envelope.minColumnX()+FAR_RADIUS&&x<=envelope.maxColumnXExclusive()-FAR_RADIUS
                &&z>=envelope.minColumnZ()+FAR_RADIUS&&z<=envelope.maxColumnZExclusive()-FAR_RADIUS;
    }
    /** Validation is constant-time because immutable construction proves all aligned leaves in the union. */
    public void validateFarFootprint(long centerX,long centerZ){if(!permitsFarCenter(centerX,centerZ))throw new IllegalArgumentException("Outside proved snapped Earth far centers");}
    public Column column(long x,long z){
        if(!envelope.containsColumn(x,z))throw new MissingTerrainException("Outside bounded Earth source profile");
        GeoPoint point=PROJECTION.blockCentre(x,z);double h=source(x,z);
        double e=source(x+SOURCE_HALO,z),w=source(x-SOURCE_HALO,z),s=source(x,z+SOURCE_HALO),n=source(x,z-SOURCE_HALO);
        double gx=(e-w)/(2*SOURCE_HALO*StrictMath.cos(StrictMath.toRadians(point.latitude()))),gz=(s-n)/(2*SOURCE_HALO);
        double shelter=Math.clamp(((e+w+s+n)/4-h)/2,0,1);
        var measured=landCover.sample(point);
        if(measured.status()!=LandCoverSample.Status.MAPPED)throw new MissingTerrainException("Unknown or uncovered appearance rejected");
        var relief=detail.sample(point,h,terrain.manifest().provenance().resolutionArcSeconds(),gx,gz,measured,shelter);
        var selection=LandCoverSurfacePolicy.DEFAULT.evaluate(measured,StrictMath.hypot(gx,gz),x,z,shelter);
        Material rock=switch(selection.materialVariant()){case 1->Material.ANDESITE;case 2->Material.TUFF;default->Material.STONE;};
        boolean snow=measured.classification()==LandCoverClass.SNOW_AND_ICE;
        Material top=snow&&selection.appearance()==LandCoverSurfacePolicy.Appearance.SNOW_ICE?Material.SNOW_BLOCK:rock;
        MaterialReceipt receipt=snow?MaterialReceipt.SOURCE_SNOW_SLOPE_SHELTER:
                measured.classification()==LandCoverClass.BARE_SPARSE_VEGETATION?MaterialReceipt.SOURCE_BARE_COSMETIC_ROCK:MaterialReceipt.UNRESOLVED_MAPPED_CLASS_CONSERVATIVE_ROCK;
        return new Column(h,relief.proceduralDeltaMetres(),relief.generatedMetres(),(long)StrictMath.floor(relief.generatedMetres()),
                measured,selection,gx,gz,shelter,top,rock,Material.STONE,receipt);
    }
    public long surfaceY(long x,long z){return column(x,z).surfaceY();}
    public Material materialAt(long x,long y,long z){
        if(y<MIN_BLOCK_Y||y>=MAX_BLOCK_Y_EXCLUSIVE)throw new MissingTerrainException("Outside finite Earth Y envelope");
        return column(x,z).materialAt(y);
    }
    public boolean knownFootprint(long minX,long minZ,int size){
        if(size<1||size>MAX_FOOTPRINT)throw new IllegalArgumentException("Footprint size outside 1..128");
        try{return knownFootprint(minX,minZ,Math.addExact(minX,size),Math.addExact(minZ,size));}
        catch(ArithmeticException e){return false;}
    }
    /** Complete continuous footprint +8m source halo, and every intersected measured cover pixel. */
    public boolean knownFootprint(long minX,long minZ,long maxXExclusive,long maxZExclusive){
        long width,depth;
        try{width=Math.subtractExact(maxXExclusive,minX);depth=Math.subtractExact(maxZExclusive,minZ);}
        catch(ArithmeticException e){return false;}
        if(width<1||depth<1||width>MAX_FOOTPRINT||depth>MAX_FOOTPRINT)throw new IllegalArgumentException("Footprint dimensions outside 1..128");
        if(minX<envelope.minColumnX()||minZ<envelope.minColumnZ()||maxXExclusive>envelope.maxColumnXExclusive()||maxZExclusive>envelope.maxColumnZExclusive())return false;
        var nw=PROJECTION.toGeographic(minX-SOURCE_HALO,minZ-SOURCE_HALO);
        var se=PROJECTION.toGeographic(maxXExclusive+SOURCE_HALO,maxZExclusive+SOURCE_HALO);
        if(!terrain.hasFiniteRectangleSupport(nw,se)||!coverageRaster.covers(nw)||!coverageRaster.covers(se))return false;
        int left=(int)StrictMath.floor((nw.longitude()-coverageRaster.west())/coverageRaster.stepLon());
        int right=(int)StrictMath.floor((se.longitude()-coverageRaster.west())/coverageRaster.stepLon());
        int top=(int)StrictMath.floor((coverageRaster.north()-nw.latitude())/coverageRaster.stepLat());
        int bottom=(int)StrictMath.floor((coverageRaster.north()-se.latitude())/coverageRaster.stepLat());
        if(left<0||top<0||right>=coverageRaster.width()||bottom>=coverageRaster.height()||(long)(right-left+1)*(bottom-top+1)>1024)return false;
        for(int row=top;row<=bottom;row++)for(int col=left;col<=right;col++)
            if(coverageRaster.sample(new GeoPoint(coverageRaster.north()-(row+0.5)*coverageRaster.stepLat(),coverageRaster.west()+(col+0.5)*coverageRaster.stepLon())).status()!=LandCoverSample.Status.MAPPED)return false;
        return true;
    }
    /** Counts source pixels intersecting the proved profile, without treating labels as measured material. */
    public Map<LandCoverClass,Long> sourceClassCounts(){
        var nw=PROJECTION.toGeographic(envelope.minColumnX()-SOURCE_HALO,envelope.minColumnZ()-SOURCE_HALO);
        var se=PROJECTION.toGeographic(envelope.maxColumnXExclusive()+SOURCE_HALO,envelope.maxColumnZExclusive()+SOURCE_HALO);
        int left=(int)StrictMath.floor((nw.longitude()-coverageRaster.west())/coverageRaster.stepLon());
        int right=(int)StrictMath.floor((se.longitude()-coverageRaster.west())/coverageRaster.stepLon());
        int top=(int)StrictMath.floor((coverageRaster.north()-nw.latitude())/coverageRaster.stepLat());
        int bottom=(int)StrictMath.floor((coverageRaster.north()-se.latitude())/coverageRaster.stepLat());
        var counts=new EnumMap<LandCoverClass,Long>(LandCoverClass.class);
        for(int row=top;row<=bottom;row++)for(int col=left;col<=right;col++){
            var c=coverageRaster.sample(new GeoPoint(coverageRaster.north()-(row+0.5)*coverageRaster.stepLat(),coverageRaster.west()+(col+0.5)*coverageRaster.stepLon())).classification();
            counts.merge(c,1L,Long::sum);
        }
        return Collections.unmodifiableMap(counts);
    }
    private double source(long x,long z){return terrain.elevationMetres(PROJECTION.blockCentre(x,z));}
    private static String hash(String value){return WorldManifest.sha256(value.getBytes(StandardCharsets.UTF_8));}
}
