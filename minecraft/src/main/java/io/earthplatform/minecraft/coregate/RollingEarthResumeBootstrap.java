package io.earthplatform.minecraft.coregate;

import io.earthplatform.core.CubePos;
import io.earthplatform.core.CubeTickets;
import io.earthplatform.minecraft.enginebridge.ReplacementCertificate;
import io.earthplatform.minecraft.network.EngineCubeSnapshot;
import io.earthplatform.minecraft.network.EnginePaletteContext;
import io.earthplatform.minecraft.scene.SceneCube;
import io.earthplatform.minecraft.scene.SceneRenderEnvelope;
import java.util.*;

/** Read-only original27 receipt, never a collision/native draw window and never editable. */
final class RollingEarthResumeBootstrap implements AutoCloseable {
    private final RollingSessionDescriptor profile;
    private final long epoch;
    private final EnginePaletteContext palette;
    private final List<CubePos> positions;
    private final Map<CubePos,SceneCube> cubes=new LinkedHashMap<>(27);
    private final Map<CubePos,SceneCube> view=Collections.unmodifiableMap(cubes);
    private boolean closed;
    private final io.earthplatform.minecraft.visual.NativeDistanceCpuBudget.Reservation reservation;
    RollingEarthResumeBootstrap(RollingSessionDescriptor profile,long epoch,EnginePaletteContext palette){
        if(!profile.earthResumeBootstrap())throw new IllegalArgumentException("Distant Earth continuation required");
        this.profile=profile;this.epoch=epoch;this.palette=palette;
        positions=CubeTickets.box(profile.pinCenter(),1,1,27).stream().sorted().toList();
        reservation=io.earthplatform.minecraft.visual.NativeDistanceCpuBudget.global().reserve(io.earthplatform.minecraft.visual.NativeDistanceCpuBudget.Pool.OWNER_METADATA,27L*(EngineCubeSnapshot.MAX_SECTION_BYTES+32768L+512L));
    }
    void accept(long generation,EngineCubeSnapshot snapshot){
        int next=cubes.size();
        if(closed||generation!=1||next>=27||!snapshot.world().equals(profile.world())||snapshot.epoch()!=epoch
                ||!snapshot.position().equals(positions.get(next))||snapshot.leaseGeneration()!=next+1)
            throw new IllegalArgumentException("Stale, duplicate or noncanonical Earth bootstrap receipt");
        cubes.put(snapshot.position(),new SceneCube(snapshot,palette,SceneRenderEnvelope.PLAYER_DIAGNOSTIC_PADDING));
    }
    Map<CubePos,SceneCube> cubes(){if(closed||cubes.size()!=27)throw new IllegalStateException("Complete original27 bootstrap required");return view;}
    void require(ReplacementCertificate certificate){RollingEarthCaptureValidation.verifyBindings(certificate,cubes());}
    int retained(){return cubes.size();}
    public void close(){if(closed)return;closed=true;cubes.clear();reservation.releaseAfterPhysicalDrain();}
}
