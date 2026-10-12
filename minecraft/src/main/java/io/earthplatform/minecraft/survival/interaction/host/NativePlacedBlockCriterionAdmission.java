package io.earthplatform.minecraft.survival.interaction.host;

import io.earthplatform.minecraft.survival.interaction.NativeStackSnapshot;
import io.earthplatform.minecraft.survival.interaction.NativeVanillaPreparation;
import io.earthplatform.minecraft.survival.interaction.NativeWholeAction;
import io.earthplatform.minecraft.survival.world.CubeCell;
import io.earthplatform.minecraft.survival.world.SurvivalRefusal;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.advancements.CriteriaTriggers;
import net.minecraft.advancements.Criterion;
import net.minecraft.advancements.CriterionTrigger;
import net.minecraft.advancements.critereon.ContextAwarePredicate;
import net.minecraft.advancements.critereon.ItemUsedOnLocationTrigger;
import net.minecraft.advancements.critereon.SimpleCriterionTrigger;
import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.storage.loot.LootContext;
import net.minecraft.world.level.storage.loot.predicates.AllOfCondition;
import net.minecraft.world.level.storage.loot.predicates.AnyOfCondition;
import net.minecraft.world.level.storage.loot.predicates.CompositeLootItemCondition;
import net.minecraft.world.level.storage.loot.predicates.LootItemBlockStatePropertyCondition;
import net.minecraft.world.level.storage.loot.predicates.LootItemCondition;

