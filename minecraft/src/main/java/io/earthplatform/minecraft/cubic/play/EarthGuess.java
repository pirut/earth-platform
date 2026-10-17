package io.earthplatform.minecraft.cubic.play;

import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import io.earthplatform.minecraft.EarthPlatformMod;
import io.earthplatform.minecraft.cubic.earth.EarthGenerator;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import javax.annotation.Nullable;
import net.minecraft.ChatFormatting;
import net.minecraft.Util;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * A GeoGuessr round: everyone playing is dropped at the same random spot on land, looks around,
 * and marks on the globe where they think they are. Points by distance, as GeoGuessr gives them:
 * 5000 for a perfect guess, about half at 1400 km. While a round is on, the players' own
 * {@code /earth where}, travel map and country names stay quiet.
 */
final class EarthGuess {
    /** How long a round lasts before it ends with whatever guesses are in. */
    private static final long ROUND = 5 * 60_000;

    private static final class Round {
        final EarthSpawns.Spot answer;
        final List<UUID> players;
        final Map<UUID, double[]> guesses = new LinkedHashMap<>();
        final long started = Util.getMillis();

        Round(EarthSpawns.Spot answer, List<UUID> players) { this.answer = answer; this.players = players; }
    }

    @Nullable private static Round round;
    /** Points over every round since the server started. */
    private static final Map<UUID, Integer> TOTALS = new HashMap<>();
    private static final Map<UUID, String> NAMES = new HashMap<>();

    private EarthGuess() {}

    static void register(LiteralArgumentBuilder<CommandSourceStack> root) {
        root.then(Commands.literal("guess").requires(source -> EarthConfig.GEOGUESSR.get())
                .executes(context -> open(context.getSource()))
                .then(Commands.argument("latitude", DoubleArgumentType.doubleArg(-90, 90))
                        .then(Commands.argument("longitude", DoubleArgumentType.doubleArg(-180, 180))
                                .executes(context -> guess(context.getSource(), DoubleArgumentType.getDouble(context, "latitude"),
                                        DoubleArgumentType.getDouble(context, "longitude")))))
                .then(Commands.literal("start").requires(EarthCommands::op)
                        .executes(context -> start(context.getSource(), context.getSource().getServer().getPlayerList().getPlayers()))
                        .then(Commands.argument("players", EntityArgument.players())
                                .executes(context -> start(context.getSource(), EntityArgument.getPlayers(context, "players")))))
                .then(Commands.literal("end").requires(EarthCommands::op).executes(context -> {
                    if (round == null) { context.getSource().sendFailure(Component.literal("No round is on.")); return 0; }
                    reveal(context.getSource().getServer());
                    return 1;
                }))
                .then(Commands.literal("scores").executes(context -> scores(context.getSource()))));
    }

    /** Whether a player is in a round, so what would give the answer away stays quiet. */
    static boolean playing(ServerPlayer player) {
        Round current = round;
        return current != null && current.players.contains(player.getUUID()) && !current.guesses.containsKey(player.getUUID());
    }

