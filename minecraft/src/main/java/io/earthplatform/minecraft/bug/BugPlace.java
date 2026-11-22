package io.earthplatform.minecraft.bug;

import io.earthplatform.geography.GeoPoint;
import io.earthplatform.minecraft.cubic.CubicLevel;
import io.earthplatform.minecraft.cubic.ServerCubeWorld;
import io.earthplatform.minecraft.cubic.earth.EarthGenerator;
import io.earthplatform.minecraft.diagnostics.BuildInfo;
import io.earthplatform.minecraft.platform.Loader;
import java.util.Locale;
import net.minecraft.SharedConstants;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;

/** Facts the server already knows about the player who ran {@code /earth bug}. */
public final class BugPlace {
    private BugPlace() {}

    public static GithubBugLink.Facts capture(CommandSourceStack source, String summary) {
        String coordinates = "not in a world";
        String latitudeLongitude = "not in a world";
        String biome = "not in a world";
        String dimension = "unknown";
        String[] hide = {};
        ServerPlayer player = source.getPlayer();
        if (player != null) {
            BlockPos pos = player.blockPosition();
            coordinates = pos.getX() + " " + pos.getY() + " " + pos.getZ();
            dimension = player.level().dimension().location().toString();
            biome = player.level().getBiome(pos).unwrapKey().map(key -> key.location().toString()).orElse("unknown");
            latitudeLongitude = latitudeLongitude(player);
            String name = player.getGameProfile().getName();
            String id = player.getGameProfile().getId() == null ? "" : player.getGameProfile().getId().toString();
            hide = new String[] {name, id, id.replace("-", "")};
        }
        return new GithubBugLink.Facts(
                BuildInfo.version(),
                SharedConstants.getCurrentVersion().getName(),
                Loader.INSTANCE.name(),
                Loader.INSTANCE.version(),
                ReportText.side(Loader.INSTANCE.isClient()),
                dimension,
                coordinates,
                latitudeLongitude,
                biome,
                LogExcerpt.tail(Loader.INSTANCE.gameDir(), 12, 1200, hide),
                summary);
    }

    private static String latitudeLongitude(ServerPlayer player) {
        if (!(CubicLevel.of(player.level()) instanceof ServerCubeWorld cubes) || !(cubes.generator() instanceof EarthGenerator earth)) {
            return "not an Earth world";
        }
        GeoPoint point = earth.grid().tryBlockCentre(player.getBlockX(), player.getBlockZ());
        if (point == null) return "outside the map";
        return String.format(Locale.ROOT, "%.5f, %.5f", point.latitude(), point.longitude());
    }
}
