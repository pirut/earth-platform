package io.earthplatform.minecraft.mixin;
import java.util.List;
import java.util.Set;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.*;
public final class SurvivalSessionBridgeMixinPlugin implements IMixinConfigPlugin {
 public void onLoad(String p){}
 public String getRefMapperConfig(){return null;}
 public boolean shouldApplyMixin(String t,String m){
  if(m.endsWith(".NativeOriginalPacketEncoderBoundaryMixin")||m.endsWith(".NativeOriginalPacketDecoderBoundaryMixin"))
   return Boolean.getBoolean("earthplatform.nativeCodecBoundaryProbe")&&Boolean.getBoolean("neoforge.gameTestServer")
    || NativeLocalObservationMixinPlugin.enabled();
  if(m.endsWith(".NativeOriginalConnectionProbeMixin"))
   return Boolean.getBoolean("earthplatform.nativeConnectionOutputProbe")&&Boolean.getBoolean("neoforge.gameTestServer");
  if(m.endsWith(".NativeEmbeddedTransformMarkerMixin"))
   return Boolean.getBoolean("earthplatform.nativeOutputTransformProbe")&&Boolean.getBoolean("neoforge.gameTestServer");
  boolean fixture=Boolean.getBoolean("earthplatform.nativeSessionBridge")&&Boolean.getBoolean("neoforge.gameTestServer");
  boolean installed=Boolean.getBoolean("earthplatform.installedMarinSession")&&!Boolean.getBoolean("neoforge.gameTestServer");
  if((fixture||installed)&&Boolean.getBoolean("earthplatform.nativePlayerTickDiagnostic"))
   throw new IllegalStateException("Native session bridge and standalone tick diagnostic mixins are mutually exclusive");
  return fixture||installed;
 }
 public void acceptTargets(Set<String> a,Set<String>b){}
 public List<String> getMixins(){return null;}
 public void preApply(String n,ClassNode c,String m,IMixinInfo i){}
 public void postApply(String n,ClassNode c,String m,IMixinInfo i){}
}
