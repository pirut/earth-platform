package io.earthplatform.minecraft.survival.interaction.host;

import io.earthplatform.core.WorldIdentity;
import io.earthplatform.minecraft.survival.interaction.NativeEffectCodec;
import io.earthplatform.minecraft.survival.world.CubeCell;
import io.earthplatform.minecraft.survival.world.SurvivalRefusal;
import java.lang.reflect.Modifier;
import java.util.UUID;
import net.minecraft.data.registries.VanillaRegistries;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Data-only negative checks. The registered native scenario and reference comparison are separate. */
class NativeBlockBreakBoundaryTest {
    @Test void destructionMetadataPreservesSignedTargetAndObservedProgress() {
        var registries=VanillaRegistries.createLookup();
        var value=new NativeEffectCodec.BlockBreakMetadata(new WorldIdentity(39,"proof","v1","source","palette"),
                CubeCell.of(-17,-33,65),UUID.randomUUID(),1,0.02F);
        assertEquals(value,NativeEffectCodec.decode(NativeEffectCodec.encode(value),registries));
    }
    @Test void metadataNeverAcceptsInvalidOrPretendReadyRate() {
        var world=new WorldIdentity(39,"proof","v1","source","palette");
        for(float rate:new float[]{0,-1,Float.NaN,Float.POSITIVE_INFINITY,Float.NEGATIVE_INFINITY})
            assertThrows(IllegalArgumentException.class,()->new NativeEffectCodec.BlockBreakMetadata(world,CubeCell.of(0,0,0),UUID.randomUUID(),1,rate));
    }
    @Test void nativeEventCoordinatesRefusePackedYAndXZAliases() {
        for(CubeCell target:java.util.List.of(CubeCell.of(0,2048,0),CubeCell.of(0,-2049,0),
                CubeCell.of(33554432,0,0),CubeCell.of(-33554433,0,0),CubeCell.of(0,0,33554432)))
            assertThrows(SurvivalRefusal.class,()->NativeBlockBreakPlan.requireMetadataCoordinates(target));
        assertDoesNotThrow(()->NativeBlockBreakPlan.requireMetadataCoordinates(CubeCell.of(-33554432,-2048,33554431)));
    }
    @Test void publicEvidenceContainsNoHostPlanActionOrEntityAndNoPublicConstructor() {
        assertTrue(Modifier.isFinal(NativeAcceptedBlockBreak.class.getModifiers()));
        assertEquals(0,NativeAcceptedBlockBreak.class.getConstructors().length);
        for(var field:NativeAcceptedBlockBreak.class.getDeclaredFields()) {
            assertTrue(Modifier.isPrivate(field.getModifiers())); assertTrue(Modifier.isFinal(field.getModifiers()));
            assertNotEquals(NativeEffectDeliveryHost.class,field.getType());
            assertNotEquals(NativeBlockBreakPlan.class,field.getType());
            assertNotEquals(io.earthplatform.minecraft.survival.interaction.NativeWholeAction.class,field.getType());
            assertFalse(net.minecraft.world.entity.Entity.class.isAssignableFrom(field.getType()));
        }
    }
    @Test void retainedAndScratchImagesHaveExplicitSmallSingleDropCaps() {
        assertEquals(4096,NativeBlockBreakPlan.DROP_IMAGE_MAX_BYTES);
        assertEquals(2*(64+4096+io.earthplatform.minecraft.survival.interaction.NativeStackSnapshot.MAX_BYTES),
                NativeBlockBreakPlan.IMAGE_ALLOWANCE_BYTES);
        assertEquals(114944,2*NativeBlockBreakPlan.IMAGE_ALLOWANCE_BYTES);
    }
    @Test void privatePlanHasNoPublicConstructionOrCallbackIngress() {
        assertEquals(0,NativeBlockBreakPlan.class.getConstructors().length);
        for(var method:NativeEffectDeliveryHost.class.getDeclaredMethods()) if(method.getName().equals("dispatchServerBreakProof"))
            assertArrayEquals(new Class<?>[]{CubeCell.class},method.getParameterTypes());
    }
}
