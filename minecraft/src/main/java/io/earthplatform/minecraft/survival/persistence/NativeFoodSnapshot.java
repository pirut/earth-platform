package io.earthplatform.minecraft.survival.persistence;

import java.util.Objects;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.food.FoodData;

/** Exact four fields persisted by pinned native FoodData; no Player callbacks or mutable aliases. */
public record NativeFoodSnapshot(int foodLevel, int tickTimer, float saturation, float exhaustion) {
    public static final int ENCODED_BYTES = 16;
    public NativeFoodSnapshot {
        if (foodLevel < 0 || foodLevel > 20 || tickTimer < 0 || tickTimer > 79
                || !Float.isFinite(saturation) || saturation < 0 || saturation > 20
                || !Float.isFinite(exhaustion) || exhaustion < 0 || exhaustion > 40)
            throw new IllegalArgumentException("Food state exceeds the admitted native Survival profile");
    }
    public static NativeFoodSnapshot capture(FoodData food) {
        Objects.requireNonNull(food);
        if (food.getClass() != FoodData.class) throw new IllegalArgumentException("Exact native FoodData required");
        CompoundTag tag = new CompoundTag(); food.addAdditionalSaveData(tag);
        return new NativeFoodSnapshot(tag.getInt("foodLevel"), tag.getInt("foodTickTimer"),
                tag.getFloat("foodSaturationLevel"), tag.getFloat("foodExhaustionLevel"));
    }
    public int encodedBytes() { return ENCODED_BYTES; }
    public CompoundTag tagCopy() {
        CompoundTag tag = new CompoundTag();
        tag.putInt("foodLevel", foodLevel); tag.putInt("foodTickTimer", tickTimer);
        tag.putFloat("foodSaturationLevel", saturation); tag.putFloat("foodExhaustionLevel", exhaustion);
        return tag;
    }
    /** Detached native state for preflight; restore remains the session owner's explicit responsibility. */
    public FoodData materialize() {
        FoodData food = new FoodData(); food.readAdditionalSaveData(tagCopy());
        if (!equals(capture(food))) throw new IllegalStateException("Native food roundtrip changed state");
        return food;
    }
}
