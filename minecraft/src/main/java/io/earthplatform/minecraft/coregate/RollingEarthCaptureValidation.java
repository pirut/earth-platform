package io.earthplatform.minecraft.coregate;

import io.earthplatform.core.CubePos;
import io.earthplatform.minecraft.enginebridge.*;
import io.earthplatform.minecraft.scene.SceneCube;
import java.util.Map;
import java.util.Objects;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/** Bounded scalar validation of received compact content against exact native snapshot authority. */
final class RollingEarthCaptureValidation implements AutoCloseable {
    static final int READS_PER_STEP=1024;
    private final ReplacementCertificate certificate;
    private final boolean exactLeases;
    private EarthReplacementCapture capture;
    private int cursor;
    RollingEarthCaptureValidation(ReplacementCertificate certificate,EarthReplacementCapture capture) {
        this(certificate,capture,true);
    }
    RollingEarthCaptureValidation(ReplacementCertificate certificate,EarthReplacementCapture capture,boolean exactLeases) {
        RollingEarthReplacementWire.require(certificate,capture);this.certificate=certificate;this.capture=capture;this.exactLeases=exactLeases;
    }
    int completedReads(){return cursor;}
    static void verifyBindings(ReplacementCertificate c,Map<CubePos,SceneCube> cubes) {verifyBindings(c,cubes,true);}
    static void verifyBindings(ReplacementCertificate c,Map<CubePos,SceneCube> cubes,boolean exactLeases) {
        if(!EarthReplacementAdmission.MATERIAL_POLICY.equals(c.materialPolicy())||!EarthReplacementAdmission.DETAIL_POLICY.equals(c.detailPolicy()))
            throw new IllegalArgumentException("Earth capture policy mismatch");
        for(var v:c.dependencies()) {
            var cube=cubes.get(v.position());if(cube==null)throw new IllegalStateException("Earth capture dependency missing");
            var s=cube.snapshot();if(!s.world().equals(c.world())||s.epoch()!=c.epoch()||s.revision()!=v.revision()||exactLeases&&s.leaseGeneration()!=v.leaseGeneration())
                throw new IllegalStateException("Earth capture dependency changed");
        }
    }
    boolean step(Map<CubePos,SceneCube> cubes) {
        var value=Objects.requireNonNull(capture,"Earth capture validation closed");verifyBindings(certificate,cubes,exactLeases);
        int end=Math.min(value.cellCount(),cursor+READS_PER_STEP);
        while(cursor<end) {
            long x=value.minX()+cursor%20,z=value.minZ()+cursor/20%20,y=value.minY()+cursor/400;
            var cube=cubes.get(CubePos.fromBlock(x,y,z));
            var state=cube.state((int)Math.floorMod(x,16),(int)Math.floorMod(y,16),(int)Math.floorMod(z,16));
            if(material(state)!=value.materialAt(x,y,z))throw new IllegalStateException("Earth replacement differs from authoritative native material");
            cursor++;
        }
        return cursor==value.cellCount();
    }
    EarthReplacementCapture finish(Map<CubePos,SceneCube> cubes) {
        if(capture==null||cursor!=capture.cellCount())throw new IllegalStateException("Complete Earth capture validation required");
        verifyBindings(certificate,cubes,exactLeases);return capture;
    }
    static int material(BlockState state) {
        if(state==Blocks.AIR.defaultBlockState())return 0;if(state==Blocks.STONE.defaultBlockState())return 1;
        if(state==Blocks.DIORITE.defaultBlockState())return 2;if(state==Blocks.ANDESITE.defaultBlockState())return 3;
        if(state==Blocks.TUFF.defaultBlockState())return 4;if(state==Blocks.SNOW_BLOCK.defaultBlockState())return 5;
        throw new IllegalArgumentException("Unsupported native Earth capture material");
    }
    public void close(){capture=null;}
}
