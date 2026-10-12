package io.earthplatform.compat.dh.shader;

import com.mojang.blaze3d.systems.RenderSystem;
import com.seibel.distanthorizons.api.enums.rendering.EDhApiRenderPass;
import com.seibel.distanthorizons.common.render.openGl.GlDhTerrainRenderer_neoforge;
import io.earthplatform.compat.dh.InstalledIrisRuntime;
import io.earthplatform.compat.dh.shader.mixin.DhSurvivalTerrainRendererAccess;
import io.earthplatform.minecraft.lod.SurvivalDhGpuPages;
import io.earthplatform.minecraft.scene.SurvivalSceneFrameJoin;
import net.irisshaders.iris.shadows.ShadowRenderingState;
import net.minecraft.client.Minecraft;

/** Private-constructor actual installed DH invocation. Scalars are observations; only the exact
 * still-active singleton-held program token and joined frame grant a synchronous draw operation. */
public final class SurvivalDhPassWitness {
    private final Thread owner=Thread.currentThread();
    private final SurvivalSceneFrameJoin.AcceptedFrame frame;
    private final Object program,invocation,level;
    private final boolean opaque;
    private final int pass;
    private final float near,far;
    private final double x,y,z;
    private final float[] view=new float[16],projection=new float[16];
    private SurvivalDhGpuPages.Completion completion;
    private IrisShaderFarBackend backend;
    private io.earthplatform.minecraft.lod.DhGeographicLodData.TileKey activeTile;
    private boolean operation,drawConsumed,retired;
    private SurvivalDhPassWitness(SurvivalSceneFrameJoin.AcceptedFrame frame){
        this.frame=frame;frame.requireDrawResources();var access=actualAccess();program=access;
        invocation=access.earthplatform$survivalDhInvocation();level=access.earthplatform$survivalDhLevel();
        opaque=access.earthplatform$survivalDhOpaque();pass=access.earthplatform$survivalDhPass();
        near=access.earthplatform$survivalDhClip(false);far=access.earthplatform$survivalDhClip(true);
        x=access.earthplatform$survivalDhCamera(0);y=access.earthplatform$survivalDhCamera(1);z=access.earthplatform$survivalDhCamera(2);
        for(int c=0;c<4;c++)for(int r=0;r<4;r++){view[c*4+r]=access.earthplatform$survivalDhMatrix(false,c,r);projection[c*4+r]=access.earthplatform$survivalDhMatrix(true,c,r);}
        requireCurrent();completion=frame.claimDhPass(this);requireCurrent();
    }
    static SurvivalDhPassWitness capture(SurvivalSceneFrameJoin.AcceptedFrame frame){return new SurvivalDhPassWitness(frame);}
    private static SurvivalDhHookAccess actualAccess(){
        RenderSystem.assertOnRenderThread();
        Object held=((DhSurvivalTerrainRendererAccess)(Object)GlDhTerrainRenderer_neoforge.INSTANCE).earthplatform$heldTerrainProgram();
        if(!(held instanceof SurvivalDhHookAccess access))throw new IllegalStateException("Actual singleton-held DH program scope is missing");
        return access;
    }
    public void requireCurrent(){
        if(Thread.currentThread()!=owner||retired)throw new IllegalStateException("DH witness retired or has a different render owner");
        frame.requireDrawResources();var access=actualAccess();
        if(access!=program||access.earthplatform$survivalDhInvocation()!=invocation||access.earthplatform$survivalDhLevel()!=level
                ||Minecraft.getInstance().level!=level||ShadowRenderingState.areShadowsCurrentlyBeingRendered()
                ||access.earthplatform$survivalDhOpaque()!=opaque||access.earthplatform$survivalDhPass()!=pass
                ||pass!=(opaque?EDhApiRenderPass.OPAQUE:EDhApiRenderPass.TRANSPARENT).ordinal())
            throw new IllegalStateException("Actual DH main-pass identity changed or combined pass is unsupported");
        if(Float.floatToRawIntBits(near)!=Float.floatToRawIntBits(access.earthplatform$survivalDhClip(false))
                ||Float.floatToRawIntBits(far)!=Float.floatToRawIntBits(access.earthplatform$survivalDhClip(true))
                ||Double.compare(x,access.earthplatform$survivalDhCamera(0))!=0||Double.compare(y,access.earthplatform$survivalDhCamera(1))!=0||Double.compare(z,access.earthplatform$survivalDhCamera(2))!=0)
            throw new IllegalStateException("Actual DH camera/clip values changed");
        for(int c=0;c<4;c++)for(int r=0;r<4;r++)if(Float.floatToRawIntBits(view[c*4+r])!=Float.floatToRawIntBits(access.earthplatform$survivalDhMatrix(false,c,r))
                ||Float.floatToRawIntBits(projection[c*4+r])!=Float.floatToRawIntBits(access.earthplatform$survivalDhMatrix(true,c,r)))
            throw new IllegalStateException("Actual DH Minecraft matrices changed");
        if(access.earthplatform$survivalDhInvocation()!=invocation)throw new IllegalStateException("DH scope ended during scalar checks");
        frame.requireDrawResources();
    }
    public boolean opaque(){return opaque;}
    public double camera(int axis){return switch(axis){case 0->x;case 1->y;case 2->z;default->throw new IndexOutOfBoundsException();};}
    public float matrix(boolean projection,int column,int row){if(column<0||column>3||row<0||row>3)throw new IndexOutOfBoundsException();return (projection?this.projection:view)[column*4+row];}
    float near(){return near;}float far(){return far;}
    InstalledIrisRuntime.Lease iris(){requireCurrent();return frame.drawIrisLease();}
    int selected(){return frame.drawSelectedDistance();}int effective(){return frame.drawEffectiveDistance();}double radius(){return frame.drawGuaranteedRadius();}
    void beginBackend(IrisShaderFarBackend exact){requireCurrent();if(backend!=null||drawConsumed||!opaque)throw new IllegalStateException("DH backend pass already consumed or not opaque");backend=exact;}
    void drawCompletion(){requireCurrent();completion.drawJoined(this);}
    public void beginPhysical(SurvivalDhGpuPages.Completion exact){
        requireCurrent();frame.requireDhPass(this,exact);if(exact!=completion||backend==null||drawConsumed||operation)throw new IllegalStateException("DH physical pass was already consumed");
        drawConsumed=true;operation=true;
    }
    public void beforeRange(SurvivalDhGpuPages.Completion exact){
        requireCurrent();frame.requireDhPass(this,exact);if(exact!=completion||!operation||backend==null)throw new IllegalStateException("No exact DH physical operation");
        completion.requireDrawConfiguration(frame);backend.requireSurvivalPass(this);if(activeTile!=null)backend.requireSurvivalPage(this,activeTile);
    }
    public void page(SurvivalDhGpuPages.Completion exact,io.earthplatform.minecraft.lod.DhGeographicLodData.TileKey key){
        beforeRange(exact);exact.requireActiveTile(this,key);backend.page(key.originX(),Math.multiplyExact((long)key.pageY(),io.earthplatform.minecraft.lod.DhTerrainGeometry.PAGE_HEIGHT),key.originZ());activeTile=key;beforeRange(exact);
    }
    public void endPhysical(){if(!operation)throw new IllegalStateException("No DH physical operation to retire");operation=false;}
    public void requireCompletionReady(){requireCurrent();if(opaque){if(!drawConsumed||operation||completion==null)throw new IllegalStateException("DH physical draw is incomplete");completion.requireJoinedDrawComplete(this);}}
    void complete(){requireCompletionReady();frame.completedDhPass(this);}
    void retire(){if(operation)throw new IllegalStateException("Cannot retire operating DH witness");if(completion!=null)completion.forgetJoinedDraw(this);backend=null;completion=null;activeTile=null;retired=true;}
}
