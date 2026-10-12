package io.earthplatform.minecraft.mixin;

import io.earthplatform.compat.dh.shader.ShaderCarrierHooks;
import io.earthplatform.minecraft.scene.NativeShaderTextureAliases;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import net.minecraft.client.Minecraft;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.resources.ReloadInstance;
import net.minecraft.server.packs.resources.ReloadableResourceManager;
import net.minecraft.util.Unit;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** A real reload begun in the exact admitted carrier is the only alias-cleanup scope.
 * Pending/overlay early returns never reach this call. Bind the resource reload's own
 * completion: Minecraft's outer future is not completed on every recovery failure. */
@Mixin(Minecraft.class)
public abstract class NativeShaderReloadScopeMixin implements io.earthplatform.minecraft.scene.SurvivalSceneResources.ReloadAccess {
    @Redirect(method="reloadResourcePacks(ZLnet/minecraft/client/Minecraft$GameLoadCookie;)Ljava/util/concurrent/CompletableFuture;",
            at=@At(value="INVOKE",target="Lnet/minecraft/server/packs/resources/ReloadableResourceManager;createReload(Ljava/util/concurrent/Executor;Ljava/util/concurrent/Executor;Ljava/util/concurrent/CompletableFuture;Ljava/util/List;)Lnet/minecraft/server/packs/resources/ReloadInstance;"),
            require=1,allow=1)
    private ReloadInstance earthplatform$scopedTextureAliases(ReloadableResourceManager resources,
            Executor background,Executor game,CompletableFuture<Unit> waiting,List<PackResources> packs){
        // Synchronous real reload entry: cancellation or a throwing createReload cannot revive old bundles.
        io.earthplatform.minecraft.scene.SurvivalSceneResources.invalidateBeforeMutation();
        Object level=((Minecraft)(Object)this).level;
        Object owner=ShaderCarrierHooks.admittedReloadOwner(level);
        if(owner==null)return resources.createReload(background,game,waiting,packs);
        var scope=NativeShaderTextureAliases.begin(level,()->ShaderCarrierHooks.requireReloadOwner(level,owner));
        try{
            ReloadInstance reload=resources.createReload(background,game,waiting,packs);
            scope.retainUntil(reload.done());
            return reload;
        }catch(RuntimeException|Error failure){
            try{scope.close();}catch(RuntimeException|Error cleanup){if(cleanup!=failure)failure.addSuppressed(cleanup);}
            throw failure;
        }
    }
}
