package io.earthplatform.minecraft.coregate;

import io.earthplatform.core.CubePos;
import io.earthplatform.minecraft.enginebridge.ReplacementCertificate;
import io.earthplatform.minecraft.lod.DhReplacementInput;
import io.earthplatform.minecraft.lod.DhVoxelReplacementGeometry;
import io.earthplatform.minecraft.scene.SceneCube;
import java.util.Map;
import java.util.Objects;
import net.minecraft.world.level.block.Blocks;

/** Owner-sliced, exact whitelist capture. No model callbacks, mutable level or source is retained. */
final class RollingReplacementCapture implements AutoCloseable {
    static final int READS_PER_STEP=1024;
    private final ReplacementCertificate certificate;
    private int[] colors=new int[DhVoxelReplacementGeometry.MAX_READ_CELLS];
    private int cursor;
    private long compared;
    private boolean closed;
    RollingReplacementCapture(ReplacementCertificate certificate){this.certificate=Objects.requireNonNull(certificate);}
    ReplacementCertificate certificate(){return certificate;}
    int completedReads(){return cursor;}
    long comparedBindings(){return compared;}
    long retainedBytes(){return colors==null?0:(long)colors.length*Integer.BYTES;}

    static void verifyBindings(ReplacementCertificate certificate,Map<CubePos,SceneCube> cubes,boolean exactLeases){
        if(!DhReplacementInput.ANALYTIC_MATERIAL_POLICY.equals(certificate.materialPolicy()))
            throw new IllegalArgumentException("Unsupported replacement material policy");
        for(var v:certificate.dependencies()){
            var cube=cubes.get(v.position());
            if(cube==null)throw new IllegalStateException("Replacement capture dependency unknown: "+v.position());
            var s=cube.snapshot();
            if(!s.world().equals(certificate.world())||s.epoch()!=certificate.epoch()
                    ||s.revision()!=v.revision()||exactLeases&&s.leaseGeneration()!=v.leaseGeneration())
                throw new IllegalStateException("Replacement capture dependency changed: "+v.position());
        }
    }

    boolean step(Map<CubePos,SceneCube> cubes){
        if(closed)throw new IllegalStateException("Replacement capture closed");
        verifyBindings(certificate,cubes,true);compared+=certificate.dependencies().size();
        var p=certificate.originalCube();int end=Math.min(colors.length,cursor+READS_PER_STEP);
        while(cursor<end){
            int x=cursor%20,z=cursor/20%20,y=cursor/400;
            long bx=p.minBlockX()-2+x,by=p.minBlockY()-2+y,bz=p.minBlockZ()-2+z;
            var cube=cubes.get(CubePos.fromBlock(bx,by,bz));
            if(cube==null)throw new IllegalStateException("Unknown replacement voxel");
            var state=cube.state((int)Math.floorMod(bx,16),(int)Math.floorMod(by,16),(int)Math.floorMod(bz,16));
            final int argb;
            if(state==Blocks.AIR.defaultBlockState())argb=0;
            else if(state==Blocks.STONE.defaultBlockState())argb=0xff808080;
            else if(state==Blocks.DIORITE.defaultBlockState())argb=0xffdedede;
            else throw new UnsupportedOperationException("Replacement requires certified air, stone or diorite: "+state);
            colors[cursor++]=argb;
        }
        return cursor==colors.length;
    }

    DhVoxelReplacementGeometry.Capture finish(Map<CubePos,SceneCube> cubes){
        if(closed||cursor!=colors.length)throw new IllegalStateException("Complete bounded replacement capture required");
        verifyBindings(certificate,cubes,true);compared+=certificate.dependencies().size();
        var p=certificate.originalCube();
        // The library privately copies only the clipped read extent and keeps no callback.
        return DhVoxelReplacementGeometry.capture(p,(x,y,z)->{
            int lx=Math.toIntExact(x-(p.minBlockX()-2)),ly=Math.toIntExact(y-(p.minBlockY()-2)),lz=Math.toIntExact(z-(p.minBlockZ()-2));
            if(lx<0||lx>=20||ly<0||ly>=20||lz<0||lz>=20)throw new IllegalStateException("Replacement library requested outside its exact halo");
            return colors[(ly*20+lz)*20+lx];
        },()->{if(closed)throw new IllegalStateException("Replacement capture cancelled");});
    }
    @Override public void close(){closed=true;colors=null;}
}
