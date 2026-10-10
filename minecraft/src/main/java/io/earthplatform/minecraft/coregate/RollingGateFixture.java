package io.earthplatform.minecraft.coregate;

import io.earthplatform.core.CancellationToken;
import io.earthplatform.core.CubePos;
import io.earthplatform.core.CubeProvider;
import io.earthplatform.core.ImmutableCube;
import io.earthplatform.core.WorldIdentity;
import io.earthplatform.minecraft.enginebridge.AnalyticNativeCubeProvider;
import io.earthplatform.minecraft.network.EnginePaletteContext;
import java.util.concurrent.CompletableFuture;

/** Isolated interior-edit fixture identity; it cannot open the older unrestricted fixture stores. */
final class RollingGateFixture {
    static final String OPT_IN="earthplatform.experimental.rollingCubeGate";
    static final String VERSION="rolling-interior-v1";
    static boolean enabled(){return Boolean.getBoolean(OPT_IN);}
    record Provider(AnalyticNativeCubeProvider delegate,WorldIdentity identity) implements CubeProvider {
        @Override public CompletableFuture<ImmutableCube> load(CubePos position,CancellationToken cancellation){return delegate.load(position,cancellation);}
    }
    static Provider provider(EnginePaletteContext palette,CoreGateProfile profile){
        var provider=profile.provider(palette);var w=provider.identity();
        return new Provider(provider,new WorldIdentity(w.seed(),w.generatorId(),w.generatorVersion()+"/"+VERSION,w.datasetVersion(),w.blockPaletteVersion()));
    }
    static String directory(CoreGateProfile profile){return "earthplatform-rolling-player-gate-v1/"+profile.id();}
}
