package io.earthplatform.minecraft.mixin;

import io.earthplatform.minecraft.survival.persistence.NativeOrdinarySaveHooks;
import java.io.IOException;
import java.nio.file.Path;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.storage.PlayerDataStorage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Exact 1.21.1 / NeoForge 21.1.256 call sites. Every hook is a no-op/vanilla delegation outside scope. */
@Mixin(PlayerDataStorage.class)
public abstract class SurvivalOrdinaryPlayerSaveMixin {
    @Inject(method="save(Lnet/minecraft/world/entity/player/Player;)V",at=@At("HEAD"),require=1,allow=1)
    private void earthplatform$saveEnter(Player player,CallbackInfo callback) {
        NativeOrdinarySaveHooks.enter((PlayerDataStorage)(Object)this,player);
    }
    @Redirect(method="save(Lnet/minecraft/world/entity/player/Player;)V",at=@At(value="INVOKE",target=
            "Lnet/minecraft/world/entity/player/Player;saveWithoutId(Lnet/minecraft/nbt/CompoundTag;)Lnet/minecraft/nbt/CompoundTag;"),require=1,allow=1)
    private CompoundTag earthplatform$savePayload(Player receiver,CompoundTag input,Player invocation) {
        return NativeOrdinarySaveHooks.serialize((PlayerDataStorage)(Object)this,receiver,input,invocation);
    }
    @Redirect(method="save(Lnet/minecraft/world/entity/player/Player;)V",at=@At(value="INVOKE",target=
            "Lnet/minecraft/nbt/NbtIo;writeCompressed(Lnet/minecraft/nbt/CompoundTag;Ljava/nio/file/Path;)V"),require=1,allow=1)
    private void earthplatform$saveWrite(CompoundTag tag,Path temporary,Player player) throws IOException {
        NativeOrdinarySaveHooks.write((PlayerDataStorage)(Object)this,player,tag,temporary);
    }
    @Redirect(method="save(Lnet/minecraft/world/entity/player/Player;)V",at=@At(value="INVOKE",target=
            "Lnet/minecraft/Util;safeReplaceFile(Ljava/nio/file/Path;Ljava/nio/file/Path;Ljava/nio/file/Path;)V"),require=1,allow=1)
    private void earthplatform$saveReplace(Path current,Path temporary,Path backup,Player player) {
        NativeOrdinarySaveHooks.replace((PlayerDataStorage)(Object)this,player,current,temporary,backup);
    }
    @Inject(method="save(Lnet/minecraft/world/entity/player/Player;)V",at=@At(value="INVOKE",target=
            "Lorg/slf4j/Logger;warn(Ljava/lang/String;Ljava/lang/Object;)V"),require=1,allow=1)
    private void earthplatform$saveCaught(Player player,CallbackInfo callback) {
        NativeOrdinarySaveHooks.caught((PlayerDataStorage)(Object)this,player);
    }
    @Inject(method="save(Lnet/minecraft/world/entity/player/Player;)V",at=@At("RETURN"),require=1,allow=1)
    private void earthplatform$saveExit(Player player,CallbackInfo callback) {
        NativeOrdinarySaveHooks.exit((PlayerDataStorage)(Object)this,player);
    }
}
