package io.earthplatform.compat.dh;

import com.seibel.distanthorizons.api.DhApi;
import com.seibel.distanthorizons.api.interfaces.world.IDhApiLevelWrapper;
import com.seibel.distanthorizons.api.methods.events.abstractEvents.DhApiBeforeRenderEvent;
import com.seibel.distanthorizons.api.methods.events.sharedParameterObjects.DhApiCancelableEventParam;
import com.seibel.distanthorizons.api.methods.events.sharedParameterObjects.DhApiRenderParam;
import com.seibel.distanthorizons.core.config.Config;
import com.seibel.distanthorizons.core.config.eventHandlers.IgnoredDimensionCsvHandler;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

/** Headless fixture only. Restores every upstream field/listener it changes; never runs in the shim. */
final class InstalledDhStockPolicyFixture implements AutoCloseable {
    final IgnoredDimensionCsvHandler stock=IgnoredDimensionCsvHandler.INSTANCE;
    private final Field dimensionNames;
    private final Object savedDimensions;
    private final List<SavedField> savedConfig=new ArrayList<>();
    private final boolean bound;
    private record SavedField(Field field,Object owner,Object value) {
        void restore()throws IllegalAccessException{field.set(owner,value);}
    }
    InstalledDhStockPolicyFixture()throws ReflectiveOperationException {
        assertNotNull(stock);
        dimensionNames=IgnoredDimensionCsvHandler.class.getDeclaredField("dimensionNames");
        dimensionNames.setAccessible(true);savedDimensions=dimensionNames.get(stock);
        // The official callback changes these transient overrides. Preserve them exactly,
        // including provenance and timestamp, without calling any configuration setter.
        for(Object entry:List.of(Config.Client.Advanced.Graphics.Fog.enableVanillaFog,
                Config.Client.Advanced.Graphics.Quality.vanillaFadeMode))
            for(String name:List.of("apiValue","apiUser","apiValueLastSetMs")){
                Field field=entry.getClass().getDeclaredField(name);field.setAccessible(true);
                savedConfig.add(new SavedField(field,entry,field.get(entry)));
            }
        var listeners=DhApi.events.getAll(DhApiBeforeRenderEvent.class);
        bound=listeners.stream().noneMatch(listener->listener==stock);
        if(bound)DhApi.events.bind(DhApiBeforeRenderEvent.class,stock);
        assertEquals(1,DhApi.events.getAll(DhApiBeforeRenderEvent.class).stream().filter(listener->listener==stock).count());
    }
    void ignored(String... names)throws IllegalAccessException {dimensionNames.set(stock,names.length==0?null:names.clone());}
    void moveStockToEnd(){
        assertTrue(DhApi.events.unbind(DhApiBeforeRenderEvent.class,stock.getClass()));
        DhApi.events.bind(DhApiBeforeRenderEvent.class,stock);
    }
    static IgnoredDimensionCsvHandler anotherStockInstance()throws ReflectiveOperationException {
        var constructor=IgnoredDimensionCsvHandler.class.getDeclaredConstructor();constructor.setAccessible(true);
        return constructor.newInstance();
    }
    static DhApiCancelableEventParam<DhApiRenderParam> event(Object level,String dimension){
        var payload=new DhApiRenderParam();
        payload.clientLevelWrapper=(IDhApiLevelWrapper)Proxy.newProxyInstance(DhApi.class.getClassLoader(),new Class<?>[]{IDhApiLevelWrapper.class},(p,m,args)->{
            if(m.getName().equals("getWrappedMcObject"))return level;
            if(m.getName().equals("getDimensionName"))return dimension;
            throw new UnsupportedOperationException(m.getName());
        });
        return new DhApiCancelableEventParam<>(payload);
    }
    @Override public void close()throws ReflectiveOperationException {
        try{if(bound)assertTrue(DhApi.events.unbind(DhApiBeforeRenderEvent.class,stock.getClass()));}
        finally{
            IgnoredDimensionCsvHandler.INSTANCE=stock;dimensionNames.set(stock,savedDimensions);
            for(var value:savedConfig)value.restore();
        }
    }
}
