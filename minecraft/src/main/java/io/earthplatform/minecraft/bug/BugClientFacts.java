package io.earthplatform.minecraft.bug;

import io.earthplatform.geography.GeoPoint;
import io.earthplatform.geography.globe.MercatorGrid;
import io.earthplatform.minecraft.cubic.client.ClientCubeWorld;
import java.util.Locale;
import javax.annotation.Nullable;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;

/**
 * Rows Bug Courier asks for when the player opens the preview, on the client thread.
 * Loaded only from {@link BugCourierBridge}.
 */
final class BugClientFacts {
    private BugClientFacts() {}

    static String coordinates() {
        LocalPlayer player = player();
        if (player == null) return "not in a world";
        BlockPos pos = player.blockPosition();
        return pos.getX() + " " + pos.getY() + " " + pos.getZ();
    }

    static String latitudeLongitude() {
        LocalPlayer player = player();
        if (player == null) return "not in a world";
        MercatorGrid grid = grid();
        if (grid == null) return "not an Earth world";
        try {
            GeoPoint point = grid.blockCentre(player.getBlockX(), player.getBlockZ());
            return String.format(Locale.ROOT, "%.5f, %.5f", point.latitude(), point.longitude());
        } catch (RuntimeException outside) {
            return "outside the map";
        }
    }

    static String biome() {
        LocalPlayer player = player();
        Minecraft minecraft = Minecraft.getInstance();
        if (player == null || minecraft.level == null) return "not in a world";
        return minecraft.level.getBiome(player.blockPosition()).unwrapKey()
                .map(key -> key.location().toString()).orElse("unknown");
    }

    /** A short line. Bug Courier also attaches the redacted end of the log and cuts it to fit the link. */
    static String recentLog() {
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        String[] hide = {};
        if (player != null) {
            String name = player.getGameProfile().getName();
            String id = player.getGameProfile().getId() == null ? "" : player.getGameProfile().getId().toString();
            hide = new String[] {name, id, id.replace("-", "")};
        }
        return ReportText.oneLine(LogExcerpt.tail(minecraft.gameDirectory.toPath(), 8, 500, hide), 400);
    }

    @Nullable private static LocalPlayer player() { return Minecraft.getInstance().player; }

    /** The Earth's block grid, once the server has sent its scale. Null outside an Earth world. */
    @Nullable private static MercatorGrid grid() {
        ClientCubeWorld world = ClientCubeWorld.current();
        double scale = world == null ? 0 : world.far().metresPerBlock();
        return scale > 0 ? new MercatorGrid(scale) : null;
    }
}
