package io.earthplatform.minecraft.mixin;

import java.util.List;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.client.resources.model.WeightedBakedModel;
import net.minecraft.util.random.WeightedEntry;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Owner-only admission reads exact pinned weighted graph; never shipped into CPU worker inputs. */
@Mixin(WeightedBakedModel.class)
public interface SceneWeightedBakedModelAccessor {
    @Accessor("list") List<WeightedEntry.Wrapper<BakedModel>> earthplatform$children();
    @Accessor("totalWeight") int earthplatform$totalWeight();
}
