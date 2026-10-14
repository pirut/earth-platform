package io.earthplatform.minecraft.lod;

import io.earthplatform.core.*;
import io.earthplatform.minecraft.scene.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.BooleanSupplier;
import org.joml.Matrix4f;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

/** Pure/fake-GL authority, pooled ownership and real asynchronous replacement geometry contracts. */
@Timeout(30)
class DhPreparedReplacementTest {
    static final String SOURCE="replacement-test-flat";
    static final WorldIdentity WORLD=new WorldIdentity(1,"test","1",SOURCE,"palette");
    static final CubePos EDIT=new CubePos(0,62,0);
    static final long EPOCH=4,RESOURCE=2;
    static final class Guard implements DhReplacementInput.AuthorityGuard {
        final Thread owner=Thread.currentThread();boolean current=true,historical=true;int calls;Runnable callback=()->{};
        public boolean isCurrent(DhReplacementInput.Certificate c){assertSame(owner,Thread.currentThread());calls++;callback.run();return current;}
        public boolean permitsRetainedPresentation(DhReplacementInput.Certificate c){assertSame(owner,Thread.currentThread());calls++;callback.run();return historical;}
    }
    static DhReplacementInput input(Guard guard,long revision,String policy){
        var capture=DhVoxelReplacementGeometry.capture(EDIT,(x,y,z)->y<=1000?0xff888888:(x==4&&y==1001&&z==4?0xffdddddd:0),()->{});
        var c=new DhReplacementInput.Certificate(WORLD,EPOCH,RESOURCE,revision+1,EDIT,revision,String.format("%064x",revision+1),"b".repeat(64),policy);
        return new DhReplacementInput(c,capture,guard);
    }
    static DhReplacementInput input(Guard g,long rev){return input(g,rev,DhReplacementInput.ANALYTIC_MATERIAL_POLICY);}
    static void until(BooleanSupplier done,Runnable work){long stop=System.nanoTime()+TimeUnit.SECONDS.toNanos(20);while(!done.getAsBoolean()){if(System.nanoTime()>stop)fail("timeout");work.run();try{Thread.sleep(1);}catch(InterruptedException e){throw new AssertionError(e);}}}
    @Test void dataHasNoOwnerCallbackAndCertificateContentChangesIdentity(){var g=new Guard();var a=input(g,1);assertTrue(Arrays.stream(DhReplacementInput.Data.class.getDeclaredFields()).noneMatch(f->DhReplacementInput.AuthorityGuard.class.isAssignableFrom(f.getType())||f.getType()==DhReplacementInput.class));assertNotEquals(a.identity(),input(g,2).identity());assertNotEquals(a.identity(),input(g,1,"other").identity());assertEquals(a.identity(),input(new Guard(),1).identity());assertEquals(0,g.calls);assertEquals(32000,a.data().declaredBytes());}
}
