package io.earthplatform.minecraft.survival.interaction.host;

import io.earthplatform.minecraft.survival.world.SurvivalRefusal;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.stats.Stat;
import net.minecraft.stats.StatsCounter;
import net.minecraft.stats.ServerStatsCounter;

/** Complete bounded native statistic/dirty before-image, charged within the mining reservation. */
final class NativeMiningStatisticsImage {
    private static final int MAX_STATS=64;
    private final ServerPlayer player;
    private final ServerStatsCounter counter;
    private final Object wrapper;
    private final it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap<?> values;
    private final Set<?> dirty;
    private final Map<Stat<?>,Integer> before=new IdentityHashMap<>();
    private final Set<Stat<?>> dirtyBefore=Collections.newSetFromMap(new IdentityHashMap<>());
    NativeMiningStatisticsImage(ServerPlayer player) {
        this.player=player;NativeBlockBreakAdmission.requireStatistics(player);counter=player.getStats();
        wrapper=NativeInventoryMenuSnapshot.read(StatsCounter.class,counter,"stats",it.unimi.dsi.fastutil.objects.Object2IntMap.class);
        values=(it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap<?>)NativeInventoryMenuSnapshot.read(wrapper.getClass(),wrapper,"map",it.unimi.dsi.fastutil.objects.Object2IntMap.class);
        dirty=NativeInventoryMenuSnapshot.read(ServerStatsCounter.class,counter,"dirty",Set.class);
        if(values.size()>MAX_STATS||dirty.size()>MAX_STATS)throw refused("Native mining statistic before-image exceeds its bounded first profile");
        for(var entry:values.object2IntEntrySet())before.put(NativeBlockBreakAdmission.requireExactStatKey(entry.getKey()),entry.getIntValue());
        for(var key:dirty)dirtyBefore.add(NativeBlockBreakAdmission.requireExactStatKey(key));
    }
    void requireIncrementFits(Stat<?> target) {
        requireBefore();Math.addExact(before.getOrDefault(target,0),1);
        if(!before.containsKey(target)&&before.size()>=MAX_STATS||!dirtyBefore.contains(target)&&dirtyBefore.size()>=MAX_STATS)
            throw refused("Native mined statistic increment exceeds prepaid before/after image");
    }
    void requireBefore(){requireAfter(null);}
    void requireAfter(Stat<?> incremented) {
        NativeBlockBreakAdmission.requireStatistics(player);
        if(player.getStats()!=counter||NativeInventoryMenuSnapshot.read(StatsCounter.class,counter,"stats",it.unimi.dsi.fastutil.objects.Object2IntMap.class)!=wrapper
                ||NativeInventoryMenuSnapshot.read(wrapper.getClass(),wrapper,"map",it.unimi.dsi.fastutil.objects.Object2IntMap.class)!=values
                ||NativeInventoryMenuSnapshot.read(ServerStatsCounter.class,counter,"dirty",Set.class)!=dirty)
            throw refused("Native mining statistics backing identity changed");
        int expectedSize=before.size()+(incremented!=null&&!before.containsKey(incremented)?1:0);
        if(values.size()!=expectedSize||dirty.size()!=dirtyBefore.size()+(incremented!=null&&!dirtyBefore.contains(incremented)?1:0))
            throw refused("Native mining statistics/dirty roster changed");
        for(var entry:values.object2IntEntrySet()) {
            var key=NativeBlockBreakAdmission.requireExactStatKey(entry.getKey());Integer original=before.get(key);
            if(original==null&&key!=incremented||entry.getIntValue()!=(key==incremented?Math.addExact(original==null?0:original,1):original))
                throw refused("Native mining changed an unowned statistic value");
        }
        for(var key:dirty)if(!dirtyBefore.contains(key)&&key!=incremented)throw refused("Native mining changed an unowned dirty statistic");
    }
    private static SurvivalRefusal refused(String message){return new SurvivalRefusal(message);}
}
