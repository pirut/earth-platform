package io.earthplatform.compat.dh;

import io.earthplatform.minecraft.lod.DhTerrainGeometry;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Actual pinned helper, existing classpath only. No isolated loader and no live GL evidence. */
class InstalledDhIndependentHelperTest {
    static InstalledDhTerrainRuntime runtime()throws Exception{
        String path=System.getProperty("earthplatform.installedDhTestJar");assertNotNull(path,"Explicit separately installed DH test JAR required");
        var api=Class.forName("com.seibel.distanthorizons.api.DhApi");
        var mod=new InstalledDhResolver.InstalledMod("3.3.3",Path.of(path),api.getClassLoader());
        return InstalledDhTerrainRuntime.open(new InstalledDhResolver.Runtime(mod,api));
    }
    static DhTerrainGeometry.PageFace top(int page,int y){return new DhTerrainGeometry.PageFace(DhTerrainGeometry.Direction.UP,page,3968,y,3968,128,64,0xff4285bc);}
    static Set<String> actualPoints(byte[] packed){
        assertEquals(64,packed.length);var bytes=ByteBuffer.wrap(packed).order(ByteOrder.nativeOrder());var points=new HashSet<String>();
        for(int v=0;v<4;v++)points.add(Short.toUnsignedInt(bytes.getShort(v*16))+","+Short.toUnsignedInt(bytes.getShort(v*16+2))+","+Short.toUnsignedInt(bytes.getShort(v*16+4)));
        return points;
    }
    static Set<String> expectedPoints(DhTerrainGeometry.PageFace f){
        int dx=0,dy=0,dz=0,ex=0,ey=0,ez=0;
        switch(f.direction()){
            case UP,DOWN->{dx=f.width();ez=f.height();}
            case NORTH,SOUTH->{dx=f.width();ey=f.height();}
            case WEST,EAST->{dz=f.width();ey=f.height();}
        }
        return Set.of(f.x()+","+f.y()+","+f.z(),(f.x()+dx)+","+(f.y()+dy)+","+(f.z()+dz),
                (f.x()+ex)+","+(f.y()+ey)+","+(f.z()+ez),(f.x()+dx+ex)+","+(f.y()+dy+ey)+","+(f.z()+dz+ez));
    }
    @Test void signedLowestHighestAndPageEdgeFacesKeepExactUnsignedCoordinates()throws Exception{
        var runtime=runtime();
        for(int page:new int[]{-6,-1,0,4})for(var direction:DhTerrainGeometry.Direction.values()){
            int y=page==-6?1288:page==4?608:1984;
            var face=new DhTerrainGeometry.PageFace(direction,page,4032,y,4032,64,64,0xff4285bc);
            assertEquals(expectedPoints(face),actualPoints(runtime.buildRectangles(List.of(face))),direction+" page "+page);
        }
        for(var face:List.of(top(-1,2048),top(4,672),top(-6,1281)))
            assertEquals(expectedPoints(face),actualPoints(runtime.buildRectangles(List.of(face))));
    }
    @Test void everyCancellationPhaseReturnsCheckoutsAndNextBuildRemainsClean()throws Exception{
        var runtime=runtime();var faces=List.of(top(-1,2048),top(-1,1984));var visits=new AtomicInteger();
        runtime.buildRectangles(faces,visits::incrementAndGet);int checkpoints=visits.get();assertTrue(checkpoints>=6);
        byte[] expected=runtime.buildRectangles(List.of(top(0,17)));
        for(int target=1;target<=checkpoints;target++){
            int stop=target;var seen=new AtomicInteger();var signal=new CancellationException("checkpoint "+stop);
            assertSame(signal,assertThrows(CancellationException.class,()->runtime.buildRectangles(faces,()->{if(seen.incrementAndGet()==stop)throw signal;})));
            assertArrayEquals(expected,runtime.buildRectangles(List.of(top(0,17))),"cleanup after checkpoint "+stop);
        }
    }
    @Test void nestedBuilderAttemptIsRejectedAndDoesNotCorruptOuterRecovery()throws Exception{
        var runtime=runtime();var face=top(0,23);var called=new AtomicInteger();
        var failure=assertThrows(IllegalStateException.class,()->runtime.buildRectangles(List.of(face),()->{
            if(called.getAndIncrement()==0)runtime.buildRectangles(List.of(face));
        }));assertTrue(failure.getMessage().contains("reentrant"));
        assertEquals(expectedPoints(face),actualPoints(runtime.buildRectangles(List.of(face))));
    }
    @Test void returnedBytesAreIndependentFromSubsequentUpstreamCheckoutReuse()throws Exception{
        var runtime=runtime();var face=top(0,31);byte[] first=runtime.buildRectangles(List.of(face));byte[] retained=first.clone();
        for(int i=0;i<200;i++)runtime.buildRectangles(List.of(top(0,i+100)));
        assertArrayEquals(retained,first);first[0]^=1;
        assertArrayEquals(retained,runtime.buildRectangles(List.of(face)));
    }
    @Test void unsupportedDownRectangleAndTransparentFaceCleanlyReleaseBuilder()throws Exception{
        var runtime=runtime();
        var down=new DhTerrainGeometry.PageFace(DhTerrainGeometry.Direction.DOWN,0,0,100,0,16,8,-1);
        assertThrows(IllegalArgumentException.class,()->runtime.buildRectangles(List.of(down)));
        var alpha=new DhTerrainGeometry.PageFace(DhTerrainGeometry.Direction.UP,0,0,101,0,8,8,0x7fffffff);
        assertThrows(IllegalArgumentException.class,()->runtime.buildRectangles(List.of(alpha)));
        var good=top(0,99);assertEquals(expectedPoints(good),actualPoints(runtime.buildRectangles(List.of(good))));
    }
}
