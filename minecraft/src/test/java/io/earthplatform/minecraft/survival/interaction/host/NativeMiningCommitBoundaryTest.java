package io.earthplatform.minecraft.survival.interaction.host;

import java.lang.reflect.Modifier;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class NativeMiningCommitBoundaryTest {
    @Test void reservationAndDestroyInvocationHaveNoCallerConstructionOrSpendingMarker() {
        for(var type:new Class<?>[]{NativeEffectDeliveryHost.MiningReservation.class,NativeMiningControlPreparation.DestroyInvocation.class}) {
            assertEquals(0,type.getConstructors().length);
            for(var constructor:type.getDeclaredConstructors())assertTrue(Modifier.isPrivate(constructor.getModifiers()));
        }
        for(var method:NativeMiningControlPreparation.Owner.class.getDeclaredMethods())if(method.getName().equals("preparePacket")||method.getName().equals("preparePulse")) {
            assertEquals(NativeEffectDeliveryHost.MiningReservation.class,method.getParameterTypes()[method.getParameterCount()-1]);
            assertFalse(Modifier.isPublic(method.getModifiers()));
        }
    }
    @Test void privateControlPlanAndPublicMixinHooksCannotApplyArbitraryReceipts() throws Exception {
        var plan=Class.forName(NativeMiningControlPreparation.class.getName()+"$Plan");
        assertTrue(Modifier.isPrivate(plan.getModifiers()));
        for(var method:NativeMiningControlPreparation.class.getDeclaredMethods())if(Modifier.isPublic(method.getModifiers())) {
            assertFalse(method.getName().startsWith("apply"));assertFalse(method.getName().startsWith("commit"));
        }
        assertFalse(NativeMiningControlPreparation.captureDestroy(null,null));
        assertFalse(NativeMiningControlPreparation.enterPacket(null,null,null));
    }
    @Test void concretePacketAndPulseIngressRemainCompileTimeClosedPendingNativeQualification() throws Exception {
        var field=NativeEffectDeliveryHost.class.getDeclaredField("NATIVE_MINING_CONTROLS_QUALIFIED");
        assertTrue(Modifier.isPrivate(field.getModifiers()));assertTrue(Modifier.isStatic(field.getModifiers()));assertTrue(Modifier.isFinal(field.getModifiers()));
        field.setAccessible(true);assertFalse(field.getBoolean(null));
        assertFalse(NativeEffectDeliveryHost.dispatchNativeMiningPacket(null,null,null));
        assertFalse(NativeEffectDeliveryHost.dispatchNativeMiningPulse(null,null));
    }
    @Test void onlyThePrivateBreakCompositionSignatureCanAddAControlTranscript() throws Exception {
        var method=NativeBlockBreakPlan.class.getDeclaredMethod("prepareFromMining",NativeEffectDeliveryHost.class,
                io.earthplatform.minecraft.survival.interaction.NativeWholeAction.class,
                io.earthplatform.minecraft.survival.world.CubeCell.class,io.earthplatform.minecraft.survival.world.CubeSnapshot.class,
                NativeMiningControlPreparation.DestroyInvocation.class);
        assertFalse(Modifier.isPublic(method.getModifiers()));
        var ordinary=NativeBlockBreakPlan.class.getDeclaredMethod("prepare",NativeEffectDeliveryHost.class,
                io.earthplatform.minecraft.survival.interaction.NativeWholeAction.class,
                io.earthplatform.minecraft.survival.world.CubeCell.class,io.earthplatform.minecraft.survival.world.CubeSnapshot.class);
        assertFalse(Modifier.isPublic(ordinary.getModifiers()));
    }
}
