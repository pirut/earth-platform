package io.earthplatform.minecraft.cubic.play;

import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import io.earthplatform.geography.GeoPoint;
import io.earthplatform.minecraft.EarthPlatformMod;
import io.earthplatform.minecraft.cubic.CubicLevel;
import io.earthplatform.minecraft.cubic.earth.EarthGenerator;
import io.earthplatform.minecraft.platform.Platform;
import java.util.Optional;
import net.minecraft.Util;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

/**
 * Camera shots: {@code /earth orbit} circles the camera round where the player stands, and
 * {@code /earth flyover} flies them to another place in a straight line above the ground. The
 * server only decides who may and where to; the camera itself is the client's work (see
 * {@code play.client.EarthCinema}), and with {@code /earth record} in front the client saves the
 * shot as a video, every frame drawn with its terrain complete.
 */
public final class EarthShots {
    public static final byte ORBIT = 0, FLYOVER = 1, STOP = 2;
    /** A flyover goes no farther than this; longer trips are for /earth goto. */
    static final double FARTHEST_KM = 1000;

    private EarthShots() {}

    /**
     * A shot for the player's client to make. An orbit carries its radius, seconds a turn and height
     * above the player ({@code a}, {@code b}, {@code c}); a flyover the block X and Z it ends above,
     * its seconds and its height above the ground ({@code a} to {@code d}) and the place's name.
     * {@code NaN} leaves a value to the client's default.
     */
    public record Shot(byte kind, boolean record, double a, double b, double c, double d, String label) implements CustomPacketPayload {
        public static final Type<Shot> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(EarthPlatformMod.MOD_ID, "earth_shot"));
        public static final StreamCodec<FriendlyByteBuf, Shot> CODEC = StreamCodec.of(
                (buf, p) -> {
                    buf.writeByte(p.kind); buf.writeBoolean(p.record);
                    buf.writeDouble(p.a); buf.writeDouble(p.b); buf.writeDouble(p.c); buf.writeDouble(p.d);
                    buf.writeUtf(p.label, 128);
                },
                buf -> new Shot(buf.readByte(), buf.readBoolean(), buf.readDouble(), buf.readDouble(), buf.readDouble(), buf.readDouble(), buf.readUtf(128)));
        @Override public Type<Shot> type() { return TYPE; }
    }

    static void register(LiteralArgumentBuilder<CommandSourceStack> root) {
        root.then(orbit(false)).then(flyover(false));
        root.then(Commands.literal("record").then(orbit(true)).then(flyover(true)));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> orbit(boolean record) {
        return Commands.literal("orbit")
                .executes(context -> orbit(context, record, 0))
                .then(Commands.literal("stop").executes(EarthShots::stop))
                .then(Commands.argument("radius", DoubleArgumentType.doubleArg(8, 5000))
                        .executes(context -> orbit(context, record, 1))
                        .then(Commands.argument("seconds", DoubleArgumentType.doubleArg(5, 3600))
                                .executes(context -> orbit(context, record, 2))
                                .then(Commands.argument("height", DoubleArgumentType.doubleArg(0, 5000))
                                        .executes(context -> orbit(context, record, 3)))));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> flyover(boolean record) {
        return Commands.literal("flyover")
                .then(Commands.literal("stop").executes(EarthShots::stop))
                .then(Commands.literal("place").then(Commands.argument("place", StringArgumentType.greedyString())
                        .suggests((context, builder) -> SharedSuggestionProvider.suggest(EarthPlaces.all().stream().map(EarthPlaces.Place::id), builder))
                        .executes(context -> {
                            String query = StringArgumentType.getString(context, "place");
                            Optional<EarthPlaces.Place> place = EarthPlaces.find(query);
                            if (place.isEmpty()) {
                                context.getSource().sendFailure(Component.literal("No famous place called '" + query + "'. Try /earth places."));
                                return 0;
                            }
                            return flyover(context.getSource(), record, place.get().latitude(), place.get().longitude(), Double.NaN, Double.NaN, place.get().label());
                        })))
                .then(Commands.argument("latitude", DoubleArgumentType.doubleArg(-80, 80))
                        .then(Commands.argument("longitude", DoubleArgumentType.doubleArg(-180, 180))
                                .executes(context -> flyover(context, record, 0))
                                .then(Commands.argument("seconds", DoubleArgumentType.doubleArg(5, 3600))
                                        .executes(context -> flyover(context, record, 1))
                                        .then(Commands.argument("height", DoubleArgumentType.doubleArg(8, 5000))
                                                .executes(context -> flyover(context, record, 2))))));
    }

    private static int stop(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        Platform.INSTANCE.sendToPlayer(context.getSource().getPlayerOrException(), new Shot(STOP, false, 0, 0, 0, 0, ""));
        return 1;
    }

    /**
     * Who may take the camera off their own eyes: anyone who may travel (in singleplayer, always),
     * and anyone in creative or spectator mode. A survival player on a server may not look round
     * the next hill for free.
     */
    private static boolean mayShoot(CommandSourceStack source, ServerPlayer player) {
        if (EarthGuess.playing(player)) {
            source.sendFailure(Component.literal("No camera shots during a Where on Earth round."));
            return false;
        }
        if (CubicLevel.of(player.level()) == null) {
            source.sendFailure(Component.literal("Camera shots work on the Earth, not in this dimension."));
            return false;
        }
        if (EarthWorlds.mayTravel(source) || player.isCreative() || player.isSpectator()) return true;
        source.sendFailure(Component.literal("Camera shots are for creative mode, spectators and players who may travel on this server."));
        return false;
    }

    private static int orbit(CommandContext<CommandSourceStack> context, boolean record, int given) throws CommandSyntaxException {
        CommandSourceStack source = context.getSource();
        ServerPlayer player = source.getPlayerOrException();
        if (!mayShoot(source, player)) return 0;
        double radius = given >= 1 ? DoubleArgumentType.getDouble(context, "radius") : Double.NaN;
        double seconds = given >= 2 ? DoubleArgumentType.getDouble(context, "seconds") : Double.NaN;
        double height = given >= 3 ? DoubleArgumentType.getDouble(context, "height") : Double.NaN;
        Platform.INSTANCE.sendToPlayer(player, new Shot(ORBIT, record, radius, seconds, height, Double.NaN, ""));
        return 1;
    }

    private static int flyover(CommandContext<CommandSourceStack> context, boolean record, int given) throws CommandSyntaxException {
        double latitude = DoubleArgumentType.getDouble(context, "latitude"), longitude = DoubleArgumentType.getDouble(context, "longitude");
        double seconds = given >= 1 ? DoubleArgumentType.getDouble(context, "seconds") : Double.NaN;
        double height = given >= 2 ? DoubleArgumentType.getDouble(context, "height") : Double.NaN;
        return flyover(context.getSource(), record, latitude, longitude, seconds, height, "");
    }

    private static int flyover(CommandSourceStack source, boolean record, double latitude, double longitude, double seconds, double height, String label) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        if (!mayShoot(source, player)) return 0;
        EarthGenerator earth = EarthRules.earth(player);
        GeoPoint here = EarthRules.where(player);
        if (earth == null || here == null) {
            source.sendFailure(Component.literal("Flyovers work on the Earth, not in this dimension."));
            return 0;
        }
        // The player goes along with the camera, so the terrain under it is sent: that is a trip, and takes flight.
        if (!EarthWorlds.mayTravel(source)) {
            source.sendFailure(Component.literal("A flyover takes you with it, and you can't travel on this server. /earth orbit stays where you are."));
            return 0;
        }
        if (!player.isCreative() && !player.isSpectator()) {
            source.sendFailure(Component.literal("A flyover carries you through the air: switch to creative or spectator mode first."));
            return 0;
        }
        double km = EarthRules.kilometers(here.latitude(), here.longitude(), latitude, longitude);
        if (km > FARTHEST_KM) {
            source.sendFailure(Component.literal(String.format(java.util.Locale.ROOT,
                    "That's %,.0f km away; a flyover goes up to %,.0f km. Go nearer first with /earth goto.", km, FARTHEST_KM)));
            return 0;
        }
        String refused = EarthRules.refuse(player, latitude, longitude);
        if (refused != null) {
            source.sendFailure(Component.literal(refused));
            return 0;
        }
        int[] target = earth.block(latitude, longitude);
        // The far end's data starts downloading now, so it overlaps the flight as a trip's does.
        if (km > 10) Util.ioPool().execute(() -> {
            try {
                earth.prepareFar(target[0], target[1]);
            } catch (RuntimeException e) {
                EarthPlatformMod.LOGGER.warn("Could not fetch ahead for a flyover to {}, {}: {}", latitude, longitude, e.toString());
            }
        });
        Platform.INSTANCE.sendToPlayer(player, new Shot(FLYOVER, record, target[0], target[1], seconds, height,
                label.isEmpty() ? earth.place(target[0], target[1]) : label));
        return 1;
    }
}
