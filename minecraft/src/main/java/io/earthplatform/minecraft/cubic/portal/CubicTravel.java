package io.earthplatform.minecraft.cubic.portal;

import io.earthplatform.minecraft.EarthPlatformMod;
import io.earthplatform.minecraft.cubic.Cubic;
import io.earthplatform.minecraft.cubic.CubicEvents;
import io.earthplatform.minecraft.cubic.CubicLevel;
import io.earthplatform.minecraft.cubic.ServerCubeWorld;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.portal.DimensionTransition;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerRespawnPositionEvent;

/**
 * Remembers which overworld each player lives in, so trips to the Nether and the End, and deaths
 * there, bring them back to the cubic Earth when that is where they came from.
 */
@EventBusSubscriber(modid = EarthPlatformMod.MOD_ID)
public final class CubicTravel {
    private CubicTravel() {}

    @SubscribeEvent public static void changedDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        if (event.getTo() == Level.OVERWORLD) player.removeTag(CubicPortals.HOME_TAG);
        else if (Cubic.isCubic(event.getTo()) || Cubic.isCubic(event.getFrom())) player.addTag(CubicPortals.HOME_TAG);
    }

    /** Dying anywhere without a bed or anchor brings a cubic player back to the Earth's arrival point. */
    @SubscribeEvent public static void respawn(PlayerRespawnPositionEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) event.setDimensionTransition(home(player, event.getDimensionTransition()));
    }

    /**
     * A respawn-style trip for a player: unchanged when they don't live in the cubic world or
     * when their bed or anchor still stands, else to the cubic world's arrival point.
     */
    public static DimensionTransition home(ServerPlayer player, DimensionTransition target) {
        if (!CubicPortals.livesInCubic(player) || Cubic.isCubic(target.newLevel().dimension())) return target;
        if (player.getRespawnPosition() != null && !target.missingRespawnBlock()) return target;
        ServerLevel cubic = CubicPortals.cubicLevel(player.serverLevel());
        if (cubic == null || !(CubicLevel.of(cubic) instanceof ServerCubeWorld cubes)) return target;
        return new DimensionTransition(cubic, CubicEvents.arrival(cubes), Vec3.ZERO, target.yRot(), 0.0F,
                target.missingRespawnBlock(), target.postDimensionTransition());
    }
}
