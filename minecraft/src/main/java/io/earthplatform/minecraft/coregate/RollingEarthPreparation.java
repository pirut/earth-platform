package io.earthplatform.minecraft.coregate;

import io.earthplatform.minecraft.enginebridge.NativeEarthCubeProvider;
import io.earthplatform.minecraft.network.EnginePaletteContext;
import io.earthplatform.minecraft.scene.SceneSurfaceSampler;
import java.util.Objects;

/** Blocking immutable provider preparation; invoke only on the bounded provider bootstrap workers. */
final class RollingEarthPreparation {
    static final String OPT_IN="earthplatform.experimental.earthPlayerGate";
    static final long ANCHOR_X=9_674_936L, ANCHOR_Z=-3_112_472L;
    static final boolean NATURAL_RELIEF=true;
    record Prepared(RollingGateFixture.Provider provider,RollingSessionDescriptor descriptor,
                    SceneSurfaceSampler sampler,String sourceUrl,String verticalDatum,String workerThread,long elapsedNanos,RollingAlpinePreparation.SourceEvidence alpine) {
        Prepared(RollingGateFixture.Provider provider,RollingSessionDescriptor descriptor,SceneSurfaceSampler sampler,String sourceUrl,String verticalDatum,String workerThread,long elapsedNanos){this(provider,descriptor,sampler,sourceUrl,verticalDatum,workerThread,elapsedNanos,null);}
        Prepared {
            Objects.requireNonNull(provider);Objects.requireNonNull(descriptor);Objects.requireNonNull(sampler);Objects.requireNonNull(sourceUrl);Objects.requireNonNull(verticalDatum);Objects.requireNonNull(workerThread);
            descriptor.requirePreparedWorld(provider.identity());
            if(descriptor.alpineMode()!=(alpine!=null))throw new IllegalArgumentException("Alpine preparation requires exact source evidence");
            if(!sampler.identity().equals(provider.identity().datasetVersion())||!sampler.supportsConcurrentReads())
                throw new IllegalArgumentException("Prepared Earth near/far identity or concurrency mismatch");
        }
    }
    private RollingEarthPreparation(){}
    static Prepared prepare(EnginePaletteContext palette,Runnable cancellationCheck){
        Objects.requireNonNull(palette);Objects.requireNonNull(cancellationCheck);long start=System.nanoTime();
        cancellationCheck.run();var earth=NativeEarthCubeProvider.prepare(palette,NATURAL_RELIEF);cancellationCheck.run();
        var provider=RollingGateFixture.wrap(earth,earth.identity());var sampler=earth.surfaceSampler();
        int ground=Math.toIntExact(sampler.surfaceY(ANCHOR_X,ANCHOR_Z));cancellationCheck.run();
        int localY=Math.floorMod(ground,16);
        if(localY<1||localY>13)throw new IllegalStateException("Measured entry does not permit an interior ground edit and adjacent placement");
        var descriptor=RollingSessionDescriptor.earth(provider.identity(),palette,ANCHOR_X,ground,ANCHOR_Z);
        descriptor.requireAllowedWindow(descriptor.center());
        if(!descriptor.containsBody(descriptor.walkEntryBody()))throw new IllegalStateException("Measured Earth entry body exceeds its pinned safety envelope");
        cancellationCheck.run();return new Prepared(provider,descriptor,sampler,earth.sourceUrl(),earth.verticalDatum(),Thread.currentThread().getName(),Math.max(0,System.nanoTime()-start));
    }
}
