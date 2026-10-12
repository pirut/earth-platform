package io.earthplatform.minecraft.survival.interaction.host;
import static org.junit.jupiter.api.Assertions.*;
import io.earthplatform.minecraft.mixin.NativeLocalObservationMixinPlugin;
import io.earthplatform.minecraft.mixin.SurvivalSessionBridgeMixinPlugin;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
class NativeLocalObservationGateTest {
    private static final String[] FLAGS={"earthplatform.nativeLocalFrontierObservation","earthplatform.installedSessionProbe","earthplatform.installedMarinSession","earthplatform.nativeSessionBridge","neoforge.gameTestServer","earthplatform.nativeCodecBoundaryProbe","earthplatform.nativeLocalRootObservation"};
    private static void scoped(Runnable test){Map<String,String> old=new HashMap<>();for(String flag:FLAGS){old.put(flag,System.getProperty(flag));System.clearProperty(flag);}try{test.run();}finally{for(String flag:FLAGS){if(old.get(flag)==null)System.clearProperty(flag);else System.setProperty(flag,old.get(flag));}}}
    @Test void defaultIsInert(){scoped(()->{assertFalse(NativeLocalObservationMixinPlugin.enabled());assertFalse(new SurvivalSessionBridgeMixinPlugin().shouldApplyMixin("net.minecraft.network.PacketEncoder","io.earthplatform.minecraft.mixin.NativeOriginalPacketEncoderBoundaryMixin"));});}
    @Test void onlyExplicitOrdinaryObservationOpensBothHooks(){scoped(()->{
        System.setProperty(FLAGS[0],"true");assertFalse(NativeLocalObservationMixinPlugin.enabled());System.setProperty(FLAGS[1],"true");assertTrue(NativeLocalObservationMixinPlugin.enabled());
        var plugin=new SurvivalSessionBridgeMixinPlugin();assertTrue(plugin.shouldApplyMixin("net.minecraft.network.PacketEncoder","io.earthplatform.minecraft.mixin.NativeOriginalPacketEncoderBoundaryMixin"));
        assertFalse(plugin.shouldApplyMixin("net.minecraft.network.Connection","io.earthplatform.minecraft.mixin.NativeOriginalConnectionProbeMixin"));
        assertFalse(plugin.shouldApplyMixin("io.netty.channel.embedded.EmbeddedChannel","io.earthplatform.minecraft.mixin.NativeEmbeddedTransformMarkerMixin"));
    });}
    @Test void productionOrGameTestFlagsCloseLocalObservation(){scoped(()->{
        System.setProperty(FLAGS[0],"true");System.setProperty(FLAGS[1],"true");for(int i=2;i<=4;i++){System.setProperty(FLAGS[i],"true");assertFalse(NativeLocalObservationMixinPlugin.enabled());System.clearProperty(FLAGS[i]);}
    });}
    @Test void additionalRootsNeedTheirOwnExplicitFlag(){scoped(()->{
        System.setProperty(FLAGS[0],"true");System.setProperty(FLAGS[1],"true");var plugin=new NativeLocalObservationMixinPlugin();
        String mixin="io.earthplatform.minecraft.mixin.NativeLocalConnectionRootsMixin";
        assertFalse(plugin.shouldApplyMixin("net.minecraft.network.Connection",mixin));
        System.setProperty(FLAGS[6],"true");assertTrue(plugin.shouldApplyMixin("net.minecraft.network.Connection",mixin));
        System.setProperty(FLAGS[2],"true");assertFalse(plugin.shouldApplyMixin("net.minecraft.network.Connection",mixin));
    });}
}
