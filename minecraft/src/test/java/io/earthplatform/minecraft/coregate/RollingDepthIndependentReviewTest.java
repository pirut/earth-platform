package io.earthplatform.minecraft.coregate;

import io.earthplatform.minecraft.lod.DhLodLayout;
import java.util.ArrayList;
import java.util.List;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Pure transaction/matrix adversaries; actual framebuffer proof is a separate mandatory gate. */
class RollingDepthIndependentReviewTest {
    private static Matrix4f camera() { return new Matrix4f().setPerspective((float)Math.toRadians(83),1.73f,.1f,256).m20(.13f).m21(-.07f); }
    private static final class Driver implements RollingDepthComposition.Driver {
        final List<String> events=new ArrayList<>(); final Thread owner=Thread.currentThread();
        final RollingDepthComposition.State original=new RollingDepthComposition.State(.2,.8,false,.37,true,13,29,101,73,true,24);
        RollingDepthComposition.State restored; RuntimeException captureFailure,clearFailure,applyFailure,restoreFailure;
        public void checkOwner(){assertSame(owner,Thread.currentThread());}
        public RollingDepthComposition.State capture(){events.add("capture");if(captureFailure!=null)throw captureFailure;return original;}
        public void clearDepth(){events.add("clear");if(clearFailure!=null)throw clearFailure;}
        public void apply(RollingDepthComposition.Pass pass){events.add("apply:"+pass.name());if(applyFailure!=null)throw applyFailure;}
        public void restore(RollingDepthComposition.State state){events.add("restore");assertSame(original,state);restored=state;if(restoreFailure!=null)throw restoreFailure;}
    }
    private static long count(RollingDepthComposition composition,String key){return composition.diagnostics().get(key).getAsLong();}
    private static boolean flag(RollingDepthComposition composition,String key){return composition.diagnostics().get(key).getAsBoolean();}

