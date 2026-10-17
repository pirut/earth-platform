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
import net.minecraft.commands.arguments.TeamArgument;
import net.minecraft.world.scores.PlayerTeam;
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
        var spawn = Commands.literal("spawn").requires(EarthCommands::mayUseHomes).executes(context -> {
            ServerPlayer player = context.getSource().getPlayerOrException();
            EarthGenerator earth = earth(context.getSource());
            GeoPoint to = earth == null ? null : spawnPoint(context.getSource(), earth);
            if (to != null && !EarthRules.pay(context.getSource(), player, to.latitude(), to.longitude())) return 0;
            return EarthWorlds.toSpawn(context.getSource(), player);
        });
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
                .executes(context -> randomSelf(context.getSource(), 0))
                .then(Commands.argument("km", DoubleArgumentType.doubleArg(1, 20_000))
                        .executes(context -> randomSelf(context.getSource(), DoubleArgumentType.getDouble(context, "km")))));

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

        // Warps: named spots for everyone.
        root.then(Commands.literal("warps").executes(context -> warps(context.getSource())));
        root.then(Commands.literal("warp").requires(EarthCommands::mayUseHomes).then(Commands.argument("name", StringArgumentType.word())
                .suggests((context, builder) -> SharedSuggestionProvider.suggest(EarthSpawns.of(context.getSource().getServer()).warps().keySet(), builder))
                .executes(context -> warp(context.getSource(), StringArgumentType.getString(context, "name")))));
        root.then(Commands.literal("setwarp").requires(EarthCommands::op).then(Commands.argument("name", StringArgumentType.word())
                .then(spot(spot -> context -> setWarp(context.getSource(), StringArgumentType.getString(context, "name"), spot.apply(context))))));
        root.then(Commands.literal("delwarp").requires(EarthCommands::op).then(Commands.argument("name", StringArgumentType.word())
                .suggests((context, builder) -> SharedSuggestionProvider.suggest(EarthSpawns.of(context.getSource().getServer()).warps().keySet(), builder))
                .executes(context -> setWarp(context.getSource(), StringArgumentType.getString(context, "name"), null))));

        // Team starts: each scoreboard team spawns in its own place.
        root.then(Commands.literal("team").requires(EarthCommands::op).then(Commands.argument("team", TeamArgument.team())
                .then(Commands.literal("spawn")
                        .then(Commands.literal("reset").executes(context -> setTeam(context.getSource(), TeamArgument.getTeam(context, "team"), null)))
                        .then(spot(spot -> context -> setTeam(context.getSource(), TeamArgument.getTeam(context, "team"), spot.apply(context)))))));
        root.then(Commands.literal("teams").requires(EarthCommands::op).then(Commands.literal("spread")
                .executes(context -> spread(context.getSource(), 2000))
                .then(Commands.argument("km", DoubleArgumentType.doubleArg(0, 10_000))
                        .executes(context -> spread(context.getSource(), DoubleArgumentType.getDouble(context, "km"))))));

        // The region border.
        root.then(Commands.literal("border").executes(context -> borderInfo(context.getSource()))
                .then(Commands.literal("box").requires(EarthCommands::op)
                        .then(Commands.argument("south", DoubleArgumentType.doubleArg(-80, 80)).then(Commands.argument("west", DoubleArgumentType.doubleArg(-180, 180))
                                .then(Commands.argument("north", DoubleArgumentType.doubleArg(-80, 80)).then(Commands.argument("east", DoubleArgumentType.doubleArg(-180, 180))
                                        .executes(context -> box(context.getSource(), DoubleArgumentType.getDouble(context, "south"), DoubleArgumentType.getDouble(context, "west"),
                                                DoubleArgumentType.getDouble(context, "north"), DoubleArgumentType.getDouble(context, "east"))))))))
                .then(Commands.literal("around").requires(EarthCommands::op).then(Commands.argument("km", DoubleArgumentType.doubleArg(1, 5000))
                        .executes(context -> around(context.getSource(), DoubleArgumentType.getDouble(context, "km")))))
                .then(Commands.literal("country").requires(EarthCommands::op).then(Commands.argument("country", StringArgumentType.greedyString())
                        .suggests((context, builder) -> SharedSuggestionProvider.suggest(EarthCountries.all().stream().map(EarthCountries.Country::name), builder))
                        .executes(context -> country(context.getSource(), StringArgumentType.getString(context, "country")))))
                .then(Commands.literal("reset").requires(EarthCommands::op).executes(context -> setBorder(context.getSource(), null))));

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

    @Nullable static EarthGenerator earth(CommandSourceStack source) {
        ServerLevel level = source.getServer().getLevel(Cubic.LEVEL);
        return level != null && CubicLevel.of(level) instanceof ServerCubeWorld cubes && cubes.generator() instanceof EarthGenerator earth ? earth : null;
    }

    /** Where a player stands on the Earth, or null off it. */
    @Nullable private static GeoPoint where(ServerPlayer player) { return EarthRules.where(player); }

    /** Where the server spawn is: as its operators set it, else the world's home. */
    static GeoPoint spawnPoint(CommandSourceStack source, EarthGenerator earth) {
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
        EarthMap.sendOthers(player);
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
            EarthGenerator earth = earth(source);
            GeoPoint to = earth == null ? null : spawnPoint(source, earth);
            if (to != null && !EarthRules.pay(source, player, to.latitude(), to.longitude())) return 0;
            if (source.getPlayer() == player) source.sendSuccess(() -> Component.literal("You have no home yet (set one with /earth sethome); going to the server spawn.")
                    .withStyle(ChatFormatting.GRAY), false);
            return EarthWorlds.toSpawn(source, player);
        }
        if (!EarthRules.pay(source, player, home.get().latitude(), home.get().longitude())) return 0;
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
        EarthSpawns.Border border = EarthSpawns.of(source.getServer()).border().orElse(null);
        for (ServerPlayer player : players) {
            GeoPoint here = where(player);
            GeoPoint centre = here != null ? here : spawnPoint(source, earth);
            RandomSource random = RandomSource.create();
            CompletableFuture.supplyAsync(() -> randomLand(earth, random, border, centre, km), Util.ioPool())
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

    /** A random spot on land inside the border (when there is one), or null when none of the tries found any. */
    @Nullable static EarthSpawns.Spot randomLand(EarthGenerator earth, RandomSource random, @Nullable EarthSpawns.Border border, @Nullable GeoPoint centre, double km) {
        int checked = 0;
        // Points outside the border cost nothing to throw away; only the land check downloads.
        for (int i = 0; i < 20_000 && checked < RANDOM_TRIES; i++) {
            double[] point = km > 0 && centre != null ? near(random, centre, km) : border != null ? within(random, border) : anywhere(random);
            if (border != null && !border.contains(point[0], point[1])) continue;
            checked++;
            if (earth.roughMetres(point[0], point[1]) >= LAND_METRES) return EarthSpawns.Spot.at(point[0], point[1]);
        }
        return null;
    }

    /** A random spot on land for a round or a team, read from the server's border on the server thread. */
    @Nullable static EarthSpawns.Spot randomLand(net.minecraft.server.MinecraftServer server, EarthGenerator earth, RandomSource random, @Nullable GeoPoint centre, double km) {
        return randomLand(earth, random, EarthSpawns.of(server).border().orElse(null), centre, km);
    }

    /** A point drawn evenly over the surface within a border's extent. */
    static double[] within(RandomSource random, EarthSpawns.Border border) {
        double south = Math.sin(Math.toRadians(border.south())), north = Math.sin(Math.toRadians(border.north()));
        double latitude = Math.toDegrees(Math.asin(south + random.nextDouble() * (north - south)));
        return new double[]{latitude, border.west() + random.nextDouble() * (border.east() - border.west())};
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

    private static int randomSelf(CommandSourceStack source, double km) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        if (!EarthRules.pay(source, player, Double.NaN, Double.NaN)) return 0;
        return random(source, List.of(player), km);
    }

    // Warps --------------------------------------------------------------------------------------

    private static int warps(CommandSourceStack source) {
        var warps = EarthSpawns.of(source.getServer()).warps();
        if (warps.isEmpty()) { source.sendFailure(Component.literal("No warps yet. Operators make them with /earth setwarp <name> here.")); return 0; }
        source.sendSuccess(() -> Component.literal("Warps (click one to go):").withStyle(ChatFormatting.GREEN), false);
        warps.forEach((name, spot) -> {
            Component line = Component.literal("  " + name).withStyle(style -> style.withColor(ChatFormatting.AQUA)
                    .withClickEvent(new net.minecraft.network.chat.ClickEvent(net.minecraft.network.chat.ClickEvent.Action.RUN_COMMAND, "/earth warp " + name))
                    .withHoverEvent(new net.minecraft.network.chat.HoverEvent(net.minecraft.network.chat.HoverEvent.Action.SHOW_TEXT, Component.literal(spot.label()))));
            source.sendSuccess(() -> line, false);
        });
        return warps.size();
    }

    private static int warp(CommandSourceStack source, String name) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        EarthSpawns.Spot spot = EarthSpawns.of(source.getServer()).warps().get(name);
        if (spot == null) { source.sendFailure(Component.literal("No warp called '" + name + "'. See /earth warps.")); return 0; }
        if (!EarthRules.pay(source, player, spot.latitude(), spot.longitude())) return 0;
        return travel(source, player, spot);
    }

    private static int setWarp(CommandSourceStack source, String name, @Nullable EarthSpawns.Spot spot) {
        EarthSpawns spawns = EarthSpawns.of(source.getServer());
        if (spot == null && !spawns.warps().containsKey(name)) { source.sendFailure(Component.literal("No warp called '" + name + "'.")); return 0; }
        EarthSpawns.Spot named = spot == null || !spot.name().isEmpty() ? spot
                : new EarthSpawns.Spot(spot.latitude(), spot.longitude(), spot.viewLatitude(), spot.viewLongitude(), name);
        spawns.setWarp(name, named);
        source.sendSuccess(() -> Component.literal(named == null ? "Removed warp " + name + "." : "Warp " + name + " set at " + named.label() + ".")
                .withStyle(ChatFormatting.GREEN), true);
        return 1;
    }

    // Teams --------------------------------------------------------------------------------------

    private static int setTeam(CommandSourceStack source, PlayerTeam team, @Nullable EarthSpawns.Spot spot) {
        EarthSpawns.of(source.getServer()).setTeam(team.getName(), spot);
        source.sendSuccess(() -> Component.literal(spot == null ? "Team " + team.getName() + " spawns at the server spawn again."
                : "Team " + team.getName() + " spawns at " + spot.label() + " (players without their own spawn or a bed).").withStyle(ChatFormatting.GREEN), true);
        return 1;
    }

    /** Gives every scoreboard team its own random start on land, at least {@code km} from the others. */
    private static int spread(CommandSourceStack source, double km) {
        EarthGenerator earth = earth(source);
        if (earth == null) { source.sendFailure(Component.literal("This world has no Earth.")); return 0; }
        List<PlayerTeam> teams = List.copyOf(source.getServer().getScoreboard().getPlayerTeams());
        if (teams.isEmpty()) { source.sendFailure(Component.literal("There are no teams. Make some with /team add <name>.")); return 0; }
        EarthSpawns.Border border = EarthSpawns.of(source.getServer()).border().orElse(null);
        source.sendSuccess(() -> Component.literal("Finding starts for " + teams.size() + " teams...").withStyle(ChatFormatting.GRAY), false);
        CompletableFuture.supplyAsync(() -> {
            RandomSource random = RandomSource.create();
            List<EarthSpawns.Spot> spots = new java.util.ArrayList<>();
            for (int attempt = 0; attempt < 400 && spots.size() < teams.size(); attempt++) {
                EarthSpawns.Spot spot = randomLand(earth, random, border, null, 0);
                if (spot == null) continue;
                // Later attempts ask for less room, so a small border still fits every team.
                double room = km * Math.max(0.1, 1 - attempt / 400.0);
                if (spots.stream().allMatch(other -> EarthRules.kilometers(other.latitude(), other.longitude(), spot.latitude(), spot.longitude()) >= room)) spots.add(spot);
            }
            return spots;
        }, Util.ioPool()).whenComplete((spots, failure) -> source.getServer().execute(() -> {
            if (failure != null || spots.size() < teams.size()) {
                source.sendFailure(Component.literal("Could not find a start for every team. Try a smaller distance."));
                return;
            }
            EarthSpawns spawns = EarthSpawns.of(source.getServer());
            for (int i = 0; i < teams.size(); i++) {
                PlayerTeam team = teams.get(i);
                EarthSpawns.Spot spot = spots.get(i);
                EarthCountries.Country country = EarthCountries.at(spot.latitude(), spot.longitude());
                EarthSpawns.Spot named = new EarthSpawns.Spot(spot.latitude(), spot.longitude(), Double.NaN, Double.NaN,
                        country == null ? "" : String.format(java.util.Locale.ROOT, "%s (%.3f, %.3f)", country.name(), spot.latitude(), spot.longitude()));
                spawns.setTeam(team.getName(), named);
                source.sendSuccess(() -> Component.literal("Team " + team.getName() + " starts in " + named.label() + ".").withStyle(ChatFormatting.GREEN), true);
            }
        }));
        return teams.size();
    }

    // The border ---------------------------------------------------------------------------------

    private static int borderInfo(CommandSourceStack source) {
        var border = EarthSpawns.of(source.getServer()).border();
        source.sendSuccess(() -> Component.literal(border.map(b -> "This server's border: " + b.label() + ".").orElse("This server has no border: the whole Earth is open.")), false);
        return 1;
    }

    private static int box(CommandSourceStack source, double south, double west, double north, double east) {
        if (south >= north || west >= east) { source.sendFailure(Component.literal("South must be below north and west below east.")); return 0; }
        return setBorder(source, new EarthSpawns.Border(south, west, north, east, ""));
    }

    private static int around(CommandSourceStack source, double km) {
        EarthGenerator earth = earth(source);
        if (earth == null) { source.sendFailure(Component.literal("This world has no Earth.")); return 0; }
        GeoPoint centre = spawnPoint(source, earth);
        double dLat = km / 111.32, dLon = km / (111.32 * Math.max(0.05, Math.cos(Math.toRadians(centre.latitude()))));
        return setBorder(source, new EarthSpawns.Border(Math.max(-80, centre.latitude() - dLat), Math.max(-180, centre.longitude() - dLon),
                Math.min(80, centre.latitude() + dLat), Math.min(180, centre.longitude() + dLon), ""));
    }

    private static int country(CommandSourceStack source, String name) {
        var country = EarthCountries.find(name);
        if (country.isEmpty()) { source.sendFailure(Component.literal("No country called '" + name + "'.")); return 0; }
        EarthCountries.Country c = country.get();
        return setBorder(source, new EarthSpawns.Border(c.south(), c.west(), c.north(), c.east(), c.name()));
    }

    private static int setBorder(CommandSourceStack source, @Nullable EarthSpawns.Border border) {
        EarthSpawns.of(source.getServer()).setBorder(border);
        source.sendSuccess(() -> Component.literal(border == null ? "The border is gone: the whole Earth is open."
                : "Border set: " + border.label() + ". Trips past it are refused, and players who walk out are put back.").withStyle(ChatFormatting.GREEN), true);
        return 1;
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
