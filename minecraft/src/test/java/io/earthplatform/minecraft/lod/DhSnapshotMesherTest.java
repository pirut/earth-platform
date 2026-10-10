package io.earthplatform.minecraft.lod;

import com.mojang.serialization.Lifecycle;
import io.earthplatform.core.CubePos;
import io.earthplatform.core.WorldIdentity;
import io.earthplatform.minecraft.network.EngineCubeSection;
import io.earthplatform.minecraft.network.EngineCubeSnapshot;
import io.earthplatform.minecraft.network.EnginePaletteContext;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Path;
import java.util.List;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.RegistrationInfo;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Native-state summaries and actual isolated upstream CPU builder; no GL context is claimed here. */
class DhSnapshotMesherTest {
    static EnginePaletteContext palette;
    static final WorldIdentity WORLD=new WorldIdentity(0,"native-fixture","1","known-marker-cubes","native-palette");
    @BeforeAll static void setup() {
        var registry=new MappedRegistry<Biome>(Registries.BIOME,Lifecycle.stable());
        registry.register(Biomes.PLAINS,VanillaRegistries.createLookup().lookupOrThrow(Registries.BIOME).getOrThrow(Biomes.PLAINS).value(),RegistrationInfo.BUILT_IN);
        palette=EnginePaletteContext.create(registry.freeze());
    }
    private static EngineCubeSnapshot snapshot(CubePos cube,EngineCubeSection data){return EngineCubeSnapshot.capture(cube,WORLD,1,1,1,data);}
    @Test void actualNativeExtremesBecomeDistinctPageRelativeGeometry() {
        for(int nativeY:new int[]{-11000,8849}) {
            var cube=CubePos.fromBlock(8,nativeY,8);var data=new EngineCubeSection(palette);data.set(8,nativeY&15,8,Blocks.GOLD_BLOCK.defaultBlockState());
            var summary=DhSnapshotMesher.summarize(snapshot(cube,data),palette);
            assertEquals(Math.floorDiv(nativeY,2048),summary.pageY());assertEquals(1,summary.sourceBlocks());assertEquals(6,summary.faces().size());
            for(var face:summary.faces())assertTrue(face.pageLocalY()+summary.pageY()*2048==nativeY||face.pageLocalY()+summary.pageY()*2048==nativeY+1);
        }
    }
    @Test void adjacentKnownSolidsHaveNoInternalFacesAndNoInventedAir() {
        var data=new EngineCubeSection(palette);data.set(7,8,8,Blocks.STONE.defaultBlockState());data.set(8,8,8,Blocks.STONE.defaultBlockState());
        var summary=DhSnapshotMesher.summarize(snapshot(new CubePos(0,-688,0),data),palette);
        assertEquals(2,summary.sourceBlocks());assertEquals(10,summary.faces().size());
        assertFalse(summary.faces().stream().anyMatch(f->f.x()==8&&(f.direction().equals("EAST")||f.direction().equals("WEST"))));
    }
    @Test void unknownBoundaryNeighborsDeferRatherThanProduceExternalCaps() {
        for(int axis=0;axis<3;axis++)for(int boundary:new int[]{0,15}) {
            var data=new EngineCubeSection(palette);int[] p={8,8,8};p[axis]=boundary;data.set(p[0],p[1],p[2],Blocks.STONE.defaultBlockState());
            assertThrows(IllegalArgumentException.class,()->DhSnapshotMesher.summarize(snapshot(new CubePos(0,0,0),data),palette));
        }
    }
    @Test void knownAirRemovesAllFarGeometryWithoutFabricatingBounds() {
        var summary=DhSnapshotMesher.summarize(snapshot(new CubePos(0,-688,0),new EngineCubeSection(palette)),palette);
        assertEquals(0,summary.sourceBlocks());assertTrue(summary.faces().isEmpty());
    }
    @Test void unsupportedFluidAndUnboundedFacesFailClosed() {
        var data=new EngineCubeSection(palette);data.set(8,8,8,Blocks.WATER.defaultBlockState());
        assertThrows(IllegalArgumentException.class,()->DhSnapshotMesher.summarize(snapshot(new CubePos(0,0,0),data),palette));
        var fragmented=new EngineCubeSection(palette);
        for(int y=1;y<15;y++)for(int z=1;z<15;z++)for(int x=1;x<15;x++)if((x+y+z)%2==0)fragmented.set(x,y,z,Blocks.STONE.defaultBlockState());
        assertThrows(IllegalArgumentException.class,()->DhSnapshotMesher.summarize(snapshot(new CubePos(0,0,0),fragmented),palette));
    }
    @Test void cubesOutsideTheEarthEnvelopeAreRejected() {
        assertThrows(IllegalArgumentException.class,()->DhSnapshotMesher.summarize(snapshot(new CubePos(0,-689,0),new EngineCubeSection(palette)),palette));
        assertThrows(IllegalArgumentException.class,()->DhSnapshotMesher.summarize(snapshot(new CubePos(0,554,0),new EngineCubeSection(palette)),palette));
    }
    @Test void isolatedOfficialDhBuilderEmitsExactNativeSnapshotFaces() throws Exception {
        String path=System.getProperty("earthplatform.lodProbeJar",System.getenv("EARTH_DH_PROBE_JAR"));
        org.junit.jupiter.api.Assumptions.assumeTrue(path!=null&&!path.isBlank(),"Optional actual DH proof requires explicit pinned artifact path");
        var runtime=DhOfficialRuntime.open(Path.of(path));assertSame(runtime,DhOfficialRuntime.open(Path.of(path)));
        for(int y:new int[]{-11000,8849}) {
            var cube=CubePos.fromBlock(8,y,8);var data=new EngineCubeSection(palette);data.set(8,y&15,8,Blocks.DIAMOND_BLOCK.defaultBlockState());
            var summary=DhSnapshotMesher.summarize(snapshot(cube,data),palette);byte[] vertices=runtime.build(summary.faces());
            assertEquals(384,vertices.length);var buffer=ByteBuffer.wrap(vertices).order(ByteOrder.nativeOrder());
            int low=0,high=0;for(int i=0;i<24;i++) {
                int nativeY=Short.toUnsignedInt(buffer.getShort(i*16+2))+summary.pageY()*2048;
                if(nativeY==y)low++;else if(nativeY==y+1)high++;else fail("DH packed a wrong native Y");
                assertEquals(255,Byte.toUnsignedInt(buffer.get(i*16+11)));
            }assertEquals(12,low);assertEquals(12,high);
        }
        assertTrue(runtime.shader(true).startsWith("#version 330"));assertTrue(runtime.shader(false).startsWith("#version 330"));
        assertThrows(IllegalArgumentException.class,()->runtime.build(List.of()));
    }
}
