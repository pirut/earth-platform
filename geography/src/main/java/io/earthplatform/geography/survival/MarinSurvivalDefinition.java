package io.earthplatform.geography.survival;

import io.earthplatform.core.CubePos;
import io.earthplatform.geography.*;
import io.earthplatform.geography.landcover.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Bounded immutable Marin source and independent resource generation, candidate 039.
 * NOAA 15-arcsecond data supply coarse macro elevation only. WorldCover supplies
 * categorical cover, not tree locations/species. Soil, rock and oak trees are procedural.
 * Only load performs I/O. Queries do not consult mutable chunks, saves or registry IDs.
 */
public final class MarinSurvivalDefinition {
    public static final String VERSION = "marin-native-survival-039-v1";
    public static final String GENERATOR_VERSION = "marin-resources-anchor-v1";
    public static final String ORE_POLICY = "reserved-disabled-v1;no-observed-subsurface-claim;no-ore-generated";
    public static final String PROFILE_ID = "marin-survival-v1", SAVE_NAMESPACE = "earthplatform-marin-survival-v1";
    public static final long SEED = 20221009L;
    public static final long ANCHOR_X = -13640057, ANCHOR_Z = -4213188, GROUND_Y = 134;
    public static final int TRAVEL_WIDTH = 512, FAR_RADIUS = 2048, FAR_ALIGNMENT = 128, FAR_SNAP = 128;
    public static final int SOURCE_HALO = 8, TREE_HALO = 2, MAX_FOOTPRINT = 128, TREE_SPACING = 12;
    public static final long MIN_BLOCK_Y = -11008, MAX_BLOCK_Y_EXCLUSIVE = 8864;
    public static final String TERRAIN_MANIFEST_FINGERPRINT = "fa0d992570f4a3da313aaede16bb28274f7830c6defbd1c649d0309c96f218ed";
    public static final String COVER_IDENTITY = "landcover-282147518de8dc9d8be4380d4653d5c04e5b590d29d8ce2ff1d4d48a47f1b97c";
    public static final String ELEVATION_SHA256 = "b6984a14952b7468cd544f2b4ec6ea0ff6d8e59308b1d530a2ce58569ef5c9bc";
    public static final String COVER_SHA256 = "82408a2d5707518f9d3deb69bc05645b1ea5674f935d3110653f830b88cfb27c";
    public static final String NOAA_ATTRIBUTION = "NOAA National Centers for Environmental Information (2022). ETOPO 2022 15 Arc-Second Global Relief Model. DOI:10.25921/fd45-gt74. CC0-1.0; EGM2008/EPSG:3855; not for navigation.";
    public static final String WORLDCOVER_ATTRIBUTION = "© ESA WorldCover project 2021 / Contains modified Copernicus Sentinel data (2021) processed by ESA WorldCover consortium. Zanaga, D. et al. (2022). ESA WorldCover 10 m 2021 v200. https://doi.org/10.5281/zenodo.7254221. CC-BY-4.0; extracted and repacked without changing class codes.";
    private static final EquirectangularProjection PROJECTION = new EquirectangularProjection(new GeoPoint(0,0),0);
    public static final Envelope ENVELOPE = createEnvelope();
    public static final Anchor ANCHOR = new Anchor(ANCHOR_X,ANCHOR_Z,GROUND_Y,GROUND_Y+1,0,0);
    public static final String SOURCE_IDENTITY = hash("marin-source-v1\n"+TERRAIN_MANIFEST_FINGERPRINT+"\n"+COVER_IDENTITY
            +"\n"+ELEVATION_SHA256+"\n"+COVER_SHA256+"\n"+NOAA_ATTRIBUTION+"\n"+WORLDCOVER_ATTRIBUTION
            +"\nprojection=equirectangular-v1;origin=0,0;standardParallel=0;block-centres;one-projected-metre"
            +"\nelevation=NOAA-ETOPO2022-15arcsecond-coarse-macro;bilinear-v1;EGM2008-metres;scale=1;offset=0;floor-to-block"
            +"\ncover=WorldCover2021v200;pixel-area-containing-cell-v1;source-halo="+SOURCE_HALO);
    public static final String GENERATOR_IDENTITY = hash(GENERATOR_VERSION+"\nseed="+SEED
            +"\ntree-grid=12;offset=3..8;density=unsigned-hash-mod4<3;class10-only;ground>=0;trunk=4..6;canopy-radius=2;topcap-radius=1"
            +"\nanchor-hash=mix64(gridX*0x9e3779b97f4a7c15 XOR rotateLeft(gridZ*0xc2b2ae3d27d4eb4f,29) XOR seed)"
            +"\nmix64=xorshift30*0xbf58476d1ce4e5b9;xorshift27*0x94d049bb133111eb;xorshift31;unsigned-wrap64;offsetX=mix(h+1)%6;offsetZ=mix(h+2)%6;height=4+mix(h+3)%3"
            +"\ncanopy=top-2..top+1;lower-manhattan<=3;cap-manhattan<=2;logs-over-leaves;terrain-over-features;natural-leaf-distance"
            +"\nsoil=top-grass-on-10,20,30,40,90,95,100;three-dirt-layers;water80=gravel;other-known=stone;deep=stone;air-above;no-fluids"
            +"\nheight-detail=none;edits=downstream-including-explicit-air;starter-inventory=none;"+ORE_POLICY
            +"\nmaterials="+SurvivalMaterials.supportedStates().stream().map(RegisteredBlockState::canonical).toList());
    public static final String DEFINITION_IDENTITY = hash(VERSION+"\n"+SOURCE_IDENTITY+"\n"+GENERATOR_IDENTITY+"\n"+ENVELOPE+"\n"+ANCHOR
            +"\nfarRadius="+FAR_RADIUS+";alignment="+FAR_ALIGNMENT+";treeHalo="+TREE_HALO+";generationY="+MIN_BLOCK_Y+":"+MAX_BLOCK_Y_EXCLUSIVE);
    public static final int MAX_VISUAL_TREE_QUERY_SPAN = 128, MAX_VISUAL_TREE_CANDIDATES = 144;
    /** Separate read-only contract; deliberately excluded from mutable world/save identity. */
    public static final String VISUAL_DEFINITION_IDENTITY = hash("marin-native-visual-source-v1\n"+DEFINITION_IDENTITY
            +"\nread-only;source-column-envelope-inset-tree-halo="+TREE_HALO
            +";generationY="+MIN_BLOCK_Y+":"+MAX_BLOCK_Y_EXCLUSIVE+";exact-native-states;no-mutable-admission"
            +";querySpan="+MAX_VISUAL_TREE_QUERY_SPAN+";maxCubeCells="+CubePos.VOLUME);
    private final OfflineTerrain terrain;
    private final LandCoverRaster coverage;
    private final RasterTile sourceRaster;

