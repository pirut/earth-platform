package io.earthplatform.minecraft.cubic.play;

import io.earthplatform.geography.GeoPoint;
import io.earthplatform.minecraft.EarthPlatformMod;
import io.earthplatform.minecraft.cubic.Cubic;
import io.earthplatform.minecraft.cubic.CubicLevel;
import io.earthplatform.minecraft.cubic.ServerCubeWorld;
import io.earthplatform.minecraft.cubic.earth.EarthGenerator;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * The creative travel map: the server tells a player's client where on the Earth they stand so the
 * map opens there, and when a trip they started lands or fails, so the client's camera flight can
 * come down.
 */
public final class EarthMap {
    /** Open the map, centred on the player. */
    public static final byte OPEN = 0;
    /** The player has landed at the end of a trip. */
    public static final byte ARRIVED = 1;
    /** The trip could not happen; the player stays put. */
    public static final byte FAILED = 2;

    public record Payload(byte kind, double latitude, double longitude) implements CustomPacketPayload {
        public static final Type<Payload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(EarthPlatformMod.MOD_ID, "earth_map"));
        public static final StreamCodec<FriendlyByteBuf, Payload> CODEC = StreamCodec.of(
                (buf, p) -> { buf.writeByte(p.kind); buf.writeDouble(p.latitude); buf.writeDouble(p.longitude); },
                buf -> new Payload(buf.readByte(), buf.readDouble(), buf.readDouble()));
        @Override public Type<Payload> type() { return TYPE; }
    }

    private EarthMap() {}

    /** The map is for players who may travel and are in creative mode, or operators. */
    static boolean mayOpen(CommandSourceStack source) {
        ServerPlayer player = source.getPlayer();
        return player != null && (player.isCreative() || source.hasPermission(2));
    }

    static int open(CommandSourceStack source) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        if (!mayOpen(source)) {
            source.sendFailure(Component.translatable("earthplatform.map.creativeOnly"));
            return 0;
        }
        GeoPoint here = where(source, player);
        if (here == null) {
            source.sendFailure(Component.literal("This world has no Earth."));
            return 0;
        }
        PacketDistributor.sendToPlayer(player, new Payload(OPEN, here.latitude(), here.longitude()));
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
