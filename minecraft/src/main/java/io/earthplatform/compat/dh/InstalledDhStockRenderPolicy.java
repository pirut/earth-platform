package io.earthplatform.compat.dh;

import com.seibel.distanthorizons.api.methods.events.abstractEvents.DhApiBeforeRenderEvent;
import com.seibel.distanthorizons.api.methods.events.abstractEvents.DhApiBeforeDeferredRenderEvent;
import com.seibel.distanthorizons.api.methods.events.interfaces.IDhApiEvent;
import com.seibel.distanthorizons.api.methods.events.sharedParameterObjects.DhApiRenderParam;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Objects;

/** Pinned read-only exception for DH's own non-drawing ignored-dimension listener.
 * Does not invoke its beforeRender callback, change config, register, or remove it. */
final class InstalledDhStockRenderPolicy {
    static final String HANDLER="com.seibel.distanthorizons.core.config.eventHandlers.IgnoredDimensionCsvHandler";
    private final Object singleton;
    private final Field singletonField;
    private final Method ignored;
    InstalledDhStockRenderPolicy(InstalledDhResolver.Runtime runtime){
        Objects.requireNonNull(runtime);
        try{
            var type=Class.forName(HANDLER,false,runtime.loader());
            if(type.getClassLoader()!=runtime.apiClass().getClassLoader()||type.getModule()!=runtime.apiClass().getModule())
                throw new IllegalStateException("Foreign stock DH policy class identity");
            InstalledDhResolver.verifyInstalledClassOrigin(runtime.installation(),type);
            singletonField=type.getField("INSTANCE");singleton=Objects.requireNonNull(singletonField.get(null),"Initialized stock DH policy required");
            if(singleton.getClass()!=type||!(singleton instanceof DhApiBeforeRenderEvent))throw new IllegalStateException("Exact installed stock DH policy singleton required");
            ignored=type.getMethod("dimensionNameShouldBeIgnored",String.class);
            if(ignored.getReturnType()!=boolean.class)throw new IllegalStateException("Unsupported stock DH policy signature");
        }catch(ReflectiveOperationException|java.io.IOException failure){throw new IllegalStateException("Cannot validate installed stock DH dimension policy",failure);}
    }
    boolean supportedListeners(Class<? extends IDhApiEvent> type,List<? extends IDhApiEvent> listeners,IDhApiEvent ours){
        requireCurrent();
        if(listeners==null||ours==null)return false;
        if(type==DhApiBeforeDeferredRenderEvent.class)return listeners.size()==1&&listeners.getFirst()==ours;
        if(type!=DhApiBeforeRenderEvent.class||listeners.size()!=2)return false;
        return listeners.get(0)==singleton&&listeners.get(1)==ours||listeners.get(0)==ours&&listeners.get(1)==singleton;
    }
    boolean carrierAllowed(DhApiRenderParam context){
        requireCurrent();Objects.requireNonNull(context);Objects.requireNonNull(context.clientLevelWrapper);
        String name=Objects.requireNonNull(context.clientLevelWrapper.getDimensionName(),"Carrier dimension name required for installed DH policy");
        return allowsDimension(name);
    }
    boolean allowsDimension(String name){
        requireCurrent();Objects.requireNonNull(name);
        try{return !((Boolean)ignored.invoke(singleton,name));}
        catch(IllegalAccessException failure){throw new IllegalStateException("Cannot read stock DH dimension policy",failure);}
        catch(InvocationTargetException failure){throw new IllegalStateException("Stock DH dimension policy failed",failure.getCause());}
    }
    boolean isExactSingleton(Object listener){requireCurrent();return listener==singleton;}
    private void requireCurrent(){
        try{if(singletonField.get(null)!=singleton)throw new IllegalStateException("Installed DH stock policy singleton changed; restart required");}
        catch(IllegalAccessException failure){throw new IllegalStateException("Cannot verify installed DH stock policy",failure);}
    }
}
