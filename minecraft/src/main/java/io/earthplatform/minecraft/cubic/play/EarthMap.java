package io.earthplatform.minecraft.cubic.play;

import io.earthplatform.geography.GeoPoint;
import io.earthplatform.minecraft.EarthPlatformMod;
import io.earthplatform.minecraft.cubic.Cubic;
import io.earthplatform.minecraft.cubic.CubicLevel;
import io.earthplatform.minecraft.cubic.ServerCubeWorld;
import io.earthplatform.minecraft.cubic.earth.EarthGenerator;
import net.minecraft.commands.CommandSourceStack;
import java.util.List;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * The travel map in creative, and the map in survival: the server tells a player's client where on
 * the Earth they stand so the map opens there, and when a trip they started lands or fails, so the
 * client's camera flight can come down. The survival map never moves anyone; it points compasses
 * (see {@link EarthCompass}).
 */
public final class EarthMap {
    /** Open the map, centred on the player. */
    public static final byte OPEN = 0;
    /** The player has landed at the end of a trip. */
    public static final byte ARRIVED = 1;
    /** The trip could not happen; the player stays put. */
    public static final byte FAILED = 2;
    /** Open the globe for an operator to pick the server spawn. */
    public static final byte PICK_SPAWN = 3;
    /** Open the globe for a new player to choose where they start. */
    public static final byte PICK_START = 4;
    /** Open the globe for a Where on Earth guess: the whole world, with no dot where the player is. */
    public static final byte PICK_GUESS = 5;
    /** Open the survival map, centred on the player: look around and point a compass, but no travel. */
    public static final byte LOOK = 6;
    /** A Where on Earth round started for the player: compasses spin and show no distances until it ends. */
    public static final byte HIDE_COMPASS = 7;
    /** The player's round ended (or they guessed): compasses show distances again. */
    public static final byte SHOW_COMPASS = 8;

    public record Payload(byte kind, double latitude, double longitude) implements CustomPacketPayload {
        public static final Type<Payload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(EarthPlatformMod.MOD_ID, "earth_map"));
        public static final StreamCodec<FriendlyByteBuf, Payload> CODEC = StreamCodec.of(
                (buf, p) -> { buf.writeByte(p.kind); buf.writeDouble(p.latitude); buf.writeDouble(p.longitude); },
                buf -> new Payload(buf.readByte(), buf.readDouble(), buf.readDouble()));
        @Override public Type<Payload> type() { return TYPE; }
    }

    /** Another player on the map. */
    public record Other(String name, double latitude, double longitude) {}

    /** Where the other players on the Earth are, sent just before a map opens. */
    public record Players(List<Other> players) implements CustomPacketPayload {
        public static final Type<Players> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(EarthPlatformMod.MOD_ID, "earth_map_players"));
        public static final StreamCodec<FriendlyByteBuf, Players> CODEC = StreamCodec.of(
                (buf, p) -> {
                    buf.writeVarInt(p.players.size());
                    for (Other other : p.players) { buf.writeUtf(other.name, 64); buf.writeDouble(other.latitude); buf.writeDouble(other.longitude); }
                },
                buf -> {
                    int count = Math.min(buf.readVarInt(), 1000);
                    List<Other> players = new java.util.ArrayList<>(count);
                    for (int i = 0; i < count; i++) players.add(new Other(buf.readUtf(64), buf.readDouble(), buf.readDouble()));
                    return new Players(players);
                });
        @Override public Type<Players> type() { return TYPE; }
    }

    /** Tells a player where the others are, when the server shows players on the map. */
    static void sendOthers(ServerPlayer player) {
        List<Other> others = new java.util.ArrayList<>();
        if (EarthConfig.SHOW_PLAYERS_ON_MAP.get()) {
            for (ServerPlayer other : player.server.getPlayerList().getPlayers()) {
                if (other == player || other.isSpectator()) continue;
                GeoPoint point = EarthRules.where(other);
                if (point != null) others.add(new Other(other.getGameProfile().getName(), point.latitude(), point.longitude()));
            }
        }
        PacketDistributor.sendToPlayer(player, new Players(others));
    }

    private EarthMap() {}

    /** The travel map is for players who may travel and are in creative mode, or operators. */
    static boolean mayOpen(CommandSourceStack source) {
        ServerPlayer player = source.getPlayer();
        return player != null && (player.isCreative() || source.hasPermission(2)) && EarthWorlds.mayTravel(source);
    }

    /** Who has a map at all: the travel map's players, and everyone else while the server allows the survival map. */
    static boolean mayUse(CommandSourceStack source) { return EarthWorlds.mayTravel(source) || EarthConfig.SURVIVAL_MAP.get(); }

    static int open(CommandSourceStack source) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        if (EarthGuess.playing(player)) {
            source.sendFailure(Component.literal("No travel map during a Where on Earth round: use /earth guess."));
            return 0;
        }
        boolean travel = mayOpen(source);
        if (!travel && !EarthConfig.SURVIVAL_MAP.get()) {
            source.sendFailure(Component.translatable("earthplatform.map.creativeOnly"));
            return 0;
        }
        GeoPoint here = where(source, player);
        if (here == null) {
            source.sendFailure(Component.literal("This world has no Earth."));
            return 0;
        }
        sendOthers(player);
        PacketDistributor.sendToPlayer(player, new Payload(travel ? OPEN : LOOK, here.latitude(), here.longitude()));
        return 1;
    }

    /** Where the player stands, or the world's home when they are not on the Earth itself. */
    private static GeoPoint where(CommandSourceStack source, ServerPlayer player) {
        if (CubicLevel.of(player.level()) instanceof ServerCubeWorld cubes && cubes.generator() instanceof EarthGenerator earth)
            return earth.grid().geographic(player.getX(), player.getZ());
        ServerLevel level = source.getServer().getLevel(Cubic.LEVEL);
        if (level != null && CubicLevel.of(level) instanceof ServerCubeWorld cubes && cubes.generator() instanceof EarthGenerator earth)
            return new GeoPoint(earth.settings().latitude(), earth.settings().longitude());
        return null;
    }

    public static void arrived(ServerPlayer player) { PacketDistributor.sendToPlayer(player, new Payload(ARRIVED, 0, 0)); }

    public static void failed(ServerPlayer player) { PacketDistributor.sendToPlayer(player, new Payload(FAILED, 0, 0)); }
}
