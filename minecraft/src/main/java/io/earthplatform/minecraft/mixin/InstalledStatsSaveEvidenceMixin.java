package io.earthplatform.minecraft.mixin;
import io.earthplatform.minecraft.survival.persistence.NativeInstalledOrdinarySaveHooks;
import java.io.File;
import java.io.IOException;
import net.minecraft.stats.ServerStatsCounter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(ServerStatsCounter.class)
abstract class InstalledStatsSaveEvidenceMixin {
 @Inject(method="save()V",at=@At("HEAD"),require=1,allow=1)
 private void earthplatform$enter(CallbackInfo ci){NativeInstalledOrdinarySaveHooks.statsEnter((ServerStatsCounter)(Object)this);}
 @Redirect(method="save()V",at=@At(value="INVOKE",target="Lorg/apache/commons/io/FileUtils;writeStringToFile(Ljava/io/File;Ljava/lang/String;)V"),require=1,allow=1)
 private void earthplatform$write(File file,String text)throws IOException{NativeInstalledOrdinarySaveHooks.statsWrite((ServerStatsCounter)(Object)this,file,text);}
 @Inject(method="save()V",at=@At(value="INVOKE",target="Lorg/slf4j/Logger;error(Ljava/lang/String;Ljava/lang/Throwable;)V"),require=1,allow=1)
 private void earthplatform$caught(CallbackInfo ci){NativeInstalledOrdinarySaveHooks.caught(this);}
 @Inject(method="save()V",at=@At("RETURN"),require=1,allow=1)
 private void earthplatform$exit(CallbackInfo ci){NativeInstalledOrdinarySaveHooks.statsExit((ServerStatsCounter)(Object)this);}
}
