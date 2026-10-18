package io.earthplatform.minecraft.cubic.client;

import io.earthplatform.minecraft.cubic.gen.FarSurfacePacket;
import java.util.BitSet;

/**
 * Conservative run-derived voxel candidates, emitted in original row/z/x order. Only fully
 * hidden opaque interior voxels can be omitted. Borders, water and every voxel next to air or
 * water (including +/- one row for unequal drops) remain in the original mesher. Coverage,
 * smooth terrain and edit overlays use its dense fallback before this helper is reached.
 */
final class FarRunCandidates {
    private FarRunCandidates() {}
    static BitSet make(FarSurfacePacket packet) {
        int height=packet.height(); BitSet candidates=new BitSet(height*256);
        candidates.set((height-1)*256,height*256); // above the packet is air
        for(int cell=0;cell<256;cell++) {
            int x=cell&15,z=cell>>4;
            if(x==0||x==15||z==0||z==15) mark(candidates,cell,0,height,height);
            int from=0;
            for(int run=packet.firstRun(cell);run<packet.afterLastRun(cell);run++) {
                int to=packet.runEnd(run);var state=packet.runState(run);
                boolean water=FarMesher.seeThrough(state);
                if(water)mark(candidates,cell,from,to,height);
                if(state.isAir()||water) {
                    mark(candidates,cell,from-1,from,height);mark(candidates,cell,to,to+1,height);
                    if(x>0)mark(candidates,cell-1,from-1,to+1,height);
                    if(x<15)mark(candidates,cell+1,from-1,to+1,height);
                    if(z>0)mark(candidates,cell-16,from-1,to+1,height);
                    if(z<15)mark(candidates,cell+16,from-1,to+1,height);
                }
                from=to;
            }
        }
        return candidates;
    }
    private static void mark(BitSet candidates,int cell,int from,int to,int height) {
        for(int row=Math.max(0,from);row<Math.min(to,height);row++)candidates.set(row*256+cell);
    }
}
