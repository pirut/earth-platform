package io.earthplatform.compat.dh;

import com.seibel.distanthorizons.api.DhApi;
import com.seibel.distanthorizons.api.methods.events.abstractEvents.DhApiBeforeRenderEvent;
import com.seibel.distanthorizons.api.methods.events.abstractEvents.DhApiBeforeDeferredRenderEvent;
import com.seibel.distanthorizons.api.methods.events.interfaces.IDhApiEvent;
import com.seibel.distanthorizons.api.methods.events.sharedParameterObjects.DhApiCancelableEventParam;
import com.seibel.distanthorizons.api.methods.events.sharedParameterObjects.DhApiRenderParam;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Exercises upstream's real multi-listener dispatcher, not a pre-cancelled wrapper passed by hand. */
class InstalledDhIndependentDispatcherTest {
    static final class ForeignOpaqueCancel extends DhApiBeforeRenderEvent {
        @Override public void beforeRender(DhApiCancelableEventParam<DhApiRenderParam> event){event.cancelEvent();}
    }
    static final class ForeignDeferredCancel extends DhApiBeforeDeferredRenderEvent {
        @Override public void beforeRender(DhApiCancelableEventParam<DhApiRenderParam> event){event.cancelEvent();}
    }
    static InstalledDhResolver.Runtime runtime(){
        var mod=new InstalledDhResolver.InstalledMod("3.3.3",Path.of(java.util.Objects.requireNonNull(System.getProperty("earthplatform.installedDhTestJar"))),DhApi.class.getClassLoader());
        return new InstalledDhResolver.Runtime(mod,DhApi.class);
    }
    @Test void realPriorOrLaterOpaqueCancellerNeverGrantsOurDrawPermit()throws Exception{
        for(boolean before:new boolean[]{true,false})exercise(before,false);
    }
    @Test void foreignDeferredListenerAlsoPreventsUnsupportedCombinedOwnership()throws Exception{
        for(boolean before:new boolean[]{true,false})exercise(before,true);
    }
    @SuppressWarnings({"rawtypes","unchecked"})
    static void exercise(boolean before,boolean deferred)throws Exception{
        try(var stock=new InstalledDhStockPolicyFixture()){
            stock.ignored();
            Class type=deferred?DhApiBeforeDeferredRenderEvent.class:DhApiBeforeRenderEvent.class;
            IDhApiEvent foreign=deferred?new ForeignDeferredCancel():new ForeignOpaqueCancel();
            var gate=new DhCarrierRenderGate();var carrier=new Object();var resources=new InstalledDhIndependentAdversarialTest.Resource();
            gate.acquire(runtime(),carrier,"test-world",1,1,resources);
            var events=new InstalledDhEvents(runtime(),gate,()->new InstalledDhEvents.Frame(1,1,true),failure->{});
            boolean foreignBound=false;
            try{
                if(before){DhApi.events.bind(type,foreign);foreignBound=true;}
                boolean admitted=true;
                try{events.start();}catch(IllegalStateException unsupported){admitted=false;}
                assertEquals(!before,admitted,"Only a prior foreign participant may reject startup; late-listener coverage requires a live bridge");
                if(!before){DhApi.events.bind(type,foreign);foreignBound=true;}
                if(admitted){
                    var payload=InstalledDhStockPolicyFixture.event(carrier,"test@earthplatform:cubic").value;
                    assertTrue(DhApi.events.fireAllEvents(DhApiBeforeRenderEvent.class,payload));
                    assertTrue(gate.counters().fenced(),"Foreign render participants require explicit unsupported/fenced ownership");
                }
                assertEquals(0,resources.draws,"Per-listener wrappers must not hide another cancellation");
                assertTrue(DhApi.events.getAll(type).contains(foreign),"The shim must never remove another mod's listener");
            }finally{
                events.close();if(foreignBound)DhApi.events.unbind(type,foreign.getClass());
            }
        }
    }
}
