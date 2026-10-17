package io.earthplatform.minecraft.cubic.play;

import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.ArgumentBuilder;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import io.earthplatform.geography.GeoPoint;
import io.earthplatform.minecraft.EarthPlatformMod;
import io.earthplatform.minecraft.cubic.Cubic;
import io.earthplatform.minecraft.cubic.CubicEvents;
import io.earthplatform.minecraft.cubic.CubicLevel;
import io.earthplatform.minecraft.cubic.ServerCubeWorld;
import io.earthplatform.minecraft.cubic.earth.EarthGenerator;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import javax.annotation.Nullable;
import net.minecraft.ChatFormatting;
import net.minecraft.Util;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Commands for running an Earth server: where players spawn (the server spawn, picked on the globe
 * or by latitude and longitude, and each player's own), homes, random trips and sending players
 * places. Changing where others go is for operators and the console; going home, to the spawn or
 * on a random trip is for players as the server's settings allow.
 */
final class EarthCommands {
    /** Random trips land between these latitudes: everywhere people live, not the ice caps. */
    static final double RANDOM_SOUTH = -56, RANDOM_NORTH = 72;
    /** How many random spots are tried for land before giving up; most of the Earth is sea. */
    private static final int RANDOM_TRIES = 40;
    /** Ground at least this high is land to a random trip; lower is sea or shoreline. */
    private static final double LAND_METRES = 2;

    private EarthCommands() {}

    static void register(LiteralArgumentBuilder<CommandSourceStack> root) {
        // The server spawn.
        var spawn = Commands.literal("spawn").requires(EarthCommands::mayUseHomes)
                .executes(context -> EarthWorlds.toSpawn(context.getSource(), context.getSource().getPlayerOrException()));
        spawn.then(Commands.literal("set").requires(EarthCommands::op)
                .then(spot(spot -> context -> setSpawn(context.getSource(), spot.apply(context)))));
        spawn.then(Commands.literal("pick").requires(EarthCommands::op).executes(context -> pick(context.getSource(), EarthMap.PICK_SPAWN)));
        spawn.then(Commands.literal("reset").requires(EarthCommands::op).executes(context -> setSpawn(context.getSource(), null)));
        spawn.then(Commands.literal("info").requires(EarthCommands::op).executes(context -> info(context.getSource())));
        spawn.then(Commands.literal("player").requires(EarthCommands::op).then(Commands.argument("players", EntityArgument.players())
                .then(Commands.literal("reset").executes(context -> setSpawn(context.getSource(), EntityArgument.getPlayers(context, "players"), null)))
                .then(spot(spot -> context -> setSpawn(context.getSource(), EntityArgument.getPlayers(context, "players"), spot.apply(context))))));
        root.then(spawn);

        // Homes.
        root.then(Commands.literal("sethome").requires(EarthCommands::mayUseHomes).executes(context -> setHome(context.getSource())));
        root.then(Commands.literal("home").requires(EarthCommands::mayUseHomes)
                .executes(context -> home(context.getSource(), context.getSource().getPlayerOrException())));

        // Random trips.
        root.then(Commands.literal("random").requires(EarthWorlds::mayTravel)
                .executes(context -> random(context.getSource(), List.of(context.getSource().getPlayerOrException()), 0))
                .then(Commands.argument("km", DoubleArgumentType.doubleArg(1, 20_000))
                        .executes(context -> random(context.getSource(), List.of(context.getSource().getPlayerOrException()),
                                DoubleArgumentType.getDouble(context, "km")))));

        // Sending players places, for operators and the console.
        root.then(Commands.literal("send").requires(EarthCommands::op).then(Commands.argument("players", EntityArgument.players())
                .then(spot(spot -> context -> send(context.getSource(), EntityArgument.getPlayers(context, "players"), spot.apply(context))))
                .then(Commands.literal("spawn").executes(context -> {
                    int sent = 0;
                    for (ServerPlayer player : EntityArgument.getPlayers(context, "players")) sent += EarthWorlds.toSpawn(context.getSource(), player);
                    return sent;
                }))
                .then(Commands.literal("home").executes(context -> {
                    int sent = 0;
                    for (ServerPlayer player : EntityArgument.getPlayers(context, "players")) sent += home(context.getSource(), player);
                    return sent;
                }))
                .then(Commands.literal("random")
                        .executes(context -> random(context.getSource(), EntityArgument.getPlayers(context, "players"), 0))
                        .then(Commands.argument("km", DoubleArgumentType.doubleArg(1, 20_000))
                                .executes(context -> random(context.getSource(), EntityArgument.getPlayers(context, "players"),
                                        DoubleArgumentType.getDouble(context, "km")))))));

        // A new player's one choice of start, when the server allows it.
        root.then(Commands.literal("start").requires(source -> source.getPlayer() != null && EarthConfig.PLAYERS_CHOOSE_START.get())
                .executes(context -> chooseStart(context.getSource()))
                .then(Commands.argument("latitude", DoubleArgumentType.doubleArg(-80, 80))
                        .then(Commands.argument("longitude", DoubleArgumentType.doubleArg(-180, 180))
                                .executes(context -> start(context.getSource(), EarthSpawns.Spot.at(
                                        DoubleArgumentType.getDouble(context, "latitude"), DoubleArgumentType.getDouble(context, "longitude")))))));
    }

    /** Reads a spot from the command being run. */
    private interface SpotArgument { EarthSpawns.Spot apply(CommandContext<CommandSourceStack> context) throws CommandSyntaxException; }

    private interface Run { int run(CommandContext<CommandSourceStack> context) throws CommandSyntaxException; }

    /**
     * The three ways to name a spot, each running {@code then} with it: {@code <latitude> <longitude>},
     * {@code place <famous place>} and {@code here}, where the one running the command stands.
     */
    private static ArgumentBuilder<CommandSourceStack, ?> spot(Function<SpotArgument, Run> then) {
        Run coordinates = then.apply(context -> EarthSpawns.Spot.at(DoubleArgumentType.getDouble(context, "latitude"), DoubleArgumentType.getDouble(context, "longitude")));
        Run place = then.apply(context -> {
            String query = StringArgumentType.getString(context, "place");
            return EarthPlaces.find(query).map(EarthSpawns.Spot::of).orElseThrow(() -> 
                    new SimpleCommandExceptionType(Component.literal("No famous place called '" + query + "'. Try /earth places.")).create());
        });
        Run here = then.apply(context -> {
            GeoPoint point = where(context.getSource().getPlayerOrException());
            if (point == null) throw new SimpleCommandExceptionType(Component.literal("You are not on the Earth.")).create();
            return EarthSpawns.Spot.at(point.latitude(), point.longitude());
        });
        return Commands.literal("to")
                .then(Commands.argument("latitude", DoubleArgumentType.doubleArg(-80, 80))
                        .then(Commands.argument("longitude", DoubleArgumentType.doubleArg(-180, 180)).executes(coordinates::run)))
                .then(Commands.literal("place").then(Commands.argument("place", StringArgumentType.greedyString())
                        .suggests((context, builder) -> SharedSuggestionProvider.suggest(EarthPlaces.all().stream().map(EarthPlaces.Place::id), builder))
                        .executes(place::run)))
                .then(Commands.literal("here").executes(here::run));
    }

    static boolean op(CommandSourceStack source) { return source.hasPermission(2); }

    private static boolean mayUseHomes(CommandSourceStack source) {
        return EarthConfig.ANYONE_CAN_USE_HOMES.get() || EarthWorlds.mayTravel(source);
    }

    /** Whether a player may still choose where they start. */
    static boolean mayChooseStart(ServerPlayer player) {
        return EarthConfig.PLAYERS_CHOOSE_START.get() && !EarthSpawns.of(player.server).started(player.getUUID());
    }

    @Nullable private static EarthGenerator earth(CommandSourceStack source) {
        ServerLevel level = source.getServer().getLevel(Cubic.LEVEL);
        return level != null && CubicLevel.of(level) instanceof ServerCubeWorld cubes && cubes.generator() instanceof EarthGenerator earth ? earth : null;
    }

    /** Where a player stands on the Earth, or null off it. */
    @Nullable private static GeoPoint where(ServerPlayer player) {
        return CubicLevel.of(player.level()) instanceof ServerCubeWorld cubes && cubes.generator() instanceof EarthGenerator earth
                ? earth.grid().geographic(player.getX(), player.getZ()) : null;
    }

    /** Where the server spawn is: as its operators set it, else the world's home. */
    private static GeoPoint spawnPoint(CommandSourceStack source, EarthGenerator earth) {
        return EarthSpawns.of(source.getServer()).spawn().map(spot -> new GeoPoint(spot.latitude(), spot.longitude()))
                .orElseGet(() -> new GeoPoint(earth.settings().latitude(), earth.settings().longitude()));
    }

    private static int travel(CommandSourceStack source, ServerPlayer player, EarthSpawns.Spot spot) {
        return CubicEvents.travel(source, player, spot.latitude(), spot.longitude(), spot.viewLatitude(), spot.viewLongitude());
    }

    // The server spawn ---------------------------------------------------------------------------

    private static int setSpawn(CommandSourceStack source, @Nullable EarthSpawns.Spot spot) {
        if (earth(source) == null) { source.sendFailure(Component.literal("This world has no Earth.")); return 0; }
        EarthSpawns.of(source.getServer()).setSpawn(spot);
        EarthWorlds.spawnMoved();
        source.sendSuccess(() -> Component.literal(spot == null ? "The server spawn is the world's start again."
                : "Server spawn set to " + spot.label() + ". New players and respawns without a bed land there.").withStyle(ChatFormatting.GREEN), true);
        return 1;
    }

    private static int setSpawn(CommandSourceStack source, Collection<ServerPlayer> players, @Nullable EarthSpawns.Spot spot) {
        EarthSpawns spawns = EarthSpawns.of(source.getServer());
        for (ServerPlayer player : players) spawns.setSpawn(player.getUUID(), spot);
        String who = players.size() == 1 ? players.iterator().next().getGameProfile().getName() : players.size() + " players";
        source.sendSuccess(() -> Component.literal(spot == null ? who + " will spawn at the server spawn again."
                : who + " will spawn at " + spot.label() + " when they have no bed.").withStyle(ChatFormatting.GREEN), true);
        return players.size();
    }

    private static int info(CommandSourceStack source) {
        EarthGenerator earth = earth(source);
        if (earth == null) { source.sendFailure(Component.literal("This world has no Earth.")); return 0; }
        Optional<EarthSpawns.Spot> spawn = EarthSpawns.of(source.getServer()).spawn();
        String text = spawn.map(spot -> "Server spawn: " + spot.label() + String.format(java.util.Locale.ROOT, " (%.5f, %.5f)", spot.latitude(), spot.longitude()))
                .orElseGet(() -> String.format(java.util.Locale.ROOT, "Server spawn: the world's start, %.5f, %.5f", earth.settings().latitude(), earth.settings().longitude()));
        source.sendSuccess(() -> Component.literal(text), false);
        return 1;
    }

    /** Opens the globe on the player's screen to pick a spot, starting where they stand or at the spawn. */
    private static int pick(CommandSourceStack source, byte kind) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        EarthGenerator earth = earth(source);
        if (earth == null) { source.sendFailure(Component.literal("This world has no Earth.")); return 0; }
        GeoPoint at = where(player);
        if (at == null) at = spawnPoint(source, earth);
        PacketDistributor.sendToPlayer(player, new EarthMap.Payload(kind, at.latitude(), at.longitude()));
        return 1;
    }

    // Homes --------------------------------------------------------------------------------------

    private static int setHome(CommandSourceStack source) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        GeoPoint point = where(player);
        if (point == null) { source.sendFailure(Component.literal("You can only set a home on the Earth.")); return 0; }
        EarthSpawns.Spot spot = EarthSpawns.Spot.at(point.latitude(), point.longitude());
        EarthSpawns.of(source.getServer()).setHome(player.getUUID(), spot);
        source.sendSuccess(() -> Component.literal("Home set at " + spot.label() + ". Go back with /earth home.").withStyle(ChatFormatting.GREEN), false);
        return 1;
    }

    /** Sends a player to their home, or to the server spawn when they have none. */
    private static int home(CommandSourceStack source, ServerPlayer player) {
        Optional<EarthSpawns.Spot> home = EarthSpawns.of(source.getServer()).home(player.getUUID());
        if (home.isEmpty()) {
            if (source.getPlayer() == player) source.sendSuccess(() -> Component.literal("You have no home yet (set one with /earth sethome); going to the server spawn.")
                    .withStyle(ChatFormatting.GRAY), false);
            return EarthWorlds.toSpawn(source, player);
        }
        return travel(source, player, home.get());
    }

    // Sending and random trips -------------------------------------------------------------------

    private static int send(CommandSourceStack source, Collection<ServerPlayer> players, EarthSpawns.Spot spot) {
        for (ServerPlayer player : players) {
            if (source.getPlayer() != player)
                player.sendSystemMessage(Component.literal("Sending you to " + spot.label() + ".").withStyle(ChatFormatting.GREEN));
            travel(source, player, spot);
        }
        return players.size();
    }

    /**
     * Sends each player somewhere random on land: anywhere people live when {@code km} is 0, else
     * within that many kilometers of where they stand (or of the server spawn when they are off the Earth).
     * Land is found off the server thread, since telling it from sea downloads coarse elevation.
     */
    private static int random(CommandSourceStack source, Collection<ServerPlayer> players, double km) {
        EarthGenerator earth = earth(source);
        if (earth == null) { source.sendFailure(Component.literal("This world has no Earth.")); return 0; }
        for (ServerPlayer player : players) {
            GeoPoint here = where(player);
            GeoPoint centre = here != null ? here : spawnPoint(source, earth);
            RandomSource random = RandomSource.create();
            CompletableFuture.supplyAsync(() -> randomLand(earth, random, centre, km), Util.ioPool())
                    .whenComplete((spot, failure) -> source.getServer().execute(() -> {
                        if (player.isRemoved()) return;
                        if (failure != null || spot == null) {
                            if (failure != null) EarthPlatformMod.LOGGER.warn("Random trip failed: {}", failure.toString());
                            source.sendFailure(Component.literal(failure != null ? "Could not reach the elevation data for a random trip. Try again in a minute."
                                    : "Found no land within " + Math.round(km) + " km. Try a bigger distance."));
                            return;
                        }
                        player.sendSystemMessage(Component.literal("Random trip to " + spot.label() + ".").withStyle(ChatFormatting.GREEN));
                        travel(source, player, spot);
                    }));
        }
        return players.size();
    }

    /** A random spot on land, or null when none of the tries found any. */
    @Nullable static EarthSpawns.Spot randomLand(EarthGenerator earth, RandomSource random, GeoPoint centre, double km) {
        for (int i = 0; i < RANDOM_TRIES; i++) {
            double[] point = km <= 0 ? anywhere(random) : near(random, centre, km);
            if (earth.roughMetres(point[0], point[1]) >= LAND_METRES) return EarthSpawns.Spot.at(point[0], point[1]);
        }
        return null;
    }

    /** A point drawn evenly over the Earth's surface between {@link #RANDOM_SOUTH} and {@link #RANDOM_NORTH}. */
    static double[] anywhere(RandomSource random) {
        double south = Math.sin(Math.toRadians(RANDOM_SOUTH)), north = Math.sin(Math.toRadians(RANDOM_NORTH));
        double latitude = Math.toDegrees(Math.asin(south + random.nextDouble() * (north - south)));
        return new double[]{latitude, random.nextDouble() * 360 - 180};
    }

    /** A point drawn evenly within {@code km} of a centre, kept off the poles the world doesn't reach. */
    static double[] near(RandomSource random, GeoPoint centre, double km) {
        double distance = km * Math.sqrt(random.nextDouble()), bearing = random.nextDouble() * 2 * Math.PI;
        double latitude = centre.latitude() + distance * Math.cos(bearing) / 111.32;
        double longitude = centre.longitude() + distance * Math.sin(bearing) / (111.32 * Math.max(0.05, Math.cos(Math.toRadians(centre.latitude()))));
        latitude = Math.max(-80, Math.min(80, latitude));
        longitude = longitude - 360 * Math.floor((longitude + 180) / 360);
        return new double[]{latitude, longitude};
    }

    // Choosing a start ---------------------------------------------------------------------------

    private static int chooseStart(CommandSourceStack source) throws CommandSyntaxException {
        if (!mayChooseStart(source.getPlayerOrException())) {
            source.sendFailure(Component.literal("You have already chosen where you start. An operator can reset it."));
            return 0;
        }
        return pick(source, EarthMap.PICK_START);
    }

    private static int start(CommandSourceStack source, EarthSpawns.Spot spot) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        if (!mayChooseStart(player)) {
            source.sendFailure(Component.literal("You have already chosen where you start. An operator can reset it."));
            return 0;
        }
        if (earth(source) == null) { source.sendFailure(Component.literal("This world has no Earth.")); return 0; }
        EarthSpawns.of(source.getServer()).start(player.getUUID(), spot);
        source.sendSuccess(() -> Component.literal("You'll start at " + spot.label() + ", and respawn there when you have no bed.")
                .withStyle(ChatFormatting.GREEN), false);
        return travel(source, player, spot);
    }
}
