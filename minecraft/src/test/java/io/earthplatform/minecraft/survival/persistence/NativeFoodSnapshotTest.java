package io.earthplatform.minecraft.survival.persistence;

import net.minecraft.world.food.FoodData;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class NativeFoodSnapshotTest {
    @Test void allFourNativeDurableFieldsRoundtripWithoutAliases() {
        var expected = new NativeFoodSnapshot(15, 73, 2.25F, 3.125F);
        FoodData food = expected.materialize();
        assertEquals(expected, NativeFoodSnapshot.capture(food));
        food.addExhaustion(0.005F);
        assertNotEquals(expected, NativeFoodSnapshot.capture(food));
        assertEquals(3.125F, expected.exhaustion());
        var tag = expected.tagCopy(); tag.putInt("foodLevel", 0);
        assertEquals(15, expected.foodLevel()); assertEquals(16, expected.encodedBytes());
    }
    @Test void nativeExhaustionClampAndOrderSurviveRoundtrip() {
        FoodData food = new NativeFoodSnapshot(20, 0, 5, 39.999F).materialize();
        food.addExhaustion(0.005F); food.addExhaustion(1F);
        var captured = NativeFoodSnapshot.capture(food);
        assertEquals(40F, captured.exhaustion());
        assertEquals(captured, NativeFoodSnapshot.capture(captured.materialize()));
    }
    @Test void malformedOrOutOfProfileFoodFailsBeforeRestore() {
        assertThrows(IllegalArgumentException.class, () -> new NativeFoodSnapshot(-1,0,0,0));
        assertThrows(IllegalArgumentException.class, () -> new NativeFoodSnapshot(21,0,0,0));
        assertThrows(IllegalArgumentException.class, () -> new NativeFoodSnapshot(20,80,5,0));
        assertThrows(IllegalArgumentException.class, () -> new NativeFoodSnapshot(20,0,Float.NaN,0));
        assertThrows(IllegalArgumentException.class, () -> new NativeFoodSnapshot(20,0,5,Float.POSITIVE_INFINITY));
        assertThrows(IllegalArgumentException.class, () -> new NativeFoodSnapshot(20,0,5,40.001F));
    }
    @Test void subclassCannotHideFoodCallbacks() {
        assertThrows(IllegalArgumentException.class, () -> NativeFoodSnapshot.capture(new FoodData() {}));
    }
}
