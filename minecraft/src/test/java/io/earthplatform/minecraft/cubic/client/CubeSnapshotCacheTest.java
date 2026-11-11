package io.earthplatform.minecraft.cubic.client;

import static org.junit.jupiter.api.Assertions.*;
import io.earthplatform.minecraft.cubic.CubeKey;
import io.earthplatform.minecraft.cubic.gen.SurfacePacketFixtures;
import java.util.HashMap;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.PalettedContainer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class CubeSnapshotCacheTest {
    @BeforeAll static void bootstrap() { SurfacePacketFixtures.biomes(); }

    static PalettedContainer<BlockState> section(BlockState state) {
        return new PalettedContainer<>(Block.BLOCK_STATE_REGISTRY, state, PalettedContainer.Strategy.SECTION_STATES);
    }

    @Test void adjacentJobsReuseCopiesAndMatchEveryFreshSnapshotBlock() {
        var cache = new CubeSnapshotCache(1024);
        var live = new HashMap<Long, PalettedContainer<BlockState>>();
        BlockState[] kinds = {Blocks.STONE.defaultBlockState(), Blocks.AIR.defaultBlockState(),
                Blocks.WATER.defaultBlockState(), Blocks.GLASS.defaultBlockState(), Blocks.DEEPSLATE.defaultBlockState()};
        int requests = 0;
        for (int cy = 0; cy < 3; cy++) for (int cz = 0; cz < 3; cz++) for (int cx = 0; cx < 3; cx++) {
            for (int dy = -1; dy <= 1; dy++) for (int dz = -1; dz <= 1; dz++) for (int dx = -1; dx <= 1; dx++) {
                long key = CubeKey.pack(cx+dx,cy+dy,cz+dz);
                var source = live.computeIfAbsent(key, k -> {
                    var data = section(kinds[Math.floorMod(k.hashCode(), kinds.length)]);
                    data.set(3,4,5,Blocks.TORCH.defaultBlockState());
                    return data;
                });
                var cached = cache.get(key, source);
                var fresh = source.copy();
                for (int y=0;y<16;y++) for (int z=0;z<16;z++) for (int x=0;x<16;x++)
                    assertSame(fresh.get(x,y,z),cached.get(x,y,z));
                requests++;
            }
        }
        assertEquals(729, requests);
        assertEquals(125, cache.stats().copies());
        assertEquals(604, cache.stats().hits());
        System.out.println("CUBE_SNAPSHOT_ORACLE requests="+requests+" copies="+cache.stats().copies()+" all block states exact");
    }

    @Test void editsCreateNewSnapshotsWithoutChangingWorkerOwnedOldCopies() {
        var cache = new CubeSnapshotCache(8);
        var source = section(Blocks.STONE.defaultBlockState());
        var old = cache.get(7, source);
        source.set(15,15,15,Blocks.AIR.defaultBlockState());
        cache.invalidate(7);
        var current = cache.get(7, source);
        assertNotSame(old,current);
        assertSame(Blocks.STONE.defaultBlockState(),old.get(15,15,15));
        assertSame(Blocks.AIR.defaultBlockState(),current.get(15,15,15));
        source.set(0,0,0,Blocks.TORCH.defaultBlockState());
        cache.invalidate(7);
        assertSame(Blocks.TORCH.defaultBlockState(),cache.get(7,source).get(0,0,0));
        assertSame(Blocks.STONE.defaultBlockState(),current.get(0,0,0));
    }

    @Test void replacementsEvictionUnloadAndClearDoNotReuseStaleData() {
        var cache = new CubeSnapshotCache(2);
        var stone = section(Blocks.STONE.defaultBlockState());
        var a=cache.get(1,stone);var b=cache.get(2,stone);
        assertSame(a,cache.get(1,stone));
        cache.get(3,stone);
        assertEquals(2,cache.stats().retained());
        assertNotSame(b,cache.get(2,stone));
        var replacement=section(Blocks.WATER.defaultBlockState());
        assertSame(Blocks.WATER.defaultBlockState(),cache.get(2,replacement).get(0,0,0));
        cache.invalidate(2);
        assertSame(Blocks.STONE.defaultBlockState(),cache.get(2,stone).get(0,0,0));
        cache.clear();assertEquals(0,cache.stats().retained());
        assertNotSame(a,cache.get(1,stone));
    }
}
