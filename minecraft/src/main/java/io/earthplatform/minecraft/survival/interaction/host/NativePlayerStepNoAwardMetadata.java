package io.earthplatform.minecraft.survival.interaction.host;

import io.earthplatform.minecraft.survival.world.SurvivalRefusal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.advancements.CriteriaTriggers;
import net.minecraft.advancements.CriterionTrigger;
import net.minecraft.advancements.critereon.DistanceTrigger;
import net.minecraft.advancements.critereon.EnterBlockTrigger;
import net.minecraft.advancements.critereon.SimpleCriterionTrigger;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/** Proves auxiliary native triggers cannot match; never removes a listener or invokes a trigger.
 * The original SimpleCriterionTrigger still creates its EntityPredicate LootContext first.
 * That pinned builder path retains Level.getRandom() without drawing RNG; the disjoint
 * match proof only skips later context/player/environment predicate evaluation.
 */
final class NativePlayerStepNoAwardMetadata {
    private final ServerPlayer player;
    private final int maximum;
    private final NativePlayerStepAdmission.Bounds bounds;
    private final Set<BlockState> states;
    private final List<CriterionTrigger.Listener<?>> admitted;
    NativePlayerStepNoAwardMetadata(ServerPlayer p,NativePlayerStepAdmission.Bounds bounds,Set<BlockState> states,int maximum){
        if(maximum<1||maximum>4096)throw refused("Bounded auxiliary listener allowance required");this.maximum=maximum;
        this.player=p;this.bounds=bounds;this.states=Set.copyOf(states);admitted=inspect();
    }
    void revalidate(){
        var now=inspect();if(now.size()!=admitted.size())throw refused("Auxiliary player-step listener membership changed");
        for(int i=0;i<now.size();i++)if(now.get(i)!=admitted.get(i))throw refused("Auxiliary player-step listener identity/order changed");
    }
    private List<CriterionTrigger.Listener<?>> inspect(){
        Object start=NativePlayerStepSnapshot.field(ServerPlayer.class,player,"startingToFallPosition");
        if(start!=null&&(!(start instanceof Vec3 pos)||pos.y<bounds.y1()||pos.y>bounds.y2()))
            throw refused("Retained native falling origin lies outside exact bounded route");
        var advancementOwner=player.getAdvancements();
        if(advancementOwner.getClass()!=net.minecraft.server.PlayerAdvancements.class)throw refused("Foreign native advancement listener owner");
        var manager=player.server.getAdvancements();
        if(manager==null||manager.getClass()!=net.minecraft.server.ServerAdvancementManager.class)throw refused("Foreign native advancement lookup manager");
        requireImmutableMap(NativePlayerStepSnapshot.field(net.minecraft.server.ServerAdvancementManager.class,manager,"advancements"),4096);
        var result=new ArrayList<CriterionTrigger.Listener<?>>();
        for(SimpleCriterionTrigger<?> trigger:List.of(CriteriaTriggers.ENTER_BLOCK,CriteriaTriggers.FALL_FROM_HEIGHT)){
            if(trigger.getClass()!=(trigger==CriteriaTriggers.ENTER_BLOCK?EnterBlockTrigger.class:DistanceTrigger.class))throw refused("Custom auxiliary step trigger");
            Object raw=NativePlayerStepSnapshot.field(SimpleCriterionTrigger.class,trigger,"players");
            if(raw==null||raw.getClass()!=IdentityHashMap.class||((IdentityHashMap<?,?>)raw).size()>1)throw refused("Auxiliary trigger owner map changed");
            Object listeners=((Map<?,?>)raw).get(advancementOwner);if(listeners==null)continue;
            if(listeners.getClass()!=HashSet.class||((Set<?>)listeners).size()>maximum-result.size())throw refused("Auxiliary trigger listener budget/implementation changed");
            for(Object object:(Set<?>)listeners){
                if(object==null||object.getClass()!=CriterionTrigger.Listener.class)throw refused("Custom auxiliary listener");
                var listener=(CriterionTrigger.Listener<?>)object;var retained=listener.advancement();
                if(retained==null||retained.getClass()!=net.minecraft.advancements.AdvancementHolder.class
                    ||retained.id()==null||retained.id().getClass()!=net.minecraft.resources.ResourceLocation.class
                    ||listener.criterion()==null||listener.criterion().length()>256)throw refused("Foreign native loaded advancement holder/key");
                var holder=manager.get(retained.id());
                if(holder!=retained||holder.value()==null||holder.value().getClass()!=net.minecraft.advancements.Advancement.class)
                    throw refused("Auxiliary listener is not exact loaded advancement");
                var criteria=requireImmutableMap(holder.value().criteria(),maximum);Object rawCriterion=criteria.get(listener.criterion());
                if(rawCriterion==null||rawCriterion.getClass()!=net.minecraft.advancements.Criterion.class)throw refused("Foreign native loaded criterion");
                var criterion=(net.minecraft.advancements.Criterion<?>)rawCriterion;
                if(criterion.trigger()!=trigger||criterion.triggerInstance()!=listener.trigger())throw refused("Auxiliary listener differs from loaded criterion");
                if(trigger==CriteriaTriggers.ENTER_BLOCK){
                    if(listener.trigger().getClass()!=EnterBlockTrigger.TriggerInstance.class)throw refused("Custom native enter-block predicate");
                    var instance=(EnterBlockTrigger.TriggerInstance)listener.trigger();
                    if(instance.block().isEmpty()||instance.block().get().getClass()!=Holder.Reference.class)throw refused("Unbounded native enter-block predicate");
                    var block=instance.block().get();
                    if(BuiltInRegistries.BLOCK.getHolderOrThrow(block.unwrapKey().orElseThrow())!=block
                            ||states.stream().anyMatch(state->state.is(block)))throw refused("Enter-block trigger can match sealed native step terrain");
                    // Native EnterBlock.matches tests the block first; opaque later conditions cannot run.
                }else{
                    if(listener.trigger().getClass()!=DistanceTrigger.TriggerInstance.class)throw refused("Custom native fall-distance predicate");
                    var instance=(DistanceTrigger.TriggerInstance)listener.trigger();
                    if(instance.startPosition().isEmpty())throw refused("Native fall trigger lacks a provably disjoint start-Y bound");
                    var location=instance.startPosition().get();
                    if(location.getClass()!=net.minecraft.advancements.critereon.LocationPredicate.class||location.position().isEmpty())
                        throw refused("Foreign native fall start-position predicate");
                    Object position=location.position().orElseThrow();
                    if(position.getClass()!=NativePlayerMoveSnapshotAdmission.nativeClass("net.minecraft.advancements.critereon.LocationPredicate$PositionPredicate",net.minecraft.advancements.critereon.LocationPredicate.class))
                        throw refused("Foreign native fall position component");
                    var y=(net.minecraft.advancements.critereon.MinMaxBounds.Doubles)NativePlayerStepSnapshot.field(position.getClass(),position,"y");
                    if(y.getClass()!=net.minecraft.advancements.critereon.MinMaxBounds.Doubles.class)throw refused("Foreign native fall-Y bound");
                    boolean disjoint=y.min().map(min->min>bounds.y2()).orElse(false)||y.max().map(max->max<bounds.y1()).orElse(false);
                    if(!disjoint)throw refused("Native landing could match a fall advancement before entry");
                    // LocationPredicate tests position first, before biome/light or the player predicate.
                }
                result.add(listener);
            }
        }
        return List.copyOf(result);
    }
    static Map<?,?> requireImmutableMap(Object raw,int maximum){
        if(raw==null)throw refused("Missing native immutable advancement map");Class<?> type=raw.getClass();
        if(type!=Map.of().getClass()&&type!=Map.of("a",1).getClass()
            &&type!=com.google.common.collect.ImmutableMap.of().getClass()
            &&type!=com.google.common.collect.ImmutableMap.of("a",1).getClass())throw refused("Foreign native immutable advancement map before traversal");
        var map=(Map<?,?>)raw;if(map.size()>maximum)throw refused("Native advancement map exceeds source bound");return map;
    }
    private static SurvivalRefusal refused(String message){return new SurvivalRefusal(message);}
}
