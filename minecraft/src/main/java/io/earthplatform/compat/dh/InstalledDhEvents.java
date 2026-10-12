package io.earthplatform.compat.dh;

import com.seibel.distanthorizons.api.DhApi;
import com.seibel.distanthorizons.api.methods.events.abstractEvents.DhApiBeforeRenderEvent;
import com.seibel.distanthorizons.api.methods.events.abstractEvents.DhApiBeforeDeferredRenderEvent;
import com.seibel.distanthorizons.api.methods.events.abstractEvents.DhApiLevelUnloadEvent;
import com.seibel.distanthorizons.api.methods.events.abstractEvents.DhApiWorldUnloadEvent;
import com.seibel.distanthorizons.api.methods.events.interfaces.IDhApiEvent;
import com.seibel.distanthorizons.api.methods.events.sharedParameterObjects.DhApiCancelableEventParam;
import com.seibel.distanthorizons.api.methods.events.sharedParameterObjects.DhApiEventParam;
import com.seibel.distanthorizons.api.methods.events.sharedParameterObjects.DhApiRenderParam;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/** Linked only after the optional installed runtime has resolved. Uses public event registration. */
public final class InstalledDhEvents implements AutoCloseable {
    public record Frame(long identity,long resourceGeneration,boolean supportedMode) { }
    @FunctionalInterface public interface FrameSource { Frame current(); }
    interface Registry {void bind(Class<? extends IDhApiEvent> type,IDhApiEvent listener);boolean unbind(Class<? extends IDhApiEvent> type,Class<? extends IDhApiEvent> listener);boolean onlyListener(Class<? extends IDhApiEvent> type,IDhApiEvent exact);default boolean carrierAllowed(DhApiRenderParam context){return true;}}
    private record Binding(Class<? extends IDhApiEvent> type,IDhApiEvent listener) { }
    private static InstalledDhEvents claimed;
    private final Thread owner=Thread.currentThread();
    private final DhCarrierRenderGate gate;private final FrameSource frames;private final Consumer<Throwable> failures;private final Registry registry;
    private IDhApiEvent opaqueListener,deferredListener;
    private final ArrayList<Binding> retained=new ArrayList<>(4);private boolean started,closed;
    public InstalledDhEvents(InstalledDhResolver.Runtime runtime,DhCarrierRenderGate gate,FrameSource frames,Consumer<Throwable> failures){
        this(gate,frames,failures,new Registry(){
            private final InstalledDhStockRenderPolicy stock=new InstalledDhStockRenderPolicy(runtime);
            public void bind(Class<? extends IDhApiEvent> t,IDhApiEvent v){DhApi.events.bind(t,v);}
            public boolean unbind(Class<? extends IDhApiEvent> t,Class<? extends IDhApiEvent> l){return DhApi.events.unbind(t,l);}
            public boolean onlyListener(Class<? extends IDhApiEvent> t,IDhApiEvent exact){return stock.supportedListeners(t,DhApi.events.getAll(t),exact);}
            public boolean carrierAllowed(DhApiRenderParam context){return stock.carrierAllowed(context);}
        });
        if(runtime.apiClass()!=DhApi.class)throw new IllegalStateException("API event classes are not the resolved installed runtime");
    }
    InstalledDhEvents(DhCarrierRenderGate gate,FrameSource frames,Consumer<Throwable> failures,Registry registry){
        this.gate=Objects.requireNonNull(gate);this.frames=Objects.requireNonNull(frames);this.failures=Objects.requireNonNull(failures);this.registry=Objects.requireNonNull(registry);
    }
    public void start(){checkOwner();synchronized(InstalledDhEvents.class){
        if(started||closed||claimed!=null)throw new IllegalStateException("One owned DH event bridge per process");
        claimed=this;started=true;
        try{
            opaqueListener=new Opaque(this);deferredListener=new Deferred(this);
            add(DhApiBeforeRenderEvent.class,opaqueListener);add(DhApiBeforeDeferredRenderEvent.class,deferredListener);
            add(DhApiLevelUnloadEvent.class,new LevelUnload(this));add(DhApiWorldUnloadEvent.class,new WorldUnload(this));
            requireExclusiveRenderListeners();
        }catch(RuntimeException|Error primary){try{close();}catch(RuntimeException|Error cleanup){if(primary!=cleanup)primary.addSuppressed(cleanup);}throw primary;}
    }}
    private void add(Class<? extends IDhApiEvent> type,IDhApiEvent listener){
        Binding b=new Binding(type,listener);retained.add(b); // retain before a possibly partially successful registration
        registry.bind(type,listener);
    }
    /** Cheap current configuration check for FrameSource; never modifies any DH value. */
    public static boolean supportedMode(){
        var render=DhApi.Delayed.renderProxy;var config=DhApi.Delayed.configs;
        return render!=null&&config!=null&&render.getRenderingApi()==com.seibel.distanthorizons.api.enums.config.EDhApiRenderingApi.OPEN_GL
                &&render.getRenderingEngine()==com.seibel.distanthorizons.api.enums.config.EDhApiRenderingEngine.OPEN_GL
                &&Boolean.TRUE.equals(config.graphics().renderingEnabled().getValue())
                &&config.graphics().renderingMode().getValue()==com.seibel.distanthorizons.api.enums.rendering.EDhApiRendererMode.DEFAULT;
    }
    private void opaque(DhApiCancelableEventParam<DhApiRenderParam> event){
        try{
            Object level=event.value.clientLevelWrapper.getWrappedMcObject();
            // Config inspection is deferred to FrameSource but cancellation ownership is decided first.
            if(!gate.owns(level)){return;}
            boolean cancelled=event.isEventCanceled();event.cancelEvent();
            requireExclusiveRenderListeners();
            requireCarrierPolicy(event.value);
            Frame frame=frames.current();
            gate.opaque(level,frame.identity(),frame.resourceGeneration(),cancelled,frame.supportedMode(),event::cancelEvent,event.value);
        }catch(RuntimeException|Error failure){contain(failure);}
    }
    private void deferred(DhApiCancelableEventParam<DhApiRenderParam> event){
        try{Object level=event.value.clientLevelWrapper.getWrappedMcObject();
            if(gate.owns(level)){event.cancelEvent();requireExclusiveRenderListeners();requireCarrierPolicy(event.value);}
            gate.deferred(level,event::cancelEvent);}
        catch(RuntimeException|Error failure){contain(failure);}
    }
    /** DH creates a separate cancellation wrapper for each listener, so prior-listener
     * cancellation cannot be inferred from our wrapper. The supported carrier path
     * therefore accepts only our listeners plus the exact pinned, non-drawing stock
     * ignored-dimension singleton in the opaque list at each dispatch. */
    private void requireExclusiveRenderListeners(){
        if(!registry.onlyListener(DhApiBeforeRenderEvent.class,opaqueListener)||!registry.onlyListener(DhApiBeforeDeferredRenderEvent.class,deferredListener))
            throw new IllegalStateException("Unsupported competing DH render listener; cubic carrier rendering is fenced");
    }
    private void requireCarrierPolicy(DhApiRenderParam context){
        if(!registry.carrierAllowed(context))throw new IllegalStateException("The installed DH ignored-dimension policy excludes this carrier; settings were not changed");
    }
    private void contain(Throwable failure){gate.invalidate("Installed DH callback failed");try{failures.accept(failure);}catch(RuntimeException|Error ignored){/* Never let our failure disable DH's global renderer. */}}
    @Override public void close(){checkOwner();synchronized(InstalledDhEvents.class){
        if(closed)return;
        gate.close(); // callbacks must remain installed while owned GPU cleanup is pending
        Throwable primary=null;
        for(int i=retained.size()-1;i>=0;i--){var b=retained.get(i);try{
            registry.unbind(b.type(),b.listener().getClass());retained.remove(i);
        }catch(RuntimeException|Error failure){if(primary==null)primary=failure;else if(primary!=failure)primary.addSuppressed(failure);}}
        if(retained.isEmpty()){closed=true;if(claimed==this)claimed=null;}
        if(primary instanceof Error e)throw e;if(primary instanceof RuntimeException e)throw e;
    }}
    public int retainedBindings(){return retained.size();}
    private void checkOwner(){if(Thread.currentThread()!=owner)throw new IllegalStateException("DH event bridge owner thread required");}
    private static final class Opaque extends DhApiBeforeRenderEvent {private final InstalledDhEvents owner;Opaque(InstalledDhEvents owner){this.owner=owner;}@Override public void beforeRender(DhApiCancelableEventParam<DhApiRenderParam> e){owner.opaque(e);}}
    private static final class Deferred extends DhApiBeforeDeferredRenderEvent {private final InstalledDhEvents owner;Deferred(InstalledDhEvents owner){this.owner=owner;}@Override public void beforeRender(DhApiCancelableEventParam<DhApiRenderParam> e){owner.deferred(e);}}
    private static final class LevelUnload extends DhApiLevelUnloadEvent {private final InstalledDhEvents owner;LevelUnload(InstalledDhEvents owner){this.owner=owner;}@Override public void onLevelUnload(DhApiEventParam<EventParam> e){try{owner.gate.levelUnloaded(e.value.levelWrapper.getWrappedMcObject());}catch(RuntimeException|Error f){owner.contain(f);}}}
    private static final class WorldUnload extends DhApiWorldUnloadEvent {private final InstalledDhEvents owner;WorldUnload(InstalledDhEvents owner){this.owner=owner;}@Override public void onWorldUnload(DhApiEventParam<EventParam> e){owner.gate.invalidate("DH world unloaded");}}
}
