package io.earthplatform.minecraft.cubic.play.client;

import io.earthplatform.minecraft.EarthPlatformMod;
import io.earthplatform.minecraft.cubic.client.ClientCubeWorld;
import io.earthplatform.minecraft.cubic.client.CubicClient;
import io.earthplatform.minecraft.cubic.client.Flawless;
import io.earthplatform.minecraft.cubic.play.EarthShots;
import java.io.IOException;
import java.util.Locale;
import javax.annotation.Nullable;
import net.minecraft.ChatFormatting;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

/**
 * The built-in camera shots. {@code /earth orbit} circles the camera round where the player stands;
 * {@code /earth flyover} flies them in a straight line to another place, a set height above the
 * ground. Like the travel map's flight ({@link EarthFlight}) this is camera work on the client: the
 * server only says who may and where to (see {@link EarthShots}).
 * <p>
 * A shot never shows terrain arriving. It first waits where it starts until the near and far
 * terrain there is complete. A live flyover then slows down wherever the far terrain ahead has not
 * caught up. A recorded shot ({@code /earth record ...}) goes further: it runs on its own clock, one
 * frame at a time, and takes each frame only once everything in it has arrived and been meshed
 * ({@link Flawless}, as a video renderer asks for), then hands it to {@link ShotRecorder}. The game's
 * own clock steps one frame's worth with every frame taken and stands still while one is held, so
 * water, clouds and the sun move in the video as they would in play.
 */
public final class EarthCinema {
    /** Frames a second of a recorded shot. */
    static final int FPS = Mth.clamp(Integer.getInteger("earthplatform.shot.fps", 60), 10, 240);
    /** Whether a live flyover slows down for far terrain that is still arriving ahead. */
    private static final boolean PACE = !"false".equals(System.getProperty("earthplatform.shot.pace"));
    /** Whether a recorded shot holds each frame until its terrain is complete. */
    private static final boolean HOLD = !"false".equals(System.getProperty("earthplatform.shot.hold"));
    /** The longest a shot waits for its first view to be complete, and a recorded shot for any one frame. */
    private static final long PREPARE_NANOS = 120_000_000_000L, HOLD_NANOS = 10_000_000_000L;
    /** No cube has arrived for this long: the server has sent what it has for where the player is. */
    private static final long QUIET_NANOS = 150_000_000L;
    /** Frames in a row that ran out of patience before a recorded shot stops holding: the terrain is not coming. */
    private static final int MISSES = 8;
    /** Seconds a live shot takes to swing out from the player's eyes, and back to them when it is stopped. */
    private static final double LEAD_IN = 2.5, LEAD_OUT = 1.5;
    /** Below this distance from the player's eyes the camera stays in first person, as the travel map's flight does. */
    private static final double DETACH = 2;
    /** Frames a recorded shot draws of its first view before it keeps one. */
    private static final int WARM_UP = 12;
    /** How eagerly a flyover's camera follows the height it should be at (see {@link ShotPath.Follower}). */
    private static final double CLIMB = 1.2;
    /** A flyover on a server keeps under the speed the server lets a player move at, in blocks a second. */
    private static final double SERVER_SPEED = 160;

    private enum Phase { PREPARE, ROLL, OUT }

    @Nullable private static EarthCinema current;

    private final EarthShots.Shot shot;
    private final boolean orbiting;
    private final Object flawless = new Object();
    private Phase phase = Phase.PREPARE;
    private final long began = System.nanoTime();
    private long readySince = -1, lastFrame, heldSince, pumped, profiled;
    /** The shot's own clock, in seconds since it began to roll; and how fast it runs against the wall clock. */
    private double time, rate = 1, outAt;
    private boolean hidGui;
    private ShotPath.View startView, view;

    // An orbit.
    private ShotPath.Orbit orbit;

    // A flyover.
    private double fromX, fromZ, toX, toZ, length, seconds, height, ramp, pathYaw, spacing;
    private double[] clearance;
    private ShotPath.Follower altitude, pitch;