    public record Anchor(long x,long z,long surfaceY,double feetY,float yaw,float pitch) {
        public CubePos center() { return CubePos.fromBlock(x,(long)StrictMath.floor(feetY),z); }
        public double centreX() { return x+0.5; }
        public double centreZ() { return z+0.5; }
    }
    /** Half-open bounds. Far columns are read-only source support; only playable columns generate mutable cubes. */
    public record Envelope(int minCenterX,int maxCenterXExclusive,int minCenterZ,int maxCenterZExclusive,
                           int minCenterY,int maxCenterYExclusive,long minColumnX,long maxColumnXExclusive,
                           long minColumnZ,long maxColumnZExclusive) {
        public long minTravelX() { return (long)minCenterX*16; }
        public long maxTravelXExclusive() { return (long)maxCenterXExclusive*16; }
        public long minTravelZ() { return (long)minCenterZ*16; }
        public long maxTravelZExclusive() { return (long)maxCenterZExclusive*16; }
        public long minFeatureAnchorX() { return minTravelX()-TREE_HALO; }
        public long maxFeatureAnchorXExclusive() { return maxTravelXExclusive()+TREE_HALO; }
        public long minFeatureAnchorZ() { return minTravelZ()-TREE_HALO; }
        public long maxFeatureAnchorZExclusive() { return maxTravelZExclusive()+TREE_HALO; }
        public boolean containsColumn(long x,long z) {
            return x>=minColumnX && x<maxColumnXExclusive && z>=minColumnZ && z<maxColumnZExclusive;
        }
        public boolean containsPlayableColumn(long x,long z) {
            return x>=minTravelX() && x<maxTravelXExclusive() && z>=minTravelZ() && z<maxTravelZExclusive();
        }
        public boolean permitsCenter(CubePos p) {
            return p!=null && p.x()>=minCenterX && p.x()<maxCenterXExclusive && p.z()>=minCenterZ && p.z()<maxCenterZExclusive
                    && p.y()>=minCenterY && p.y()<maxCenterYExclusive;
        }
    }
    public record Column(double sourceMetres,long surfaceY,LandCoverSample sourceClassification,
                         RegisteredBlockState top,RegisteredBlockState subsurface,RegisteredBlockState deep) {
        public Column {
            Objects.requireNonNull(sourceClassification); Objects.requireNonNull(top); Objects.requireNonNull(subsurface); Objects.requireNonNull(deep);
            if (!Double.isFinite(sourceMetres) || surfaceY!=(long)StrictMath.floor(sourceMetres)
                    || sourceClassification.status()!=LandCoverSample.Status.MAPPED || top.isAir() || subsurface.isAir() || !deep.equals(SurvivalMaterials.STONE))
                throw new IllegalArgumentException("Invalid measured-source/procedural-material column");
        }
        public double proceduralDeltaMetres() { return 0; }
        public double generatedMetres() { return sourceMetres; }
        public RegisteredBlockState materialAt(long y) {
            return y>surfaceY?SurvivalMaterials.AIR:y==surfaceY?top:y>=surfaceY-3?subsurface:deep;
        }
        public int argb() { return SurvivalMaterials.argb(top); }
    }
    /** An immutable procedural feature anchor; canopy fragments can be evaluated in any cube order. */
    public record Tree(long x,long groundY,long z,int trunkHeight) {
        public Tree { if(trunkHeight<4 || trunkHeight>6) throw new IllegalArgumentException("Tree height outside versioned policy"); }
        public long topY() { return groundY+trunkHeight; }
        public RegisteredBlockState stateAt(long blockX,long y,long blockZ) {
            long dx=blockX-x,dz=blockZ-z;
            if (dx==0 && dz==0 && y>groundY && y<=topY()) return SurvivalMaterials.OAK_LOG;
            if (Math.abs(dx)>TREE_HALO || Math.abs(dz)>TREE_HALO || y<topY()-2 || y>topY()+1) return SurvivalMaterials.AIR;
            long horizontal=Math.abs(dx)+Math.abs(dz);
            if (y==topY()+1 ? Math.abs(dx)>1 || Math.abs(dz)>1 || horizontal>2 : horizontal>3) return SurvivalMaterials.AIR;
            return SurvivalMaterials.oakLeaves((int)(horizontal+Math.max(0,y-topY())));
        }
    }