    @Test void bothOpaqueRenderersRunInEachOrderedPassWithOneClearAndOneRestore(){
        var d=new Driver();try(var c=new RollingDepthComposition(d)){
            assertTrue(c.draw(camera(),DhLodLayout.LONG_4096,p->{d.events.add(p.name()+":dh");d.events.add(p.name()+":native");return true;}));
            assertEquals(List.of("capture","clear","apply:far","far:dh","far:native","apply:near","near:dh","near:native","restore"),d.events);
            assertSame(d.original,d.restored);assertEquals(1,count(c,"frames"));assertEquals(2,count(c,"rendererPasses"));assertEquals(24,count(c,"depthBits"));
            assertFalse(flag(c,"restorePending"));assertFalse(flag(c,"failed"));
        }
    }
    @Test void callbackRefusalNeverSubmitsLaterPassAndAlwaysRestores(){
        var d=new Driver();try(var c=new RollingDepthComposition(d)){
            assertFalse(c.draw(camera(),DhLodLayout.LONG_4096,p->false));
            assertEquals(List.of("capture","clear","apply:far","restore"),d.events);
            assertEquals(0,count(c,"frames"));assertEquals(1,count(c,"abortedFrames"));assertFalse(flag(c,"restorePending"));
        }
    }
    @Test void setupAndRenderFailuresFenceReuseAndRetainPrimaryException(){
        for(String stage:List.of("capture","clear","apply","renderer")){
            var d=new Driver();var failure=new IllegalStateException(stage);
            switch(stage){case "capture"->d.captureFailure=failure;case "clear"->d.clearFailure=failure;case "apply"->d.applyFailure=failure;}
            var c=new RollingDepthComposition(d);
            assertSame(failure,assertThrows(IllegalStateException.class,()->c.draw(camera(),DhLodLayout.LONG_4096,p->{throw failure;})));
            assertTrue(flag(c,"failed"));assertEquals(0,count(c,"frames"));assertEquals(stage.equals("capture")?0:1,count(c,"restoreAttempts"));
            int calls=d.events.size();assertThrows(IllegalStateException.class,()->c.draw(camera(),DhLodLayout.LONG_4096,p->true));assertEquals(calls,d.events.size());c.close();
        }
    }
    @Test void failedRestorationKeepsExactOwnerThroughRepeatedCloseUntilVerified(){
        var d=new Driver();var failure=new IllegalStateException("restoration");d.restoreFailure=failure;var c=new RollingDepthComposition(d);
        assertSame(failure,assertThrows(IllegalStateException.class,()->c.draw(camera(),DhLodLayout.LONG_4096,p->true)));
        assertTrue(flag(c,"restorePending"));assertFalse(flag(c,"closed"));assertEquals(0,count(c,"frames"));
        assertSame(failure,assertThrows(IllegalStateException.class,c::close));assertTrue(flag(c,"restorePending"));assertFalse(flag(c,"closed"));
        d.restoreFailure=null;c.close();assertFalse(flag(c,"restorePending"));assertTrue(flag(c,"closed"));
        assertEquals(3,count(c,"restoreAttempts"));assertEquals(2,count(c,"restoreFailures"));assertEquals(1,d.events.stream().filter("capture"::equals).count());
    }
    @Test void renderFailureAndDifferentRestoreFailurePreserveBothCauses(){
        var d=new Driver();var primary=new IllegalArgumentException("draw");var secondary=new IllegalStateException("restore");d.restoreFailure=secondary;var c=new RollingDepthComposition(d);
        assertSame(primary,assertThrows(IllegalArgumentException.class,()->c.draw(camera(),DhLodLayout.LONG_4096,p->{throw primary;})));
        assertArrayEquals(new Throwable[]{secondary},primary.getSuppressed());assertTrue(flag(c,"restorePending"));d.restoreFailure=null;c.close();
    }
    @Test void sameThrowableFromDrawAndRestoreCannotSelfSuppressOrReplacePrimary(){
        var d=new Driver();var primary=new IllegalStateException("shared failure instance");d.restoreFailure=primary;var c=new RollingDepthComposition(d);
        assertSame(primary,assertThrows(IllegalStateException.class,()->c.draw(camera(),DhLodLayout.LONG_4096,p->{throw primary;})));
        assertTrue(flag(c,"restorePending"));assertTrue(flag(c,"failed"));d.restoreFailure=null;c.close();
    }
    @Test void nestedDrawAndCloseCannotMutateAnActiveFrame(){
        var d=new Driver();try(var c=new RollingDepthComposition(d)){
            assertTrue(c.draw(camera(),DhLodLayout.LONG_4096,p->{
                assertThrows(IllegalStateException.class,()->c.draw(camera(),DhLodLayout.LONG_4096,q->true));
                assertThrows(IllegalStateException.class,c::close);return true;
            }));assertEquals(1,count(c,"frames"));assertEquals(1,count(c,"restoreAttempts"));
        }
    }
    @Test void projectionCopiesPreserveEveryNondepthCoefficientAndRejectInvalidInputsBeforeCapture(){
        var original=camera();var copy=new Matrix4f(original);var plan=RollingDepthComposition.passes(original,DhLodLayout.LONG_4096);
        assertEquals(copy,original);assertEquals(List.of("far","near"),plan.stream().map(RollingDepthComposition.Pass::name).toList());
        assertThrows(UnsupportedOperationException.class,plan::clear);
        for(var pass:plan){float[] a=new float[16],b=new float[16];original.get(a);pass.projection().get(b);
            for(int i=0;i<16;i++)if(i!=10&&i!=14)assertEquals(a[i],b[i]);
            Matrix4f leaked=pass.projection();leaked.zero();assertNotEquals(leaked,pass.projection());
        }
        var d=new Driver();try(var c=new RollingDepthComposition(d)){
            for(var invalid:List.of(new Matrix4f().setOrtho(-1,1,-1,1,.05f,8192),new Matrix4f(original).m00(Float.NaN),new Matrix4f(original).m02(.1f),new Matrix4f(original).m23(.5f)))
                assertThrows(IllegalArgumentException.class,()->c.draw(invalid,DhLodLayout.LONG_4096,p->true));
            assertTrue(d.events.isEmpty());assertFalse(flag(c,"failed"));
        }
        var legacy=RollingDepthComposition.passes(original,DhLodLayout.FIXED_512);assertEquals(1,legacy.size());assertEquals(2048,legacy.getFirst().farPlane());assertEquals(0,legacy.getFirst().depthMin());assertEquals(1,legacy.getFirst().depthMax());
    }
    private static double depth(RollingDepthComposition.Pass p,float distance){
        var q=p.projection().transform(new Vector4f(0,0,-distance,1));return p.depthMin()+(p.depthMax()-p.depthMin())*(q.z/q.w*.5+.5);
    }
    private static boolean clipsIn(RollingDepthComposition.Pass p,float distance){var q=p.projection().transform(new Vector4f(0,0,-distance,1));return -q.w<=q.z&&q.z<=q.w;}
    @Test void splitBoundaryHasNoMissingRepresentableAxialPointAndOrderedDepthDomains(){
        var p=RollingDepthComposition.passes(camera(),DhLodLayout.LONG_4096);var far=p.getFirst();var near=p.getLast();
        assertEquals(.5,depth(far,16),1e-7);assertEquals(.5,depth(near,16),1e-7);
        float low=16,high=16;for(int i=0;i<2048;i++){low=Math.nextDown(low);high=Math.nextUp(high);
            assertTrue(clipsIn(far,low)||clipsIn(near,low));assertTrue(clipsIn(far,high)||clipsIn(near,high));}
        double previous=-1;
        for(float z:new float[]{.051f,.1f,1,4,15.99f}){double value=depth(near,z);assertTrue(value>=0&&value<.5);assertTrue(value>previous);previous=value;}
        previous=.5;for(float z:new float[]{16.01f,17,256,2048,4096,8191}){double value=depth(far,z);assertTrue(value>.5&&value<=1);assertTrue(value>previous);previous=value;}
        assertFalse(clipsIn(near,.049f));assertFalse(clipsIn(far,15.99f));assertFalse(clipsIn(near,16.01f));assertFalse(clipsIn(far,8200));
    }
    @Test void twentyFourBitModelShowsSubmetreFarResolutionWithoutClaimingGpuReadback(){
        double oneDepthUnit=1.0/((1<<24)-1),at4000=4000d*4000*(8192-16)/(8192*16d)*oneDepthUnit/.5;
        double wideAt4000=4000d*4000*(8192-.05)/(8192*.05)*oneDepthUnit;
        assertTrue(at4000<.12);assertTrue(wideAt4000>19);assertTrue(wideAt4000/at4000>159);
        var far=RollingDepthComposition.passes(camera(),DhLodLayout.LONG_4096).getFirst();
        assertTrue(depth(far,4001)-depth(far,4000)>oneDepthUnit*4);
    }
    @Test void pinnedMinecraftAffineBobbingHurtAndNauseaPreserveEffectiveCameraDepthAndScreenCoordinates() {
        var bob=new Matrix4f().translate(.08f,-.12f,0).rotateZ(.03f).rotateX(.06f);
        var hurt=new Matrix4f().rotateY(.7f).rotateZ(-.2f).rotateY(-.7f);
        float axis=(float)Math.sqrt(.5);
        var nausea=new Matrix4f().rotate(.38f,0,axis,axis).scale(.83f,1,1).rotate(-.38f,0,axis,axis);
        for(var effect:List.of(bob,hurt,nausea,new Matrix4f(hurt).mul(bob).mul(nausea),new Matrix4f(bob).translate(0,0,.2f))) {
            var original=new Matrix4f(camera()).mul(effect);var before=new Matrix4f(original);
            var inverse=new Matrix4f(effect).invert();
            for(var layout:DhLodLayout.values()) for(var pass:RollingDepthComposition.passes(original,layout)) {
                float[] old=original.get(new float[16]),now=pass.projection().get(new float[16]);
                for(int i=0;i<16;i++)if(i%4!=2)assertEquals(old[i],now[i],"Preserve x/y/w coefficient "+i);
                for(float distance:new float[]{.051f,1,15.999f,16,16.001f,256,4096}) {
                    var world=inverse.transform(new Vector4f(distance*.1f,-distance*.08f,-distance,1));
                    var oldClip=original.transform(new Vector4f(world));var clip=pass.projection().transform(new Vector4f(world));
                    assertEquals(oldClip.x/oldClip.w,clip.x/clip.w,1e-6);assertEquals(oldClip.y/oldClip.w,clip.y/clip.w,1e-6);
                    assertEquals(distance,clip.w,Math.max(.000002,distance*.000001));
                    double a=(pass.farPlane()+pass.nearPlane())/(pass.farPlane()-pass.nearPlane());
                    double b=-2*pass.farPlane()*pass.nearPlane()/(pass.farPlane()-pass.nearPlane());
                    assertEquals(a+b/distance,clip.z/clip.w,Math.max(.00004,Math.abs(a+b/distance)*.00001));
                    if(distance>=pass.nearPlane()+.001&&distance<=pass.farPlane()-.001)assertTrue(-clip.w<=clip.z&&clip.z<=clip.w);
                }
            }
            assertEquals(before,original);
        }
    }

}
