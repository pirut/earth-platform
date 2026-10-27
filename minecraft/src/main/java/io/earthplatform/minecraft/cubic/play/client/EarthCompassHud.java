package io.earthplatform.minecraft.cubic.play.client;

import io.earthplatform.geography.GeoPoint;
import io.earthplatform.geography.globe.MercatorGrid;
import io.earthplatform.minecraft.cubic.client.ClientCubeWorld;
import io.earthplatform.minecraft.cubic.jet.JetHud;
import io.earthplatform.minecraft.cubic.mixin.GuiOverlayAccess;
import io.earthplatform.minecraft.cubic.play.EarthCompass;
import io.earthplatform.minecraft.cubic.play.EarthRules;
import javax.annotation.Nullable;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.LodestoneTracker;

/**
 * While a compass is in hand on the Earth, the action bar says what it points to and how far that
 * is over the ground: a spot or famous place it was pointed at, the player it follows, a lodestone,
 * the holder's spawn for a plain compass, or where they died for a recovery compass. It keeps
 * quiet during a Where on Earth round, where a distance to a known spot would give the answer away
 * (and the needles spin, see {@code mixin.EarthCompassSpinMixin}).
 */
public final class EarthCompassHud {
    /** Whether this player is in a Where on Earth round. */
    private static boolean round;
    private static int ticks;
    /** The last line this put on the action bar, to tell it from someone else's message. */
    @Nullable private static Component shown;

    private EarthCompassHud() {}

    /** The server's word that a Where on Earth round started or ended for this player. */
    static void round(boolean on) { round = on; }

    public static boolean inRound() { return round; }

    public static void loggedOut() { round = false; shown = null; }

    public static void tick() {
        if (++ticks % 10 != 0) return;
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        // A glider's jet stream line carries the compass's along with it.
        if (player == null || JetHud.showing()) return;
        Component line = line(player);
        if (line == null) return;
        GuiOverlayAccess overlay = (GuiOverlayAccess) minecraft.gui;
        // A message from someone else, such as the server, keeps the action bar until its time is up.
        if (overlay.earthplatform$overlayTime() > 0 && overlay.earthplatform$overlay() != shown) return;
        minecraft.gui.setOverlayMessage(line, false);
        shown = line;
    }

    /** What the compass in the player's hands points to and how far it is, or null with none in hand, off the Earth or during a round. */
    @Nullable public static Component line(LocalPlayer player) {
        if (round) return null;
        ItemStack compass = held(player);
        if (compass == null) return null;
        MercatorGrid grid = grid();
        if (grid == null) return null;
        GeoPoint here = grid.geographic(player.getX(), player.getZ());
        GeoPoint to;
        Component name;
        CompoundTag target = EarthCompass.target(compass);
        if (target != null) {
            to = new GeoPoint(target.getDouble("lat"), target.getDouble("lon"));
            name = Component.literal(EarthCompass.label(target.getString("name"), to.latitude(), to.longitude()));
        } else {
            BlockPos pos = position(player, compass);
            if (pos == null) return null;
            to = grid.geographic(pos.getX() + 0.5, pos.getZ() + 0.5);
            name = Component.translatable(compass.is(Items.RECOVERY_COMPASS) ? "earthplatform.compass.death"
                    : compass.has(DataComponents.LODESTONE_TRACKER) ? "earthplatform.compass.lodestone" : "earthplatform.compass.spawn");
        }
        double km = EarthRules.kilometers(here.latitude(), here.longitude(), to.latitude(), to.longitude());
        return Component.translatable("earthplatform.compass.line", name,
                km < EarthCompass.THERE ? Component.translatable("earthplatform.compass.there") : Component.literal(EarthCompass.distance(km)));
    }

    /** Where the compass in hand points to on the Earth when this didn't point it, or null when it points nowhere here. */
    @Nullable private static BlockPos position(LocalPlayer player, ItemStack compass) {
        if (compass.is(Items.RECOVERY_COMPASS)) return player.getLastDeathLocation().filter(death -> death.dimension() == player.level().dimension())
                .map(GlobalPos::pos).orElse(null);
        LodestoneTracker tracker = compass.get(DataComponents.LODESTONE_TRACKER);
        if (tracker != null) return tracker.target().filter(pos -> pos.dimension() == player.level().dimension()).map(GlobalPos::pos).orElse(null);
        return player.clientLevel.getSharedSpawnPos();
    }

    /** The compass of any kind in the main hand, else the other hand. */
    @Nullable private static ItemStack held(LocalPlayer player) {
        for (InteractionHand hand : InteractionHand.values()) {
            ItemStack stack = player.getItemInHand(hand);
            if (stack.is(Items.COMPASS) || stack.is(Items.RECOVERY_COMPASS)) return stack;
        }
        return null;
    }

    /** The Earth's block grid, once the server has said its scale; null outside Earth worlds. */
    @Nullable static MercatorGrid grid() {
        ClientCubeWorld world = ClientCubeWorld.current();
        double scale = world == null ? 0 : world.far().metresPerBlock();
        return scale > 0 ? new MercatorGrid(scale) : null;
    }

    /** Where the first compass the player carries points, from this mod's own pointing, or null. */
    @Nullable static GeoPoint carriedTarget(LocalPlayer player) {
        ItemStack inHand = held(player);
        if (inHand != null) {
            CompoundTag target = EarthCompass.target(inHand);
            return target == null ? null : new GeoPoint(target.getDouble("lat"), target.getDouble("lon"));
        }
        var inventory = player.getInventory();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            CompoundTag target = inventory.getItem(slot).is(Items.COMPASS) ? EarthCompass.target(inventory.getItem(slot)) : null;
            if (target != null) return new GeoPoint(target.getDouble("lat"), target.getDouble("lon"));
        }
        return null;
    }
}