    public static MarinSurvivalDefinition load(Path geographyRoot) throws IOException {
        return loadPaths(geographyRoot.resolve("data/noaa-etopo2022-proof/world-global.properties"),
                geographyRoot.resolve("data/worldcover-2021-proof/landcover.properties"),
                geographyRoot.resolve("data/worldcover-2021-proof/marin-headlands/landcover.elc"));
    }
    public static MarinSurvivalDefinition loadPaths(Path terrainManifest,Path landCoverManifest,Path marinRaster) throws IOException {
        var terrain=OfflineTerrain.load(terrainManifest);
        if (!terrain.manifest().fingerprint().equals(TERRAIN_MANIFEST_FINGERPRINT))
            throw new IOException("Marin requires the exact pinned NOAA global-projection manifest");
        var landCover=OfflineLandCover.load(landCoverManifest);
        if (!landCover.identity().equals(COVER_IDENTITY)) throw new IOException("Marin requires the exact pinned WorldCover manifest");
        var spec=terrain.manifest().tiles().stream().filter(tile -> tile.id().equals("san-francisco-west")).findFirst().orElseThrow();
        byte[] elevation=readPinned(terrainManifest.toAbsolutePath().getParent().resolve(spec.path()),spec.encodedBytes(),ELEVATION_SHA256);
        byte[] cover=readPinned(marinRaster,LandCoverRaster.HEADER_BYTES+1024L*1024,COVER_SHA256);
        return new MarinSurvivalDefinition(terrain,RasterTile.decode(spec,elevation),LandCoverRaster.decode("marin-headlands",cover));
    }
    private static byte[] readPinned(Path path,long expectedSize,String expectedHash) throws IOException {
        if (!Files.isRegularFile(path) || Files.size(path)!=expectedSize) throw new IOException("Pinned source length changed: "+path.getFileName());
        byte[] bytes;
        try(var input=Files.newInputStream(path)) { bytes=input.readNBytes(Math.toIntExact(expectedSize+1)); }
        if (bytes.length!=expectedSize || !WorldManifest.sha256(bytes).equals(expectedHash)) throw new IOException("Pinned source checksum changed: "+path.getFileName());
        return bytes;
    }
    private MarinSurvivalDefinition(OfflineTerrain terrain,RasterTile sourceRaster,LandCoverRaster coverage) throws IOException {
        this.terrain=terrain; this.sourceRaster=sourceRaster; this.coverage=coverage;
        for(long z=ENVELOPE.minColumnZ();z<ENVELOPE.maxColumnZExclusive();z+=MAX_FOOTPRINT)
            for(long x=ENVELOPE.minColumnX();x<ENVELOPE.maxColumnXExclusive();x+=MAX_FOOTPRINT)
                if(!knownFootprint(x,z,(int)Math.min(MAX_FOOTPRINT,ENVELOPE.maxColumnXExclusive()-x),
                        (int)Math.min(MAX_FOOTPRINT,ENVELOPE.maxColumnZExclusive()-z)))
                    throw new IOException("Marin source/cover support is unavailable at "+x+","+z);
        if (surfaceY(ANCHOR_X,ANCHOR_Z)!=GROUND_Y || !stateAt(ANCHOR_X,GROUND_Y,ANCHOR_Z).equals(SurvivalMaterials.GRASS)
                || !stateAt(ANCHOR_X,GROUND_Y+1,ANCHOR_Z).isAir() || !stateAt(ANCHOR_X,GROUND_Y+2,ANCHOR_Z).isAir())
            throw new IOException("Unmodified Marin anchor has changed support or body clearance");
    }
    private static Envelope createEnvelope() {
        int x=CubePos.section(ANCHOR_X),z=CubePos.section(ANCHOR_Z);
        return new Envelope(x-16,x+16,z-16,z+16,Math.toIntExact(MIN_BLOCK_Y/16),Math.toIntExact(MAX_BLOCK_Y_EXCLUSIVE/16),
                snapCubeCenter(x-16)-FAR_RADIUS,snapCubeCenter(x+15)+FAR_RADIUS,
                snapCubeCenter(z-16)-FAR_RADIUS,snapCubeCenter(z+15)+FAR_RADIUS);
    }
    public static long snapCubeCenter(int section) { return Math.floorDiv((long)section*16+8+64,128)*128; }
    public String identity() { return DEFINITION_IDENTITY; }
    public String sourceIdentity() { return SOURCE_IDENTITY; }
    public String generatorIdentity() { return GENERATOR_IDENTITY; }
    public String materialPolicyIdentity() { return GENERATOR_IDENTITY; }
    public String landCoverIdentity() { return COVER_IDENTITY; }
    public WorldManifest sourceManifest() { return terrain.manifest(); }
    public EquirectangularProjection projection() { return PROJECTION; }
    public Anchor anchor() { return ANCHOR; }
    public Envelope envelope() { return ENVELOPE; }
    public boolean permitsCenter(CubePos center) { return ENVELOPE.permitsCenter(center); }
    public void validateCenter(CubePos center) { if(!permitsCenter(center)) throw new MissingTerrainException("Outside Marin playable cube domain"); }
    /** Read-only visual identity does not grant generation, admission, persistence, or edit authority. */
    public String visualIdentity() { return VISUAL_DEFINITION_IDENTITY; }
    /**
     * Full signed cube support, including every possible procedural tree root. The constructor
     * already proved every source column and its SOURCE_HALO; this only intersects that proof.
     * A renderer must separately validate every exact page/meshing dependency, without clamping.
     */
    public boolean permitsVisualCube(CubePos position) {
        return position!=null && position.minBlockY()>=MIN_BLOCK_Y
                && position.minBlockY()+CubePos.SIZE<=MAX_BLOCK_Y_EXCLUSIVE
                && permitsVisualFootprint(position.minBlockX(),position.minBlockZ(),
                        position.minBlockX()+CubePos.SIZE,position.minBlockZ()+CubePos.SIZE);
    }
    public void validateVisualCube(CubePos position) {
        if(!permitsVisualCube(position)) throw new MissingTerrainException("Outside proved Marin read-only visual cube support");
    }
    private static boolean permitsVisualFootprint(long minX,long minZ,long maxXExclusive,long maxZExclusive) {
        // Compare before adding/subtracting caller coordinates so even Long.MIN/MAX fail closed.
        return minX<maxXExclusive && minZ<maxZExclusive
                && minX>=ENVELOPE.minColumnX()+TREE_HALO && minZ>=ENVELOPE.minColumnZ()+TREE_HALO
                && maxXExclusive<=ENVELOPE.maxColumnXExclusive()-TREE_HALO
                && maxZExclusive<=ENVELOPE.maxColumnZExclusive()-TREE_HALO;
    }
    public boolean permitsFarCenter(long x,long z) {
        return Math.floorMod(x,FAR_ALIGNMENT)==0 && Math.floorMod(z,FAR_ALIGNMENT)==0
                && x>=ENVELOPE.minColumnX()+FAR_RADIUS && x<=ENVELOPE.maxColumnXExclusive()-FAR_RADIUS
                && z>=ENVELOPE.minColumnZ()+FAR_RADIUS && z<=ENVELOPE.maxColumnZExclusive()-FAR_RADIUS;
    }
    public void validateFarFootprint(long x,long z) { if(!permitsFarCenter(x,z)) throw new MissingTerrainException("Outside proved Marin far source support"); }
    public void validatePreparedGround(long x,long groundY,long z) {
        if(x!=ANCHOR_X || z!=ANCHOR_Z || groundY!=GROUND_Y || surfaceY(x,z)!=groundY)
            throw new IllegalArgumentException("Prepared Marin spawn differs from its pinned natural anchor");
    }
    public Column column(long x,long z) {
        if(!ENVELOPE.containsColumn(x,z)) throw new MissingTerrainException("Outside bounded Marin read-only source domain");
        var point=PROJECTION.blockCentre(x,z);
        double metres=sourceRaster.sample(point);
        var cover=coverage.sample(point);
        if(cover.status()!=LandCoverSample.Status.MAPPED) throw new MissingTerrainException("Marin cover is unknown; no fallback terrain");
        var top=switch(cover.classification()) {
            case TREE_COVER, SHRUBLAND, GRASSLAND, CROPLAND, HERBACEOUS_WETLAND, MANGROVES, MOSS_AND_LICHEN -> SurvivalMaterials.GRASS;
            case PERMANENT_WATER -> SurvivalMaterials.GRAVEL;
            default -> SurvivalMaterials.STONE;
        };
        return new Column(metres,(long)StrictMath.floor(metres),cover,top,top.equals(SurvivalMaterials.GRASS)?SurvivalMaterials.DIRT:top,SurvivalMaterials.STONE);
    }
    public long surfaceY(long x,long z) { return column(x,z).surfaceY(); }
    /** State access is a generation-domain query. Far-source availability is not permission to mutate far terrain. */
    public RegisteredBlockState stateAt(long x,long y,long z) {
        validateBlock(x,y,z);
        return generatedStateAt(x,y,z);
    }
    /** Exact immutable source state; availability here never changes mutable stateAt admission. */
    public RegisteredBlockState visualStateAt(long x,long y,long z) {
        if(y<MIN_BLOCK_Y || y>=MAX_BLOCK_Y_EXCLUSIVE
                || x<ENVELOPE.minColumnX()+TREE_HALO || x>=ENVELOPE.maxColumnXExclusive()-TREE_HALO
                || z<ENVELOPE.minColumnZ()+TREE_HALO || z>=ENVELOPE.maxColumnZExclusive()-TREE_HALO)
            throw new MissingTerrainException("Outside proved Marin read-only visual block support");
        return generatedStateAt(x,y,z);
    }
    private RegisteredBlockState generatedStateAt(long x,long y,long z) {
        var base=column(x,z).materialAt(y);
        if(!base.isAir()) return base;
        RegisteredBlockState result=base;
        for(var tree:generatedTreesIntersecting(x,z,x+1,z+1)) {
            var feature=tree.stateAt(x,y,z);
            if(feature.equals(SurvivalMaterials.OAK_LOG)) return feature;
            if(!feature.isAir()) result=feature;
        }
        return result;
    }
    public RegisteredBlockState materialAt(long x,long y,long z) { return stateAt(x,y,z); }
    private static void validateBlock(long x,long y,long z) {
        if(!ENVELOPE.containsPlayableColumn(x,z) || y<MIN_BLOCK_Y || y>=MAX_BLOCK_Y_EXCLUSIVE)
            throw new MissingTerrainException("Outside Marin playable generation domain");
    }
    /** Exactly 4096 cells, X then Z then Y. Always a fresh array; edits must be applied downstream, including AIR. */
    public RegisteredBlockState[] generateCube(CubePos position) {
        validateCenter(position);
        return generatedCube(position);
    }
    /**
     * Fresh, exact 4096-cell immutable source payload. Caller-owned arrays grant no simulation
     * ownership. It shares the versioned terrain/tree evaluator with the strict mutable source.
     */
    public RegisteredBlockState[] generateVisualCube(CubePos position) {
        validateVisualCube(position);
        return generatedCube(position);
    }
    private RegisteredBlockState[] generatedCube(CubePos position) {
        var cells=new RegisteredBlockState[CubePos.VOLUME];
        var columns=new Column[256];
        long bx=position.minBlockX(),by=position.minBlockY(),bz=position.minBlockZ();
        for(int z=0;z<16;z++) for(int x=0;x<16;x++) columns[z*16+x]=column(bx+x,bz+z);
        for(int y=0;y<16;y++) for(int z=0;z<16;z++) for(int x=0;x<16;x++)
            cells[CubePos.index(x,y,z)]=columns[z*16+x].materialAt(by+y);
        var trees=generatedTreesIntersecting(bx,bz,bx+16,bz+16);
        for(var tree:trees) {
            long y0=Math.max(by,tree.groundY()+1),y1=Math.min(by+16,tree.topY()+2);
            for(long y=y0;y<y1;y++) for(long z=Math.max(bz,tree.z()-TREE_HALO);z<Math.min(bz+16,tree.z()+TREE_HALO+1);z++)
                for(long x=Math.max(bx,tree.x()-TREE_HALO);x<Math.min(bx+16,tree.x()+TREE_HALO+1);x++) {
                    var feature=tree.stateAt(x,y,z);
                    if(feature.isAir() || y<=columns[(int)(z-bz)*16+(int)(x-bx)].surfaceY()) continue;
                    int index=CubePos.index((int)(x-bx),(int)(y-by),(int)(z-bz));
                    if(!cells[index].equals(SurvivalMaterials.OAK_LOG)) cells[index]=feature;
                }
        }
        return cells;
    }
    /** Bounded root discovery includes roots outside a cube whose crowns intersect it; no neighbour load is needed. */
    public List<Tree> treesIntersecting(long minX,long minZ,long maxXExclusive,long maxZExclusive) {
        if(minX>=maxXExclusive || minZ>=maxZExclusive || minX<ENVELOPE.minTravelX() || minZ<ENVELOPE.minTravelZ()
                || maxXExclusive>ENVELOPE.maxTravelXExclusive() || maxZExclusive>ENVELOPE.maxTravelZExclusive())
            throw new MissingTerrainException("Tree query outside bounded playable rectangle");
        return generatedTreesIntersecting(minX,minZ,maxXExclusive,maxZExclusive);
    }
    /**
     * Read-only feature query with complete source-backed roots, including outside queried cubes.
     * At most 128 x 128 blocks, hence 12 x 12 candidate grid anchors; never a distance-wide allocation.
     */
    public List<Tree> visualTreesIntersecting(long minX,long minZ,long maxXExclusive,long maxZExclusive) {
        if(!permitsVisualFootprint(minX,minZ,maxXExclusive,maxZExclusive)
                || maxXExclusive-minX>MAX_VISUAL_TREE_QUERY_SPAN || maxZExclusive-minZ>MAX_VISUAL_TREE_QUERY_SPAN)
            throw new MissingTerrainException("Tree query outside proved Marin read-only visual support");
        return generatedTreesIntersecting(minX,minZ,maxXExclusive,maxZExclusive);
    }
    private List<Tree> generatedTreesIntersecting(long minX,long minZ,long maxXExclusive,long maxZExclusive) {
        var trees=new ArrayList<Tree>();
        long left=Math.floorDiv(minX-TREE_HALO,TREE_SPACING),right=Math.floorDiv(maxXExclusive-1+TREE_HALO,TREE_SPACING);
        long top=Math.floorDiv(minZ-TREE_HALO,TREE_SPACING),bottom=Math.floorDiv(maxZExclusive-1+TREE_HALO,TREE_SPACING);
        for(long gz=top;gz<=bottom;gz++) for(long gx=left;gx<=right;gx++) {
            long h=mix64(gx*0x9e3779b97f4a7c15L ^ Long.rotateLeft(gz*0xc2b2ae3d27d4eb4fL,29) ^ SEED);
            if(Long.remainderUnsigned(h,4)==3) continue;
            long x=gx*TREE_SPACING+3+Long.remainderUnsigned(mix64(h+1),6);
            long z=gz*TREE_SPACING+3+Long.remainderUnsigned(mix64(h+2),6);
            if(x+TREE_HALO<minX || x-TREE_HALO>=maxXExclusive || z+TREE_HALO<minZ || z-TREE_HALO>=maxZExclusive) continue;
            var root=column(x,z);
            if(root.sourceClassification().classification()!=LandCoverClass.TREE_COVER || root.surfaceY()<0) continue;
            trees.add(new Tree(x,root.surfaceY(),z,4+(int)Long.remainderUnsigned(mix64(h+3),3)));
        }
        return List.copyOf(trees);
    }
    public boolean knownFootprint(long minX,long minZ,int size) { return knownFootprint(minX,minZ,size,size); }
    public boolean knownFootprint(long minX,long minZ,long maxXExclusive,long maxZExclusive) {
        long width,depth;
        try { width=Math.subtractExact(maxXExclusive,minX); depth=Math.subtractExact(maxZExclusive,minZ); }
        catch(ArithmeticException overflow) { return false; }
        if(width<1 || depth<1 || width>MAX_FOOTPRINT || depth>MAX_FOOTPRINT) throw new IllegalArgumentException("Footprint dimensions outside 1..128");
        return knownFootprint(minX,minZ,(int)width,(int)depth);
    }
    public boolean knownFootprint(long minX,long minZ,int width,int depth) {
        if(width<1 || depth<1 || width>MAX_FOOTPRINT || depth>MAX_FOOTPRINT) throw new IllegalArgumentException("Footprint dimensions outside 1..128");
        long maxX,maxZ;
        try { maxX=Math.addExact(minX,width); maxZ=Math.addExact(minZ,depth); }
        catch(ArithmeticException overflow) { return false; }
        if(minX<ENVELOPE.minColumnX() || minZ<ENVELOPE.minColumnZ() || maxX>ENVELOPE.maxColumnXExclusive() || maxZ>ENVELOPE.maxColumnZExclusive()) return false;
        var nw=PROJECTION.toGeographic(minX-SOURCE_HALO,minZ-SOURCE_HALO);
        var se=PROJECTION.toGeographic(maxX+SOURCE_HALO,maxZ+SOURCE_HALO);
        if(!sourceRaster.hasFiniteRectangleSupport(nw,se) || !coverage.covers(nw) || !coverage.covers(se)) return false;
        int left=(int)StrictMath.floor((nw.longitude()-coverage.west())/coverage.stepLon());
        int right=(int)StrictMath.floor((se.longitude()-coverage.west())/coverage.stepLon());
        int top=(int)StrictMath.floor((coverage.north()-nw.latitude())/coverage.stepLat());
        int bottom=(int)StrictMath.floor((coverage.north()-se.latitude())/coverage.stepLat());
        if(left<0 || top<0 || right>=coverage.width() || bottom>=coverage.height() || (long)(right-left+1)*(bottom-top+1)>1024) return false;
        for(int row=top;row<=bottom;row++) for(int col=left;col<=right;col++)
            if(coverage.sample(new GeoPoint(coverage.north()-(row+0.5)*coverage.stepLat(),coverage.west()+(col+0.5)*coverage.stepLon())).status()!=LandCoverSample.Status.MAPPED) return false;
        return true;
    }
    private static long mix64(long value) {
        value=(value^(value>>>30))*0xbf58476d1ce4e5b9L;
        value=(value^(value>>>27))*0x94d049bb133111ebL;
        return value^(value>>>31);
    }
    private static String hash(String value) { return WorldManifest.sha256(value.getBytes(StandardCharsets.UTF_8)); }
}
