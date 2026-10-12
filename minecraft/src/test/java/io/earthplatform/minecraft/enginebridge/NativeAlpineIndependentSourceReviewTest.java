package io.earthplatform.minecraft.enginebridge;

import io.earthplatform.core.CancellationToken;
import io.earthplatform.core.CubePos;
import io.earthplatform.geography.earth.EarthAlpineDefinition;
import io.earthplatform.geography.landcover.LandCoverClass;
import io.earthplatform.minecraft.network.EnginePaletteContext;
import java.io.DataInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** Independent raw-byte/voxel oracles; no network, client, Gradle, or source-policy mutation. */
class NativeAlpineIndependentSourceReviewTest {
    @TempDir static Path temporary;
    private static EarthAlpineDefinition source;
    private static EnginePaletteContext palette;
    private static RawRaster dem, cover;
    private record RawRaster(int width,int height,double west,double north,double dx,double dz,byte[] data) {
        int column(double longitude){return (int)Math.floor((longitude-west)/dx);}
        int row(double latitude){return (int)Math.floor((north-latitude)/dz);}
        int code(int x,int z){return Byte.toUnsignedInt(data[z*width+x]);}
        float height(int x,int z){return java.nio.ByteBuffer.wrap(data,4*(z*width+x),4).getFloat();}
    }
    @BeforeAll static void prepare()throws Exception {
        net.minecraft.SharedConstants.tryDetectVersion();net.minecraft.server.Bootstrap.bootStrap();
        palette=NativeCubeCodecTest.createPalette();
        source=AlpineEarthResources.installAndLoad(temporary.resolve("prepared"));
        dem=raw("alpine_data/everest-expanded.egp",0x45475031,2162732,"597f435d639ea71f35d7b716b6a5f6b846e7dd22a8b8b13e7b12482e90927bca");
        cover=raw("worldcover_proof_data/everest/landcover.elc",0x454c4331,1048620,"5276fb2adcf1c570e8328c126bc03a7c757cef2b8553f85a5a107b4a2608fdc7");
    }
    private static RawRaster raw(String path,int magic,int size,String digest)throws Exception {
        byte[] bytes;
        try(var in=NativeAlpineIndependentSourceReviewTest.class.getResourceAsStream("/earthplatform/"+path)) {
            assertNotNull(in,path);bytes=in.readAllBytes();
        }
        assertEquals(size,bytes.length);assertEquals(digest,HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)));
        try(var in=new DataInputStream(new java.io.ByteArrayInputStream(bytes))) {
            assertEquals(magic,in.readInt());int width=in.readInt(),height=in.readInt();
            return new RawRaster(width,height,in.readDouble(),in.readDouble(),in.readDouble(),in.readDouble(),in.readAllBytes());
        }
    }
    private static double longitude(double x){return StrictMath.toDegrees(x/6_378_137.0);}
    private static double latitude(double z){return -StrictMath.toDegrees(z/6_378_137.0);}
    private static double measured(long x,long z) {
        double sx=(longitude(x+.5)-dem.west)/dem.dx,sz=(dem.north-latitude(z+.5))/dem.dz;
        int ix=(int)Math.floor(sx),iz=(int)Math.floor(sz);double fx=sx-ix,fz=sz-iz;
        return (1-fx)*(1-fz)*dem.height(ix,iz)+fx*(1-fz)*dem.height(ix+1,iz)
                +(1-fx)*fz*dem.height(ix,iz+1)+fx*fz*dem.height(ix+1,iz+1);
    }
    @Test void originalFloatPostsIndependentBilinearHeightAndDetailStayDistinct() {
        assertEquals(528,dem.width);assertEquals(1024,dem.height);
        assertEquals("5c180d8b046dee2ef5e41536efd4d8b65f72d197bb36295b5e04eb05a0b05444",source.identity());
        assertEquals(EarthAlpineDefinition.SOURCE_IDENTITY,source.sourceIdentity());
        assertEquals(EarthAlpineDefinition.DETAIL_IDENTITY,source.detailIdentity());
        assertEquals(EarthAlpineDefinition.MATERIAL_POLICY_IDENTITY,source.materialPolicyIdentity());
        var e=source.envelope();int changed=0,samples=0;
        for(long z=e.minColumnZ();z<e.maxColumnZExclusive();z+=97)
            for(long x=e.minColumnX();x<e.maxColumnXExclusive();x+=101) {
                var column=source.column(x,z);assertEquals(measured(x,z),column.sourceMetres(),1e-9);
                assertTrue(Math.abs(column.proceduralDeltaMetres())<=2);
                assertEquals(column.sourceMetres()+column.proceduralDeltaMetres(),column.generatedMetres());
                assertEquals((long)Math.floor(column.generatedMetres()),column.surfaceY());
                if((long)Math.floor(column.sourceMetres())!=column.surfaceY())changed++;
                samples++;
            }
        assertTrue(samples>2000);assertTrue(changed>100,"Explicit bounded detail must not silently disappear");
        var anchor=source.column(EarthAlpineDefinition.ANCHOR_X,EarthAlpineDefinition.ANCHOR_Z);
        assertEquals(7314.399947011059,anchor.sourceMetres(),1e-9);
        assertEquals(-0.011740635060279229,anchor.proceduralDeltaMetres(),1e-12);
    }
    @Test void rawCoverCensusPreservesAll528WaterLabelsAndNativeUnionIsOnlySnow() {
        var e=source.envelope();int snow=0,bare=0,water=0,receipts=0;
        int left=cover.column(longitude(e.minColumnX()-8)),right=cover.column(longitude(e.maxColumnXExclusive()+8));
        int top=cover.row(latitude(e.minColumnZ()-8)),bottom=cover.row(latitude(e.maxColumnZExclusive()+8));
        for(int z=top;z<=bottom;z++)for(int x=left;x<=right;x++) {
            int code=cover.code(x,z);
            if(code==70)snow++;else if(code==60)bare++;else {assertEquals(80,code,"Unreviewed source class in complete halo");water++;}
            if(code==80) {
                long bx=(long)Math.floor(6_378_137.0*StrictMath.toRadians(cover.west+(x+.5)*cover.dx));
                long bz=(long)Math.floor(-6_378_137.0*StrictMath.toRadians(cover.north-(z+.5)*cover.dz));
                assertTrue(e.containsColumn(bx,bz));var c=source.column(bx,bz);
                assertEquals(LandCoverClass.PERMANENT_WATER,c.sourceClassification().classification());
                assertEquals(EarthAlpineDefinition.MaterialReceipt.UNRESOLVED_MAPPED_CLASS_CONSERVATIVE_ROCK,c.materialReceipt());
                assertTrue(c.top()==EarthAlpineDefinition.Material.STONE||c.top()==EarthAlpineDefinition.Material.ANDESITE||c.top()==EarthAlpineDefinition.Material.TUFF);
                assertEquals(EarthAlpineDefinition.Material.AIR,c.materialAt(c.surfaceY()+1));receipts++;
            }
        }
        assertEquals(243444,snow);assertEquals(5528,bare);assertEquals(528,water);assertEquals(water,receipts);
        left=cover.column(longitude(e.minTravelX()-32-8));right=cover.column(longitude(e.maxTravelXExclusive()+32+8));
        top=cover.row(latitude(e.minTravelZ()-32-8));bottom=cover.row(latitude(e.maxTravelZExclusive()+32+8));
        int nativePixels=0;
        for(int z=top;z<=bottom;z++)for(int x=left;x<=right;x++){assertEquals(70,cover.code(x,z));nativePixels++;}
        assertEquals(4225,nativePixels);
        assertThrows(UnsupportedOperationException.class,()->source.sourceClassCounts().clear());
    }
    @Test void coldProviderStillLoadsAndSamplesAfterEveryInstalledInputIsRemoved()throws Exception {
        Path root=temporary.resolve("removed");var retained=AlpineEarthResources.installAndLoad(root);
        try(var paths=Files.walk(root)){for(Path path:paths.sorted(Comparator.reverseOrder()).toList())Files.delete(path);}
        assertFalse(Files.exists(root));var provider=new NativeAlpineCubeProvider(retained,palette);
        var e=retained.envelope();int sampled=0;
        for(long z:new long[]{e.minTravelZ()-32,e.minTravelZ(),e.maxTravelZExclusive()-16,e.maxTravelZExclusive()+16})
            for(long x:new long[]{e.minTravelX()-32,e.minTravelX(),e.maxTravelXExclusive()-16,e.maxTravelXExclusive()+16}) {
                long h=provider.surfaceSampler().surfaceY(x,z);var position=CubePos.fromBlock(x,h,z);
                var cube=provider.load(position,new CancellationToken()).join();
                for(int lz=0;lz<16;lz++)for(int lx=0;lx<16;lx++)for(int ly=0;ly<16;ly++) {
                    var expected=retained.materialAt(position.minBlockX()+lx,position.minBlockY()+ly,position.minBlockZ()+lz);
                    assertSame(NativeAlpineCubeProvider.nativeState(expected),NativeCubeCodec.registeredState(cube.get(lx,ly,lz)));sampled++;
                }
            }
        assertEquals(16*4096,sampled);assertFalse(Files.exists(root));
    }
    @Test void evictionIsBoundedAndRetainedResultsCannotBeRewrittenByCacheReuse()throws Exception {
        var provider=new NativeAlpineCubeProvider(source,palette);var anchor=source.anchor();
        var first=provider.load(anchor.center(),new CancellationToken()).join();
        int[] original=new int[4096];for(int z=0;z<16;z++)for(int y=0;y<16;y++)for(int x=0;x<16;x++)original[CubePos.index(x,y,z)]=first.get(x,y,z);
        var retained=source.column(anchor.x(),anchor.z());var sampler=provider.surfaceSampler();
        for(int i=0;i<NativeAlpineCubeProvider.MAX_CACHED_COLUMNS+257;i++) {
            long x=source.envelope().minTravelX()+i%256,z=source.envelope().minTravelZ()+i/256;
            long y=sampler.surfaceY(x,z);var bands=sampler.columnMaterials(x,z,y);
            assertEquals(3,bands.subsurfaceDepth());assertEquals(255,bands.top().argb()>>>24);
        }
        var field=NativeAlpineCubeProvider.class.getDeclaredField("columns");field.setAccessible(true);
        assertEquals(NativeAlpineCubeProvider.MAX_CACHED_COLUMNS,((Map<?,?>)field.get(provider)).size());
        var second=provider.load(anchor.center(),new CancellationToken()).join();
        for(int z=0;z<16;z++)for(int y=0;y<16;y++)for(int x=0;x<16;x++) {
            assertEquals(original[CubePos.index(x,y,z)],first.get(x,y,z));assertEquals(first.get(x,y,z),second.get(x,y,z));
        }
        assertEquals(retained,source.column(anchor.x(),anchor.z()));
        assertThrows(IllegalArgumentException.class,()->sampler.columnMaterials(anchor.x(),anchor.z(),anchor.surfaceY()+1));
    }
    @Test void naturalAnchorHasExactSupportClearBodyAndInteriorSupportedEditTargets() {
        var a=source.anchor();assertEquals(EarthAlpineDefinition.ANCHOR,a);assertEquals(7315.0,a.feetY());
        for(long z=a.z()-1;z<=a.z()+1;z++)for(long x=a.x()-1;x<=a.x()+1;x++) {
            assertEquals(a.surfaceY(),source.surfaceY(x,z));assertNotEquals(EarthAlpineDefinition.Material.AIR,source.materialAt(x,a.surfaceY(),z));
            assertEquals(EarthAlpineDefinition.Material.AIR,source.materialAt(x,a.surfaceY()+1,z));
            assertEquals(EarthAlpineDefinition.Material.AIR,source.materialAt(x,a.surfaceY()+2,z));
        }
        for(var p:new EarthAlpineDefinition.Block[]{a.digTarget(),a.placementTarget()})
            for(long coordinate:new long[]{p.x(),p.y(),p.z()})assertTrue(CubePos.local(coordinate)>=1&&CubePos.local(coordinate)<=14);
        assertNotEquals(EarthAlpineDefinition.Material.AIR,source.materialAt(a.digTarget().x(),a.digTarget().y(),a.digTarget().z()));
        assertEquals(EarthAlpineDefinition.Material.AIR,source.materialAt(a.placementTarget().x(),a.placementTarget().y(),a.placementTarget().z()));
        assertNotEquals(EarthAlpineDefinition.Material.AIR,source.materialAt(a.placementTarget().x(),a.placementTarget().y()-1,a.placementTarget().z()));
    }
}
