package io.earthplatform.minecraft.startupfixture.mixin;

import io.earthplatform.minecraft.startupfixture.Hooks;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Test-only structural analogue of the pinned upstream overwrite and two injection stages. */
public final class TerrainPriorityMixins {
    @Mixin(targets="io.earthplatform.minecraft.startupfixture.TerrainTargets$Priority1001", priority=1000, remap=false)
    public abstract static class SodiumOverwrite1001 {
        /** @author Earth Platform fixture @reason Reproduce the installed Sodium managed-code overwrite. */
        @Overwrite public void renderSectionLayer() {
            Hooks.enterManagedCode();
            try { Hooks.drawChunkLayer(); }
            finally { Hooks.exitManagedCode(); }
            Hooks.getInstance();
        }
    }
    @Mixin(targets="io.earthplatform.minecraft.startupfixture.TerrainTargets$Priority900", priority=1000, remap=false)
    public abstract static class SodiumOverwrite900 {
        /** @author Earth Platform fixture @reason Reproduce the installed Sodium managed-code overwrite. */
        @Overwrite public void renderSectionLayer() {
            Hooks.enterManagedCode();
            try { Hooks.drawChunkLayer(); }
            finally { Hooks.exitManagedCode(); }
            Hooks.getInstance();
        }
    }
    @Mixin(targets="io.earthplatform.minecraft.startupfixture.TerrainTargets$Priority1000", priority=1000, remap=false)
    public abstract static class SodiumOverwrite1000 {
        /** @author Earth Platform fixture @reason Reproduce the installed Sodium managed-code overwrite. */
        @Overwrite public void renderSectionLayer() {
            Hooks.enterManagedCode();
            try { Hooks.drawChunkLayer(); }
            finally { Hooks.exitManagedCode(); }
            Hooks.getInstance();
        }
    }
    @Mixin(targets={"io.earthplatform.minecraft.startupfixture.TerrainTargets$Priority1001",
            "io.earthplatform.minecraft.startupfixture.TerrainTargets$Priority900",
            "io.earthplatform.minecraft.startupfixture.TerrainTargets$Priority1000"}, priority=1000, remap=false)
    public abstract static class IrisPhase {
        @Inject(method="renderSectionLayer()V", at=@At("HEAD"), require=1)
        private void fixture$irisHead(CallbackInfo callback) { Hooks.irisHead(); }
        @Inject(method="renderSectionLayer()V", at=@At("RETURN"), require=1)
        private void fixture$irisTail(CallbackInfo callback) { Hooks.irisTail(); }
    }
    @Mixin(targets="io.earthplatform.minecraft.startupfixture.TerrainTargets$Priority1001", priority=1001, remap=false)
    public abstract static class Native1001 {
        @Inject(method="renderSectionLayer()V", at=@At(value="INVOKE",
                target="Lio/earthplatform/minecraft/startupfixture/Hooks;getInstance()V", remap=false), require=1)
        private void fixture$nativeTerrain(CallbackInfo callback) { Hooks.dispatch(); }
    }
    @Mixin(targets="io.earthplatform.minecraft.startupfixture.TerrainTargets$Priority900", priority=900, remap=false)
    public abstract static class Native900 {
        @Inject(method="renderSectionLayer()V", at=@At(value="INVOKE",
                target="Lio/earthplatform/minecraft/startupfixture/Hooks;getInstance()V", remap=false), require=1)
        private void fixture$nativeTerrain(CallbackInfo callback) { Hooks.dispatch(); }
    }
    @Mixin(targets="io.earthplatform.minecraft.startupfixture.TerrainTargets$Priority1000", priority=1000, remap=false)
    public abstract static class Native1000 {
        @Inject(method="renderSectionLayer()V", at=@At(value="INVOKE",
                target="Lio/earthplatform/minecraft/startupfixture/Hooks;getInstance()V", remap=false), require=1)
        private void fixture$nativeTerrain(CallbackInfo callback) { Hooks.dispatch(); }
    }
    private TerrainPriorityMixins() {}
}
