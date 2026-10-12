package io.earthplatform.minecraft.survival.interaction.host;

import io.earthplatform.minecraft.survival.world.SurvivalRefusal;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.advancements.AdvancementProgress;
import net.minecraft.advancements.CriteriaTriggers;
import net.minecraft.advancements.Criterion;
import net.minecraft.advancements.CriterionTrigger;
import net.minecraft.advancements.critereon.ContextAwarePredicate;
import net.minecraft.advancements.critereon.EntityPredicate;
import net.minecraft.advancements.critereon.ItemPredicate;
import net.minecraft.advancements.critereon.LocationPredicate;
import net.minecraft.advancements.critereon.PlayerTrigger;
import net.minecraft.advancements.critereon.RecipeUnlockedTrigger;
import net.minecraft.advancements.critereon.SimpleCriterionTrigger;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.PlayerAdvancements;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.stats.RecipeBook;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.storage.loot.LootContext;
import net.minecraft.world.level.storage.loot.predicates.LootItemEntityPropertyCondition;

/**
 * Exact read-only preflight for one pinned fresh dry native tick. It never invokes a trigger,
 * awards a criterion, changes progress, edits a recipe book or suppresses a native callback.
 * Native requirements and the actual registered listener iteration order drive a detached
 * simulation of TICK, recursive RECIPE_UNLOCKED and LOCATION. Conserved rewards and chat refuse
 * before native entry, including a returning player's last AdventuringTime criterion.
 */
public final class NativePlayerTickMetadata {
    private static final int MAX_ADVANCEMENTS=4096, MAX_CRITERIA=16384, MAX_AWARDS=256;
    private static final class Work {
        private long remaining=1048576;
        void spend(long amount) {
            if(amount<0||amount>remaining)throw refused("Native player-tick preflight work budget exceeded");
            remaining-=amount;
        }
    }
    private static final NativeFirstLoopMetadata.Limits LIMITS =
            new NativeFirstLoopMetadata.Limits(4096,16384,8192,65536,128,128,1);
    private final ServerPlayer player;
    private final PlayerAdvancements owner;
    private final NativeFirstLoopMetadata firstLoop;
    private final Snapshot admitted;
    private final List<Award> expected;
    private final Map<AdvancementHolder,Set<String>> expectedCompleted;
    private final Set<ResourceLocation> expectedKnown,expectedHighlight;
    private final net.minecraft.nbt.CompoundTag expectedBook;
    private final List<Award> actual = new ArrayList<>();
    private final boolean includeTick;
    private int cursor;
    private boolean entered;
    public record Award(AdvancementHolder holder,String criterion,boolean newGrant) {}
    private static final class Identity {
        private final Object value;
        Identity(Object value) {this.value=value;}
        @Override public boolean equals(Object other) {return other instanceof Identity identity&&value==identity.value;}
        @Override public int hashCode() {return System.identityHashCode(value);}
    }
    private record Progress(AdvancementProgress identity,Map<String,net.minecraft.advancements.CriterionProgress> criteria,Map<String,java.time.Instant> completed) {}
    private record Simulation(List<Award> awards,Map<AdvancementHolder,Set<String>> completed,Set<ResourceLocation> known,Set<ResourceLocation> highlight) {}
    private record Listener(SimpleCriterionTrigger<?> family,CriterionTrigger.Listener<?> identity) {}
    private record Snapshot(Map<ResourceLocation,AdvancementHolder> loaded,Map<AdvancementHolder,Progress> progress,
            List<Listener> listeners,Set<ResourceLocation> known,Set<ResourceLocation> highlight,
            net.minecraft.nbt.CompoundTag book,List<Object> predicateData,Map<AdvancementHolder,Integer> requirementCosts) {}

