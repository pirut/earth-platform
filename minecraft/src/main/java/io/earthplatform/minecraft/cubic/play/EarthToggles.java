package io.earthplatform.minecraft.cubic.play;

import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import java.util.List;
import java.util.function.Predicate;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * The server features operators switch on and off in game with {@code /earth settings}: each is a
 * setting in earthplatform-common.toml, saved there and in effect at once, as an edit to the file is.
 */
final class EarthToggles {
    private record Toggle(String name, ModConfigSpec.BooleanValue value, String what) {}
    private record Number(String name, ModConfigSpec.IntValue value, int min, int max, String what) {}
    private record Text(String name, ModConfigSpec.ConfigValue<String> value, Predicate<String> valid, String rule, String what) {}

    private static final List<Toggle> TOGGLES = List.of(
            new Toggle("homes", EarthConfig.ANYONE_CAN_USE_HOMES, "players use /earth sethome, home and spawn"),
            new Toggle("warps", EarthConfig.WARPS, "players use /earth warp"),
            new Toggle("randomTrips", EarthConfig.RANDOM_TRIPS, "players use /earth random"),
            new Toggle("teleportRequests", EarthConfig.TELEPORT_REQUESTS, "players use /earth tpa and tpahere"),
            new Toggle("anyoneCanTravel", EarthConfig.ANYONE_CAN_TRAVEL, "players use /earth goto, visit and the travel map"),
            new Toggle("chooseStart", EarthConfig.PLAYERS_CHOOSE_START, "new players choose their start on the globe"),
            new Toggle("countryNames", EarthConfig.COUNTRY_NAMES, "\"Now entering <country>\" on screen"),
            new Toggle("playersOnMap", EarthConfig.SHOW_PLAYERS_ON_MAP, "other players as dots on the globe"),
            new Toggle("survivalMap", EarthConfig.SURVIVAL_MAP, "players open the map (N) and point compasses with it (/earth compass)"),
            new Toggle("whereOnEarth", EarthConfig.WHERE_ON_EARTH, "Where on Earth guessing rounds (/earth guess)"),
            new Toggle("races", EarthConfig.RACES, "races to famous places (/earth race)"),
            new Toggle("dailyRace", EarthConfig.DAILY_RACE, "a race every day"),
            new Toggle("webMap", EarthConfig.WEB_MAP, "the live web map"));
    private static final List<Number> NUMBERS = List.of(
            new Number("travelCost", EarthConfig.TRAVEL_LEVELS_PER_1000_KM, 0, 1000, "experience levels per 1000 km in survival"),
            new Number("travelCooldown", EarthConfig.TRAVEL_COOLDOWN, 0, 86_400, "seconds between a survival player's trips"),
            new Number("webMapPort", EarthConfig.WEB_MAP_PORT, 1, 65_535, "the web map's port"));
    private static final List<Text> TEXTS = List.of(
            new Text("webMapAddress", EarthConfig.WEB_MAP_ADDRESS, address -> EarthAdmin.webMapAddress(address) != null,
                    "0.0.0.0 for every network, 127.0.0.1 for this computer only, or one of the server's own IP addresses",
                    "where the web map answers (0.0.0.0 every network, 127.0.0.1 this computer only)"));

    private EarthToggles() {}

    static void register(LiteralArgumentBuilder<CommandSourceStack> root) {
        var settings = Commands.literal("settings").requires(EarthCommands::op).executes(context -> list(context.getSource()));
        for (Toggle toggle : TOGGLES) {
            settings.then(Commands.literal(toggle.name)
                    .executes(context -> list(context.getSource()))
                    .then(Commands.literal("on").executes(context -> set(context.getSource(), toggle, true)))
                    .then(Commands.literal("off").executes(context -> set(context.getSource(), toggle, false)))
                    .then(Commands.argument("enabled", BoolArgumentType.bool())
                            .executes(context -> set(context.getSource(), toggle, BoolArgumentType.getBool(context, "enabled")))));
        }
        for (Number number : NUMBERS) {
            settings.then(Commands.literal(number.name)
                    .executes(context -> list(context.getSource()))
                    .then(Commands.argument("value", IntegerArgumentType.integer(0))
                            .executes(context -> set(context.getSource(), number, IntegerArgumentType.getInteger(context, "value")))));
        }
        for (Text text : TEXTS) {
            settings.then(Commands.literal(text.name)
                    .executes(context -> list(context.getSource()))
                    .then(Commands.argument("value", StringArgumentType.greedyString())
                            .executes(context -> set(context.getSource(), text, StringArgumentType.getString(context, "value")))));
        }
        root.then(settings);
    }

