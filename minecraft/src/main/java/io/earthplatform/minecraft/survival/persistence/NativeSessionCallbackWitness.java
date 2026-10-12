package io.earthplatform.minecraft.survival.persistence;

import java.util.EnumMap;
import java.util.function.Consumer;
import io.earthplatform.minecraft.survival.world.SurvivalRefusal;

/** Three one-shot identities captured at exact original registration call sites. No callback runs here.
 * The injected private receiver method AND its immediate constructor frame are authenticated.
 * A lookalike hidden lambda/nest host is not a witness and cannot mint this identity.
 */
public final class NativeSessionCallbackWitness {
 public enum Site {
  CAPABILITY_POST("net.neoforged.neoforge.common.NeoForgeMod","earthplatform$observeCapabilityPost"),
  EARTH_DIMENSION("io.earthplatform.minecraft.EarthPlatformMod","earthplatform$observeEarthDimension"),
  EARTH_LOGIN("io.earthplatform.minecraft.EarthPlatformMod","earthplatform$observeEarthLogin");
  final String host,method;Site(String host,String method){this.host=host;this.method=method;}
 }
 private static final EnumMap<Site,Consumer<?>> SEEN=new EnumMap<>(Site.class);
 private NativeSessionCallbackWitness(){}
 public static synchronized void observe(Site site,Consumer<?> consumer){
  boolean fixture=Boolean.getBoolean("earthplatform.nativeSessionBridge")&&Boolean.getBoolean("neoforge.gameTestServer");
  boolean installed=Boolean.getBoolean("earthplatform.installedMarinSession")&&!Boolean.getBoolean("neoforge.gameTestServer");
  if(!(fixture||installed) || consumer==null)
   throw new SurvivalRefusal("Session callback witness is default-off; observing registration does not grant installed admission");
  Class<?> expectedHost=NativeSessionListenerCensus.type(site.host);
  var frames=StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE).walk(s->s.skip(1).limit(2).toList());
  if(frames.size()!=2 || frames.get(0).getDeclaringClass()!=expectedHost
    || !frames.get(0).getMethodName().endsWith("$"+site.method)
    || frames.get(1).getDeclaringClass()!=expectedHost || !frames.get(1).getMethodName().equals("<init>"))
   throw new SurvivalRefusal("Session callback witness lacks exact injected registration origin");
  NativeSessionCallbackPins.require(site.host);
  if(SEEN.containsKey(site))throw new SurvivalRefusal("Session callback registration witness already exists: "+site);
  SEEN.put(site,consumer);
 }
 static synchronized Consumer<?> require(Site site){
  Consumer<?> value=SEEN.get(site);if(value==null)throw new SurvivalRefusal("Required native registration witness absent: "+site);return value;
 }
}
