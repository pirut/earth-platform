package io.earthplatform.minecraft.cubic;

import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.phys.AABB;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;

/**
 * Villagers' claims on beds, job sites and bells at cubic heights. Those points of interest live
 * only in memory and are rebuilt from a cube's blocks, free, whenever the cube loads, while the
 * villagers remember what they claimed. So claims are taken again from the villagers' memories:
 * when a cube's points are rebuilt and when a villager joins the level. Without this a village
 * left and revisited, or reloaded, reads as empty (no raids) and its beds can be claimed twice.
 */
@EventBusSubscriber(modid = Cubic.MOD_ID)
public final class CubicPoiClaims {
    private CubicPoiClaims() {}

    private static final List<MemoryModuleType<GlobalPos>> CLAIMS =
            List.of(MemoryModuleType.HOME, MemoryModuleType.JOB_SITE, MemoryModuleType.POTENTIAL_JOB_SITE, MemoryModuleType.MEETING_POINT);
    /** How far from a cube to look for villagers whose claims may lie in it. */
    private static final int REACH = 64;

    /** Takes again the claims loaded villagers hold in a cube whose points of interest were just rebuilt. */
    static void reclaim(ServerLevel level, SectionPos cube) {
        if (!level.isOutsideBuildHeight(cube.minBlockY())) return; // the carrier's own store keeps these
        AABB around = new AABB(cube.minBlockX(), cube.minBlockY(), cube.minBlockZ(), cube.maxBlockX() + 1, cube.maxBlockY() + 1, cube.maxBlockZ() + 1).inflate(REACH);
        for (Villager villager : level.getEntitiesOfClass(Villager.class, around)) reclaim(level, villager, cube.asLong());
    }

    @SubscribeEvent public static void join(EntityJoinLevelEvent event) {
        if (event.loadedFromDisk() && event.getEntity() instanceof Villager villager && event.getLevel() instanceof ServerLevel level
                && CubicLevel.of(level) instanceof ServerCubeWorld) reclaim(level, villager, Long.MIN_VALUE);
    }

    /** Takes a ticket on each point the villager remembers claiming, in one cube or (MIN_VALUE) in any. */
    private static void reclaim(ServerLevel level, Villager villager, long onlyCube) {
        PoiManager pois = level.getPoiManager();
        for (MemoryModuleType<GlobalPos> type : CLAIMS) {
            GlobalPos claim = villager.getBrain().getMemoryInternal(type).orElse(null);
            if (claim == null || claim.dimension() != level.dimension()) continue;
            BlockPos pos = claim.pos();
            if (!level.isOutsideBuildHeight(pos.getY())) continue;
            if (onlyCube != Long.MIN_VALUE && SectionPos.asLong(pos) != onlyCube) continue;
            pois.take(poi -> true, (poi, at) -> at.equals(pos), pos, 1);
        }
    }
}