    /** Lists every feature with buttons to switch it. */
    private static int list(CommandSourceStack source) {
        source.sendSuccess(() -> Component.literal("Server features (click to switch; saved to earthplatform-common.toml):").withStyle(ChatFormatting.GOLD), false);
        for (Toggle toggle : TOGGLES) {
            boolean on = toggle.value.get();
            MutableComponent line = Component.literal("  ")
                    .append(button("ON", on, "/earth settings " + toggle.name + " on"))
                    .append(Component.literal(" "))
                    .append(button("OFF", !on, "/earth settings " + toggle.name + " off"))
                    .append(Component.literal(" " + toggle.name).withStyle(ChatFormatting.WHITE))
                    .append(Component.literal(": " + toggle.what).withStyle(ChatFormatting.GRAY));
            source.sendSuccess(() -> line, false);
        }
        for (Number number : NUMBERS) {
            String command = "/earth settings " + number.name + " ";
            Component line = Component.literal("  " + number.name + " = " + number.value.get()).withStyle(style -> style.withColor(ChatFormatting.WHITE)
                            .withClickEvent(new ClickEvent(ClickEvent.Action.SUGGEST_COMMAND, command))
                            .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.literal("Click to change"))))
                    .append(Component.literal(": " + number.what).withStyle(ChatFormatting.GRAY));
            source.sendSuccess(() -> line, false);
        }
        for (Text text : TEXTS) {
            String command = "/earth settings " + text.name + " ";
            Component line = Component.literal("  " + text.name + " = " + text.value.get()).withStyle(style -> style.withColor(ChatFormatting.WHITE)
                            .withClickEvent(new ClickEvent(ClickEvent.Action.SUGGEST_COMMAND, command))
                            .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.literal("Click to change"))))
                    .append(Component.literal(": " + text.what).withStyle(ChatFormatting.GRAY));
            source.sendSuccess(() -> line, false);
        }
        return TOGGLES.size();
    }

    private static Component button(String text, boolean lit, String command) {
        return Component.literal("[" + text + "]").withStyle(style -> style
                .withColor(lit ? (text.equals("ON") ? ChatFormatting.GREEN : ChatFormatting.RED) : ChatFormatting.DARK_GRAY)
                .withBold(lit)
                .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, command))
                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.literal(command))));
    }

    private static int set(CommandSourceStack source, Toggle toggle, boolean on) {
        toggle.value.set(on);
        saved(source, toggle.name + " is " + (on ? "on" : "off") + ".");
        return 1;
    }

    private static int set(CommandSourceStack source, Number number, int value) {
        if (value < number.min || value > number.max) {
            source.sendFailure(Component.literal(number.name + " goes from " + number.min + " to " + number.max + "."));
            return 0;
        }
        number.value.set(value);
        saved(source, number.name + " is " + value + ".");
        return 1;
    }

    private static int set(CommandSourceStack source, Text text, String value) {
        value = value.trim();
        if (!text.valid.test(value)) {
            source.sendFailure(Component.literal(text.name + " must be " + text.rule + "."));
            return 0;
        }
        text.value.set(value);
        saved(source, text.name + " is " + value + ".");
        return 1;
    }

    private static void saved(CommandSourceStack source, String message) {
        EarthConfig.COMMON_SPEC.save();
        try {
            changed(source.getServer());
        } catch (RuntimeException failure) {
            io.earthplatform.minecraft.EarthPlatformMod.LOGGER.warn("Applying the server settings failed", failure);
        }
        source.sendSuccess(() -> Component.literal(message).withStyle(ChatFormatting.GREEN), true);
    }

    /** The settings changed, from a command or the file: features switched off stop, and players' commands match. */
    static void changed(MinecraftServer server) {
        if (!EarthConfig.RACES.get()) EarthRace.off(server);
        if (!EarthConfig.WHERE_ON_EARTH.get()) EarthGuess.end(server);
        EarthAdmin.webMap(server);
        // Each player's command list hides what they may not use, so it is sent again.
        for (ServerPlayer player : server.getPlayerList().getPlayers()) server.getCommands().sendCommands(player);
    }
}
