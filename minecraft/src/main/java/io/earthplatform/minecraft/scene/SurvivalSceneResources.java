package io.earthplatform.minecraft.scene;

import com.mojang.blaze3d.systems.RenderSystem;
import io.earthplatform.compat.dh.InstalledIrisRuntime;
import io.earthplatform.minecraft.visual.NativeDistanceCpuBudget;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.texture.TextureAtlas;

/** Privately captures resources BEFORE native compilation. No existing Capture or caller epoch can
 * be attached. Closing/retiring forbids drawing immediately; the parent still drains all GPU owners. */
public final class SurvivalSceneResources implements AutoCloseable {
    /** Marker on the actual Minecraft class with the synchronous real createReload redirect. */
    public interface ReloadAccess {}
    private static final AtomicLong EPOCH=new AtomicLong(1);
    private static volatile boolean exhausted;
    private final Thread owner=Thread.currentThread();
    private final NativeDistanceCpuBudget budget;
    /** The Capture retains only this small independently-accounted revocation token. */
    private static final class ResourceEpoch {
        private final long epoch;
        private volatile boolean retired;
        private ResourceEpoch(long epoch){this.epoch=epoch;}
        private boolean current(){if(exhausted||epoch!=EPOCH.get())retired=true;return !retired;}
        private void retire(){retired=true;}
    }
    private final ResourceEpoch epoch;
    private SceneNativeShaderContract.Lifecycle lifecycle;
    private SceneNativeShaderContract.Capture capture;
    private InstalledIrisRuntime.Lease iris;
    private ClientLevel level;
    private LevelRenderer renderer;
    private Object connection,models,atlas;
    private NativeDistanceCpuBudget.Reservation charge;
    private int references=1;
    private boolean closed;
    private SurvivalSceneResources(InstalledIrisRuntime.Lease iris,NativeDistanceCpuBudget budget,
            NativeDistanceCpuBudget.Reservation charge) {
        this.budget=budget;this.charge=charge;this.iris=iris;epoch=new ResourceEpoch(EPOCH.get());
        var game=Minecraft.getInstance();
        if(!((Object)game instanceof ReloadAccess)||!((Object)game.levelRenderer instanceof SurvivalRenderHookAccess))
            throw new IllegalStateException("Actual reload/level/frame lifecycle transforms are required");
        level=Objects.requireNonNull(game.level);renderer=game.levelRenderer;connection=Objects.requireNonNull(game.getConnection());
        models=Objects.requireNonNull(game.getModelManager());atlas=Objects.requireNonNull(game.getTextureManager().getTexture(TextureAtlas.LOCATION_BLOCKS));
        lifecycle=new SceneNativeShaderContract.Lifecycle();capture=SceneNativeShaderContract.captureIris(lifecycle,epoch::current);
        requireCurrent();
    }
    /** Call on the actual render owner, then give nativeCapture() to the real producer. */
    public static SurvivalSceneResources capture(InstalledIrisRuntime.Lease lease,NativeDistanceCpuBudget budget) {
        RenderSystem.assertOnRenderThread();Objects.requireNonNull(lease);Objects.requireNonNull(budget);
        if(!lease.distanceMode()||!lease.current())throw new IllegalStateException("Current selected-distance Iris lease required");
        var charge=budget.reserve(NativeDistanceCpuBudget.Pool.OWNER_METADATA,16L<<10);
        try{return new SurvivalSceneResources(lease,budget,charge);}
        catch(RuntimeException|Error error){charge.releaseAfterPhysicalDrain();throw error;}
    }
    /** Revocation only; real reload entry and renderer setLevel HEAD call this before mutation. */
    public static void invalidateBeforeMutation() {
        if(exhausted)return;
        for(;;){long before=EPOCH.get();if(before==Long.MAX_VALUE){exhausted=true;return;}if(EPOCH.compareAndSet(before,before+1))return;}
    }
    public SceneNativeShaderContract.Capture nativeCapture(){requireCurrent();return capture;}
    public InstalledIrisRuntime.Lease iris(){requireCurrent();return iris;}
    public void requireBudget(NativeDistanceCpuBudget exact){checkOwner();if(budget!=exact)throw new IllegalArgumentException("Resource bundle belongs to another exact CPU budget");}
    public void requireCurrent() {
        checkOwner();if(closed||!epoch.current()){epoch.retire();throw new IllegalStateException("Survival render resources were irreversibly retired");}
        var game=Minecraft.getInstance();
        try {
            if(game.level!=level||game.levelRenderer!=renderer||game.getConnection()!=connection
                    ||game.getModelManager()!=models||game.getTextureManager().getTexture(TextureAtlas.LOCATION_BLOCKS)!=atlas
                    ||!iris.current()||!capture.isCurrent()||!capture.values().shaders()
                    ||capture.values().pipeline()!=iris.pipelineIdentity()||capture.values().pack()!=iris.packIdentity())
                throw new IllegalStateException("Exact survival level/connection/models/atlas/Iris capture changed");
            if(!epoch.current())throw new IllegalStateException("Resources changed during validation");
        }catch(RuntimeException|Error error){epoch.retire();throw error;}
    }
    SurvivalSceneResources retain(){requireCurrent();references=Math.incrementExact(references);return this;}
    void release(){checkOwner();if(references<1)throw new IllegalStateException("Resource reference underflow");
        if(--references==0){capture=null;lifecycle=null;iris=null;level=null;renderer=null;connection=null;models=null;atlas=null;
            charge.releaseAfterPhysicalDrain();charge=null;}}
    /** Revocation without releasing producer/candidate ownership or physical cleanup charges. */
    public void retire(){checkOwner();epoch.retire();}
    /** Irreversible revocation. Metadata stays charged while a joined candidate retains the bundle. */
    @Override public void close(){checkOwner();if(closed)return;closed=true;epoch.retire();lifecycle.invalidate();release();}
    private void checkOwner(){if(Thread.currentThread()!=owner)throw new IllegalStateException("Survival resources require their render owner");RenderSystem.assertOnRenderThread();}
}
