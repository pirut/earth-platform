package io.earthplatform.compat.dh;
import com.seibel.distanthorizons.api.DhApi;
import com.seibel.distanthorizons.api.interfaces.world.IDhApiLevelWrapper;
import java.util.Objects;
/** Read-only exact-level lookup; no guessed display name or global configuration mutation. */
public final class InstalledDhCarrierPolicy {
    private final InstalledDhStockRenderPolicy stock;
    public InstalledDhCarrierPolicy(InstalledDhResolver.Runtime runtime){if(runtime.apiClass()!=DhApi.class)throw new IllegalArgumentException("Foreign installed DH API");stock=new InstalledDhStockRenderPolicy(runtime);}
    public boolean allows(Object exactLevel){
        Objects.requireNonNull(exactLevel);var proxy=DhApi.Delayed.worldProxy;
        if(proxy==null||!proxy.worldLoaded())throw new IllegalStateException("Installed DH world is not ready");
        // In pinned DH this public method returns MC_CLIENT.getWrappedClientLevel().
        // getAllLoadedLevelWrappers instead yields server wrappers in an integrated world.
        IDhApiLevelWrapper match=proxy.getSinglePlayerLevel();
        if(match==null||match.getWrappedMcObject()!=exactLevel)throw new IllegalStateException("No installed DH wrapper for the exact shader carrier");
        return stock.allowsDimension(Objects.requireNonNull(match.getDimensionName(),"Exact DH carrier dimension name"));
    }
}
