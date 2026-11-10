package io.earthplatform.minecraft.cubic.play;

import io.earthplatform.geography.GeoPoint;
import io.earthplatform.minecraft.EarthPlatformMod;
import io.earthplatform.minecraft.cubic.CubicEvents;
import io.earthplatform.minecraft.cubic.CubicLevel;
import io.earthplatform.minecraft.cubic.ServerCubeWorld;
import io.earthplatform.minecraft.platform.Platform;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import java.util.Locale;
import javax.annotation.Nullable;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

/**
 * Place codes and postcards. A place code is twelve letters and digits that hold a spot on the
 * Earth to about a meter and the way someone there was facing, so a player can share where they
 * stood: {@code /earth code} tells a player theirs, and {@code /earth code <code>} takes anyone who
 * may travel to that spot, facing the same way. A postcard is a screenshot with the place, its
 * height and its code written along the bottom; the client takes it (see the client's
 * {@code EarthPostcardScreen}) once the server has told it where the player stands. During a
 * Where on Earth round neither is given out.
 */
public final class EarthPostcard {
    /** A spot and a facing: the compass bearing looked along, in degrees clockwise from north. */
    public record Spot(double latitude, double longitude, double bearing) {}

    /** Where a player stands, for their postcard: {@code meters} is their height above sea level. */
    public record Payload(double latitude, double longitude, int meters) implements CustomPacketPayload {
        public static final Type<Payload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(EarthPlatformMod.MOD_ID, "earth_postcard"));
        public static final StreamCodec<FriendlyByteBuf, Payload> CODEC = StreamCodec.of(
                (buf, p) -> { buf.writeDouble(p.latitude); buf.writeDouble(p.longitude); buf.writeVarInt(p.meters); },
                buf -> new Payload(buf.readDouble(), buf.readDouble(), buf.readVarInt()));
        @Override public Type<Payload> type() { return TYPE; }
    }

    private static final String LETTERS = "0123456789ABCDEFGHJKMNPQRSTVWXYZ";
    private static final int LAT_BITS = 24, LON_BITS = 26, FACE_BITS = 6, CHECK_BITS = 4;
    /** Codes cover the latitudes the Earth world does. */
    private static final double LIMIT = 80;

    private EarthPostcard() {}

    /** The code of a spot and facing, as {@code XXXX-XXXX-XXXX}. Latitude is held to 80 degrees either way. */
    public static String code(double latitude, double longitude, double bearing) {
        long lat = Math.round((Math.clamp(latitude, -LIMIT, LIMIT) + LIMIT) / (2 * LIMIT) * ((1L << LAT_BITS) - 1));
        long lon = Math.floorMod(Math.round((longitude + 180) / 360 * (1L << LON_BITS)), 1L << LON_BITS);
        long face = Math.floorMod(Math.round(bearing / 360 * (1 << FACE_BITS)), 1L << FACE_BITS);
        long bits = (lat << LON_BITS | lon) << FACE_BITS | face;
        bits = bits << CHECK_BITS | check(bits);
        StringBuilder text = new StringBuilder();
        for (int i = 11; i >= 0; i--) {
            text.append(LETTERS.charAt((int) (bits >>> 5 * i & 31)));
            if (i == 8 || i == 4) text.append('-');
        }
        return text.toString();
    }

    /**
     * The spot a code holds, or null when it is not a code: wrong length, a letter codes never
     * use, or a mistyped one (the last letter checks the rest). Case, dashes and spaces don't
     * count, and the look-alikes O, I and L read as 0, 1 and 1.
     */
    @Nullable public static Spot spot(String code) {
        long bits = 0;
        int count = 0;
        for (char c : code.toUpperCase(Locale.ROOT).toCharArray()) {
            if (c == '-' || c == ' ') continue;
            int value = LETTERS.indexOf(c == 'O' ? '0' : c == 'I' || c == 'L' ? '1' : c);
            if (value < 0 || ++count > 12) return null;
            bits = bits << 5 | value;
        }
        if (count != 12 || check(bits >>> CHECK_BITS) != (bits & (1 << CHECK_BITS) - 1)) return null;
        bits >>>= CHECK_BITS;
        long face = bits & (1 << FACE_BITS) - 1, lon = bits >>> FACE_BITS & (1L << LON_BITS) - 1, lat = bits >>> FACE_BITS + LON_BITS;
        return new Spot(lat * (2 * LIMIT) / ((1L << LAT_BITS) - 1) - LIMIT, lon * 360.0 / (1L << LON_BITS) - 180, face * 360.0 / (1 << FACE_BITS));
    }

    private static long check(long bits) {
        long sum = 0;
        for (long rest = bits; rest != 0; rest >>>= 4) sum = sum * 3 + (rest & 15);
        return sum & (1 << CHECK_BITS) - 1;
    }

    /** The compass bearing a Minecraft yaw looks along: yaw 0 faces south, 90 west. */
    public static double bearing(float yaw) { return Math.floorMod(Math.round(yaw + 180.0), 360); }

    static void register(LiteralArgumentBuilder<CommandSourceStack> root) {
        root.then(Commands.literal("code").executes(context -> tell(context.getSource()))
                .then(Commands.argument("code", StringArgumentType.greedyString()).requires(EarthWorlds::mayTravel)
                        .executes(context -> go(context.getSource(), StringArgumentType.getString(context, "code")))));
        root.then(Commands.literal("postcard").executes(context -> postcard(context.getSource())));
    }

    /** Where a player is for a code or a postcard, or null (with the reason told) when that can't be given out. */
    @Nullable private static GeoPoint here(CommandSourceStack source, ServerPlayer player) {
        if (EarthGuess.playing(player)) {
            source.sendFailure(Component.literal("No place codes or postcards during a Where on Earth round."));
            return null;
        }
        GeoPoint point = EarthRules.where(player);
        if (point == null) source.sendFailure(Component.literal("You are not on the Earth."));
        return point;
    }

    private static int tell(CommandSourceStack source) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        GeoPoint point = here(source, player);
        if (point == null) return 0;
        String code = code(point.latitude(), point.longitude(), bearing(player.getYRot()));
        source.sendSuccess(() -> Component.literal("Your place code: ").append(Component.literal(code).withStyle(style -> style.withColor(ChatFormatting.AQUA)
                        .withClickEvent(new ClickEvent(ClickEvent.Action.COPY_TO_CLIPBOARD, code))
                        .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.literal("Click to copy")))))
                .append(". Anyone can come here with /earth code " + code), false);
        return 1;
    }

    private static int go(CommandSourceStack source, String code) throws CommandSyntaxException {
        Spot spot = spot(code);
        if (spot == null) {
            source.sendFailure(Component.literal("'" + code + "' is not a place code. A code is twelve letters and digits, such as 7H2K-9QXM-4T1B."));
            return 0;
        }
        ServerPlayer player = source.getPlayerOrException();
        if (!EarthRules.pay(source, player, spot.latitude(), spot.longitude())) return 0;
        // Face the same way: look at a point a few kilometers off along the bearing.
        double turn = Math.toRadians(spot.bearing()), away = 0.05;
        double viewLatitude = Math.clamp(spot.latitude() + Math.cos(turn) * away, -LIMIT, LIMIT);
        double viewLongitude = spot.longitude() + Math.sin(turn) * away / Math.max(0.1, Math.cos(Math.toRadians(spot.latitude())));
        return CubicEvents.travel(source, player, spot.latitude(), spot.longitude(), viewLatitude, viewLongitude);
    }

    private static int postcard(CommandSourceStack source) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        GeoPoint point = here(source, player);
        if (point == null || !(CubicLevel.of(player.level()) instanceof ServerCubeWorld cubes)) return 0;
        int meters = (int) Math.floor(player.getY()) - cubes.generator().seaLevel() - 1;
        Platform.INSTANCE.sendToPlayer(player, new Payload(point.latitude(), point.longitude(), meters));
        return 1;
    }
}
