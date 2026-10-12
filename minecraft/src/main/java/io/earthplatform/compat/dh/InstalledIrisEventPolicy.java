package io.earthplatform.compat.dh;

import com.seibel.distanthorizons.api.DhApi;
import com.seibel.distanthorizons.api.methods.events.interfaces.IDhApiEvent;
import java.util.ArrayList;
import java.util.List;

/** Exact, bounded stock/Iris listener identities. Never invokes or mutates a listener.
 * DH's dispatcher aggregates cancellations after callbacks; native admission must reject
 * unsupported foreign callbacks before its earlier stage. Concurrent registry mutation
 * during a dispatch is outside the pinned upstream single-owner contract. */
public final class InstalledIrisEventPolicy {
    private static final String API="com.seibel.distanthorizons.api.methods.events.abstractEvents.";
    private static final String IRIS="net.irisshaders.iris.compat.dh.LodRendererEvents$";
    private static final String[] EVENTS={"DhApiBeforeRenderEvent","DhApiBeforeDeferredRenderEvent","DhApiBeforeRenderSetupEvent","DhApiBeforeRenderPassEvent","DhApiBeforeBufferRenderEvent","DhApiBeforeRenderCleanupEvent","DhApiBeforeApplyShaderRenderEvent","DhApiBeforeTextureClearEvent","DhApiColorDepthTextureCreatedEvent"};
    private static final int[][] TYPES={{2,7},{8},{12},{13},{11},{9},{14},{3,10},{6}};
    private record Entry(Class<? extends IDhApiEvent> type,List<? extends IDhApiEvent> listeners){}
    private final InstalledDhStockRenderPolicy stock;
    private final List<Entry> entries;
    @SuppressWarnings("unchecked")
    public InstalledIrisEventPolicy(InstalledDhResolver.Runtime dh,InstalledIrisRuntime.Lease iris){
        if(dh.apiClass()!=DhApi.class||!iris.current())throw new IllegalStateException("Exact current installed DH/Iris identities required");
        stock=new InstalledDhStockRenderPolicy(dh);Class<?> pipeline=iris.pipelineIdentity().getClass();var built=new ArrayList<Entry>(EVENTS.length);
        try{
            for(int e=0;e<EVENTS.length;e++){
                Class<?> event=Class.forName(API+EVENTS[e],false,dh.loader());InstalledDhResolver.verifyInstalledClassOrigin(dh.installation(),event);
                var type=(Class<? extends IDhApiEvent>)event;var listeners=DhApi.events.getAll(type);
                if(listeners==null||listeners.size()!=TYPES[e].length+(e==0?1:0))throw new IllegalStateException("Unsupported DH/Iris listener count for "+EVENTS[e]);
                boolean[] seen=new boolean[TYPES[e].length];boolean seenStock=false;
                for(var listener:listeners){
                    if(e==0&&stock.isExactSingleton(listener)){if(seenStock)throw new IllegalStateException("Duplicate stock DH policy");seenStock=true;continue;}
                    if(listener==null)throw new IllegalStateException("Missing pinned Iris listener");
                    Class<?> actual=listener.getClass();int index=-1;
                    for(int i=0;i<TYPES[e].length;i++)if(actual.getName().equals(IRIS+TYPES[e][i]))index=i;
                    if(index<0||seen[index]||actual.getClassLoader()!=pipeline.getClassLoader()||actual.getModule()!=pipeline.getModule())throw new IllegalStateException("Foreign/duplicate installed shader listener for "+EVENTS[e]);
                    seen[index]=true;
                }
                if(e==0&&!seenStock)throw new IllegalStateException("Missing exact stock ignored-dimension policy");
                built.add(new Entry(type,List.copyOf(listeners)));
            }
        }catch(ReflectiveOperationException|java.io.IOException error){throw new IllegalStateException("Cannot attest installed shader listener origin",error);}
        entries=List.copyOf(built);
    }
    public void requireCurrent(){
        for(var entry:entries){var actual=DhApi.events.getAll(entry.type());if(actual==null||actual.size()!=entry.listeners().size())throw new IllegalStateException("Installed shader listener set changed");
            for(int i=0;i<actual.size();i++)if(actual.get(i)!=entry.listeners().get(i))throw new IllegalStateException("Installed shader listener identity/order changed");}
        // Includes the mutable stock singleton even if its old object remains registered.
        for(var listener:entries.getFirst().listeners())if(listener.getClass().getName().equals(InstalledDhStockRenderPolicy.HANDLER)&&!stock.isExactSingleton(listener))throw new IllegalStateException("Installed stock policy changed");
    }
}