    // A recording.
    @Nullable private ShotRecorder recorder;
    private boolean drawn, complete, holding;
    private int misses, taken, holds, gaveUp, warmed;
    private long dayStart, gameStart, rolledAt, heldNanos;

    private EarthCinema(EarthShots.Shot shot) {
        this.shot = shot;
        orbiting = shot.kind() == EarthShots.ORBIT;
    }

    // Starting and ending ---------------------------------------------------------------------------

    /** The server's word: make this shot, or stop the one going. */
    public static void received(EarthShots.Shot shot) {
        Minecraft minecraft = Minecraft.getInstance();
        minecraft.execute(() -> {
            if (shot.kind() == EarthShots.STOP) {
                if (current != null) current.stop();
                return;
            }
            LocalPlayer player = minecraft.player;
            if (player == null || ClientCubeWorld.current() == null || EarthFlight.flying()) return;
            if (current != null) current.end();
            current = new EarthCinema(shot);
        });
    }

    public static boolean active() { return current != null; }

    /** Whether a shot is carrying the player through the air: they are not held for cubes, as a glider is not. */
    public static boolean carrying() {
        EarthCinema shot = current;
        return shot != null && !shot.orbiting && shot.phase == Phase.ROLL;
    }

    /** Stops the shot the gentle way: a live orbit swings back to the player first. */
    private void stop() {
        if (phase == Phase.ROLL && orbiting && recorder == null) {
            phase = Phase.OUT;
            outAt = time;
        } else {
            end();
        }
    }

    /** The shot is over, finished or cut short; the screen goes back to normal and a recording is closed. */
    private void end() {
        if (current != this) return;
        current = null;
        Minecraft minecraft = Minecraft.getInstance();
        if (hidGui) minecraft.options.hideGui = false;
        Flawless.hold(flawless, false);
        ShotRecorder recording = recorder;
        recorder = null;
        if (recording == null) return;
        EarthPlatformMod.LOGGER.info("Recorded {} frames of {} in {} s; {} of them waited for terrain, {} s in all, and {} were taken without it",
                recording.frames(), orbiting ? "an orbit" : "a flyover", (System.nanoTime() - rolledAt) / 1_000_000_000L, holds, heldNanos / 1_000_000_000L, gaveUp);
        finish(recording);
    }

    /** Closes a recording off the render thread (FFmpeg may have frames to go) and tells the player where it is. */
    private static void finish(ShotRecorder recording) {
        say(Component.translatable("earthplatform.shot.saving", recording.frames()).withStyle(ChatFormatting.GRAY));
        Util.ioPool().execute(() -> {
            String failure = recording.finish();
            String path = recording.output().toAbsolutePath().toString();
            Component file = Component.literal(recording.output().getFileName().toString()).withStyle(style -> style
                    .withColor(ChatFormatting.AQUA).withUnderlined(true).withClickEvent(new ClickEvent(ClickEvent.Action.OPEN_FILE, path)));
            Minecraft.getInstance().execute(() -> {
                if (failure != null) say(Component.translatable("earthplatform.shot.failed", failure).withStyle(ChatFormatting.RED));
                else if (recording.video()) say(Component.translatable("earthplatform.shot.savedVideo", file).withStyle(ChatFormatting.GREEN));
                else say(Component.translatable("earthplatform.shot.savedImages", recording.frames(), file, FPS).withStyle(ChatFormatting.GREEN));
            });
        });
    }