    private NativePlayerTickMetadata(ServerPlayer player,boolean includeTick) {
        this.player=Objects.requireNonNull(player);this.owner=player.getAdvancements();this.includeTick=includeTick;
        firstLoop=NativeFirstLoopMetadata.capture(player,LIMITS);
        admitted=snapshot();
        Simulation simulation=simulate(admitted);
        expected=simulation.awards;expectedCompleted=simulation.completed;expectedKnown=simulation.known;expectedHighlight=simulation.highlight;
        expectedBook=admitted.book.copy();
        expectedBook.put("recipes",recipeList(expectedKnown));expectedBook.put("toBeDisplayed",recipeList(expectedHighlight));
    }
    public static NativePlayerTickMetadata prepare(ServerPlayer player,boolean includeTick) {
        return new NativePlayerTickMetadata(player,includeTick);
    }
    /** Called inside the same read-only environment scope immediately before native entry. */
    public void enter() {
        if(entered)throw refused("Player tick metadata admission was reused");
        firstLoop.revalidate();
        Snapshot now=snapshot();
        if(!sameSnapshot(admitted,now)||!new Simulation(expected,expectedCompleted,expectedKnown,expectedHighlight).equals(simulate(now)))
            throw refused("Player tick predicate/listener/progress/recipe metadata changed after preflight");
        entered=true;
    }
    /** At original PlayerAdvancements.award HEAD: permission, never replacement execution. */
    public void beforeAward(PlayerAdvancements receiver,ServerPlayer source,AdvancementHolder holder,String criterion) {
        if(!entered||source!=player||receiver!=owner||owner!=player.getAdvancements()||!player.server.isSameThread())
            throw refused("Foreign or inactive exact native tick advancement owner");
        firstLoop.revalidate();
        if(cursor>=expected.size())throw refused("Unexpected native tick award: "+holder.id()+"/"+criterion);
        Award next=expected.get(cursor);
        if(next.holder()!=holder||!next.criterion().equals(criterion))
            throw refused("Native tick award order/identity escaped prepared graph: "+holder.id()+"/"+criterion);
        var progress=progressMap().get(holder);
        if(progress==null)throw refused("Native advancement progress identity disappeared");
        var cp=progress.getCriterion(criterion);
        boolean newGrant=cp!=null&&!cp.isDone();
        if(next.newGrant()!=newGrant)throw refused("Native tick grant/no-op status changed after preflight");
        cursor++;actual.add(next);
    }
    public void requireComplete() {
        if(!entered||cursor!=expected.size())throw refused("Native tick did not execute its exact prepared award sequence");
        firstLoop.revalidate();
        var progress=progressMap();
        if(progress.size()!=admitted.progress.size()||progress.size()>MAX_ADVANCEMENTS)
            throw refused("Native tick changed progress-map membership or exceeded its bound");
        for(var entry:progress.entrySet()) {
            if(admitted.loaded.get(entry.getKey().id())!=entry.getKey()
                    ||!admitted.progress.containsKey(entry.getKey())
                    ||entry.getValue()!=admitted.progress.get(entry.getKey()).identity)
                throw refused("Native tick replaced a loaded progress holder/value identity");
        }
        for(var entry:expectedCompleted.entrySet()) {
            var current=progress.get(entry.getKey());
            if(current==null||current!=admitted.progress.get(entry.getKey()).identity)throw refused("Native tick replaced its advancement progress owner");
            var currentCriteria=criteriaMap(current,entry.getKey());
            var completed=new HashSet<String>();
            for(var criterion:currentCriteria.entrySet()) {
                if(criterion.getValue()!=admitted.progress.get(entry.getKey()).criteria.get(criterion.getKey()))
                    throw refused("Native tick replaced a criterion-progress identity");
                if(criterion.getValue().isDone())completed.add(criterion.getKey());
            }
            if(!completed.equals(entry.getValue()))throw refused("Native tick final progress differs from detached prediction");
            for(var old:admitted.progress.get(entry.getKey()).completed.entrySet()) {
                var criterion=current.getCriterion(old.getKey());
                if(criterion==null||criterion.getClass()!=net.minecraft.advancements.CriterionProgress.class
                        ||!old.getValue().equals(criterion.getObtained()))
                    throw refused("Native tick changed a preexisting criterion timestamp");
            }
        }
        if(!bookSet("known").equals(expectedKnown)||!bookSet("highlight").equals(expectedHighlight)
                ||!canonicalBook(player.getRecipeBook().toNbt()).equals(expectedBook))
            throw refused("Native tick final recipe/highlight/settings NBT differs from detached prediction");
    }
    public List<Award> actualAwards() { return List.copyOf(actual); }
    public List<Award> expectedAwards() { return expected; }

