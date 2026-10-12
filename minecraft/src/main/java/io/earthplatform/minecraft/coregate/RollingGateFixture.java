package io.earthplatform.minecraft.coregate;

import io.earthplatform.core.CancellationToken;
import io.earthplatform.core.CubePos;
import io.earthplatform.core.CubeProvider;
import io.earthplatform.core.ImmutableCube;
import io.earthplatform.core.WorldIdentity;
import io.earthplatform.minecraft.network.EnginePaletteContext;
import java.util.concurrent.CompletableFuture;

/** Isolated interior-edit fixture identity; it cannot open the older unrestricted fixture stores. */
final class RollingGateFixture {
    static final String OPT_IN="earthplatform.experimental.rollingCubeGate";
    static final String INDEXED_WORKSPACE_OPT_IN="earthplatform.experimental.indexedWorkspaceGate";
    static final String INDEXED_SCENARIO_OPT_IN="earthplatform.experimental.indexedScenario";
    static final String ADJACENT_WORKSPACE_OPT_IN="earthplatform.experimental.adjacentWorkspaceGate";
    static final String TWO_WORKSPACE_OPT_IN="earthplatform.experimental.twoWorkspaceGate";
    static final String BOUNDARY_OPT_IN="earthplatform.experimental.originalCubeBoundaryEdits";
    static final String REPLACEMENT_OPT_IN="earthplatform.experimental.authoritativeReplacement";
    static final String REPLACEMENT_DETAIL_POLICY="exact-owned18-read20-unit-faces-v1";
    static final String VERSION="rolling-interior-v1";
    static boolean enabled(){return Boolean.getBoolean(OPT_IN)||io.earthplatform.compat.dh.EarlyPlayableAdmission.enabled();}
    record Provider(CubeProvider delegate,WorldIdentity identity) implements CubeProvider {
        @Override public CompletableFuture<ImmutableCube> load(CubePos position,CancellationToken cancellation){return delegate.load(position,cancellation);}
    }
    static Provider provider(EnginePaletteContext palette,CoreGateProfile profile){
        var provider=profile.provider(palette);return wrap(provider,provider.identity());
    }
    static Provider wrap(CubeProvider provider,WorldIdentity w){
        return new Provider(provider,new WorldIdentity(w.seed(),w.generatorId(),w.generatorVersion()+"/"+VERSION,w.datasetVersion(),w.blockPaletteVersion()));
    }
    static String directory(CoreGateProfile profile){return "earthplatform-rolling-player-gate-v1/"+profile.id();}
}
