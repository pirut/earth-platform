package io.earthplatform.minecraft.startupfixture;

import java.util.ArrayList;
import java.util.List;

/** Synthetic DH/native observations; these never produce a production survival capability. */
public final class SurvivalDrawOrderHooks {
    public interface Scope { Object fixture$frame(); Object fixture$preparation(); }
    public static final List<String> EVENTS=new ArrayList<>();
    public static boolean FAIL_DRAW;
    public static Object RECEIVER,LAYER,VIEW,PROJECTION,TOKEN,FRAME;
    public static double X,Y,Z;
    public static void event(String name){EVENTS.add(name);}
    public static void arguments(Object receiver,Object layer,double x,double y,double z,Object view,Object projection){
        if(receiver!=RECEIVER||layer!=LAYER||view!=VIEW||projection!=PROJECTION
                ||Double.doubleToRawLongBits(x)!=Double.doubleToRawLongBits(X)
                ||Double.doubleToRawLongBits(y)!=Double.doubleToRawLongBits(Y)
                ||Double.doubleToRawLongBits(z)!=Double.doubleToRawLongBits(Z))throw new AssertionError("Fixture arguments changed");
    }
    public static void frameBegun(Object receiver){FRAME=((Scope)receiver).fixture$frame();if(FRAME==null)throw new AssertionError("Missing fixture frame");event("frame-begin");}
    public static void frameRetired(Object receiver){if(((Scope)receiver).fixture$frame()!=null||TOKEN!=null)throw new AssertionError("Frame leaked");FRAME=null;event("frame-retire");}
    public static void prepare(Object receiver,Object layer,double x,double y,double z,Object view,Object projection){
        arguments(receiver,layer,x,y,z,view,projection);
        TOKEN=((Scope)receiver).fixture$preparation();if(FRAME==null||((Scope)receiver).fixture$frame()!=FRAME||TOKEN==null)throw new AssertionError("Missing private preparation");event("prepare");
    }
    public static void observe(Object receiver,String name,Object layer,double x,double y,double z,Object view,Object projection){
        arguments(receiver,layer,x,y,z,view,projection);
        if(FRAME==null||((Scope)receiver).fixture$frame()!=FRAME||TOKEN==null||((Scope)receiver).fixture$preparation()!=TOKEN)throw new IllegalStateException("No same-invocation fixture preparation");event(name);
    }
    public static void draw(){event("sodium-draw");if(FAIL_DRAW)throw new IllegalStateException("Synthetic draw failure");}
    public static void anchor(){event("platform-hook");}
    public static void retired(Object receiver){if(((Scope)receiver).fixture$preparation()!=null)throw new AssertionError("Preparation leaked");TOKEN=null;event("retire");}
    public static void clear(){EVENTS.clear();FAIL_DRAW=false;RECEIVER=null;LAYER=null;VIEW=null;PROJECTION=null;TOKEN=null;FRAME=null;X=Y=Z=0;}
    private SurvivalDrawOrderHooks(){}
}
