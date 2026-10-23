package io.earthplatform.minecraft.cubic.play;

import com.mojang.brigadier.arguments.DoubleArgumentType;
import io.earthplatform.geography.globe.Downloads;
import io.earthplatform.minecraft.EarthPlatformMod;
import io.github.pirut.chunks3.CubicEvents;
import io.github.pirut.chunks3.CubicLevel;
import io.github.pirut.chunks3.ServerCubeWorld;
import io.earthplatform.minecraft.cubic.earth.EarthGenerator;
import io.earthplatform.minecraft.cubic.earth.EarthWorld;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import net.minecraft.ChatFormatting;
import net.minecraft.Util;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

/** Travel to a latitude and longitude on the Earth, and the /cubes goto command for it. */
@EventBusSubscriber(modid = EarthPlatformMod.MOD_ID)
public final class EarthTravel {
    private EarthTravel() {}

    @SubscribeEvent public static void commands(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("cubes").then(Commands.literal("goto")
                .then(Commands.argument("latitude", DoubleArgumentType.doubleArg(-85, 85))
                .then(Commands.argument("longitude", DoubleArgumentType.doubleArg(-180, 180)).executes(context -> travel(context.getSource(),
                        DoubleArgumentType.getDouble(context, "latitude"), DoubleArgumentType.getDouble(context, "longitude")))))));
    }

    /**
     * Travels to a latitude and longitude on the Earth: terrain there is fetched off the server
     * thread, then the player lands on the nearest gentle dry spot, or on the water when there is
     * none close by.
     */
    public static int travel(CommandSourceStack source, double latitude, double longitude) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        return travel(source, source.getPlayerOrException(), latitude, longitude);
    }

    /** Sends {@code player} there; the replies go to {@code source}, which may be the console. */
    public static int travel(CommandSourceStack source, ServerPlayer player, double latitude, double longitude) {
        return travel(source, player, latitude, longitude, Double.NaN, Double.NaN);
    }

    /**
     * Travels there and faces a sight, such as the peak a famous place is known for (NaN for none).
     * A famous place may land farther from its point than a bare latitude and longitude does, as a
     * new world's start does, to find good ground.
     */
    public static int travel(CommandSourceStack source, ServerPlayer player, double latitude, double longitude, double viewLatitude, double viewLongitude) {
        ServerLevel level = source.getServer().getLevel(EarthWorld.LEVEL);
        if (level == null || !(CubicLevel.of(level) instanceof ServerCubeWorld cubes) || !(cubes.generator() instanceof EarthGenerator earth)) {
            source.sendFailure(Component.literal("This save's cubic world is not the Earth."));
            return 0;
        }
        // The server's rules, such as a region border, may refuse the trip.
        String refused = EarthRules.refuse(player, latitude, longitude);
        if (refused != null) {
            source.sendFailure(Component.literal(refused));
            EarthMap.failed(player);
            return 0;
        }
        int[] target = earth.block(latitude, longitude);
        int[] view = Double.isNaN(viewLatitude) ? null : earth.block(viewLatitude, viewLongitude);
        source.sendSuccess(() -> Component.literal("Fetching terrain around " + earth.place(target[0], target[1]) + "...").withStyle(ChatFormatting.GRAY), false);
        CompletableFuture.supplyAsync(() -> {
            // How long each part takes and what it downloads, so a slow first visit shows whether the network is why.
            Map<String, long[]> before = Downloads.totals();
            long start = System.nanoTime();
            // Only the data about the landing first; the far levels' data comes in after the player arrives.
            // A famous place lands within sight of its view, which may stand kilometres off the point: fetch about there.
            boolean famous = !Double.isNaN(viewLatitude);
            int[] centre = famous ? CubicEvents.withinSight(target, view) : target;
            earth.prepareNear(centre[0], centre[1]);
            long prepared = System.nanoTime();
            String fetched = Downloads.since(before);
            before = Downloads.totals();
            int[] spot = CubicEvents.landing(earth, centre, famous ? 2048 : 256, famous);
            long landed = System.nanoTime();
            EarthPlatformMod.LOGGER.info("Travel to {}, {}: data {} ms ({}); landing {} ms ({})", latitude, longitude,
                    (prepared - start) / 1_000_000, fetched, (landed - prepared) / 1_000_000, Downloads.since(before));
            Map<String, long[]> far = Downloads.totals();
            Util.ioPool().execute(() -> {
                earth.prepare(centre[0], centre[1]);
                EarthPlatformMod.LOGGER.info("Travel to {}, {}: far data {} ms after landing ({})", latitude, longitude,
                        (System.nanoTime() - landed) / 1_000_000, Downloads.since(far));
            });
            return spot;
        }, Util.ioPool()).whenComplete((spot, failure) -> source.getServer().execute(() -> {
            if (failure != null) {
                // The data's own words, without the exception's class names: most often a data server can't be reached.
                Throwable cause = failure instanceof java.util.concurrent.CompletionException && failure.getCause() != null ? failure.getCause() : failure;
                source.sendFailure(Component.literal(cause instanceof io.earthplatform.geography.MissingTerrainException
                        ? "Could not load terrain there yet: " + cause.getMessage() + ". Try again in a minute."
                        : "Could not load terrain there: " + cause));
                if (!player.isRemoved()) EarthMap.failed(player);
                return;
            }
            CubicEvents.arriveWhenReady(source, player, spot[0], spot[1], view);
        }));
        return 1;
    }
}
