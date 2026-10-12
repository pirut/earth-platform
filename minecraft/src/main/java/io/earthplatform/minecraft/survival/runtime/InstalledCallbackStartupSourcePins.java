package io.earthplatform.minecraft.survival.runtime;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.neoforged.fml.ModList;

/** Finite installed-observation source-cut pins, not production admission.
 * Requires the parent-sealed exact launch inputs and transform inventory in PRESTART-CUT.md.
 * The runtime checks reinforce that reviewed scope; they do not discover new compatible mods.
 */
final class InstalledCallbackStartupSourcePins {
    private InstalledCallbackStartupSourcePins() {}
    private static final Map<String,String> VERSIONS=Map.ofEntries(
        Map.entry("minecraft","1.21.1"),Map.entry("neoforge","21.1.256"),
        Map.entry("earthplatform","0.1.0-engine-prototype"),Map.entry("distanthorizons","3.3.3"),
        Map.entry("iris","1.8.14-beta.1+mc1.21.1"),Map.entry("sodium","0.8.12-beta.1+mc1.21.1"),
        Map.entry("fabric_api_base","0.4.42+d1308ded19"),
        Map.entry("fabric_block_view_api_v2","1.0.10+9afaaf8c19"),
        Map.entry("fabric_renderer_api_v1","3.4.1+9125b6dc19"),
        Map.entry("fabric_rendering_data_attachment_v1","0.3.48+73761d2e19"));
    private static final Map<String,String> DISTRIBUTIONS=Map.of(
        "DistantHorizons-3.3.3-1.21.1-fabric-neoforge.jar","864e70def5b0d54d619b940b66bb2784e91d3f3fc253064fdc1b3fc5786cbfab",
        "iris-neoforge-1.8.14-beta.1+mc1.21.1.jar","60d5f8bf52f25e9986440f4a4270a6bd986d9cff994510e1108ac1547063b314",
        "sodium-neoforge-0.8.12-beta.1+mc1.21.1.jar","dc2838d5538db39414171fe86586ab89d1a30d2fd97f59229cc44cf3777f569d");
    private static final Map<String,String> ORIGINALS=Map.ofEntries(
        Map.entry("com.seibel.distanthorizons.common.AbstractModInitializer","3680f918cf325646f4ccce7c4f722109ae2c51457efe01d1cf972460d4e310bc"),
        Map.entry("com.seibel.distanthorizons.common.AbstractModInitializer_neoforge","5c1e9a41d94c9419a30e9c4766366ff1a3f333f8b4c59b41a68886fb0df3f187"),
        Map.entry("com.seibel.distanthorizons.common.wrappers.DependencySetup_neoforge","54014d73d4a3dac81df3c38c1c18c0153e131b1447f94ac02a8a5870802390e5"),
        Map.entry("com.seibel.distanthorizons.neoforge.NeoforgeClientProxy","4cb7b36050deddfb956f02c9fef5b7c48374b479435979e088c9f53d1723ee07"),
        Map.entry("com.seibel.distanthorizons.neoforge.NeoforgeMain","eeba08a36ec3135b84732015a60b6c2de7d306852fd9c1edc8baf1649cde97d6"),
        Map.entry("com.seibel.distanthorizons.neoforge.NeoforgeServerProxy","9de829c00eb94e63736d4baf618fd8a047f61774ab140acafe30749212973e2e"),
        Map.entry("io.earthplatform.minecraft.EarthPlatformMod","c867a3049ea72e690f879d2018e6c5cb359414f56bb3404a9fa0cb2f4e8555ef"),
        Map.entry("io.earthplatform.minecraft.survival.gametest.NativeCraftingTableGameTests","e05873ceec007e7a671155f03ad7dd953626aad0547e15608180e1821a4ccf78"),
        Map.entry("io.earthplatform.minecraft.survival.gametest.NativeCraftingTableGameTests$2","6360286fd3db969df67c776dc0a14e55d5f7d58711e15331eb72d0faaf8db36e"),
        Map.entry("io.earthplatform.minecraft.survival.gametest.NativeMenuSessionGameTests","283cda0de506d00b4550598c97f4f9314810bd6a7e44406f0afde78012a55734"),
        Map.entry("io.earthplatform.minecraft.survival.gametest.NativeMenuSessionGameTests$1","204bb0e995c768e005066a5ad58738e7adff50225eee0c01140fd2ef866669d6"),
        Map.entry("io.earthplatform.minecraft.survival.interaction.host.PinnedNativeEventAdmission","9c0bff62a14d7cc7b8efb8c007bf9439fe09814a669a5d1407cde2d4b7d1ab98"),
        Map.entry("io.earthplatform.minecraft.survival.persistence.NativeSessionListenerCensus","b61aca28e8e0f37961d81e2865d23dc768fcf8f6d44194c2a455cd819445c9aa"),
        Map.entry("net.irisshaders.iris.platform.IrisForgeMod","85d576f38fe2e07b74cca16ab1015a735df5d17ac7298399649c16d05d2fc9c9"),
        Map.entry("net.minecraft.Util","5e074737ae8085de47c55795d46449a5b7b31a2e6462421e0e155a816c50a614"),
        Map.entry("net.minecraft.client.Minecraft","495f5dc97d07476e23835fcdd034019a020a58b980f7c764d4953ce4ed16d283"),
        Map.entry("net.minecraft.client.Options","d4931d21c76890026e9ee4f5f6ef6d1d9670b73141db5a4f6d78c6ad32abf8fd"),
        Map.entry("net.minecraft.client.gui.screens.LoadingOverlay","3f08491a58f158da3a59760fa0f45d179c1e780d80da680459f625b0c814c621"),
        Map.entry("net.minecraft.server.packs.resources.ProfiledReloadInstance","e21a741ed3d66bf7a72d99aeb8fdd7097074a30a1d05f95c9f2bba2344f49b8b"),
        Map.entry("net.minecraft.server.packs.resources.ReloadInstance","41bf471f9d66334c0d8ad8778de1f70fc569a3356ca05335bbffaacd56d880d1"),
        Map.entry("net.minecraft.server.packs.resources.ReloadableResourceManager","6430c4c08f0ab6b2f0589c0ba907154c49f52fd1c047de7536e8ad6c12792c94"),
        Map.entry("net.minecraft.server.packs.resources.SimpleReloadInstance","4c1a8ee5350f08ed0c26372d4a60d6eaa4f63863266f812eb246c6ea9654fa63"),
        Map.entry("net.neoforged.bus.ConsumerEventHandler","aa624b2d0714835fc2258bf0fad66c4b83e15916d13f0e85d308cd6e3efce68d"),
        Map.entry("net.neoforged.bus.ConsumerEventHandler$WithPredicate","238c2d7d75aaac9aefaadebed46a2a7b16044ee81ab32e7f0a81bdeaae6a1da5"),
        Map.entry("net.neoforged.bus.EventBus","cda8876c904d1374f77bbff02e8cdb01fba48ae0f528c69713eaefa11b5128a4"),
        Map.entry("net.neoforged.bus.EventListenerFactory","807424c87435f908d9a75b9fe52b43ab2e2925e97affb7b4e4057c8808485ed0"),
        Map.entry("net.neoforged.bus.GeneratedEventListener","57f93a48701152d66414a84655fb7cd60cc19e83d03502daf2336240c222c4ad"),
        Map.entry("net.neoforged.bus.ListenerList","3daccbcb1ef602dff0edfbbd1ed5483e4633d5bed6c0a1ccb35c01cef5c83888"),
        Map.entry("net.neoforged.bus.LockHelper","28a7dd87748b9c42334ea2a1853d43ff808b73cb70cb8ce03eb40adfe93a50bc"),
        Map.entry("net.neoforged.bus.SubscribeEventListener","2dc270df84436f3458564e2a9542cf4a175d9b2426db70917672397998e0efd0"),
        Map.entry("net.neoforged.fml.DeferredWorkQueue","a21260557cffad3e4b7cd1165d364d8f4a59099469a971e23ddc797f7dceffe8"),
        Map.entry("net.neoforged.fml.ModLoader","5fa4a2bd9e8a2b0a4a2add1ca245229bdacf74e62e4f21cc53f98fd2beccfda1"),
        Map.entry("net.neoforged.fml.javafmlmod.AutomaticEventSubscriber","c7ac0102b5f8114e5ebda79951daaa58ad4a71ff6c537cbf7cac2245111cfa7a"),
        Map.entry("net.neoforged.fml.javafmlmod.FMLModContainer","dff277ee42194cb26b03293ddfa164fea912b229282f60592d52c43a5e7012c4"),
        Map.entry("net.neoforged.fml.loading.FMLLoader","67b3ae93d48098995897b54e01a992bd9734d308a0408713f286c94f4bceb798"),
        Map.entry("net.neoforged.neoforge.client.ClientCommandHandler","53e7647e24adbf037e677eb0bc3d86abc0dc143651ea8bdd9bcc6fa012ac953c"),
        Map.entry("net.neoforged.neoforge.client.ClientNeoForgeMod","3f04c8ecd39b350cfadcff90a615a9de78997adaf87624d57aa1cfa716e11b23"),
        Map.entry("net.neoforged.neoforge.client.TagConventionLogWarningClient","9d6bb385487b2c05b435d3fe6547fe929fdd388a8a48f25924a0e14923887449"),
        Map.entry("net.neoforged.neoforge.client.internal.SelfTestClient","47b46dd9425dfb16ab3462653b3ac00e8e162c89fc9159e099268971281b2816"),
        Map.entry("net.neoforged.neoforge.client.loading.ClientModLoader","f4671fd5a47fb476dc0e5ee0ba0e6974411f51eb8256999f1c7a7f26d976eaa3"),
        Map.entry("net.neoforged.neoforge.common.NeoForgeMod","98dc057e7bc44f959c9b8587e4f978230385a31ac20e8aa73a6991b279f874c4"),
        Map.entry("net.neoforged.neoforge.common.TagConventionLogWarning","e12754b5a0359c9315ad046e3abab81c5fb1768bdb173db9ec86a85593c54930"),
        Map.entry("net.neoforged.neoforge.common.util.SelfTest","2e2389d231097cfe7793fe37883e0fb78ccf403722741b8d3cb2d0e8bd4bbefd"),
        Map.entry("net.neoforged.neoforge.gametest.GameTestHooks","98d253ebd0cb6d5e12623a9984d2d173b6bb3d372a610c5fa68fda4fe6f10007"),
        Map.entry("net.neoforged.neoforge.internal.CommonModLoader","0d52aac4d595f34d2e920641a4ad9881152f0cecfffae757ee2ad89caa704b55"),
        Map.entry("net.neoforged.neoforge.registries.DeferredRegister","cd5da7b9ad502d54a3265cde9c64f2ced86986ecce6c301a153821761c4ecb95"),
        Map.entry("net.neoforged.neoforge.registries.NeoForgeRegistriesSetup","96f116600a24284b78d7029620471ef3fb431c263ff047f254bf4c1cf35dfbb5"),
        Map.entry("java.util.concurrent.CompletableFuture","34f551d18d1f14df5bf0b47e2f6bafbea597a7e73249d83c001ee051d6481b0c"));
    static void requireSourceCut(InstalledCallbackStartupCensus.Capture capture)throws java.io.IOException,java.security.NoSuchAlgorithmException {
        for(String key:new String[]{"neoforge.gameTestServer","neoforge.enableGameTest","earthplatform.nativeSessionBridge","earthplatform.installedMarinSession"}){
            boolean enabled=Boolean.getBoolean(key);
            check(capture,"MODE",key,"false",Boolean.toString(enabled));
            if(enabled)throw new IllegalStateException("Foreign authority/fixture mode at observational startup cut");
        }
        check(capture,"MOD_COUNT","ModList","10","CHECKING");
        var mods=ModList.get().getMods();
        check(capture,"MOD_COUNT","ModList",Integer.toString(VERSIONS.size()),Integer.toString(mods.size()));
        if(mods.size()!=VERSIONS.size())throw new IllegalStateException("Unreviewed actual startup mod inventory");
        var versions=new HashMap<String,String>();
        for(var mod:mods)if(versions.put(mod.getModId(),mod.getVersion().toString())!=null)
            throw new IllegalStateException("Duplicate startup mod identity");
        for(var entry:VERSIONS.entrySet()){
            check(capture,"MOD_VERSION",entry.getKey(),entry.getValue(),clip(versions.get(entry.getKey())));
            if(!entry.getValue().equals(versions.get(entry.getKey())))throw new IllegalStateException("Changed startup mod versions");
        }
        Path folder=Minecraft.getInstance().gameDirectory.toPath().resolve("mods");
        for(var entry:DISTRIBUTIONS.entrySet()){
            check(capture,"DISTRIBUTION",entry.getKey(),entry.getValue(),"CHECKING");
            Path path=folder.resolve(entry.getKey());
            if(!Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS))throw new java.io.IOException("Startup vendor source is not a plain file");
            try(var in=Files.newInputStream(path,LinkOption.NOFOLLOW_LINKS)){
                String actual=digest(in,128L<<20);check(capture,"DISTRIBUTION",entry.getKey(),entry.getValue(),actual);
                if(!actual.equals(entry.getValue()))throw new java.io.IOException("Changed startup vendor distribution");
            }
        }
        // Resource checks do not initialize dormant fixture/reflective producer classes.
        // Exact loaded bus/marker classes are checked separately through their actual owners.
        ClassLoader loader=InstalledCallbackStartupSourcePins.class.getClassLoader();
        for(var entry:ORIGINALS.entrySet()){
            check(capture,"ORIGINAL",entry.getKey(),entry.getValue(),"CHECKING");
            try(var in=loader.getResourceAsStream(entry.getKey().replace('.','/')+".class")){
                String actual=in==null?"RESOURCE_ABSENT":digest(in,2L<<20);
                check(capture,"ORIGINAL",entry.getKey(),entry.getValue(),actual);
                if(!actual.equals(entry.getValue()))throw new java.io.IOException("Changed startup producer original");
            }
        }
    }
    private static void check(InstalledCallbackStartupCensus.Capture capture,String kind,String key,String expected,String actual) {
        capture.allocation.reserve(2048); // Prepay retained check plus bounded owned text before replacement.
        capture.stage="SOURCE_"+kind;
        capture.sourceCheck=new InstalledCallbackStartupCensus.SourceCheck(kind,clip(key),clip(expected),clip(actual));
    }
    private static String clip(String value){return value==null?"ABSENT":value.substring(0,Math.min(value.length(),192));}
    private static String digest(java.io.InputStream in,long bound)throws java.io.IOException,java.security.NoSuchAlgorithmException {
        var digest=MessageDigest.getInstance("SHA-256");byte[] buffer=new byte[8192];long total=0;
        for(int n;(n=in.read(buffer))!=-1;){total=Math.addExact(total,n);if(total>bound)throw new java.io.IOException("Startup input bound");digest.update(buffer,0,n);}
        return HexFormat.of().formatHex(digest.digest());
    }
}
