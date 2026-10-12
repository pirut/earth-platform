package io.earthplatform.minecraft.mixin;
import io.earthplatform.minecraft.survival.persistence.NativeInstalledOrdinarySaveHooks;
import java.io.IOException;
import java.nio.file.Path;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.storage.LevelStorageSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(LevelStorageSource.LevelStorageAccess.class)
abstract class InstalledLevelSaveEvidenceMixin {
 @Inject(method="saveLevelData(Lnet/minecraft/nbt/CompoundTag;)V",at=@At("HEAD"),require=1,allow=1)
 private void earthplatform$enter(CompoundTag tag,CallbackInfo ci){NativeInstalledOrdinarySaveHooks.levelEnter((LevelStorageSource.LevelStorageAccess)(Object)this,tag);}
 @Redirect(method="saveLevelData(Lnet/minecraft/nbt/CompoundTag;)V",at=@At(value="INVOKE",target="Lnet/minecraft/nbt/NbtIo;writeCompressed(Lnet/minecraft/nbt/CompoundTag;Ljava/nio/file/Path;)V"),require=1,allow=1)
 private void earthplatform$write(CompoundTag tag,Path temporary)throws IOException{NativeInstalledOrdinarySaveHooks.levelWrite((LevelStorageSource.LevelStorageAccess)(Object)this,tag,temporary);}
 @Redirect(method="saveLevelData(Lnet/minecraft/nbt/CompoundTag;)V",at=@At(value="INVOKE",target="Lnet/minecraft/Util;safeReplaceFile(Ljava/nio/file/Path;Ljava/nio/file/Path;Ljava/nio/file/Path;)V"),require=1,allow=1)
 private void earthplatform$replace(Path current,Path temporary,Path backup){NativeInstalledOrdinarySaveHooks.levelReplace((LevelStorageSource.LevelStorageAccess)(Object)this,current,temporary,backup);}
 @Inject(method="saveLevelData(Lnet/minecraft/nbt/CompoundTag;)V",at=@At(value="INVOKE",target="Lorg/slf4j/Logger;error(Ljava/lang/String;Ljava/lang/Object;Ljava/lang/Object;)V"),require=1,allow=1)
 private void earthplatform$caught(CompoundTag tag,CallbackInfo ci){NativeInstalledOrdinarySaveHooks.caught(this);}
 @Inject(method="saveLevelData(Lnet/minecraft/nbt/CompoundTag;)V",at=@At("RETURN"),require=1,allow=1)
 private void earthplatform$exit(CompoundTag tag,CallbackInfo ci){NativeInstalledOrdinarySaveHooks.levelExit((LevelStorageSource.LevelStorageAccess)(Object)this);}
}
