package io.earthplatform.minecraft.enginebridge;

import io.earthplatform.core.*;
import io.earthplatform.geography.earth.EarthAlpineDefinition;
import io.earthplatform.minecraft.network.EnginePaletteContext;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class NativeAlpineCubeProviderTest {
    @TempDir Path temporary;
    private static EnginePaletteContext palette;
    @BeforeAll static void registries(){net.minecraft.SharedConstants.tryDetectVersion();net.minecraft.server.Bootstrap.bootStrap();palette=NativeCubeCodecTest.createPalette();}
    private EarthAlpineDefinition source()throws Exception{return AlpineEarthResources.installAndLoad(temporary.resolve("source"));}
    @Test void bundledSourceExactIdentityCompleteCubeAndStratifiedSamplerAgree()throws Exception{
        var definition=source();var provider=new NativeAlpineCubeProvider(definition,palette);var anchor=definition.anchor();
        assertEquals(EarthAlpineDefinition.DEFINITION_IDENTITY,provider.identity().datasetVersion());
        assertEquals(NativeCubeCodec.paletteIdentity(palette),provider.identity().blockPaletteVersion());
        assertTrue(provider.surfaceSampler().supportsStratifiedSides());assertTrue(provider.surfaceSampler().supportsConcurrentReads());
        for(int dy=-1;dy<=1;dy++){
            var pos=new CubePos(anchor.center().x(),anchor.center().y()+dy,anchor.center().z());
            var cube=provider.load(pos,new CancellationToken()).join();
            for(int z=0;z<16;z++)for(int x=0;x<16;x++){
                long ax=pos.minBlockX()+x,az=pos.minBlockZ()+z;var c=definition.column(ax,az);
                assertEquals(c.surfaceY(),provider.surfaceSampler().surfaceY(ax,az));
                var bands=provider.surfaceSampler().columnMaterials(ax,az,c.surfaceY());
                assertEquals(c.top().argb(),bands.top().argb());assertEquals(c.subsurface().argb(),bands.subsurface().argb());assertEquals(c.deep().argb(),bands.deep().argb());
                assertEquals(c.top()==EarthAlpineDefinition.Material.SNOW_BLOCK?8:2,bands.top().dhMaterial());
                for(int y=0;y<16;y++)assertSame(NativeAlpineCubeProvider.nativeState(c.materialAt(pos.minBlockY()+y)),NativeCubeCodec.registeredState(cube.get(x,y,z)));
            }
        }
    }
    @Test void cancelledAndUnknownRequestsSettleWithoutInventingAirOrStone()throws Exception{
        var provider=new NativeAlpineCubeProvider(source(),palette);var cancelled=new CancellationToken();var cancel=CancellationToken.class.getDeclaredMethod("cancel");cancel.setAccessible(true);cancel.invoke(cancelled);
        var result=provider.load(provider.definition().anchor().center(),cancelled);assertTrue(result.isDone());
        assertThrows(CancellationException.class,result::join);
        var outside=new CubePos(Integer.MIN_VALUE,0,0);
        assertThrows(CompletionException.class,()->provider.load(outside,new CancellationToken()).join());
        var origin=provider.definition().anchor().center();
        var top=provider.load(new CubePos(origin.x(),553,origin.z()),new CancellationToken()).join();
        var bottom=provider.load(new CubePos(origin.x(),-688,origin.z()),new CancellationToken()).join();
        for(int y=0;y<16;y++)for(int z=0;z<16;z++)for(int x=0;x<16;x++){
            assertSame(net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(),NativeCubeCodec.registeredState(top.get(x,y,z)));
            assertSame(net.minecraft.world.level.block.Blocks.STONE.defaultBlockState(),NativeCubeCodec.registeredState(bottom.get(x,y,z)));
        }
        assertThrows(CompletionException.class,()->provider.load(new CubePos(origin.x(),554,origin.z()),new CancellationToken()).join());
        assertThrows(CompletionException.class,()->provider.load(new CubePos(origin.x(),-689,origin.z()),new CancellationToken()).join());
        assertThrows(IllegalArgumentException.class,()->provider.surfaceSampler().argb(EarthAlpineDefinition.ANCHOR_X,EarthAlpineDefinition.ANCHOR_Z,0));
    }
    @Test void parallelPreparedReadsAreDeterministicAndNoMutableSourceEscapes()throws Exception{
        var provider=new NativeAlpineCubeProvider(source(),palette);var center=provider.definition().anchor().center();
        try(var workers=Executors.newFixedThreadPool(2)){
            var jobs=new ArrayList<Future<ImmutableCube>>();
            for(int i=0;i<8;i++){var p=new CubePos(center.x()+i%2,center.y()+i%3-1,center.z()+i%2);jobs.add(workers.submit(()->provider.load(p,new CancellationToken()).join()));}
            for(int i=0;i<jobs.size();i++){var p=new CubePos(center.x()+i%2,center.y()+i%3-1,center.z()+i%2);var expected=provider.load(p,new CancellationToken()).join();var actual=jobs.get(i).get(20,TimeUnit.SECONDS);
                for(int y=0;y<16;y++)for(int z=0;z<16;z++)for(int x=0;x<16;x++)assertEquals(expected.get(x,y,z),actual.get(x,y,z));}
        }
    }
    @Test void boundedResourceInstallRepairsRegularStaleBytesButRejectsSymlinkTargets()throws Exception{
        var root=temporary.resolve("source");var first=AlpineEarthResources.installAndLoad(root);
        var raster=root.resolve("terrain/everest-expanded.egp");Files.write(raster,new byte[]{0,1,2});
        assertEquals(first.identity(),AlpineEarthResources.installAndLoad(root).identity());assertEquals(2162732,Files.size(raster));
        Files.delete(raster);Files.createSymbolicLink(raster,temporary.resolve("outside"));
        assertThrows(java.io.IOException.class,()->AlpineEarthResources.installAndLoad(root));assertFalse(Files.exists(temporary.resolve("outside")));
    }
}
