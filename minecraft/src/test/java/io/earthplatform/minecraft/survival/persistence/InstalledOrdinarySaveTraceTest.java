package io.earthplatform.minecraft.survival.persistence;

import io.earthplatform.minecraft.survival.world.SurvivalRefusal;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Pure sequence/failure tests; no native player, serialization, disk durability or admission proof. */
class InstalledOrdinarySaveTraceTest {
    private static final List<Consumer<InstalledOrdinarySaveTrace>> STEPS=List.of(
            InstalledOrdinarySaveTrace::cacheEnter,InstalledOrdinarySaveTrace::cacheDone,
            InstalledOrdinarySaveTrace::playerEnter,InstalledOrdinarySaveTrace::playerDone,
            InstalledOrdinarySaveTrace::statsEnter,InstalledOrdinarySaveTrace::statsWritten,
            InstalledOrdinarySaveTrace::statsDone,InstalledOrdinarySaveTrace::advancementsEnter,
            InstalledOrdinarySaveTrace::advancementsWritten,InstalledOrdinarySaveTrace::advancementsDone,
            InstalledOrdinarySaveTrace::listDone,InstalledOrdinarySaveTrace::levelEnter,
            InstalledOrdinarySaveTrace::levelWritten,trace->trace.levelReplaced(true),
            InstalledOrdinarySaveTrace::levelDone,InstalledOrdinarySaveTrace::verified);
    @Test void onlyAllOriginalEffectsAndReadbackBecomeVerified(){
        var trace=new InstalledOrdinarySaveTrace();for(var step:STEPS)step.accept(trace);
        assertEquals(InstalledOrdinarySaveTrace.Step.VERIFIED,trace.step());trace.requireVerified();assertNull(trace.failure());
    }
    @Test void everyInterruptedPrefixRemainsFailedWithOriginalFailure(){
        for(int prefix=0;prefix<=STEPS.size();prefix++){
            var trace=new InstalledOrdinarySaveTrace();for(int i=0;i<prefix;i++)STEPS.get(i).accept(trace);
            var first=new IllegalStateException("original prefix "+prefix);trace.poison(first);
            assertThrows(SurvivalRefusal.class,trace::requireVerified);assertSame(first,trace.failure());
            for(var later:STEPS){assertThrows(SurvivalRefusal.class,()->later.accept(trace));assertSame(first,trace.failure());}
        }
    }
    @Test void everyMissingOrDuplicateNativeOperationIsSticky(){
        for(int missing=0;missing<STEPS.size();missing++){
            var trace=new InstalledOrdinarySaveTrace();for(int i=0;i<missing;i++)STEPS.get(i).accept(trace);
            if(missing+1<STEPS.size()){var wrong=STEPS.get(missing+1);assertThrows(SurvivalRefusal.class,()->wrong.accept(trace));}
            else assertThrows(SurvivalRefusal.class,trace::requireVerified);
            assertEquals(InstalledOrdinarySaveTrace.Step.FAILED,trace.step());
        }
        for(int duplicate=0;duplicate<STEPS.size();duplicate++){
            var trace=new InstalledOrdinarySaveTrace();for(int i=0;i<=duplicate;i++)STEPS.get(i).accept(trace);
            var wrong=STEPS.get(duplicate);assertThrows(SurvivalRefusal.class,()->wrong.accept(trace));
            assertEquals(InstalledOrdinarySaveTrace.Step.FAILED,trace.step());
        }
    }
    @Test void nativeReplacementFalseCannotBeHiddenByMethodReturn(){
        var trace=new InstalledOrdinarySaveTrace();for(int i=0;i<13;i++)STEPS.get(i).accept(trace);
        assertThrows(SurvivalRefusal.class,()->trace.levelReplaced(false));
        Throwable first=trace.failure();assertThrows(SurvivalRefusal.class,trace::levelDone);
        assertThrows(SurvivalRefusal.class,trace::verified);assertSame(first,trace.failure());
    }
}
