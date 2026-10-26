package io.earthplatform.minecraft.cubic.play;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import io.earthplatform.geography.GeoPoint;
import io.earthplatform.minecraft.EarthPlatformMod;
import io.earthplatform.minecraft.cubic.earth.EarthGenerator;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import javax.annotation.Nullable;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetDefaultSpawnPositionPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.item.component.LodestoneTracker;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Compasses on the Earth. A plain compass points to where its holder spawns on the Earth; vanilla
 * would point it at the stand-in overworld's spawn, which means nothing in Earth coordinates. Any
 * compass can be pointed at a spot, a famous place, a home or another player, from the map (N) or
 * with {@code /earth compass}. It then works as a lodestone compass with no lodestone, and one
 * pointed at a player follows them while they are on the Earth. The distance shows on the action
 * bar while it is in hand (see {@code play.client.EarthCompassHud}).
 */
@EventBusSubscriber(modid = EarthPlatformMod.MOD_ID)
public final class EarthCompass {
    /** Where a pointed compass keeps its target in its custom data: latitude, longitude, a name and whom it follows. */
    public static final String TAG = "earthplatform_compass";
    /** Closer than this, in kilometers, a compass says you're there. */
    public static final double THERE = 0.02;
    /** A followed player has to move this far, or a hundredth of the way to them, before the needle is moved. */
    private static final double FOLLOW_STEP = 8;
    /** This close to the player a compass follows, its holder has caught up with them. */
    private static final double CAUGHT_UP = 16;
    /** Players in a Where on Earth round, whose clients were told to show no distances. */
    private static final Set<UUID> QUIET = new HashSet<>();

    private EarthCompass() {}

    static void register(LiteralArgumentBuilder<CommandSourceStack> root) {
        var compass = Commands.literal("compass").requires(EarthCompass::allowed).executes(context -> show(context.getSource()));
        EarthCommands.spots(compass, spot -> context -> point(context.getSource(), spot.apply(context)));
        compass.then(Commands.literal("player").then(Commands.argument("player", EntityArgument.player())
                .executes(context -> follow(context.getSource(), EntityArgument.getPlayer(context, "player")))));
        compass.then(Commands.literal("home").executes(context -> home(context.getSource())));
        compass.then(Commands.literal("spawn").executes(context -> spawn(context.getSource())));
        root.then(compass);
    }

    /** Pointing compasses goes with the survival map, which operators can switch off. */
    static boolean allowed(CommandSourceStack source) {
        return source.getPlayer() != null && (EarthConfig.SURVIVAL_MAP.get() || source.hasPermission(2));
    }

    // Pointing ------------------------------------------------------------------------------------