    private Simulation simulate(Snapshot snapshot) {
        Work work=new Work();work.spend(snapshot.progress.size()+MAX_CRITERIA);
        Map<AdvancementHolder,Set<String>> done=new HashMap<>();
        snapshot.progress.forEach((holder,progress)->done.put(holder,new HashSet<>(progress.completed.keySet())));
        Set<ResourceLocation> known=new HashSet<>(snapshot.known),highlight=new HashSet<>(snapshot.highlight);
        List<Award> result=new ArrayList<>();
        // Native matching is read-only only after complete concrete predicate/cache inspection.
        Map<Listener,Boolean> matches=new HashMap<>();
        for(Listener listener:snapshot.listeners) {
            work.spend(1);
            if(listener.family==CriteriaTriggers.TICK||listener.family==CriteriaTriggers.LOCATION) {
                var instance=(PlayerTrigger.TriggerInstance)listener.identity.trigger();
                if(instance.player().isPresent())work.spend(snapshot.predicateData.size()+64L);
                matches.put(listener,instance.player().isEmpty()||instance.player().get().matches(EntityPredicate.createContext(player,player)));
            }
        }
        if(includeTick)simulateTrigger(CriteriaTriggers.TICK,null,snapshot,done,known,highlight,matches,result,work);
        if(player.tickCount%20==0)simulateTrigger(CriteriaTriggers.LOCATION,null,snapshot,done,known,highlight,matches,result,work);
        var frozen=new HashMap<AdvancementHolder,Set<String>>();done.forEach((holder,completed)->frozen.put(holder,Set.copyOf(completed)));
        return new Simulation(List.copyOf(result),Map.copyOf(frozen),Set.copyOf(known),Set.copyOf(highlight));
    }
    private void simulateTrigger(SimpleCriterionTrigger<?> family,ResourceLocation recipe,Snapshot snapshot,
            Map<AdvancementHolder,Set<String>> done,Set<ResourceLocation> known,Set<ResourceLocation> highlight,
            Map<Listener,Boolean> matches,List<Award> result,Work work) {
        var selected=new ArrayList<Listener>();
        for(Listener listener:snapshot.listeners) {
            work.spend(1);
            if(listener.family!=family)continue;
            var entry=listener.identity;var holder=entry.advancement();var completed=done.get(holder);
            if(completed==null||completed.contains(entry.criterion())||requirementsMatch(holder,completed,snapshot,work))continue;
            boolean match=family==CriteriaTriggers.RECIPE_UNLOCKED
                    ? ((RecipeUnlockedTrigger.TriggerInstance)entry.trigger()).recipe().equals(recipe)
                    : Boolean.TRUE.equals(matches.get(listener));
            if(match)selected.add(listener);
        }
        // SimpleCriterionTrigger builds a list before running it. An earlier award may remove a
        // later listener; the already-collected callback still runs, including a genuine no-op.
        for(Listener listener:selected) {
            work.spend(1);
            var entry=listener.identity;var holder=entry.advancement();var completed=done.get(holder);
            boolean wasDone=requirementsMatch(holder,completed,snapshot,work);
            boolean newGrant=completed.add(entry.criterion());
            if(result.size()>=MAX_AWARDS)throw refused("Native player-tick award bound exceeded");
            result.add(new Award(holder,entry.criterion(),newGrant));
            if(!newGrant||wasDone||!requirementsMatch(holder,completed,snapshot,work))continue;
            var reward=holder.value().rewards();
            if(reward.experience()!=0||!reward.loot().isEmpty()||reward.function().isPresent())
                throw refused("Native tick would complete unsupported conserved/function reward before entry: "+holder.id());
            if(holder.value().display().isPresent()&&holder.value().display().get().shouldAnnounceChat()
                    &&player.level().getGameRules().getBoolean(GameRules.RULE_ANNOUNCE_ADVANCEMENTS))
                throw refused("Native tick would announce unadmitted advancement chat before entry: "+holder.id());
            if(reward.recipes().size()>128)throw refused("Native tick recipe reward bound exceeded");
            for(ResourceLocation id:reward.recipes()) {
                var recipeHolder=player.server.getRecipeManager().byKey(id)
                        .orElseThrow(()->refused("Missing native tick reward recipe: "+id));
                firstLoop.requireUnlockRecipe(recipeHolder);
                if(known.add(id)) {
                    highlight.add(id);
                    simulateTrigger(CriteriaTriggers.RECIPE_UNLOCKED,id,snapshot,done,known,highlight,matches,result,work);
                }
            }
        }
    }
    private static boolean requirementsMatch(AdvancementHolder holder,Set<String> completed,Snapshot snapshot,Work work) {
        Integer cost=snapshot.requirementCosts.get(holder);
        if(cost==null)throw refused("Missing captured native requirement cost");
        work.spend(cost);
        return holder.value().requirements().test(completed::contains);
    }
    private Snapshot snapshot() {
        firstLoop.revalidate();
        Work work=new Work();
        var requirementCosts=new HashMap<AdvancementHolder,Integer>();
        var loaded=new LinkedHashMap<ResourceLocation,AdvancementHolder>();
        var progress=new LinkedHashMap<AdvancementHolder,Progress>();
        var predicateData=new ArrayList<Object>();
        var original=progressMap();
        if(original.size()>MAX_ADVANCEMENTS)throw refused("Native tick progress map exceeds bound");
        int criteria=0,requirementGroups=0,requirementTerms=0;
        for(AdvancementHolder holder:player.server.getAdvancements().getAllAdvancements()) {
            work.spend(1);
            if(loaded.size()>=MAX_ADVANCEMENTS||loaded.put(holder.id(),holder)!=null)throw refused("Loaded tick advancement bound/identity failure");
            if(!trustedMap(holder.value().criteria()))throw refused("Unrecognized loaded native criteria map");
            if(holder.value().criteria().size()>MAX_CRITERIA-criteria)throw refused("Loaded tick criterion bound exceeded");
            criteria+=holder.value().criteria().size();
            var requirements=holder.value().requirements().requirements();
            if(!trustedList(requirements)||requirements.size()>MAX_CRITERIA-requirementGroups)
                throw refused("Loaded tick global requirement-group bound/implementation exceeded");
            requirementGroups+=requirements.size();
            int requirementCost=1+requirements.size();
            work.spend(requirements.size());
            var requiredNames=new HashSet<String>();var requirementCopy=new ArrayList<List<String>>();
            for(var group:requirements) {
                if(!trustedList(group)||group.size()>MAX_CRITERIA-requirementTerms)
                    throw refused("Loaded tick global OR-term bound/implementation exceeded");
                requirementTerms+=group.size();requirementCost+=group.size();
                work.spend(group.size());
                requiredNames.addAll(group);requirementCopy.add(List.copyOf(group));
            }
            if(!requiredNames.equals(holder.value().criteria().keySet()))throw refused("Loaded native requirements/criteria differ");
            predicateData.add(List.copyOf(requirementCopy));
            requirementCosts.put(holder,requirementCost);
            AdvancementProgress nativeProgress=original.get(holder);
            if(nativeProgress==null||nativeProgress.getClass()!=AdvancementProgress.class
                    ||field(AdvancementProgress.class,"requirements",nativeProgress)!=holder.value().requirements())
                throw refused("Exact initialized native progress/requirements required for "+holder.id());
            var nativeCriteria=criteriaMap(nativeProgress,holder);
            var completed=new HashMap<String,java.time.Instant>();
            for(String name:holder.value().criteria().keySet()) {
                var criterion=nativeProgress.getCriterion(name);
                if(criterion==null||criterion.getClass()!=net.minecraft.advancements.CriterionProgress.class)
                    throw refused("Native progress criterion missing or replaced");
                if(criterion.isDone())completed.put(name,Objects.requireNonNull(criterion.getObtained()));
            }
            work.spend(requirementCost*2L);
            if(nativeProgress.isDone()!=holder.value().requirements().test(completed::containsKey))throw refused("Native progress requirements differ from loaded definition");
            progress.put(holder,new Progress(nativeProgress,Map.copyOf(nativeCriteria),Map.copyOf(completed)));
        }
        if(original.size()!=progress.size())throw refused("Native progress has foreign or duplicate loaded holders");
        var listeners=new ArrayList<Listener>();
        for(SimpleCriterionTrigger<?> family:List.of(CriteriaTriggers.TICK,CriteriaTriggers.LOCATION,CriteriaTriggers.RECIPE_UNLOCKED)) {
            if(family.getClass()!=(family==CriteriaTriggers.RECIPE_UNLOCKED?RecipeUnlockedTrigger.class:PlayerTrigger.class))
                throw refused("Custom player-tick trigger family");
            Object raw=field(SimpleCriterionTrigger.class,"players",family);
            if(raw.getClass()!=java.util.IdentityHashMap.class||!(raw instanceof Map<?,?> map))throw refused("Native trigger listener map unavailable");
            Object registered=map.get(owner);if(registered==null)continue;
            if(registered.getClass()!=HashSet.class||!(registered instanceof Set<?> set)||set.size()>MAX_CRITERIA-listeners.size())throw refused("Native tick listener bound exceeded");
            for(Object value:set) {
                work.spend(1);
                if(!(value instanceof CriterionTrigger.Listener<?> entry))throw refused("Unrecognized tick criterion listener");
                var holder=loaded.get(entry.advancement().id());
                if(holder!=entry.advancement())throw refused("Tick listener is not the exact loaded advancement");
                Criterion<?> criterion=holder.value().criteria().get(entry.criterion());
                if(criterion==null||criterion.trigger()!=family||criterion.triggerInstance()!=entry.trigger())throw refused("Tick listener differs from native loaded criterion");
                if(family==CriteriaTriggers.RECIPE_UNLOCKED) {
                    if(entry.trigger().getClass()!=RecipeUnlockedTrigger.TriggerInstance.class
                            ||((RecipeUnlockedTrigger.TriggerInstance)entry.trigger()).player().isPresent())throw refused("Custom unlocked predicate in tick closure");
                } else {
                    if(entry.trigger().getClass()!=PlayerTrigger.TriggerInstance.class)throw refused("Custom native player trigger instance");
                    var instance=(PlayerTrigger.TriggerInstance)entry.trigger();
                    if(family==CriteriaTriggers.TICK&&instance.player().isPresent())throw refused("Conditional TICK predicate is outside first dry profile");
                    instance.player().ifPresent(context->inspectContext(context,predicateData,work));
                }
                listeners.add(new Listener(family,entry));
            }
        }
        return new Snapshot(Map.copyOf(loaded),Map.copyOf(progress),List.copyOf(listeners),bookSet("known"),bookSet("highlight"),
                canonicalBook(player.getRecipeBook().toNbt()),List.copyOf(predicateData),Map.copyOf(requirementCosts));
    }
    @SuppressWarnings("unchecked") private static Map<String,net.minecraft.advancements.CriterionProgress> criteriaMap(
            AdvancementProgress progress,AdvancementHolder holder) {
        Object raw=field(AdvancementProgress.class,"criteria",progress);
        if(raw.getClass()!=HashMap.class)throw refused("Unrecognized native criterion-progress map implementation");
        Map<?,?> map=(Map<?,?>)raw;
        if(map.size()!=holder.value().criteria().size()||map.size()>MAX_CRITERIA)
            throw refused("Native criterion-progress map membership/bound differs from loaded criteria");
        for(var entry:map.entrySet()) {
            if(!(entry.getKey() instanceof String name)||!holder.value().criteria().containsKey(name)
                    ||entry.getValue()==null||entry.getValue().getClass()!=net.minecraft.advancements.CriterionProgress.class)
                throw refused("Custom or foreign native criterion-progress map entry");
        }
        return (Map<String,net.minecraft.advancements.CriterionProgress>)raw;
    }
    @SuppressWarnings("unchecked") private Map<AdvancementHolder,AdvancementProgress> progressMap() {
        Object raw=field(PlayerAdvancements.class,"progress",owner);
        if(raw.getClass()!=LinkedHashMap.class)throw refused("Unrecognized native progress map implementation");
        return (Map<AdvancementHolder,AdvancementProgress>)raw;
    }
    private Set<ResourceLocation> bookSet(String name) {
        Object raw=field(RecipeBook.class,name,player.getRecipeBook());
        if(raw.getClass()!=HashSet.class||((Set<?>)raw).size()>4096)throw refused("Unrecognized or unbounded native recipe book");
        var result=new HashSet<ResourceLocation>();
        for(Object value:(Set<?>)raw) {if(!(value instanceof ResourceLocation id))throw refused("Unknown native recipe-book entry");result.add(id);}
        return Set.copyOf(result);
    }
    private static net.minecraft.nbt.ListTag recipeList(Set<ResourceLocation> recipes) {
        var result=new net.minecraft.nbt.ListTag();
        recipes.stream().map(ResourceLocation::toString).sorted().forEach(value->result.add(net.minecraft.nbt.StringTag.valueOf(value)));
        return result;
    }
    private static net.minecraft.nbt.CompoundTag canonicalBook(net.minecraft.nbt.CompoundTag original) {
        var result=original.copy();
        for(String key:List.of("recipes","toBeDisplayed")) {
            var list=result.getList(key,net.minecraft.nbt.Tag.TAG_STRING);
            if(list.size()>4096)throw refused("Native recipe-book NBT exceeds bound");
            var ids=new HashSet<ResourceLocation>();
            for(int i=0;i<list.size();i++)if(!ids.add(ResourceLocation.parse(list.getString(i))))
                throw refused("Duplicate native recipe-book NBT entry");
            result.put(key,recipeList(ids));
        }
        return result;
    }
    private static boolean sameSnapshot(Snapshot before,Snapshot after) {
        if(!before.equals(after))return false;
        for(var entry:before.loaded.entrySet())if(after.loaded.get(entry.getKey())!=entry.getValue())return false;
        for(int i=0;i<before.listeners.size();i++)if(before.listeners.get(i).identity!=after.listeners.get(i).identity)return false;
        return true;
    }
    private void inspectContext(ContextAwarePredicate context,List<Object> data,Work work) {
        if(context.getClass()!=ContextAwarePredicate.class)throw refused("Custom tick context predicate");
        Object raw=field(ContextAwarePredicate.class,"conditions",context);
        if(!(raw instanceof List<?> conditions)||!trustedList(conditions)||conditions.size()!=1)
            throw refused("Only one exact native entity condition is admitted for LOCATION");
        Object condition=conditions.getFirst();
        // Pinned Util.allOf(size=1) returns this exact condition. No opaque lambda is trusted.
        if(field(ContextAwarePredicate.class,"compositePredicates",context)!=condition
                ||condition.getClass()!=LootItemEntityPropertyCondition.class)throw refused("LOCATION cached predicate is not its inspected native condition");
        var property=(LootItemEntityPropertyCondition)condition;
        if(property.entityTarget()!=LootContext.EntityTarget.THIS||property.predicate().isEmpty())throw refused("LOCATION must inspect the exact native player");
        append(data,new Identity(context));append(data,new Identity(condition));inspectEntity(property.predicate().get(),data,work);
    }
    private void inspectEntity(EntityPredicate entity,List<Object> data,Work work) {
        if(entity.entityType().isPresent()||entity.distanceToPlayer().isPresent()||entity.movement().isPresent()
                ||entity.effects().isPresent()||entity.nbt().isPresent()||entity.flags().isPresent()
                ||entity.periodicTick().isPresent()||entity.vehicle().isPresent()
                ||entity.passenger().isPresent()||entity.targetedEntity().isPresent()||entity.team().isPresent()
                ||entity.slots().isPresent())throw refused("LOCATION native entity predicate exceeds audited dry graph");
        // Official NeoForge overlay uses this one native predicate instead of leather-boots
        // equipment matching. The diagnostic already requires empty inventory; close this
        // exact callback path explicitly rather than admitting arbitrary subpredicates.
        entity.subPredicate().ifPresent(predicate -> {
            var feet=player.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.FEET);
            if(predicate!=net.neoforged.neoforge.common.advancements.critereon.SnowBootsEntityPredicate.INSTANCE
                    ||predicate.getClass()!=net.neoforged.neoforge.common.advancements.critereon.SnowBootsEntityPredicate.class
                    ||feet.getClass()!=net.minecraft.world.item.ItemStack.class||!feet.isEmpty()
                    ||feet.getItem()!=net.minecraft.world.item.Items.AIR
                    ||feet.getItem().getClass()!=net.minecraft.world.item.AirItem.class)
                throw refused("Unsupported LOCATION subpredicate or nonempty native snow-boots feet");
            try {
                if(net.minecraft.world.item.AirItem.class.getMethod("canWalkOnPowderedSnow",
                        net.minecraft.world.item.ItemStack.class,net.minecraft.world.entity.LivingEntity.class).getDeclaringClass()
                        !=net.neoforged.neoforge.common.extensions.IItemExtension.class
                        ||net.minecraft.world.item.ItemStack.class.getMethod("canWalkOnPowderedSnow",
                        net.minecraft.world.entity.LivingEntity.class).getDeclaringClass()
                        !=net.neoforged.neoforge.common.extensions.IItemStackExtension.class)
                    throw refused("Native snow-boots callback method ownership changed");
            } catch(ReflectiveOperationException failure) { throw refused("Native snow-boots callback descriptor unavailable"); }
            append(data,new Identity(predicate));
        });
        append(data,new Identity(entity));
        entity.location().located().ifPresent(p->inspectLocation(p,data,work));
        entity.location().steppingOn().ifPresent(p->inspectLocation(p,data,work));
        if(entity.location().affectsMovement().isPresent())throw refused("Movement-affected LOCATION predicate is outside dry profile");
        entity.equipment().ifPresent(e->{
            for(var item:List.of(e.head(),e.chest(),e.legs(),e.feet(),e.body(),e.mainhand(),e.offhand()))item.ifPresent(p->inspectItem(p,data,work));
        });
    }
    private void inspectLocation(LocationPredicate location,List<Object> data,Work work) {
        if(location.position().isPresent()||location.smokey().isPresent()||location.light().isPresent()
                ||location.fluid().isPresent()||location.canSeeSky().isPresent())throw refused("LOCATION predicate needs unsupported environment semantics");
        append(data,new Identity(location));
        location.biomes().ifPresent(set->inspectHolders(set,player.registryAccess().registryOrThrow(Registries.BIOME),data,work));
        location.structures().ifPresent(set->inspectHolders(set,player.registryAccess().registryOrThrow(Registries.STRUCTURE),data,work));
        location.block().ifPresent(block->{
            if(block.nbt().isPresent()||block.properties().isPresent())throw refused("LOCATION block predicate exceeds exact holder-only graph");
            block.blocks().ifPresent(set->inspectHolders(set,net.minecraft.core.registries.BuiltInRegistries.BLOCK,data,work));
        });
    }
    private void inspectItem(ItemPredicate item,List<Object> data,Work work) {
        if(item.components()!=net.minecraft.core.component.DataComponentPredicate.EMPTY
                ||!trustedMap(item.subPredicates())||!item.subPredicates().isEmpty())
            throw refused("LOCATION item predicate needs unsupported component/subpredicate semantics");
        append(data,new Identity(item));
        item.items().ifPresent(set->inspectHolders(set,net.minecraft.core.registries.BuiltInRegistries.ITEM,data,work));
    }
    private <T> void inspectHolders(HolderSet<T> set,Registry<T> registry,List<Object> data,Work work) {
        if(set.getClass()!=HolderSet.Direct.class&&set.getClass()!=HolderSet.Named.class)throw refused("Custom LOCATION holder set");
        if(registry.getClass()!=net.minecraft.core.MappedRegistry.class&&registry.getClass()!=net.minecraft.core.DefaultedMappedRegistry.class)
            throw refused("Custom LOCATION registry implementation");
        Object contents=field(set.getClass(),"contents",set);
        if(!(contents instanceof List<?> list)||!trustedList(list)||list.size()>4096||data.size()>65536)
            throw refused("LOCATION holder contents/budget exceeded");
        work.spend(list.size()*3L+1);
        var members=new HashSet<ResourceLocation>();
        for(Holder<T> holder:set) {
            if(holder.getClass()!=Holder.Reference.class)throw refused("Direct/unkeyed LOCATION holder");
            var key=holder.unwrapKey().orElseThrow(()->refused("Unkeyed LOCATION holder"));
            if(registry.getHolder(key).orElse(null)!=holder)throw refused("Foreign LOCATION registry holder");
            append(data,new Identity(holder));members.add(key.location());
        }
        if(set instanceof HolderSet.Direct<T>) {
            Object cached=field(HolderSet.Direct.class,"contentsSet",set);
            // Native lazy Set.copyOf initialization is deterministic. If already initialized,
            // prove the exact trusted immutable cache agrees with the inspected list before use.
            if(cached!=null) {
                if(!(cached instanceof Set<?> cache)||!trustedImmutableSet(cache)||cache.size()!=members.size())
                    throw refused("Unrecognized LOCATION direct-holder cache");
                work.spend(list.size()+cache.size());
                var exact=java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<Object,Boolean>());
                exact.addAll(list);
                for(Object cachedHolder:cache)if(!exact.contains(cachedHolder))
                    throw refused("LOCATION direct-holder cached member is not its exact loaded object");
            }
        }
        if(set instanceof HolderSet.Named<T> named) {
            // Native Named.contains uses holder tag membership; its backing list must agree.
            if(registry.size()>4096)throw refused("LOCATION registry membership audit bound exceeded");
            work.spend(registry.size());
            for(var holder:registry.holders().toList()) {
                if(holder.getClass()!=Holder.Reference.class)throw refused("Custom LOCATION registry reference implementation");
                Object tags=field(Holder.Reference.class,"tags",holder);
                if(!(tags instanceof Set<?> tagSet)||!trustedImmutableSet(tagSet)||tagSet.size()>4096)
                    throw refused("Unrecognized LOCATION registry-holder tag membership implementation");
                work.spend(tagSet.size()+1L);
                boolean member=holder.is(named.key());
                if(member!=members.contains(holder.key().location()))throw refused("LOCATION tag membership disagrees with loaded holder contents");
                append(data,new Identity(holder));append(data,member);
            }
        }
        append(data,Set.copyOf(members));
    }
    private static void append(List<Object> data,Object value) {
        if(data.size()>=65536)throw refused("LOCATION captured predicate-data bound exceeded");
        data.add(value);
    }
    private static boolean trustedImmutableSet(Set<?> set) {
        String name=set.getClass().getName();
        if(set.getClass().getClassLoader()!=null)return false;
        return name.equals("java.util.ImmutableCollections$Set12")||name.equals("java.util.ImmutableCollections$SetN");
    }
    private static boolean trustedMap(Map<?,?> map) {
        String name=map.getClass().getName();
        if(map.getClass().getClassLoader()!=(name.startsWith("java.")?null:com.google.common.collect.ImmutableMap.class.getClassLoader()))return false;
        return name.equals("java.util.HashMap")||name.equals("java.util.LinkedHashMap")
                ||name.equals("java.util.ImmutableCollections$Map1")||name.equals("java.util.ImmutableCollections$MapN")
                ||name.equals("com.google.common.collect.RegularImmutableMap")||name.equals("com.google.common.collect.SingletonImmutableBiMap");
    }
    private static boolean trustedList(List<?> list) {
        String name=list.getClass().getName();
        if(list.getClass().getClassLoader()!=(name.startsWith("java.")?null:com.google.common.collect.ImmutableList.class.getClassLoader()))return false;
        return name.equals("java.util.ArrayList")||name.equals("java.util.ImmutableCollections$List12")
                ||name.equals("java.util.ImmutableCollections$ListN")||name.equals("java.util.Collections$SingletonList")
                ||name.equals("com.google.common.collect.SingletonImmutableList")||name.equals("com.google.common.collect.RegularImmutableList");
    }
    private static Object field(Class<?> type,String name,Object receiver) {
        try {Field field=type.getDeclaredField(name);if(!field.trySetAccessible())throw refused("Native tick reflection unavailable: "+name);return field.get(receiver);}
        catch(ReflectiveOperationException|SecurityException failure){var refusal=refused("Cannot inspect native tick field: "+name);refusal.initCause(failure);throw refusal;}
    }
    private static SurvivalRefusal refused(String detail) { return new SurvivalRefusal(detail); }
}
