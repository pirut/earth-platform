package io.earthplatform.minecraft.survival.interaction.host;

import io.earthplatform.minecraft.survival.world.CubeCell;
import io.earthplatform.minecraft.survival.world.SurvivalRefusal;
import org.junit.jupiter.api.Test;
import java.util.List;
import io.earthplatform.minecraft.survival.world.NativeActionEffect;
import static org.junit.jupiter.api.Assertions.*;

class NativeEffectDeliveryHostBoundsTest {
    @Test void capacitiesCountPendingAndRetainedWithoutIntegerOverflow() {
        NativeEffectDeliveryHost.requireCapacity(4, 3, 7, "entities");
        assertThrows(SurvivalRefusal.class, () -> NativeEffectDeliveryHost.requireCapacity(4, 4, 7, "entities"));
        assertThrows(SurvivalRefusal.class, () -> NativeEffectDeliveryHost.requireCapacity(Integer.MAX_VALUE, 1, Integer.MAX_VALUE, "ticks"));
        assertThrows(SurvivalRefusal.class, () -> NativeEffectDeliveryHost.requireCapacity(-1, 1, 4, "owners"));
    }
    @Test void nativeTrackingPositionPreservesNegativeAndHighYWithoutWrapping() {
        var position = NativeEffectDeliveryHost.exactNativePosition(CubeCell.of(-17, 8849, -1));
        assertEquals(-17, position.getX()); assertEquals(8849, position.getY()); assertEquals(-1, position.getZ());
        assertThrows(SurvivalRefusal.class, () -> NativeEffectDeliveryHost.exactNativePosition(CubeCell.of(0, 8388608, 0)));
        assertThrows(SurvivalRefusal.class, () -> NativeEffectDeliveryHost.exactNativePosition(CubeCell.of(33554432, 0, 0)));
    }
    @Test void statsRefuseOverflowInsteadOfSaturatingAwayConservedAmount() {
        assertEquals(Integer.MAX_VALUE, NativeEffectDeliveryHost.checkedAdd(Integer.MAX_VALUE - 1, 1, "stat"));
        assertThrows(SurvivalRefusal.class, () -> NativeEffectDeliveryHost.checkedAdd(Integer.MAX_VALUE, 1, "stat"));
    }
    @Test void soundPacketsRefuseCoordinateOverflow() {
        NativeEffectDeliveryHost.requireSoundCoordinate(-8849.5);
        assertThrows(SurvivalRefusal.class, () -> NativeEffectDeliveryHost.requireSoundCoordinate(268435456.0));
        assertThrows(SurvivalRefusal.class, () -> NativeEffectDeliveryHost.requireSoundCoordinate(Double.NaN));
    }
    @Test void nativeAllowanceReservesExactEscrowBeforeEveryAction() {
        assertEquals(3072, NativeEffectDeliveryHost.checkedBoundaryAllowance(4096, 1024, 3072));
        assertThrows(SurvivalRefusal.class, () -> NativeEffectDeliveryHost.checkedBoundaryAllowance(4096, 1024, 3073));
        assertThrows(SurvivalRefusal.class, () -> NativeEffectDeliveryHost.checkedBoundaryAllowance(4096, 1024, 0));
        assertThrows(SurvivalRefusal.class, () -> NativeEffectDeliveryHost.checkedBoundaryAllowance(4096, Integer.MAX_VALUE, 1));
    }

    @Test void menuJournalAndNativeDeliveryShareOneWholeActionByteBudget() {
        var effects = List.of(new NativeActionEffect("random_seed_v1", new byte[100]),
                new NativeActionEffect("resolved_menu_script_v1", new byte[200]),
                new NativeActionEffect("inventory_slot_v1", new byte[300]));
        assertEquals(1024, NativeEffectDeliveryHost.checkedMenuEffectBytes(effects, 424, 1024));
        assertThrows(SurvivalRefusal.class, () -> NativeEffectDeliveryHost.checkedMenuEffectBytes(effects, 425, 1024));
        assertThrows(SurvivalRefusal.class, () -> NativeEffectDeliveryHost.checkedMenuEffectBytes(effects, 0, 1024));
        assertThrows(SurvivalRefusal.class, () -> NativeEffectDeliveryHost.checkedMenuEffectBytes(effects, -1, 1024));
        assertThrows(SurvivalRefusal.class, () -> NativeEffectDeliveryHost.checkedMenuEffectBytes(effects, Integer.MAX_VALUE, Integer.MAX_VALUE));
    }

    @Test void menuNoticesAndBroadcastClosureShareExistingMetadataCountCap() {
        assertEquals(128, NativeEffectDeliveryHost.checkedMenuMetadataCount(24, 104, 128));
        assertThrows(SurvivalRefusal.class, () -> NativeEffectDeliveryHost.checkedMenuMetadataCount(24, 105, 128));
        assertThrows(SurvivalRefusal.class, () -> NativeEffectDeliveryHost.checkedMenuMetadataCount(1, 47, 32));
        assertThrows(SurvivalRefusal.class, () -> NativeEffectDeliveryHost.checkedMenuMetadataCount(0, 0, 32));
        assertThrows(SurvivalRefusal.class, () -> NativeEffectDeliveryHost.checkedMenuMetadataCount(-1, 1, 32));
        assertThrows(SurvivalRefusal.class, () -> NativeEffectDeliveryHost.checkedMenuMetadataCount(Integer.MAX_VALUE, 1, Integer.MAX_VALUE));
    }

    @Test void gameEventMetadataRejectsMultiEventUnionBeforeNativeAllocation() {
        NativeEffectDeliveryHost.requirePreparedGameEventCount(0);
        NativeEffectDeliveryHost.requirePreparedGameEventCount(1);
        for (int count : new int[]{-1, 2, Integer.MAX_VALUE})
            assertThrows(SurvivalRefusal.class, () -> NativeEffectDeliveryHost.requirePreparedGameEventCount(count));
    }
}
