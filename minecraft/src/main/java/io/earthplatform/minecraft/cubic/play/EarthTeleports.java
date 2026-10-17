package io.earthplatform.minecraft.cubic.play;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import io.earthplatform.geography.GeoPoint;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
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

/**
 * Teleport requests: a player asks to go to another ({@code /earth tpa}) or asks another to come to
 * them ({@code /earth tpahere}); the other accepts or denies. For servers where free travel is off
 * but friends still want to meet up.
 */
final class EarthTeleports {
    private static final long EXPIRES = 120_000;

    /** A request from one player; {@code here} when the asker wants the other to come to them. */
    private record Request(UUID from, UUID to, boolean here, long at) {}

    private static final List<Request> REQUESTS = new ArrayList<>();

    private EarthTeleports() {}

    static void register(LiteralArgumentBuilder<CommandSourceStack> root) {
        root.then(Commands.literal("tpa").requires(EarthTeleports::allowed).then(Commands.argument("player", EntityArgument.player())
                .executes(context -> ask(context.getSource(), EntityArgument.getPlayer(context, "player"), false))));
        root.then(Commands.literal("tpahere").requires(EarthTeleports::allowed).then(Commands.argument("player", EntityArgument.player())
                .executes(context -> ask(context.getSource(), EntityArgument.getPlayer(context, "player"), true))));
        root.then(Commands.literal("tpaccept").requires(EarthTeleports::allowed)
                .executes(context -> answer(context.getSource(), null, true))
                .then(Commands.argument("player", EntityArgument.player())
                        .executes(context -> answer(context.getSource(), EntityArgument.getPlayer(context, "player"), true))));
        root.then(Commands.literal("tpdeny").requires(EarthTeleports::allowed)
                .executes(context -> answer(context.getSource(), null, false))
                .then(Commands.argument("player", EntityArgument.player())
                        .executes(context -> answer(context.getSource(), EntityArgument.getPlayer(context, "player"), false))));
    }

    private static boolean allowed(CommandSourceStack source) {
        return source.getPlayer() != null && (EarthConfig.TELEPORT_REQUESTS.get() || source.hasPermission(2));
    }

    private static int ask(CommandSourceStack source, ServerPlayer other, boolean here) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        if (other == player) { source.sendFailure(Component.literal("That's you.")); return 0; }
        REQUESTS.removeIf(request -> request.from.equals(player.getUUID()) && request.to.equals(other.getUUID()));
        REQUESTS.add(new Request(player.getUUID(), other.getUUID(), here, Util.getMillis()));
        String name = player.getGameProfile().getName();
        other.sendSystemMessage(Component.literal(here ? name + " asks you to come to them. " : name + " asks to come to you. ")
                .withStyle(ChatFormatting.GOLD)
                .append(button("[Accept]", "/earth tpaccept " + name, ChatFormatting.GREEN))
                .append(Component.literal(" "))
                .append(button("[Deny]", "/earth tpdeny " + name, ChatFormatting.RED)));
        source.sendSuccess(() -> Component.literal("Asked " + other.getGameProfile().getName() + ". The request lasts two minutes."), false);
        return 1;
    }

    private static Component button(String text, String command, ChatFormatting color) {
        return Component.literal(text).withStyle(style -> style.withColor(color)
                .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, command))
                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.literal(command))));
    }

    private static int answer(CommandSourceStack source, @Nullable ServerPlayer asker, boolean accept) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        Request found = null;
        for (int i = REQUESTS.size() - 1; i >= 0; i--) {
            Request request = REQUESTS.get(i);
            if (request.to.equals(player.getUUID()) && (asker == null || request.from.equals(asker.getUUID()))) { found = request; break; }
        }
        if (found == null) { source.sendFailure(Component.literal("No teleport request to answer.")); return 0; }
        REQUESTS.remove(found);
        ServerPlayer from = player.server.getPlayerList().getPlayer(found.from);
        if (from == null) { source.sendFailure(Component.literal("They have left.")); return 0; }
        if (!accept) {
            from.sendSystemMessage(Component.literal(player.getGameProfile().getName() + " said no.").withStyle(ChatFormatting.GRAY));
            source.sendSuccess(() -> Component.literal("Denied."), false);
            return 1;
        }
        ServerPlayer mover = found.here ? player : from, target = found.here ? from : player;
        if (EarthRules.where(target) == null) { source.sendFailure(Component.literal("They're not on the Earth right now.")); return 0; }
        // The one who moves pays, as for any trip they asked for.
        GeoPoint to = EarthRules.where(target);
        CommandSourceStack payer = mover.createCommandSourceStack();
        if (!EarthRules.pay(payer, mover, to.latitude(), to.longitude())) return 0;
        mover.teleportTo(target.serverLevel(), target.getX(), target.getY(), target.getZ(), target.getYRot(), mover.getXRot());
        mover.resetFallDistance();
        EarthRace.travelled(mover);
        mover.sendSystemMessage(Component.literal("Teleported to " + target.getGameProfile().getName() + ".").withStyle(ChatFormatting.GREEN));
        if (mover != player) source.sendSuccess(() -> Component.literal("Accepted."), false);
        return 1;
    }

    static void second(MinecraftServer server) {
        long now = Util.getMillis();
        REQUESTS.removeIf(request -> now - request.at > EXPIRES);
    }
}
