package io.earthplatform.minecraft.mixin;

import io.earthplatform.minecraft.survival.interaction.host.NativePlacedBlockCriterionAdmission;
import java.util.List;
import java.util.function.Predicate;
import net.minecraft.advancements.critereon.ContextAwarePredicate;
import net.minecraft.world.level.storage.loot.LootContext;
import net.minecraft.world.level.storage.loot.predicates.LootItemCondition;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Default-off constructor provenance, never alters a native predicate or resource. */
@Mixin(ContextAwarePredicate.class)
public abstract class SurvivalContextPredicateProvenanceMixin {
    @Shadow @Final private List<LootItemCondition> conditions;
    @Shadow @Final private Predicate<LootContext> compositePredicates;
    @Inject(method="<init>",at=@At("RETURN"),require=1)
    private void earthplatform$recordPredicate(List<LootItemCondition> input,CallbackInfo ci) {
        NativePlacedBlockCriterionAdmission.recordContext((ContextAwarePredicate)(Object)this,conditions,compositePredicates);
    }
}
