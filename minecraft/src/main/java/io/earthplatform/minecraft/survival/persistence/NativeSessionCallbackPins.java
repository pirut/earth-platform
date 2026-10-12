package io.earthplatform.minecraft.survival.persistence;
import java.util.Map;
import java.util.HexFormat;
import java.security.MessageDigest;
import io.earthplatform.minecraft.survival.world.SurvivalRefusal;
/** Exact parent-built source/official binary inputs; changing them needs a fresh closure review. */
final class NativeSessionCallbackPins {
 private NativeSessionCallbackPins(){}
 static final Map<String,String> PINS=Map.ofEntries(
  Map.entry("io.earthplatform.compat.PlayableProfile","a0b451e75cf45d58eb9fdac9380e10d0429075b96c97479b1a34a5e7659b05c8"),
  Map.entry("io.earthplatform.compat.PlayableProfile$Loaded","9a02c825cfea7b5d639d00cdc79a0102208eca0945a9bd098a727983dee991de"),
  Map.entry("io.earthplatform.compat.PlayableProfile$Selection","69f1c34fdcd42e0fcf865398b395ce771ac380aa4daa2b4d9e99206f606bae08"),
  Map.entry("io.earthplatform.compat.PlayableProfile$State","076fa3034a9563ff76e3780814e9925a584007723e39bc2e240c5a156d3ab3eb"),
  Map.entry("io.earthplatform.compat.dh.EarlyPlayableAdmission","eaea169f2bd22b675f88f297a51da928767855373a75f68293fe9c6ae95d6fba"),
  Map.entry("io.earthplatform.minecraft.coregate.CoreGateFixture","13ff9a994a8e577538f02bc3e79722e558dafc9a8433f6da2fce309147c88b70"),
  Map.entry("net.neoforged.neoforge.common.UsernameCache$SaveThread","bc2a63bcc65b12f5cfb2ba8f3a5244bfad37bd7a51bf68e2887b46fdb9a9d35c"),
  Map.entry("net.neoforged.neoforge.common.UsernameCache","12da7c0d38d86276b0fed63db45f6e5055c755845a82e7d7398ee8815d50cf47"),
  Map.entry("io.earthplatform.minecraft.EarthPlatformMod","c867a3049ea72e690f879d2018e6c5cb359414f56bb3404a9fa0cb2f4e8555ef"),
  Map.entry("io.earthplatform.minecraft.coregate.CoreGateCommands","e2717af4baa14bdcde140aef4329bd81934932b2f644d68530138c3d62f32ce8"),
  Map.entry("io.earthplatform.minecraft.coregate.RollingGateCommands","5ff3b51ffe2631516b0abaa1099fc79d7b127f15fbd2865033f8108ea544a42f"),
  Map.entry("io.earthplatform.minecraft.coregate.CoreRecoverySlot","4bd5f1804e4631175116c1c6a33ce16e2b6c2fd4b5f2f975e9eeb8fa68e8d757"),
  Map.entry("io.earthplatform.minecraft.coregate.RollingGateFixture","e9ec383e4121a09444293ed09bfeb68b10f14722c29f3c83c093a9b3a69e4d85"),
  Map.entry("net.neoforged.neoforge.common.NeoForgeMod","98dc057e7bc44f959c9b8587e4f978230385a31ac20e8aa73a6991b279f874c4"),
  Map.entry("net.neoforged.neoforge.capabilities.CapabilityHooks","9df3a2d0792e09e5cddad9e0dcc478b129605da0b1816b8f85533a9179fbf54b"),
  Map.entry("net.neoforged.neoforge.capabilities.CapabilityListenerHolder","efcc973df1847af770ee47af6e0ffa6f3d934480c72977e605dac404140b07b1"),
  Map.entry("net.neoforged.neoforge.common.WorldWorkerManager","d60db89c1bc8f847c6c19eead09b61f3334d6b495075e7fc3eac9f7c42ec59b8"),
  Map.entry("net.neoforged.neoforge.attachment.AttachmentSync","686a18705d3a13d2c85f3d50c51479a7d50b21b6ade5dca348977f4c773eb221"),
  Map.entry("net.neoforged.neoforge.common.NeoForgeEventHandler","b9b9592eac4bf7a548a85993509d3614d7e1f6f76012bb4252a11f3eb46aa9fa"),
  Map.entry("net.neoforged.neoforge.network.registration.NetworkRegistry","68d5cf8a94f4d15812e3bc2ec8a547a66b717eebb3ae0490b791eab2dedbdbb8"),
  Map.entry("net.neoforged.bus.EventBus","cda8876c904d1374f77bbff02e8cdb01fba48ae0f528c69713eaefa11b5128a4"),
  Map.entry("net.neoforged.bus.ListenerList","3daccbcb1ef602dff0edfbbd1ed5483e4633d5bed6c0a1ccb35c01cef5c83888"),
  Map.entry("net.neoforged.bus.ConsumerEventHandler","aa624b2d0714835fc2258bf0fad66c4b83e15916d13f0e85d308cd6e3efce68d"),
  Map.entry("net.neoforged.bus.ConsumerEventHandler$WithPredicate","238c2d7d75aaac9aefaadebed46a2a7b16044ee81ab32e7f0a81bdeaae6a1da5"),
  Map.entry("net.neoforged.bus.SubscribeEventListener","2dc270df84436f3458564e2a9542cf4a175d9b2426db70917672397998e0efd0"),
  Map.entry("net.neoforged.bus.GeneratedEventListener","57f93a48701152d66414a84655fb7cd60cc19e83d03502daf2336240c222c4ad"),
  Map.entry("net.neoforged.bus.EventListenerFactory","807424c87435f908d9a75b9fe52b43ab2e2925e97affb7b4e4057c8808485ed0"));
 static void require(String name){ verifiedBytes(name); }
 static byte[] verifiedBytes(String name){
  String expected=PINS.get(name);if(expected==null)throw new SurvivalRefusal("Unpinned session callback class: "+name);
  return verifyExactBinary(name,expected);
 }
 static byte[] verifyExactBinary(String name,String expected){
  try{
   Class<?> owner=Class.forName(name,false,NativeSessionCallbackPins.class.getClassLoader());
   try(var stream=owner.getResourceAsStream("/"+name.replace('.','/')+".class")){
    if(stream==null)throw new SurvivalRefusal("Session callback resource unavailable through exact owner: "+name+" module="+owner.getModule().getName());
    byte[] bytes=stream.readNBytes(2*1024*1024+1);
    if(bytes.length>2*1024*1024 || !expected.equals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))))
     throw new SurvivalRefusal("Session callback binary changed: "+name+" module="+owner.getModule().getName());
    return bytes;
   }
  }catch(ClassNotFoundException|java.io.IOException|java.security.NoSuchAlgorithmException error){
   var refused=new SurvivalRefusal("Cannot verify session callback binary: "+name);refused.initCause(error);throw refused;
  }
 }
}
