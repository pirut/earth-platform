package io.earthplatform.compat.dh.shader;

import com.seibel.distanthorizons.api.methods.events.sharedParameterObjects.DhApiRenderParam;

/** One render-thread registration. No unbounded maps, stock cache mutation, or external resources. */
public final class ShaderCarrierHooks {
    private static volatile InstalledShaderCarrier current;
    private static volatile InstalledSurvivalShaderCarrier survival;
    private ShaderCarrierHooks(){}
    static synchronized void claim(InstalledShaderCarrier carrier){if(current!=null||survival!=null)throw new IllegalStateException("Prior shader carrier remains owned");current=carrier;}
    static synchronized void release(InstalledShaderCarrier carrier){if(current!=carrier)throw new IllegalStateException("Shader carrier ownership changed");current=null;}
    static synchronized void claim(InstalledSurvivalShaderCarrier carrier){if(current!=null||survival!=null)throw new IllegalStateException("Prior shader carrier remains owned");survival=carrier;}
    static synchronized void release(InstalledSurvivalShaderCarrier carrier){if(survival!=carrier)throw new IllegalStateException("Survival shader ownership changed");survival=null;}
    public static boolean survivalRetains(io.earthplatform.minecraft.scene.SurvivalSceneFrameJoin exact){var owner=survival;return owner!=null&&owner.retainsJoin(exact);}
    /** No caller-provided carrier or restoration predicate can authorize a content rebind. */
    public static void requireSurvivalRebindQuiescent(io.earthplatform.minecraft.scene.SurvivalSceneFrameJoin exact){
        var owner=survival;
        if(owner==null||!owner.retainsJoin(exact))throw new IllegalStateException("No exact registered survival carrier");
        owner.requireRebindQuiescent();
    }
    public static boolean survivalOwns(Object exactLevel){var owner=survival;return owner!=null&&owner.owns(exactLevel);}
    public static void requireSurvivalHookReservation(){var owner=survival;if(owner==null)throw new IllegalStateException("No retained survival hook owner");owner.requireHookReservation();}
    public static boolean owns(Object exactLevel){var owner=current;return owner!=null?owner.owns(exactLevel):survivalOwns(exactLevel);}
    public static void terrain(Object exactLevel,DhApiRenderParam parameters,boolean opaque,boolean shadow,double x,double y,double z){var owner=current;if(owner!=null)owner.terrain(exactLevel,parameters,opaque,shadow,x,y,z);else {var typed=survival;if(typed!=null&&typed.owns(exactLevel))typed.terrain();}}
    public static void nativeLayer(Object exactLevel,DhShaderPassGate.Pass pass,Object transientContext){var owner=current;if(owner!=null)owner.nativeLayer(exactLevel,pass,transientContext);else {var typed=survival;if(typed!=null&&typed.owns(exactLevel))typed.nativeLayer();}}
    /** A first pipeline/dimension/material setup may occur after carrier metadata but
     * before any lease or native material capture. Only that unadmitted bootstrap is exempt. */
    public static void invalidateAdmitted(String reason){var owner=current;if(owner!=null&&owner.pipelineAcquired())owner.invalidate(reason);var typed=survival;if(typed!=null&&typed.pipelineAcquired())typed.invalidate(reason);}
    public static void invalidate(String reason){var owner=current;if(owner!=null)owner.invalidate(reason);var typed=survival;if(typed!=null)typed.invalidate(reason);}
    public static void failed(Throwable problem){var owner=current;if(owner!=null)owner.contain(problem);var typed=survival;if(typed!=null)typed.contain(problem);}
    public static boolean hasRetainedCarrier(){return current!=null||survival!=null;}
    /** Admission for one real resource reload, before that reload revokes this owner. */
    public static Object admittedReloadOwner(Object exactLevel){
        var owner=current;
        if(owner!=null)return owner.owns(exactLevel)&&owner.pipelineCurrent()?owner:null;
        var typed=survival;return typed!=null&&typed.owns(exactLevel)&&typed.pipelineCurrent()?typed:null;
    }
    public static void requireReloadOwner(Object exactLevel,Object expected){
        if(expected==null||admittedReloadOwner(exactLevel)!=expected)
            throw new IllegalStateException("Shader reload owner changed before scope admission");
    }
}
