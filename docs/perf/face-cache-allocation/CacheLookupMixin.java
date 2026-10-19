package eptestprobe;
import java.util.concurrent.ConcurrentHashMap;
import eptest.CacheControl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
/** Local control: returning null invokes the unchanged original computeIfAbsent path. */
@Mixin(targets="io.earthplatform.minecraft.cubic.client.FarFaces",remap=false)
abstract class CacheLookupMixin {
 @Redirect(method={"face","tint"},at=@At(value="INVOKE",target="Ljava/util/concurrent/ConcurrentHashMap;get(Ljava/lang/Object;)Ljava/lang/Object;"),remap=false)
 private Object eptest$cached(ConcurrentHashMap<?,?> values,Object key){return CacheControl.forceCompute?null:values.get(key);}
}