    private static int start(CommandSourceStack source, Collection<ServerPlayer> players) {
        if (round != null) { source.sendFailure(Component.literal("A round is already on; /earth guess end finishes it.")); return 0; }
        EarthGenerator earth = EarthCommands.earth(source);
        if (earth == null) { source.sendFailure(Component.literal("This world has no Earth.")); return 0; }
        List<ServerPlayer> playing = List.copyOf(players);
        if (playing.isEmpty()) { source.sendFailure(Component.literal("Nobody to play.")); return 0; }
        source.sendSuccess(() -> Component.literal("Finding a spot for the round...").withStyle(ChatFormatting.GRAY), false);
        MinecraftServer server = source.getServer();
        CompletableFuture.supplyAsync(() -> EarthCommands.randomLand(server, earth, RandomSource.create(), null, 0), Util.ioPool())
                .whenComplete((spot, failure) -> server.execute(() -> {
                    if (failure != null || spot == null) {
                        source.sendFailure(Component.literal("Could not find a spot for the round. Try again in a minute."));
                        if (failure != null) EarthPlatformMod.LOGGER.warn("GeoGuessr round: {}", failure.toString());
                        return;
                    }
                    List<UUID> ids = new ArrayList<>();
                    // The trip's own messages would name the spot, so they stay quiet.
                    CommandSourceStack quiet = server.createCommandSourceStack().withSuppressedOutput();
                    for (ServerPlayer player : playing) {
                        if (player.isRemoved()) continue;
                        ids.add(player.getUUID());
                        NAMES.put(player.getUUID(), player.getGameProfile().getName());
                        EarthCommands.wander(quiet, player, spot);
                        player.sendSystemMessage(Component.literal("GeoGuessr! You're off to somewhere on Earth. Look around, then ")
                                .append(Component.literal("[Make your guess]").withStyle(style -> style.withColor(ChatFormatting.AQUA)
                                        .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/earth guess"))
                                        .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.literal("Opens the globe")))))
                                .append(Component.literal(". Five minutes.")).withStyle(ChatFormatting.GOLD));
                    }
                    round = new Round(spot, ids);
                }));
        return playing.size();
    }

    private static int open(CommandSourceStack source) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        if (!playing(player)) { source.sendFailure(Component.literal("You're not in a GeoGuessr round.")); return 0; }
        // NaN: the globe opens on the whole world, with no dot where the player is.
        PacketDistributor.sendToPlayer(player, new EarthMap.Payload(EarthMap.PICK_GUESS, Double.NaN, Double.NaN));
        return 1;
    }

    private static int guess(CommandSourceStack source, double latitude, double longitude) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        Round current = round;
        if (current == null || !playing(player)) { source.sendFailure(Component.literal("You're not in a GeoGuessr round, or you've guessed.")); return 0; }
        current.guesses.put(player.getUUID(), new double[]{latitude, longitude});
        double km = EarthRules.kilometers(latitude, longitude, current.answer.latitude(), current.answer.longitude());
        source.sendSuccess(() -> Component.literal(String.format(Locale.ROOT, "Guess in: %,.0f km off, %d points. Wait for the others.", km, points(km)))
                .withStyle(ChatFormatting.GREEN), false);
        if (current.guesses.size() >= current.players.stream().filter(id -> source.getServer().getPlayerList().getPlayer(id) != null).count())
            reveal(source.getServer());
        return 1;
    }

    /** GeoGuessr's scoring: 5000 at the spot, falling off with distance. */
    static int points(double km) { return (int) Math.round(5000 * Math.exp(-km / 2000)); }

    private static void reveal(MinecraftServer server) {
        Round current = round;
        if (current == null) return;
        round = null;
        EarthSpawns.Spot answer = current.answer;
        EarthCountries.Country country = EarthCountries.at(answer.latitude(), answer.longitude());
        List<Map.Entry<UUID, double[]>> ranked = new ArrayList<>(current.guesses.entrySet());
        ranked.sort((a, b) -> Double.compare(distance(a.getValue(), answer), distance(b.getValue(), answer)));
        List<Component> lines = new ArrayList<>();
        lines.add(Component.literal(String.format(Locale.ROOT, "The spot was %s(%.4f, %.4f).", country == null ? "" : "in " + country.name() + " ",
                answer.latitude(), answer.longitude())).withStyle(ChatFormatting.GOLD));
        int place = 1;
        for (Map.Entry<UUID, double[]> entry : ranked) {
            double km = distance(entry.getValue(), answer);
            int points = points(km);
            TOTALS.merge(entry.getKey(), points, Integer::sum);
            lines.add(Component.literal(String.format(Locale.ROOT, "  %d. %s: %,.0f km, %d points", place++, NAMES.getOrDefault(entry.getKey(), "?"), km, points)));
        }
        if (ranked.isEmpty()) lines.add(Component.literal("  Nobody guessed."));
        for (UUID id : current.players) {
            ServerPlayer player = server.getPlayerList().getPlayer(id);
            if (player != null) for (Component line : lines) player.sendSystemMessage(line);
        }
    }

    private static double distance(double[] guess, EarthSpawns.Spot answer) {
        return EarthRules.kilometers(guess[0], guess[1], answer.latitude(), answer.longitude());
    }

    private static int scores(CommandSourceStack source) {
        if (TOTALS.isEmpty()) { source.sendFailure(Component.literal("No rounds played yet.")); return 0; }
        source.sendSuccess(() -> Component.literal("GeoGuessr points:").withStyle(ChatFormatting.GOLD), false);
        TOTALS.entrySet().stream().sorted(Map.Entry.<UUID, Integer>comparingByValue().reversed()).forEach(entry ->
                source.sendSuccess(() -> Component.literal("  " + NAMES.getOrDefault(entry.getKey(), "?") + ": " + entry.getValue()), false));
        return TOTALS.size();
    }

    static void second(MinecraftServer server) {
        Round current = round;
        if (current != null && Util.getMillis() - current.started > ROUND) reveal(server);
    }

    /** GeoGuessr was switched off: a round that is on ends with the guesses in. */
    static void end(MinecraftServer server) { if (round != null) reveal(server); }

    static void stopped() { round = null; TOTALS.clear(); NAMES.clear(); }
}