/** Test-only source-closed proof that the genuine table PLACED_BLOCK trigger cannot match. */
public final class NativePlacedBlockCriterionAdmission {
    public static final String WITNESS_PROPERTY="earthplatform.gametest.placedBlockWitness";
    private static final WitnessBook WITNESSES=new WitnessBook(Boolean.getBoolean(WITNESS_PROPERTY),8192,65536,4*1024*1024);
    private static final StackWalker CONSTRUCTOR_CALLER=StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE);
    private record Key(ResourceLocation advancement,String criterion) {}
    private record Loaded(AdvancementHolder holder,Criterion<?> criterion) {}
    private final ServerPlayer player;
    private final NativeWholeAction action;
    private final CubeCell target;
    private final NativeStackSnapshot held;
    private final Map<Key,Loaded> loaded;
    private boolean invoked;
    private NativePlacedBlockCriterionAdmission(ServerPlayer player,NativeWholeAction action,CubeCell target,ItemStack held) {
        this.player=player;this.action=action;this.target=target;
        if (player==null || action==null || target==null || player.getClass()!=ServerPlayer.class || !player.server.isSameThread()
                || NativeWholeAction.current()!=action || action.state()!=NativeWholeAction.State.PREPARING
                || !action.identity().actor().equals(player.getUUID()))
            throw refused("Placed-block admission requires its exact native preparing actor before inspection");
        if (held==null || held.getClass()!=ItemStack.class || !held.is(net.minecraft.world.item.Items.CRAFTING_TABLE)
                || !held.getComponentsPatch().isEmpty()) throw refused("Placed-block first profile requires an unpatched earned native table stack");
        if (!(player.server instanceof net.minecraft.gametest.framework.GameTestServer) || !WITNESSES.enabled)
            throw refused("Placed-block constructor provenance is unavailable outside its enabled isolated GameTest process");
        this.held=NativeStackSnapshot.capture(held,player.registryAccess()); loaded=scan(); requirePreparing();
    }
    static NativePlacedBlockCriterionAdmission capture(ServerPlayer player,NativeWholeAction action,CubeCell target,ItemStack held) {
        return new NativePlacedBlockCriterionAdmission(player,action,target,held);
    }
    int criterionCount() { return loaded.size(); }
    void invokeNoEffect(ItemStack exactHeld) {
        requirePreparing();
        if (invoked || !held.matches(exactHeld,player.registryAccess())) throw refused("Placed-block no-effect callback is repeated or has a foreign item");
        invoked=true;
        var before=progressImage();
        CriteriaTriggers.PLACED_BLOCK.trigger(player,NativeVanillaPreparation.nativePos(target),exactHeld);
        requirePreparing();
        if (!held.matches(exactHeld,player.registryAccess()) || !before.equals(progressImage()))
            throw refused("Guaranteed-reject native placed-block callback unexpectedly changed progress or held item");
    }
    private void requirePreparing() {
        if (!player.server.isSameThread() || player.getClass()!=ServerPlayer.class || NativeWholeAction.current()!=action
                || action.state()!=NativeWholeAction.State.PREPARING || !action.identity().actor().equals(player.getUUID())
                || !action.read(target).is(Blocks.CRAFTING_TABLE)) throw refused("Placed-block no-effect proof lacks its exact read-your-writes table action");
        Map<Key,Loaded> now=scan();
        if (!now.keySet().equals(loaded.keySet())) throw refused("Loaded placed-block criterion domain changed");
        for (var entry:loaded.entrySet()) {
            Loaded current=now.get(entry.getKey()),old=entry.getValue();
            if (current.holder()!=old.holder() || current.criterion()!=old.criterion()) throw refused("Loaded placed-block criterion instance changed");
        }
        requireListeners();
    }
    private Map<Key,Loaded> scan() {
        if (CriteriaTriggers.PLACED_BLOCK.getClass()!=ItemUsedOnLocationTrigger.class) throw refused("Custom placed-block trigger implementation");
        var all=player.server.getAdvancements().getAllAdvancements();
        if (all.size()>2048) throw refused("Placed-block loaded advancement census exceeds its bound");
        var result=new HashMap<Key,Loaded>(); int criteria=0;
        for (var holder:all) {
            if (holder.value().criteria().size()>256) throw refused("Placed-block per-advancement criterion census exceeds its bound");
            if (holder.value().criteria().size()>8192-criteria) throw refused("Placed-block aggregate loaded criterion bound exceeded");
            criteria+=holder.value().criteria().size();
            for (var entry:holder.value().criteria().entrySet()) {
                Criterion<?> criterion=entry.getValue(); if (criterion.trigger()!=CriteriaTriggers.PLACED_BLOCK) continue;
                if (result.size()>=64 || criterion.triggerInstance().getClass()!=ItemUsedOnLocationTrigger.TriggerInstance.class)
                    throw refused("Placed-block criterion type/count is outside the no-effect profile");
                var trigger=(ItemUsedOnLocationTrigger.TriggerInstance)criterion.triggerInstance();
                if (trigger.player().isPresent() || trigger.location().isEmpty()) throw refused("Placed-block predicate cannot be proved to reject before player callbacks");
                requireContextRejects(trigger.location().get());
                result.put(new Key(holder.id(),entry.getKey()),new Loaded(holder,criterion));
            }
        }
        return Map.copyOf(result);
    }
    private void requireListeners() {
        var map=NativeInventoryMenuSnapshot.read(SimpleCriterionTrigger.class,CriteriaTriggers.PLACED_BLOCK,"players",Map.class);
        if (map.getClass()!=java.util.IdentityHashMap.class) throw refused("Placed-block listener map is not native identity storage");
        Object raw=map.get(player.getAdvancements()); if (raw==null) return;
        if (raw.getClass()!=java.util.HashSet.class || !(raw instanceof Set<?> listeners) || listeners.size()>64)
            throw refused("Placed-block listener roster exceeds its exact native bound");
        for (Object value:listeners) {
            if (value.getClass()!=CriterionTrigger.Listener.class) throw refused("Custom placed-block listener implementation");
            var listener=(CriterionTrigger.Listener<?>)value;
            Loaded known=loaded.get(new Key(listener.advancement().id(),listener.criterion()));
            if (known==null || known.holder()!=listener.advancement() || known.criterion().triggerInstance()!=listener.trigger())
                throw refused("Placed-block listener does not belong to the exact loaded rejecting graph");
        }
    }
    private Map<ResourceLocation,Map<String,java.util.Optional<java.time.Instant>>> progressImage() {
        var values=NativeInventoryMenuSnapshot.read(net.minecraft.server.PlayerAdvancements.class,player.getAdvancements(),"progress",Map.class);
        if (values.getClass()!=java.util.LinkedHashMap.class || values.size()>2048) throw refused("Native placed-block progress census/type exceeds its bound");
        var result=new HashMap<ResourceLocation,Map<String,java.util.Optional<java.time.Instant>>>(); int totalNames=0;
        for (var raw:values.entrySet()) {
            var entry=(Map.Entry<?,?>)raw;
            if (!(entry.getKey() instanceof AdvancementHolder holder) || entry.getValue().getClass()!=net.minecraft.advancements.AdvancementProgress.class)
                throw refused("Custom native advancement progress entry");
            var criteria=NativeInventoryMenuSnapshot.read(net.minecraft.advancements.AdvancementProgress.class,entry.getValue(),"criteria",Map.class);
            if (criteria.getClass()!=java.util.HashMap.class || criteria.size()>256 || criteria.size()>8192-totalNames)
                throw refused("Native progress criterion census/type exceeds its aggregate bound");
            totalNames+=criteria.size(); // Charge before any native completed-name list or retained map allocation.
            var image=new java.util.HashMap<String,java.util.Optional<java.time.Instant>>();
            for (var rawCriterion:criteria.entrySet()) {
                var value=(Map.Entry<?,?>)rawCriterion;
                if (!(value.getKey() instanceof String name) || name.length()>256
                        || value.getValue()==null || value.getValue().getClass()!=net.minecraft.advancements.CriterionProgress.class)
                    throw refused("Native criterion progress name/type is outside the bounded profile");
                var criterion=(net.minecraft.advancements.CriterionProgress)value.getValue();
                image.put(name,java.util.Optional.ofNullable(criterion.getObtained()));
            }
            if (result.put(holder.id(),Map.copyOf(image))!=null) throw refused("Native progress contains duplicate advancement identities");
        }
        return Map.copyOf(result);
    }
    private static void requireContextRejects(ContextAwarePredicate predicate) {
        if (predicate.getClass()!=ContextAwarePredicate.class) throw refused("Custom placed-block context predicate");
        List<?> conditions=NativeInventoryMenuSnapshot.read(ContextAwarePredicate.class,predicate,"conditions",List.class);
        Object cache=NativeInventoryMenuSnapshot.read(ContextAwarePredicate.class,predicate,"compositePredicates",Predicate.class);
        WITNESSES.require(predicate,conditions,cache);
        if (conditions.isEmpty() || !rejectsWithoutEffects(conditions.getFirst(),new int[]{0},0))
            throw refused("Placed-block location predicate is not provably false for the native table");
    }
    private static boolean rejectsWithoutEffects(Object condition,int[] visited,int depth) {
        if (++visited[0]>128 || depth>8) throw refused("Placed-block rejecting predicate traversal exceeds its bound");
        if (condition.getClass()==LootItemBlockStatePropertyCondition.class) {
            var block=(LootItemBlockStatePropertyCondition)condition;
            if (block.block().getClass()!=Holder.Reference.class) throw refused("Custom placed-block holder implementation");
            // Native block-state test short-circuits before optional properties on a different holder.
            return block.block().value()!=Blocks.CRAFTING_TABLE;
        }
        if (condition.getClass()==AllOfCondition.class || condition.getClass()==AnyOfCondition.class) {
            List<?> terms=NativeInventoryMenuSnapshot.read(CompositeLootItemCondition.class,condition,"terms",List.class);
            Object cache=NativeInventoryMenuSnapshot.read(CompositeLootItemCondition.class,condition,"composedPredicate",Predicate.class);
            WITNESSES.require(condition,terms,cache);
            if (condition.getClass()==AllOfCondition.class)
                return !terms.isEmpty() && rejectsWithoutEffects(terms.getFirst(),visited,depth+1);
            for (Object term:terms) if (!rejectsWithoutEffects(term,visited,depth+1)) return false;
            return true;
        }
        // No test() invocation is used to decide whether a custom/reachable callback is safe.
        return false;
    }
    public record WitnessUsage(boolean enabled,int owners,int edges,int bytes,boolean overflow) {}
    public static WitnessUsage witnessUsage() { return WITNESSES.usage(); }
    /** Called only by the exact injected constructor-return site, before native resource load. */
    public static void recordContext(ContextAwarePredicate owner,List<LootItemCondition> conditions,Predicate<LootContext> cache) {
        if (!WITNESSES.enabled) return;
        if (owner.getClass()==ContextAwarePredicate.class && constructorCaller(ContextAwarePredicate.class)) WITNESSES.record(owner,conditions,cache);
    }
    public static void recordComposite(CompositeLootItemCondition owner,List<LootItemCondition> terms,Predicate<LootContext> cache) {
        if (!WITNESSES.enabled) return;
        if ((owner.getClass()==AllOfCondition.class || owner.getClass()==AnyOfCondition.class)
                && constructorCaller(CompositeLootItemCondition.class)) WITNESSES.record(owner,terms,cache);
    }
    private static boolean constructorCaller(Class<?> expected) {
        // Frames: this helper, record method, injected method on native class, native constructor.
        return CONSTRUCTOR_CALLER.walk(stream->{ var frames=stream.skip(2).limit(2).toList();
            return frames.size()==2 && frames.get(0).getDeclaringClass()==expected
                    && frames.get(1).getDeclaringClass()==expected && frames.get(1).getMethodName().equals("<init>"); });
    }
    /** Weak values too: a cached closure/terms list can never keep its own weak owner alive. */
    static final class WitnessBook {
        final boolean enabled;
        private final int ownerLimit,edgeLimit,byteLimit;
        private final ReferenceQueue<Object> queue=new ReferenceQueue<>();
        private final Map<IdentityKey,Witness> entries=new HashMap<>();
        private int edges,bytes;
        private boolean overflow;
        private record Witness(WeakReference<Object> terms,List<WeakReference<Object>> children,WeakReference<Object> cache,int bytes) {}
        private static final class IdentityKey extends WeakReference<Object> {
            private final int hash;
            IdentityKey(Object value,ReferenceQueue<Object> queue) { super(value,queue); hash=System.identityHashCode(value); }
            public int hashCode() { return hash; }
            public boolean equals(Object other) { return this==other || other instanceof IdentityKey key && get()!=null && get()==key.get(); }
        }
        WitnessBook(boolean enabled,int ownerLimit,int edgeLimit,int byteLimit) {
            if (ownerLimit<1 || edgeLimit<1 || byteLimit<1) throw new IllegalArgumentException("Positive witness bounds required");
            this.enabled=enabled;this.ownerLimit=ownerLimit;this.edgeLimit=edgeLimit;this.byteLimit=byteLimit;
        }
        synchronized void record(Object owner,List<?> terms,Object cache) {
            drain(); if (!enabled || overflow || entries.containsKey(new IdentityKey(owner,null))) return;
            if (!trustedList(terms)) return; // No custom collection callback is invoked during inspection.
            int size=terms.size(); long charge=256L+64L*size;
            if (size>256 || entries.size()>=ownerLimit || size>edgeLimit-edges || charge>byteLimit-bytes) { overflow=true;return; }
            var children=new ArrayList<WeakReference<Object>>(size);
            for (Object child:terms) children.add(new WeakReference<>(child));
            var witness=new Witness(new WeakReference<>(terms),List.copyOf(children),new WeakReference<>(cache),(int)charge);
            entries.put(new IdentityKey(owner,queue),witness);edges+=size;bytes+=(int)charge;
        }
        synchronized void require(Object owner,List<?> terms,Object cache) {
            drain(); if (!enabled || overflow) throw refused("Native constructor witness owner is disabled or exhausted");
            Witness value=entries.get(new IdentityKey(owner,null));
            if (value==null || value.terms.get()!=terms || value.cache.get()!=cache || !trustedList(terms)
                    || terms.size()!=value.children.size()) throw refused("Native predicate cache lacks its exact constructor provenance: "+owner.getClass().getName()+" / "+terms.getClass().getName());
            for (int i=0;i<value.children.size();i++) if (value.children.get(i).get()!=terms.get(i))
                throw refused("Native predicate term order/identity changed after construction");
        }
        synchronized WitnessUsage usage() { drain();return new WitnessUsage(enabled,entries.size(),edges,bytes,overflow); }
        private void drain() {
            IdentityKey key;while ((key=(IdentityKey)queue.poll())!=null) {
                Witness value=entries.remove(key); if (value!=null) { edges-=value.children.size();bytes-=value.bytes; }
            }
        }
        private static boolean trustedList(List<?> list) {
            String name=list.getClass().getName();ClassLoader loader=list.getClass().getClassLoader();
            if (loader==null) return name.equals("java.util.ImmutableCollections$List12")
                    || name.equals("java.util.ImmutableCollections$ListN") || name.equals("java.util.Collections$EmptyList")
                    || name.equals("java.util.Collections$SingletonList");
            return loader==com.google.common.collect.ImmutableList.class.getClassLoader()
                    && (name.equals("com.google.common.collect.RegularImmutableList") || name.equals("com.google.common.collect.SingletonImmutableList"));
        }
    }
    private static SurvivalRefusal refused(String message) { return new SurvivalRefusal(message); }
}
