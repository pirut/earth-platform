package io.earthplatform.minecraft.cubic.play;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import io.earthplatform.geography.GeoPoint;
import io.earthplatform.minecraft.EarthPlatformMod;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import javax.annotation.Nullable;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemStack;

/**
 * Races to a famous place: the server names one, and the first player to get within
 * {@link #FINISH} meters of it on foot, horse, boat or wing wins the reward. A trip by command
 * (or an accepted teleport request) during the race takes a player out of it. With
 * {@code dailyRace} on, a new race starts each day (UTC).
 */
final class EarthRace {
    static final double FINISH = 0.25;

    private record Race(EarthPlaces.Place place, long started) {}

    @Nullable private static Race race;
    private static final Set<UUID> OUT = new HashSet<>();

    private EarthRace() {}

    static void register(LiteralArgumentBuilder<CommandSourceStack> root) {
        root.then(Commands.literal("race").requires(source -> EarthConfig.RACES.get()).executes(context -> show(context.getSource()))
                .then(Commands.literal("start").requires(EarthCommands::op)
                        .executes(context -> start(context.getSource().getServer(), null))
                        .then(Commands.argument("place", StringArgumentType.greedyString())
                                .suggests((context, builder) -> SharedSuggestionProvider.suggest(EarthPlaces.all().stream().map(EarthPlaces.Place::id), builder))
                                .executes(context -> {
                                    String query = StringArgumentType.getString(context, "place");
                                    var place = EarthPlaces.find(query);
                                    if (place.isEmpty()) { context.getSource().sendFailure(Component.literal("No famous place called '" + query + "'.")); return 0; }
                                    return start(context.getSource().getServer(), place.get());
                                })))
                .then(Commands.literal("stop").requires(EarthCommands::op).executes(context -> {
                    if (race == null) { context.getSource().sendFailure(Component.literal("No race is on.")); return 0; }
                    race = null;
                    context.getSource().getServer().getPlayerList().broadcastSystemMessage(Component.literal("The race is called off.").withStyle(ChatFormatting.GOLD), false);
                    return 1;
                })));
    }

    /** Starts a race to a place, or to a random famous place. */
    static int start(MinecraftServer server, @Nullable EarthPlaces.Place place) {
        if (place == null) {
            List<EarthPlaces.Place> places = EarthPlaces.all();
            place = places.get(RandomSource.create().nextInt(places.size()));
        }
        race = new Race(place, net.minecraft.Util.getMillis());
        OUT.clear();
        server.getPlayerList().broadcastSystemMessage(Component.literal("Race! First to reach " + place.label() + " ")
                .append(Component.literal(String.format(Locale.ROOT, "(%.4f, %.4f)", place.latitude(), place.longitude())).withStyle(ChatFormatting.GRAY))
                .append(Component.literal(" wins. No travel commands; /earth race shows the way.")).withStyle(ChatFormatting.GOLD), false);
        return 1;
    }

    private static int show(CommandSourceStack source) {
        Race current = race;
        if (current == null) { source.sendFailure(Component.literal("No race is on.")); return 0; }
        ServerPlayer player = source.getPlayer();
        GeoPoint here = player == null ? null : EarthRules.where(player);
        EarthPlaces.Place place = current.place;
        String way = here == null ? "" : String.format(Locale.ROOT, ": %.0f km %s of you", EarthRules.kilometers(here.latitude(), here.longitude(), place.latitude(), place.longitude()),
                EarthRules.heading(here.latitude(), here.longitude(), place.latitude(), place.longitude()));
        boolean out = player != null && OUT.contains(player.getUUID());
        source.sendSuccess(() -> Component.literal("Race to " + place.label() + way + (out ? " (you're out: you used a travel command)" : "") + ".")
                .withStyle(ChatFormatting.GOLD), false);
        return 1;
    }

    /** A player travelled by command: out of the race. */
    static void travelled(ServerPlayer player) {
        if (race != null && OUT.add(player.getUUID()))
            player.sendSystemMessage(Component.literal("That trip takes you out of the race.").withStyle(ChatFormatting.GRAY));
    }

    static void second(MinecraftServer server) {
        daily(server);
        Race current = race;
        if (current == null) return;
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (OUT.contains(player.getUUID()) || player.isSpectator()) continue;
            GeoPoint here = EarthRules.where(player);
            if (here == null) continue;
            if (EarthRules.kilometers(here.latitude(), here.longitude(), current.place.latitude(), current.place.longitude()) > FINISH) continue;
            race = null;
            long minutes = (net.minecraft.Util.getMillis() - current.started) / 60_000;
            server.getPlayerList().broadcastSystemMessage(Component.literal(player.getGameProfile().getName() + " won the race to "
                    + current.place.label() + " in " + minutes + " minutes!").withStyle(ChatFormatting.GOLD), false);
            ItemStack reward = reward();
            if (!reward.isEmpty()) {
                player.sendSystemMessage(Component.literal("Your prize: " + reward.getHoverName().getString() + (reward.getCount() > 1 ? " x" + reward.getCount() : "") + ".").withStyle(ChatFormatting.GREEN));
                if (!player.getInventory().add(reward)) player.drop(reward, false);
            }
            return;
        }
    }

    /** The prize from {@code raceReward}, "item count", e.g. "minecraft:diamond 5"; empty for none. */
    static ItemStack reward() {
        String[] parts = EarthConfig.RACE_REWARD.get().trim().split("\\s+");
        if (parts.length == 0 || parts[0].isEmpty()) return ItemStack.EMPTY;
        ResourceLocation id = ResourceLocation.tryParse(parts[0]);
        if (id == null || !BuiltInRegistries.ITEM.containsKey(id)) {
            EarthPlatformMod.LOGGER.warn("raceReward names no item: {}", parts[0]);
            return ItemStack.EMPTY;
        }
        int count = 1;
        try { if (parts.length > 1) count = Math.max(1, Integer.parseInt(parts[1])); } catch (NumberFormatException ignored) {}
        return new ItemStack(BuiltInRegistries.ITEM.get(id), count);
    }

    private static void daily(MinecraftServer server) {
        if (!EarthConfig.DAILY_RACE.get() || !EarthConfig.RACES.get() || server.getPlayerList().getPlayerCount() == 0 || !EarthWorlds.isEarth(server)) return;
        long day = java.time.LocalDate.now(java.time.ZoneOffset.UTC).toEpochDay();
        EarthSpawns spawns = EarthSpawns.of(server);
        if (spawns.raceDay() == day) return;
        spawns.setRaceDay(day);
        start(server, null);
    }

    static void stopped() { race = null; OUT.clear(); }

    /** Races were switched off: a race that is on is called off. */
    static void off(MinecraftServer server) {
        if (race == null) return;
        stopped();
        server.getPlayerList().broadcastSystemMessage(Component.literal("The race is called off.").withStyle(ChatFormatting.GOLD), false);
    }
}
