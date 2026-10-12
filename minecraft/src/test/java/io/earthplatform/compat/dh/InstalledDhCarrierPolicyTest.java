package io.earthplatform.compat.dh;

import com.seibel.distanthorizons.api.methods.events.abstractEvents.DhApiBeforeRenderEvent;
import com.seibel.distanthorizons.api.methods.events.abstractEvents.DhApiBeforeDeferredRenderEvent;
import com.seibel.distanthorizons.api.methods.events.interfaces.IDhApiEvent;
import com.seibel.distanthorizons.api.methods.events.sharedParameterObjects.DhApiRenderParam;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Policy ordering/lifecycle contracts with no config or live-world mutation. */
class InstalledDhCarrierPolicyTest {
    static final class Registry implements InstalledDhEvents.Registry {
        final InstalledDhContractsTest.Registry base=new InstalledDhContractsTest.Registry();
        int policyReads;boolean allowed=true;RuntimeException policyFailure;Runnable inspect=()->{};
        public void bind(Class<? extends IDhApiEvent> type,IDhApiEvent value){base.bind(type,value);}
        public boolean unbind(Class<? extends IDhApiEvent> type,Class<? extends IDhApiEvent> listener){return base.unbind(type,listener);}
        public boolean onlyListener(Class<? extends IDhApiEvent> type,IDhApiEvent exact){return base.onlyListener(type,exact);}
        public boolean carrierAllowed(DhApiRenderParam context){policyReads++;inspect.run();if(policyFailure!=null)throw policyFailure;return allowed;}
    }
    @Test void ignoredOwnedOpaqueCancelsBeforePolicyAndNeverObtainsFrameOrDraw(){
        var r=new Registry();r.allowed=false;var gate=new DhCarrierRenderGate();var carrier=new Object();var owned=new InstalledDhContractsTest.Resource();
        gate.acquire(InstalledDhContractsTest.runtime(),carrier,"world",1,1,owned);var frames=new AtomicInteger();var failures=new AtomicInteger();
        var events=new InstalledDhEvents(gate,()->{frames.incrementAndGet();return new InstalledDhEvents.Frame(1,1,true);},f->failures.incrementAndGet(),r);
        events.start();try{
            var event=InstalledDhContractsTest.event(carrier);r.inspect=()->assertTrue(event.isEventCanceled());
            ((DhApiBeforeRenderEvent)r.base.listeners.get(DhApiBeforeRenderEvent.class)).beforeRender(event);
            assertTrue(event.isEventCanceled());assertTrue(gate.counters().fenced());assertEquals(0,frames.get());assertEquals(0,owned.draws);assertEquals(1,failures.get());
        }finally{events.close();}
        assertTrue(r.base.listeners.isEmpty());assertEquals(1,owned.closes);
    }
    @Test void normalLevelsDoNotReadPolicyEvenIfPolicyWouldFail(){
        var r=new Registry();r.policyFailure=new IllegalStateException("must not inspect ordinary world");var gate=new DhCarrierRenderGate();var carrier=new Object();var owned=new InstalledDhContractsTest.Resource();
        gate.acquire(InstalledDhContractsTest.runtime(),carrier,"world",1,1,owned);
        var events=new InstalledDhEvents(gate,()->{throw new AssertionError("ordinary frame lookup");},f->{throw new AssertionError(f);},r);events.start();try{
            var ordinary=InstalledDhContractsTest.event(new Object());
            ((DhApiBeforeRenderEvent)r.base.listeners.get(DhApiBeforeRenderEvent.class)).beforeRender(ordinary);
            ((DhApiBeforeDeferredRenderEvent)r.base.listeners.get(DhApiBeforeDeferredRenderEvent.class)).beforeRender(ordinary);
            assertFalse(ordinary.isEventCanceled());assertEquals(0,r.policyReads);assertFalse(gate.counters().fenced());
        }finally{events.close();}
    }
    @Test void policyChangeAndPolicyReadFailureFenceDeferredWithoutSecondSubmission(){
        for(boolean throwsFailure:new boolean[]{false,true}){
            var r=new Registry();var gate=new DhCarrierRenderGate();var carrier=new Object();var owned=new InstalledDhContractsTest.Resource();
            gate.acquire(InstalledDhContractsTest.runtime(),carrier,"world",1,1,owned);var frames=new AtomicInteger();var failures=new AtomicInteger();
            var events=new InstalledDhEvents(gate,()->new InstalledDhEvents.Frame(frames.incrementAndGet(),1,true),f->failures.incrementAndGet(),r);events.start();try{
                ((DhApiBeforeRenderEvent)r.base.listeners.get(DhApiBeforeRenderEvent.class)).beforeRender(InstalledDhContractsTest.event(carrier));assertEquals(1,owned.draws);
                if(throwsFailure)r.policyFailure=new IllegalStateException("unavailable policy");else r.allowed=false;
                var event=InstalledDhContractsTest.event(carrier);r.inspect=()->assertTrue(event.isEventCanceled());
                ((DhApiBeforeDeferredRenderEvent)r.base.listeners.get(DhApiBeforeDeferredRenderEvent.class)).beforeRender(event);
                assertTrue(event.isEventCanceled());assertTrue(gate.counters().fenced());assertEquals(1,owned.draws);assertEquals(1,frames.get());assertEquals(1,failures.get());
            }finally{events.close();}
        }
    }
}