    private static void say(Component text) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player != null) player.displayClientMessage(text, false);
    }

    // Each tick -------------------------------------------------------------------------------------

    /** Carries a flyover's player along before the game moves them, so the server streams the ground under the camera. */
    public static void tickStart() {
        EarthCinema shot = current;
        if (shot != null && carrying()) shot.carry();
    }

    public static void clientTick() {
        EarthCinema shot = current;
        if (shot == null) return;
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        ClientCubeWorld world = ClientCubeWorld.current();
        if (player == null || world == null || !player.isAlive()) { shot.end(); return; }
        if (shot.phase == Phase.PREPARE) shot.prepare(world, player);
        else if (carrying()) shot.carry();
    }

    /** Waits where the shot starts until the terrain there is complete, so it opens on a finished view. */
    private void prepare(ClientCubeWorld world, LocalPlayer player) {
        long now = System.nanoTime();
        boolean ready = finished(world, now);
        if (!ready) readySince = -1;
        else if (readySince < 0) readySince = now;
        // Long enough to read how to stop it, even when everything is already here.
        if (ready && now - readySince > 500_000_000L && now - began > 2_000_000_000L || now - began > PREPARE_NANOS) {
            roll(world, player);
            return;
        }
        player.displayClientMessage(Component.translatable(shot.record() ? "earthplatform.shot.preparingRecording" : "earthplatform.shot.preparing",
                world.far().settledPercent(), Minecraft.getInstance().options.keyShift.getTranslatedKeyMessage()), true);
    }

    /** Whether nothing is left to arrive or be meshed for where the player is. */
    private static boolean finished(ClientCubeWorld world, long now) {
        return world.far().outstanding() == 0 && world.renderer().pendingCount() == 0 && now - world.lastArrival() > QUIET_NANOS;
    }

    private void roll(ClientCubeWorld world, LocalPlayer player) {
        Minecraft minecraft = Minecraft.getInstance();
        Vec3 eye = player.getEyePosition();
        startView = view = new ShotPath.View(eye.x, eye.y, eye.z, player.getYRot(), player.getXRot());
        if (orbiting) startOrbit(world, eye, player.getYRot());
        else startFlyover(world, player, eye);
        if (shot.record()) {
            try {
                recorder = ShotRecorder.open(orbiting ? "orbit" : "flyover", FPS);
                Flawless.hold(flawless, true);
                dayStart = player.level().getDayTime();
                gameStart = player.level().getGameTime();
                rolledAt = System.nanoTime();
            } catch (IOException | RuntimeException e) {
                say(Component.translatable("earthplatform.shot.failed", e.toString()).withStyle(ChatFormatting.RED));
                EarthPlatformMod.LOGGER.warn("Could not start recording a shot", e);
                end();
                return;
            }
        }
        // The action bar's last line would be in the first frames.
        minecraft.gui.setOverlayMessage(Component.empty(), false);
        hidGui = !minecraft.options.hideGui;
        minecraft.options.hideGui = true;
        phase = Phase.ROLL;
        time = 0;
        lastFrame = heldSince = System.nanoTime();
        if (!orbiting) {
            // A creative player hovers where the flyover leaves them rather than falling out of the sky.
            if (player.getAbilities().mayfly && !player.getAbilities().flying) {
                player.getAbilities().flying = true;
                player.onUpdateAbilities();
            }
            carry();
        }
    }

    // An orbit --------------------------------------------------------------------------------------

    private void startOrbit(ClientCubeWorld world, Vec3 eye, float yaw) {
        double radius = Double.isNaN(shot.a()) ? 200 : shot.a(), turn = Double.isNaN(shot.b()) ? 40 : shot.b();
        double above = Double.isNaN(shot.c()) ? Math.max(12, radius * 0.3) : shot.c();
        // One height all the way round, clear of the highest ground and treetops under the circle.
        double highest = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < 96; i++) {
            double angle = i * Math.PI / 48;
            highest = Math.max(highest, ground(world, eye.x + Math.sin(angle) * radius, eye.z - Math.cos(angle) * radius, Double.NEGATIVE_INFINITY));
        }
        above = Math.max(above, highest + 6 - eye.y);
        orbit = new ShotPath.Orbit(eye.x, eye.y, eye.z, radius, above, yaw, turn);
        seconds = turn;
    }

    // A flyover -------------------------------------------------------------------------------------

    private void startFlyover(ClientCubeWorld world, LocalPlayer player, Vec3 eye) {
        fromX = eye.x;
        fromZ = eye.z;
        toX = shot.a() + 0.5;
        toZ = shot.b() + 0.5;
        length = Math.max(1, Math.hypot(toX - fromX, toZ - fromZ));
        height = Double.isNaN(shot.d()) ? 120 : shot.d();
        seconds = Double.isNaN(shot.c()) ? Mth.clamp(length / 80, 20, 240) : shot.c();
        ramp = Math.min(4, seconds / 4);
        // A server puts back a player who moves faster than it allows; the singleplayer owner may go at any speed.
        if (!Minecraft.getInstance().isLocalServer()) seconds = Math.max(seconds, length / SERVER_SPEED + ramp);
        pathYaw = Math.toDegrees(Math.atan2(-(toX - fromX), toZ - fromZ));
        altitude = new ShotPath.Follower(eye.y, CLIMB);
        pitch = new ShotPath.Follower(player.getXRot(), 1.5);
        profile(world);
    }

    /**
     * Works out the height to keep clear of along the line from the far terrain held now. Far ground
     * is known coarsely at first and more finely as the flyover nears it, so this is done again
     * every couple of seconds; the camera follows the result smoothly (see {@link ShotPath.Follower}).
     */
    private void profile(ClientCubeWorld world) {
        int samples = Mth.clamp((int) Math.ceil(length / 32) + 1, 2, 4097);
        spacing = length / (samples - 1);
        double[] ground = new double[samples];
        // Half the flyover's height to either side counts too: a cliff beside the line is as much in the way as one on it.
        double sideX = -(toZ - fromZ) / length, sideZ = (toX - fromX) / length, wide = Math.min(height, 128) / 2;
        for (int i = 0; i < samples; i++) {
            double part = i / (double) (samples - 1), x = Mth.lerp(part, fromX, toX), z = Mth.lerp(part, fromZ, toZ);
            double top = ground(world, x, z, Double.NaN);
            for (int side = -2; side <= 2; side++) {
                if (side == 0) continue;
                double beside = ground(world, x + sideX * wide * side / 2, z + sideZ * wide * side / 2, Double.NaN);
                if (!Double.isNaN(beside)) top = Double.isNaN(top) ? beside : Math.max(top, beside);
            }
            ground[i] = top;
        }
        // Clear of everything within three seconds' flight, and never less than 256 blocks.
        double reach = Math.max(256, 3 * length / (seconds - ramp));
        clearance = ShotPath.clearance(ground, Math.max(4, (int) Math.round(reach / spacing)));
        profiled = System.nanoTime();
    }

    /** The top of the ground at a spot, trees and all: the near cubes' where they are loaded, else the far terrain's. */
    private static double ground(ClientCubeWorld world, double x, double z, double unknown) {
        int bx = Mth.floor(x), bz = Mth.floor(z);
        double far = world.far().groundAt(bx, bz, Double.NaN);
        int near = world.skyTop(bx, bz);
        if (Double.isNaN(far)) return near > ClientCubeWorld.MIN_Y ? near + 1 : unknown;
        return Math.max(far, near + 1);
    }

    /** Where the flyover's camera is on its line at the shot's time now. */
    private double along() { return ShotPath.travelled(time, seconds, length, ramp); }

    /** Moves the flyover's view on by a stretch of the shot's clock. */
    private void fly(ClientCubeWorld world, double dt) {
        double gone = along(), part = gone / length;
        double x = Mth.lerp(part, fromX, toX), z = Mth.lerp(part, fromZ, toZ);
        // The camera trails what it follows by about 2 / quickness seconds: it follows the height that far ahead, so it is level with the plan.
        double lead = ShotPath.travelled(time + 2 / CLIMB, seconds, length, ramp) - gone;
        altitude.step(ShotPath.sample(clearance, (gone + lead) / spacing) + height, dt);
        // Whatever the plan said, never into the ground that is really here.
        altitude.atLeast(ground(world, x, z, Double.NEGATIVE_INFINITY) + Math.min(height, 6));
        double y = altitude.value();
        // Looks at the ground a way ahead, so the horizon sits in the upper part of the picture and the camera looks up at a ridge.
        double ahead = Math.max(4 * height, 200);
        double target = ShotPath.sample(clearance, (gone + ahead) / spacing) + 0.3 * height;
        pitch.step(Mth.clamp(Math.toDegrees(Math.atan2(y - target, ahead)), -10, 50), dt);
        double yaw = startView.yaw() + ShotPath.wrap(pathYaw - startView.yaw()) * ShotPath.smooth(time / LEAD_IN);
        view = new ShotPath.View(x, y, z, yaw, pitch.value());
    }

    /** Puts the player where the flyover's camera is, looking where it looks. */
    private void carry() {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null || view == null) return;
        player.absMoveTo(view.x(), view.y() - player.getEyeHeight(), view.z(), (float) view.yaw(), (float) view.pitch());
        player.setDeltaMovement(Vec3.ZERO);
        player.resetFallDistance();
    }

    // Each frame ------------------------------------------------------------------------------------

    /**
     * Called by the game's timer at the start of every frame. Moves a live shot's clock on by the
     * time that passed. For a recorded shot it takes the frame just drawn, if that was complete,
     * and returns how many game ticks this frame is worth: one frame's share after a frame was
     * taken, none while a frame is held. {@code NaN} leaves the game's clock alone.
     */
    public static float frameTicks() {
        EarthCinema shot = current;
        return shot == null ? Float.NaN : shot.frame();
    }

    private float frame() {
        Minecraft minecraft = Minecraft.getInstance();
        ClientCubeWorld world = ClientCubeWorld.current();
        long now = System.nanoTime();
        if (world == null || minecraft.player == null) { end(); return Float.NaN; }
        if (phase == Phase.PREPARE) {
            // Sneak calls it off, but not the sneak of someone still typing the command.
            if (minecraft.screen == null && minecraft.options.keyShift.isDown() && now - began > 500_000_000L) end();
            return Float.NaN;
        }
        // Any screen, the pause menu included, ends the shot: the picture would have it in.
        if (minecraft.screen != null || minecraft.options.keyShift.isDown()) {
            if (minecraft.screen != null) end();
            else if (phase == Phase.ROLL) stop();
            if (current != this) return Float.NaN;
        }
        if (recorder == null) {
            double dt = Math.min((now - lastFrame) / 1e9, 0.25);
            lastFrame = now;
            if (!orbiting && phase == Phase.ROLL) {
                // Slows where under four fifths of the far terrain in view is at its final detail, down to a quarter speed.
                double wanted = PACE ? Mth.clamp((world.far().settledInViewPercent() - 40) / 40.0, 0.25, 1) : 1;
                rate += (wanted - rate) * (1 - Math.exp(-dt / 1.0));
                if (now - profiled > 2_000_000_000L) profile(world);
            }
            advance(world, dt * (orbiting ? 1 : rate));
            return Float.NaN;
        }
        return record(world, now);
    }

    private float record(ClientCubeWorld world, long now) {
        ShotRecorder recording = recorder;
        float step = 20.0F / FPS;
        // Nothing drawn since the clock last moved: the first frame, or a frame the game skipped.
        if (!drawn) return 0;
        drawn = false;
        // The cut from the player's eyes to the shot's first view: a shader pack that blends each frame with the
        // ones before it (TAA) needs a few frames of the new view before one is clean enough to keep.
        if (warmed < WARM_UP) { warmed++; return 0; }
        boolean patience = now - heldSince > HOLD_NANOS;
        if (HOLD && misses < MISSES && !complete && !patience) {
            // Held: the same moment is drawn again once more has arrived. The game's clock stands still, so the
            // terrain's own ticking (what to ask the server for next, what to mesh) is done from here.
            holding = true;
            if (now - pumped > 50_000_000L) {
                pumped = now;
                CubicClient.terrainTick();
            }
            return 0;
        }
        misses = complete ? 0 : misses + 1;
        if (!complete) gaveUp++;
        if (holding) { holds++; heldNanos += now - heldSince; holding = false; }
        if (!recording.capture()) {
            say(Component.translatable("earthplatform.shot.failed", String.valueOf(recording.failure())).withStyle(ChatFormatting.RED));
            end();
            return Float.NaN;
        }
        taken++;
        // An orbit's last frame is the one before its first comes round again, so the video loops.
        if (time + 1.0 / FPS >= seconds - 0.5 / FPS) {
            if (!orbiting) carry();
            end();
            return Float.NaN;
        }
        if (!orbiting && now - profiled > 2_000_000_000L) profile(world);
        advance(world, 1.0 / FPS);
        heldSince = System.nanoTime();
        return step;
    }

    /** The level is about to be drawn with everything meshed that could be: notes whether that was everything. */
    public static void drawing(ClientCubeWorld world) {
        EarthCinema shot = current;
        if (shot == null || shot.recorder == null) return;
        shot.drawn = true;
        shot.complete = finished(world, System.nanoTime());
    }

    /** How far the game's clock has fallen behind the wall clock through recorded shots, in nanoseconds. */
    private static long behind;
    private static long shaderBase = Long.MIN_VALUE;

    /**
     * The time a shader pack's own clock should read, given the real time in nanoseconds: a
     * recorded shot's frames are each a frame's time apart however long they took, and a held
     * frame takes none, so the pack's water and clouds move in the video as they do in play.
     */
    public static long shaderClock(long real) {
        EarthCinema shot = current;
        if (shot == null || shot.recorder == null) {
            shaderBase = Long.MIN_VALUE;
            return real - behind;
        }
        if (shaderBase == Long.MIN_VALUE) shaderBase = real - behind - shot.taken * (1_000_000_000L / FPS);
        long now = shaderBase + shot.taken * (1_000_000_000L / FPS);
        behind = real - now;
        return now;
    }

    /**
     * The level's time while a shot is recorded: where it stood when the recording began plus the
     * frames taken since, whatever the server or the game's own ticking says. A time of day below
     * zero means the daylight cycle is off, and stays as it is.
     */
    public static long videoTime(long time, boolean ofDay) {
        EarthCinema shot = current;
        if (shot == null || shot.recorder == null || ofDay && time < 0) return time;
        return (ofDay ? shot.dayStart : shot.gameStart) + (long) (shot.taken * 20.0 / FPS);
    }

    /** Moves the shot's clock and view on. */
    private void advance(ClientCubeWorld world, double dt) {
        time += dt;
        if (orbiting) {
            if (phase == Phase.OUT && time - outAt >= LEAD_OUT) end();
            return;
        }
        fly(world, dt);
        if (recorder == null && time >= seconds) {
            carry();
            end();
        }
    }

    /**
     * Where the camera is and which way it looks, overriding the player's view: x, y, z, yaw, pitch,
     * and 1 when it has left the player (0 while it is still at their eyes). Null when no shot is rolling.
     */
    @Nullable public static double[] camera(Entity entity, float partialTick) {
        EarthCinema shot = current;
        if (shot == null || shot.phase == Phase.PREPARE || entity != Minecraft.getInstance().player) return null;
        if (!shot.orbiting) {
            ShotPath.View v = shot.view;
            return new double[]{v.x(), v.y(), v.z(), v.yaw(), v.pitch(), 0};
        }
        ShotPath.View v = shot.orbit.at(shot.time);
        Vec3 eye = entity.getEyePosition(partialTick);
        if (shot.recorder == null) {
            // Swings out from the player's eyes, already turning, and back to them when stopped.
            v = shot.startView.toward(v, ShotPath.smooth(shot.time / LEAD_IN));
            if (shot.phase == Phase.OUT) {
                ShotPath.View home = new ShotPath.View(eye.x, eye.y, eye.z, entity.getViewYRot(partialTick), entity.getViewXRot(partialTick));
                v = v.toward(home, ShotPath.smooth((shot.time - shot.outAt) / LEAD_OUT));
            }
        }
        double away = eye.distanceTo(new Vec3(v.x(), v.y(), v.z()));
        return new double[]{v.x(), v.y(), v.z(), v.yaw(), v.pitch(), away >= DETACH ? 1 : 0};
    }

    @Override public String toString() {
        return String.format(Locale.ROOT, "%s %s at %.2f s", orbiting ? "orbit" : "flyover", phase, time);
    }
}
