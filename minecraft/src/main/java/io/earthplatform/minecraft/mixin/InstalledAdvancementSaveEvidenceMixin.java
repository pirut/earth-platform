package io.earthplatform.minecraft.mixin;
import io.earthplatform.minecraft.survival.persistence.NativeInstalledOrdinarySaveHooks;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.*;
import net.minecraft.server.PlayerAdvancements;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(PlayerAdvancements.class)
abstract class InstalledAdvancementSaveEvidenceMixin {
 @Inject(method="save()V",at=@At("HEAD"),require=1,allow=1)
 private void earthplatform$enter(CallbackInfo ci){NativeInstalledOrdinarySaveHooks.advancementsEnter((PlayerAdvancements)(Object)this);}
 @Redirect(method="save()V",at=@At(value="INVOKE",target="Lnet/minecraft/FileUtil;createDirectoriesSafe(Ljava/nio/file/Path;)V"),require=1,allow=1)
 private void earthplatform$directory(Path path)throws IOException{NativeInstalledOrdinarySaveHooks.advancementDirectory((PlayerAdvancements)(Object)this,path);}
 @Redirect(method="save()V",at=@At(value="INVOKE",target="Ljava/nio/file/Files;newBufferedWriter(Ljava/nio/file/Path;Ljava/nio/charset/Charset;[Ljava/nio/file/OpenOption;)Ljava/io/BufferedWriter;"),require=1,allow=1)
 private BufferedWriter earthplatform$writer(Path path,Charset charset,OpenOption[] options)throws IOException{return NativeInstalledOrdinarySaveHooks.advancementsWriter((PlayerAdvancements)(Object)this,path,charset,options);}
 @Inject(method="save()V",at=@At(value="INVOKE",target="Lorg/slf4j/Logger;error(Ljava/lang/String;Ljava/lang/Object;Ljava/lang/Object;)V"),require=1,allow=1)
 private void earthplatform$caught(CallbackInfo ci){NativeInstalledOrdinarySaveHooks.caught(this);}
 @Inject(method="save()V",at=@At("RETURN"),require=1,allow=1)
 private void earthplatform$exit(CallbackInfo ci){NativeInstalledOrdinarySaveHooks.advancementsExit((PlayerAdvancements)(Object)this);}
}