    private static int point(CommandSourceStack source, EarthSpawns.Spot spot) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        String name = spot.name().isEmpty() ? EarthPlaces.near(spot.latitude(), spot.longitude()).map(EarthPlaces.Place::label).orElse("") : spot.name();
        return aim(source, player, spot.latitude(), spot.longitude(), name, null);
    }

    private static int follow(CommandSourceStack source, ServerPlayer other) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        if (other == player) { source.sendFailure(Component.literal("That's you.")); return 0; }
        if (!EarthConfig.SHOW_PLAYERS_ON_MAP.get() && !source.hasPermission(2)) {
            source.sendFailure(Component.literal("This server keeps players off the map, so compasses can't follow them."));
            return 0;
        }
        GeoPoint at = EarthRules.where(other);
        if (at == null || other.isSpectator()) { source.sendFailure(Component.literal(other.getGameProfile().getName() + " isn't on the Earth.")); return 0; }
        int done = aim(source, player, at.latitude(), at.longitude(), other.getGameProfile().getName(), other.getUUID());
        if (done > 0) other.sendSystemMessage(Component.literal(player.getGameProfile().getName() + "'s compass now follows you.").withStyle(ChatFormatting.GRAY));
        return done;
    }

    private static int home(CommandSourceStack source) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        Optional<EarthSpawns.Spot> home = EarthSpawns.of(source.getServer()).home(player.getUUID());
        if (home.isEmpty()) { source.sendFailure(Component.literal("You have no home yet: set one with /earth sethome.")); return 0; }
        return aim(source, player, home.get().latitude(), home.get().longitude(), player.getGameProfile().getName() + "'s home", null);
    }

    /** Points a compass the player has at a spot, naming it after {@code name} (or the spot's latitude and longitude when empty). */
    private static int aim(CommandSourceStack source, ServerPlayer player, double latitude, double longitude, String name, @Nullable UUID follows) {
        EarthGenerator earth = EarthRules.earth(player);
        if (earth == null) { source.sendFailure(Component.literal("You can only point a compass on the Earth.")); return 0; }
        if (EarthGuess.playing(player)) { source.sendFailure(Component.literal("Not during a Where on Earth round: make your guess with /earth guess.")); return 0; }
        ItemStack compass = find(player);
        if (compass == null) { source.sendFailure(Component.literal("Hold or carry a compass to point it.")); return 0; }
        // One compass off a stack is pointed, staying where it was; the rest are put back once they no longer match it.
        ItemStack rest = compass.split(compass.getCount() - 1);
        set(compass, player, earth, latitude, longitude, name, follows);
        if (!rest.isEmpty() && !player.getInventory().add(rest)) player.drop(rest, false);
        GeoPoint here = EarthRules.where(player);
        String said = (follows != null ? "Your compass follows " : "Your compass points to ") + label(name, latitude, longitude)
                + (here == null ? "" : ": " + way(here, latitude, longitude)) + ".";
        source.sendSuccess(() -> Component.literal(said).withStyle(ChatFormatting.GREEN), false);
        EarthExplorer.award(player, "way_finder");
        return 1;
    }

    /** A plain compass again, pointing to the holder's spawn. */
    private static int spawn(CommandSourceStack source) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        ItemStack compass = held(player);
        if (compass == null) { source.sendFailure(Component.literal("Hold a compass to set it back.")); return 0; }
        compass.remove(DataComponents.LODESTONE_TRACKER);
        compass.remove(DataComponents.ITEM_NAME);
        compass.remove(DataComponents.LORE);
        CustomData.update(DataComponents.CUSTOM_DATA, compass, tag -> tag.remove(TAG));
        source.sendSuccess(() -> Component.literal("Your compass points to your spawn again.").withStyle(ChatFormatting.GREEN), false);
        return 1;
    }

    /** Where the compass in hand points, and how far that is. */
    private static int show(CommandSourceStack source) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        EarthGenerator earth = EarthRules.earth(player);
        ItemStack compass = held(player);
        if (compass == null) {
            source.sendFailure(Component.literal("Hold a compass to see where it points. Point one with /earth compass to, place, here, player or home, or on the map (N)."));
            return 0;
        }
        GeoPoint here = EarthRules.where(player);
        if (earth == null || here == null) { source.sendFailure(Component.literal("You can only use a compass this way on the Earth.")); return 0; }
        if (EarthGuess.playing(player)) { source.sendFailure(Component.literal("Nice try! Make your guess with /earth guess.")); return 0; }
        CompoundTag target = target(compass);
        GeoPoint to;
        String label;
        if (target != null) {
            to = new GeoPoint(target.getDouble("lat"), target.getDouble("lon"));
            label = label(target.getString("name"), to.latitude(), to.longitude());
        } else if (compass.has(DataComponents.LODESTONE_TRACKER)) {
            source.sendSuccess(() -> Component.literal("Your compass points to a lodestone."), false);
            return 1;
        } else {
            to = spawnOf(player, earth);
            label = "your spawn";
        }
        String said = "Your compass points to " + label + ": " + way(here, to.latitude(), to.longitude()) + ".";
        source.sendSuccess(() -> Component.literal(said), false);
        return 1;
    }

    /** Gives a compass its target: a lodestone tracker with no lodestone, the spot in its custom data, and a name and lore saying where. */
    private static void set(ItemStack compass, ServerPlayer holder, EarthGenerator earth, double latitude, double longitude, String name, @Nullable UUID follows) {
        retarget(compass, holder, earth, latitude, longitude);
        CustomData.update(DataComponents.CUSTOM_DATA, compass, tag -> {
            CompoundTag target = new CompoundTag();
            target.putDouble("lat", latitude);
            target.putDouble("lon", longitude);
            if (!name.isEmpty()) target.putString("name", name);
            if (follows != null) target.putUUID("follows", follows);
            tag.put(TAG, target);
        });
        String label = label(name, latitude, longitude);
        compass.set(DataComponents.ITEM_NAME, Component.translatableWithFallback("item.earthplatform.compass", "Compass to %s", label));
        List<Component> lore = new ArrayList<>();
        if (follows != null) lore.add(Component.translatableWithFallback("item.earthplatform.compass.follows", "Follows %s while they're on the Earth", name));
        else {
            if (!name.isEmpty()) lore.add(Component.literal(coordinates(latitude, longitude)));
            EarthCountries.Country country = EarthCountries.at(latitude, longitude);
            if (country != null) lore.add(Component.literal(country.name()));
        }
        compass.set(DataComponents.LORE, new ItemLore(lore.stream().map(line -> (Component) line.copy().withStyle(style -> style.withColor(ChatFormatting.GRAY).withItalic(false))).toList()));
    }

    /** Moves a compass's needle to a latitude and longitude, leaving what it says it points to alone. */
    private static void retarget(ItemStack compass, ServerPlayer holder, EarthGenerator earth, double latitude, double longitude) {
        int[] at = nearest(earth, holder.getX(), latitude, longitude);
        compass.set(DataComponents.LODESTONE_TRACKER, new LodestoneTracker(Optional.of(GlobalPos.of(holder.level().dimension(),
                new BlockPos(at[0], earth.seaLevel(), at[1]))), false));
    }

    /**
     * Block X and Z of a point, X from the copy of the world nearest {@code x}: the Earth repeats
     * east and west, so a compass near the date line points the short way across it.
     */
    private static int[] nearest(EarthGenerator earth, double x, double latitude, double longitude) {
        int[] at = earth.block(latitude, longitude);
        double width = earth.grid().worldWidthBlocks();
        long shifted = Math.round(at[0] + Math.round((x - at[0]) / width) * width);
        // Block positions only travel so far on the wire.
        if (Math.abs(shifted) < 30_000_000) at[0] = (int) shifted;
        return at;
    }

    /** The compass to point: the one in hand, else the first carried that no lodestone needs. */
    @Nullable private static ItemStack find(ServerPlayer player) {
        ItemStack compass = held(player);
        if (compass != null) return compass;
        Inventory inventory = player.getInventory();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            LodestoneTracker tracker = stack.get(DataComponents.LODESTONE_TRACKER);
            if (stack.is(Items.COMPASS) && (tracker == null || !tracker.tracked())) return stack;
        }
        return null;
    }

    /** The compass in the player's main hand, else the other. */
    @Nullable private static ItemStack held(ServerPlayer player) {
        for (InteractionHand hand : InteractionHand.values()) if (player.getItemInHand(hand).is(Items.COMPASS)) return player.getItemInHand(hand);
        return null;
    }

    /** Whether two stacks are one compass following the same player, wherever its needle points. */
    public static boolean sameFollower(ItemStack a, ItemStack b) {
        if (!a.is(Items.COMPASS) || !b.is(Items.COMPASS) || a.getCount() != b.getCount()) return false;
        CompoundTag first = target(a), second = target(b);
        return first != null && second != null && first.hasUUID("follows") && second.hasUUID("follows")
                && first.getUUID("follows").equals(second.getUUID("follows"));
    }

    /** A pointed compass's target, or null for a compass this didn't point. */
    @Nullable public static CompoundTag target(ItemStack stack) {
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        if (data == null || !data.contains(TAG)) return null;
        CompoundTag tag = data.copyTag().getCompound(TAG);
        return tag.contains("lat") && tag.contains("lon") ? tag : null;
    }

    /** What a target is called: its name, else its latitude and longitude. */
    public static String label(String name, double latitude, double longitude) { return name.isEmpty() ? coordinates(latitude, longitude) : name; }

    public static String coordinates(double latitude, double longitude) {
        return String.format(Locale.ROOT, "%.4f° %s, %.4f° %s", Math.abs(latitude), latitude < 0 ? "S" : "N", Math.abs(longitude), longitude < 0 ? "W" : "E");
    }

    /** How far a spot is from a player and which way, as in "12.3 km northeast", or that they're there. */
    private static String way(GeoPoint here, double latitude, double longitude) {
        double km = EarthRules.kilometers(here.latitude(), here.longitude(), latitude, longitude);
        return km < THERE ? "you're there" : distance(km) + " " + EarthRules.heading(here.latitude(), here.longitude(), latitude, longitude);
    }

    /** A distance in kilometers as people say it: meters close up, a tenth of a kilometer to 100 km, whole kilometers beyond. */
    public static String distance(double km) {
        // Bounds a rounding short of 1 and 100, so 999.6 m reads 1.0 km rather than 1,000 m.
        if (km < 0.9995) return String.format(Locale.ROOT, "%,d m", Math.round(km * 1000));
        if (km < 99.95) return String.format(Locale.ROOT, "%.1f km", km);
        return String.format(Locale.ROOT, "%,d km", Math.round(km));
    }

    // Spawns --------------------------------------------------------------------------------------

    /** Where a player lands without a bed: their own or their team's spawn, else the server spawn, else the world's home. */
    static GeoPoint spawnOf(ServerPlayer player, EarthGenerator earth) {
        EarthSpawns spawns = EarthSpawns.of(player.server);
        return spawns.personal(player).or(spawns::spawn).map(spot -> new GeoPoint(spot.latitude(), spot.longitude()))
                .orElseGet(() -> new GeoPoint(earth.settings().latitude(), earth.settings().longitude()));
    }

    /** Tells a player on the Earth where their spawn is, which is where a plain compass points. */
    static void tellSpawn(ServerPlayer player) {
        EarthGenerator earth = EarthRules.earth(player);
        if (earth == null) return;
        GeoPoint spawn = spawnOf(player, earth);
        int[] at = nearest(earth, player.getX(), spawn.latitude(), spawn.longitude());
        player.connection.send(new ClientboundSetDefaultSpawnPositionPacket(new BlockPos(at[0], earth.seaLevel(), at[1]), 0.0F));
    }

    /** Some spawn moved: everyone on the Earth hears where theirs is now. */
    static void spawnsChanged(MinecraftServer server) {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) tellSpawn(player);
    }

    // The vanilla spawn packets go out as a player joins, respawns or changes dimension, before these events.
    @SubscribeEvent public static void loggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) tellSpawn(player);
    }

    @SubscribeEvent public static void respawned(PlayerEvent.PlayerRespawnEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) tellSpawn(player);
    }

    @SubscribeEvent public static void changedDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) tellSpawn(player);
    }

    // Each second ---------------------------------------------------------------------------------

    static void second(MinecraftServer server) {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            // A Where on Earth round: a distance to a known spot would give the answer away.
            boolean quiet = EarthGuess.playing(player);
            if (quiet ? QUIET.add(player.getUUID()) : QUIET.remove(player.getUUID()))
                PacketDistributor.sendToPlayer(player, new EarthMap.Payload(quiet ? EarthMap.HIDE_COMPASS : EarthMap.SHOW_COMPASS, 0, 0));
            follow(server, player);
        }
    }

    /** Moves the needle of a compass in the player's hands that follows someone, when they have moved enough to notice. */
    private static void follow(MinecraftServer server, ServerPlayer player) {
        EarthGenerator earth = null;
        for (InteractionHand hand : InteractionHand.values()) {
            ItemStack stack = player.getItemInHand(hand);
            CompoundTag target = stack.is(Items.COMPASS) ? target(stack) : null;
            if (target == null || !target.hasUUID("follows")) continue;
            if (earth == null && (earth = EarthRules.earth(player)) == null) return;
            ServerPlayer other = server.getPlayerList().getPlayer(target.getUUID("follows"));
            if (other == null || other.isSpectator() || other.level() != player.level()) continue;
            if (!EarthConfig.SHOW_PLAYERS_ON_MAP.get() && !player.hasPermissions(2)) continue;
            GeoPoint at = EarthRules.where(other);
            if (at == null) continue;
            // Measured in the copy of the world the needle points into, which may be across the date line from them.
            int[] there = nearest(earth, player.getX(), at.latitude(), at.longitude());
            LodestoneTracker tracker = stack.get(DataComponents.LODESTONE_TRACKER);
            BlockPos needle = tracker == null ? null : tracker.target().map(GlobalPos::pos).orElse(null);
            double gap = Math.hypot(there[0] - player.getX(), there[1] - player.getZ());
            if (gap <= CAUGHT_UP && Math.abs(other.getY() - player.getY()) <= CAUGHT_UP) EarthExplorer.award(player, "found_you");
            if (needle != null && Math.hypot(there[0] - needle.getX(), there[1] - needle.getZ()) < Math.max(FOLLOW_STEP, gap / 100)) continue;
            retarget(stack, player, earth, at.latitude(), at.longitude());
            CustomData.update(DataComponents.CUSTOM_DATA, stack, tag -> {
                CompoundTag moved = tag.getCompound(TAG);
                moved.putDouble("lat", at.latitude());
                moved.putDouble("lon", at.longitude());
                tag.put(TAG, moved);
            });
        }
    }

    @SubscribeEvent public static void loggedOut(PlayerEvent.PlayerLoggedOutEvent event) { QUIET.remove(event.getEntity().getUUID()); }

    @SubscribeEvent public static void stopped(ServerStoppedEvent event) { QUIET.clear(); }
}
