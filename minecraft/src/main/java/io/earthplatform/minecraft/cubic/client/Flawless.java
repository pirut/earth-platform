package io.earthplatform.minecraft.cubic.client;

import io.earthplatform.minecraft.cubic.Cubic;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Function;
import net.minecraft.client.Minecraft;
import net.neoforged.fml.ModList;

/**
 * "Flawless frames": a video renderer such as Replay Mod asks every renderer to finish each frame
 * completely, however long it takes, instead of streaming terrain in over later frames. This is the
 * FREX convention Sodium follows on NeoForge: a mod names a handler class under the mod property
 * {@code frex:flawless_frames_handler}, and each renderer hands that class's static
 * {@code acceptController(Function<String, Consumer<Boolean>>)} a way to switch it on and off.
 * <p>
 * Our near and far terrain draw outside Sodium, so they need their own controller: without it a
 * Replay Mod render drew only a patch of near cubes and empty sky where the far terrain should be.
 */
public final class Flawless {
    private static final Set<Object> ACTIVE = ConcurrentHashMap.newKeySet();

    private Flawless() {}

    public static boolean active() { return !ACTIVE.isEmpty(); }

    /** Offers our controller to every mod that asks for flawless frames. Runs once, at client setup. */
    public static void offer() {
        Function<String, Consumer<Boolean>> provider = name -> {
            Object token = new Object();
            return on -> { if (on) ACTIVE.add(token); else ACTIVE.remove(token); };
        };
        for (var mod : ModList.get().getMods()) {
            if (!(mod.getModProperties().get("frex:flawless_frames_handler") instanceof String handler)) continue;
            try {
                MethodHandles.publicLookup().findStatic(Class.forName(handler), "acceptController",
                        MethodType.methodType(void.class, Function.class)).invoke(provider);
                Cubic.LOGGER.info("Earth terrain renders flawless frames for {}", mod.getModId());
            } catch (Throwable e) {
                Cubic.LOGGER.warn("Could not offer flawless frames to {} ({})", mod.getModId(), e.toString());
            }
        }
    }

    /**
     * The far terrain's fade clock while frames are flawless, in hundredths of a second: game time,
     * which a video renderer steps by exactly one frame's worth, so fades last as long in the video
     * as they do in play however slowly each frame renders.
     */
    static int clock() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) return 1;
        double ticks = minecraft.level.getGameTime() + minecraft.getTimer().getGameTimeDeltaPartialTick(true);
        int now = (int) (ticks * 5) & 0x7FFFFFF;
        return now == 0 ? 1 : now;
    }

    /**
     * Before a flawless frame draws: uploads and meshes everything that can finish without more game
     * ticks, near cubes and far columns alike, so the frame shows the terrain complete. Gives up after
     * a while rather than hang a render on something that never arrives.
     */
    static void settle(ClientCubeWorld world) {
        var camera = Minecraft.getInstance().getCameraEntity();
        if (camera == null) return;
        long until = System.nanoTime() + 20_000_000_000L;
        while (System.nanoTime() < until) {
            int left = world.renderer().settle(camera.getEyePosition(), camera.getYRot()) + world.far().settle(camera.getBlockX(), camera.getBlockZ());
            if (left == 0) return;
            java.util.concurrent.locks.LockSupport.parkNanos(2_000_000L);
        }
        Cubic.LOGGER.warn("Flawless frame drawn before terrain finished");
    }
}
