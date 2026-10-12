package io.earthplatform.minecraft.mixin;

import io.earthplatform.minecraft.survival.interaction.host.NativePlacedBlockCriterionAdmission;
import java.util.List;
import java.util.function.Predicate;
import net.minecraft.world.level.storage.loot.LootContext;
import net.minecraft.world.level.storage.loot.predicates.CompositeLootItemCondition;
import net.minecraft.world.level.storage.loot.predicates.LootItemCondition;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Native AllOf/AnyOf super-constructor provenance; unrelated/custom objects remain unadmitted. */
@Mixin(CompositeLootItemCondition.class)
public abstract class SurvivalCompositePredicateProvenanceMixin {
    @Shadow @Final protected List<LootItemCondition> terms;
    @Shadow @Final private Predicate<LootContext> composedPredicate;
    @Inject(method="<init>",at=@At("RETURN"),require=1)
    private void earthplatform$recordPredicate(List<LootItemCondition> input,Predicate<LootContext> predicate,CallbackInfo ci) {
        NativePlacedBlockCriterionAdmission.recordComposite((CompositeLootItemCondition)(Object)this,terms,composedPredicate);
    }
}
