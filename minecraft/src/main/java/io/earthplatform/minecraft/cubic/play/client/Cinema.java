package io.earthplatform.minecraft.cubic.play.client;

import com.mojang.blaze3d.platform.NativeImage;
import io.earthplatform.geography.GeoPoint;
import io.earthplatform.minecraft.EarthPlatformMod;
import io.earthplatform.minecraft.cubic.Cubic;
import io.earthplatform.minecraft.cubic.CubicLevel;
import io.earthplatform.minecraft.cubic.client.ClientCubeWorld;
import io.earthplatform.minecraft.cubic.earth.EarthGenerator;
import io.earthplatform.minecraft.cubic.ServerCubeWorld;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.client.event.ViewportEvent;

/**
 * Dev-only trailer camera, on with {@code -Dearthplatform.cinema=SCRIPT}. Flies the camera along
 * scripted keyframes one rendered frame at a time and saves every frame, so a slow software
 * renderer still gives smooth footage. Script lines:
 * <pre>
 * out DIR | fps N | time TICKS
 * shot NAME frames=N settle=PCT warm=SECONDS clear=BLOCKS
 *   cam T LAT LON H     (H: metres above the ground there, =Y for an absolute height, or ^H above the
 *                        ground under the shot's first cam key, for level flight over uneven ground)
 *   look T LAT LON H
 * map NAME FROMLAT FROMLON TOLAT TOLON tail=FRAMES
 *   at NAME LAT LON     (a ground anchor for run lines: {NAME} or {NAME:DX:DY:DZ} becomes block X Y Z)
 *   run COMMAND         (server command, run once while the shot warms, e.g. to build a set)
 *   mark NAME LAT LON H (a place to label in the edit: H the absolute height, or 0 for the ground there;
 *                        saved with the shot's poses as NAME.marks and NAME.poses next to its frames)
 * </pre>
 * Shots also take fov=DEGREES and hold=RENDERS (extra renders per pose before saving, so far
 * terrain keeps up with a fast move) and even=1 (constant speed along the path, shaped only by ease).
 * <p>third=1 films the player's body in creative instead of a spectator camera: cam keys are then
 * the body's eye path, look keys where it faces, and the camera sits back=M behind (easing to
 * back2=M over the shot), up=M above the eyes and side=M to the right, aimed at the look point
 * (aimy=M moves that aim up or down). Legs swing from the body's speed along the path. glide=1
 * wears an elytra in the gliding pose; type=1 taps both hands as if typing.
 */
@EventBusSubscriber(modid = EarthPlatformMod.MOD_ID, value = Dist.CLIENT)
public final class Cinema {
    private static final String SCRIPT = System.getProperty("earthplatform.cinema");

    private record Key(double t, double lat, double lon, double h, boolean absolute, boolean fromStart) {}
    private static final class Shot {
        String name; boolean map; int frames = 150, tail = 45; long time = -1; double settle = 25, warm = 150, clear = 40, ease = 1, fov = -1;
        int hold;
        boolean third, glide, type, even;
        final List<double[]> marks = new ArrayList<>();
        final List<String> markNames = new ArrayList<>();
        double back = 5, back2 = Double.NaN, up = 1.2, side, aimy;
        boolean built;
        final java.util.Map<String, double[]> at = new java.util.HashMap<>();
        final List<String> run = new ArrayList<>();
        double fromLat, fromLon, toLat, toLon;
        final List<Key> cam = new ArrayList<>(), look = new ArrayList<>();
        double[][] poses;
    }

    private static final List<Shot> shots = new ArrayList<>();
    private static Path out;
    private static int fps = 30;
    private static long dayTime = 1000;
    private static boolean loaded;
    /** When above 0, record only this many frames spread over each shot, to check framing. */
    private static int preview;
    private static List<String> only;

    private static int shotIndex = -1;
    private static int frame;
    /** 0 idle, 1 planning, 2 warming, 3 recording, 4 map flight, 5 done */
    private static int state;
    private static long stateAt, startedAt;
    private static boolean posed;
    /** Renders of the current pose so far, while a shot holds each pose. */
    private static int held;
    private static int mapFrames;
    private static CompletableFuture<double[][]> planning;
    private static final ExecutorService writers = Executors.newFixedThreadPool(2);
    private static final Semaphore inFlight = new Semaphore(6);

    private Cinema() {}

    public static boolean active() { return SCRIPT != null && state == 4; }

    /** The flight clock while a map shot records: frames turned into milliseconds. */
    public static long clock() { return mapFrames * 1000L / fps; }

    private static void load() {
        loaded = true;
        try {
            Shot shot = null;
            for (String raw : Files.readAllLines(Path.of(SCRIPT))) {
                // run lines keep '#' for block tags like #minecraft:leaves; elsewhere it starts a comment
                String line = raw.trim().startsWith("run ") ? raw.trim() : raw.replaceAll("#.*", "").trim();
                if (line.isEmpty()) continue;
                String[] p = line.split("\\s+");
                switch (p[0]) {
                    case "out" -> out = Path.of(p[1]);
                    case "fps" -> fps = Integer.parseInt(p[1]);
                    case "time" -> dayTime = Long.parseLong(p[1]);
                    case "preview" -> preview = Integer.parseInt(p[1]);
                    case "only" -> only = List.of(p).subList(1, p.length);
                    case "shot", "map" -> {
                        shot = new Shot();
                        shot.name = p[1];
                        shot.map = p[0].equals("map");
                        int at = 2;
                        if (shot.map) {
                            shot.fromLat = Double.parseDouble(p[2]); shot.fromLon = Double.parseDouble(p[3]);
                            shot.toLat = Double.parseDouble(p[4]); shot.toLon = Double.parseDouble(p[5]);
                            at = 6;
                        }
                        for (int i = at; i < p.length; i++) {
                            String[] kv = p[i].split("=");
                            double v = Double.parseDouble(kv[1]);
                            switch (kv[0]) {
                                case "frames" -> shot.frames = (int) v;
                                case "settle" -> shot.settle = v;
                                case "warm" -> shot.warm = v;
                                case "clear" -> shot.clear = v;
                                case "tail" -> shot.tail = (int) v;
                                case "ease" -> shot.ease = v;
                                case "time" -> shot.time = (long) v;
                                case "fov" -> shot.fov = v;
                                case "hold" -> shot.hold = (int) v;
                                case "third" -> shot.third = v != 0;
                                case "glide" -> shot.glide = v != 0;
                                case "type" -> shot.type = v != 0;
                                case "even" -> shot.even = v != 0;
                                case "back" -> shot.back = v;
                                case "back2" -> shot.back2 = v;
                                case "up" -> shot.up = v;
                                case "side" -> shot.side = v;
                                case "aimy" -> shot.aimy = v;
                                default -> throw new IllegalArgumentException("unknown " + kv[0]);
                            }
                        }
                        shots.add(shot);
                    }
                    case "cam", "look" -> {
                        boolean absolute = p[4].startsWith("="), fromStart = p[4].startsWith("^");
                        Key key = new Key(Double.parseDouble(p[1]), Double.parseDouble(p[2]), Double.parseDouble(p[3]),
                                Double.parseDouble(absolute || fromStart ? p[4].substring(1) : p[4]), absolute, fromStart);
                        (p[0].equals("cam") ? shot.cam : shot.look).add(key);
                    }
                    case "at" -> shot.at.put(p[1], new double[]{Double.parseDouble(p[2]), Double.parseDouble(p[3])});
                    case "run" -> shot.run.add(line.substring(4).trim());
                    case "mark" -> { shot.markNames.add(p[1]); shot.marks.add(new double[]{Double.parseDouble(p[2]), Double.parseDouble(p[3]), Double.parseDouble(p[4])}); }
                    default -> throw new IllegalArgumentException("unknown line " + line);
                }
            }
            Files.createDirectories(out);
            log("loaded {} shots", shots.size());
        } catch (Exception e) {
            EarthPlatformMod.LOGGER.error("cinema: bad script", e);
            state = 5;
        }
    }

    private static void log(String text, Object... args) { EarthPlatformMod.LOGGER.info("cinema: " + text, args); }

    private static EarthGenerator earth() {
        MinecraftServer server = Minecraft.getInstance().getSingleplayerServer();
        ServerLevel level = server == null ? null : server.getLevel(Cubic.LEVEL);
        if (level != null && CubicLevel.of(level) instanceof ServerCubeWorld cubes && cubes.generator() instanceof EarthGenerator earth) return earth;
        return null;
    }

    private static void server(String command) {
        MinecraftServer server = Minecraft.getInstance().getSingleplayerServer();
        server.execute(() -> server.getCommands().performPrefixedCommand(server.createCommandSourceStack().withSuppressedOutput(), command));
    }

    /** Runs a shot's set-building commands, with {NAME} / {NAME:DX:DY:DZ} replaced by anchor block coordinates. */
    private static void build(Shot shot) {
        shot.built = true;
        EarthGenerator earth = earth();
        for (String cmd : shot.run) {
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\{(\\w+)(?::(-?\\d+):(-?\\d+):(-?\\d+))?}").matcher(cmd);
            StringBuilder sb = new StringBuilder();
            while (m.find()) {
                double[] ll = shot.at.get(m.group(1));
                if (ll == null) throw new IllegalArgumentException("no anchor " + m.group(1));
                GeoPoint g = new GeoPoint(ll[0], ll[1]);
                int x = Mth.floor(earth.grid().blockX(g)), z = Mth.floor(earth.grid().blockZ(g));
                int y = (int) Math.round(earth.surfaceY(x, z)) + 1;
                if (m.group(2) != null) { x += Integer.parseInt(m.group(2)); y += Integer.parseInt(m.group(3)); z += Integer.parseInt(m.group(4)); }
                m.appendReplacement(sb, x + " " + y + " " + z);
            }
            m.appendTail(sb);
            log("set {}: {}", shot.name, sb);
            String command = sb.toString();
            // Run in the cubic level (the default source is the overworld) and log failures instead of hiding them.
            MinecraftServer server = Minecraft.getInstance().getSingleplayerServer();
            server.execute(() -> {
                var source = server.createCommandSourceStack().withLevel(server.getLevel(Cubic.LEVEL)).withSuppressedOutput();
                try {
                    int n = server.getCommands().getDispatcher().execute(command, source);
                    if (n == 0) log("set {} did nothing: {}", shot.name, command);
                } catch (Exception e) {
                    log("set {} failed: {} ({})", shot.name, command, e.getMessage());
                }
            });
        }
    }

    // Planning: keyframes to a block-space pose per frame ------------------------------------------

    private static double[] point(EarthGenerator earth, Key key) { return point(earth, key, key); }

    private static double[] point(EarthGenerator earth, Key key, Key first) {
        GeoPoint g = new GeoPoint(key.lat, key.lon);
        double x = earth.grid().blockX(g), z = earth.grid().blockZ(g);
        Key ground = key.fromStart ? first : key;
        GeoPoint gg = new GeoPoint(ground.lat, ground.lon);
        double y = key.absolute ? key.h : earth.surfaceY(Mth.floor(earth.grid().blockX(gg)), Mth.floor(earth.grid().blockZ(gg))) + key.h;
        return new double[]{x, y, z};
    }

    /** Catmull-Rom through keyframe points at parameter t. */
    private static double[] along(List<Key> keys, List<double[]> pts, double t) {
        if (pts.size() == 1) return pts.get(0).clone();
        int i = 0;
        while (i < keys.size() - 2 && t > keys.get(i + 1).t) i++;
        double t0 = keys.get(i).t, t1 = keys.get(i + 1).t;
        double u = Mth.clamp((t - t0) / (t1 - t0), 0, 1);
        double[] p0 = pts.get(Math.max(0, i - 1)), p1 = pts.get(i), p2 = pts.get(i + 1), p3 = pts.get(Math.min(pts.size() - 1, i + 2));
        double[] r = new double[3];
        for (int k = 0; k < 3; k++) {
            r[k] = 0.5 * (2 * p1[k] + (-p0[k] + p2[k]) * u + (2 * p0[k] - 5 * p1[k] + 4 * p2[k] - p3[k]) * u * u
                    + (-p0[k] + 3 * p1[k] - 3 * p2[k] + p3[k]) * u * u * u);
        }
        return r;
    }

    private static double eased(double s, double amount) {
        double smooth = s * s * (3 - 2 * s);
        return Mth.lerp(amount, s, smooth);
    }

    private static double[][] plan(EarthGenerator earth, Shot shot) {
        List<double[]> cams = new ArrayList<>(), looks = new ArrayList<>();
        for (Key k : shot.cam) cams.add(point(earth, k, shot.cam.get(0)));
        for (Key k : shot.look) looks.add(point(earth, k, shot.cam.get(0)));
        int n = shot.frames;
        double[][] pos = new double[n][];
        double[] floor = new double[n];
        // even=1: move along the camera path at a speed set only by the ease, not by how the keys are spaced.
        double[] tOf = new double[n];
        if (shot.even && n > 1 && cams.size() > 1) {
            int m = n * 20;
            double[] len = new double[m + 1];
            double[] prev = along(shot.cam, cams, 0);
            for (int i = 1; i <= m; i++) {
                double[] c = along(shot.cam, cams, i / (double) m);
                len[i] = len[i - 1] + Math.sqrt((c[0] - prev[0]) * (c[0] - prev[0]) + (c[1] - prev[1]) * (c[1] - prev[1]) + (c[2] - prev[2]) * (c[2] - prev[2]));
                prev = c;
            }
            for (int f = 0; f < n; f++) {
                double want = eased(f / (double) (n - 1), shot.ease) * len[m];
                int i = 0;
                while (i < m && len[i + 1] < want) i++;
                double seg = len[i + 1] - len[i];
                tOf[f] = (i + (seg > 0 ? (want - len[i]) / seg : 0)) / m;
            }
        } else {
            for (int f = 0; f < n; f++) tOf[f] = eased(n == 1 ? 0 : f / (double) (n - 1), shot.ease);
        }
        for (int f = 0; f < n; f++) {
            double t = tOf[f];
            pos[f] = along(shot.cam, cams, t);
            floor[f] = earth.surfaceY(Mth.floor(pos[f][0]), Mth.floor(pos[f][2])) + shot.clear;
        }
        // Keep clear of the ground, lifting smoothly over a window rather than hopping.
        int w = Math.max(1, fps);
        double[] lift = new double[n];
        for (int f = 0; f < n; f++) {
            double need = 0;
            for (int g = Math.max(0, f - w); g < Math.min(n, f + w + 1); g++) {
                double d = floor[g] - pos[g][1];
                if (d > 0) need = Math.max(need, d * (1 - Math.abs(g - f) / (double) (w + 1)));
            }
            lift[f] = need;
        }
        double[][] poses = new double[n][];
        for (int f = 0; f < n; f++) {
            double t = tOf[f];
            double[] c = pos[f];
            c[1] += lift[f];
            double[] l = along(shot.look, looks, t);
            double dx = l[0] - c[0], dy = l[1] - c[1], dz = l[2] - c[2];
            double yaw = Math.toDegrees(Math.atan2(-dx, dz));
            double pitch = -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
            // 5-7 look point, 8 eased progress, 9-10 leg swing speed and phase (filled below)
            poses[f] = new double[]{c[0], c[1], c[2], yaw, pitch, l[0], l[1], l[2], t, 0, 0};
        }
        // Leg swing as vanilla would give it for this ground speed: per tick, speed eases toward
        // min(1, 4 * blocks moved) and the phase advances by the speed.
        double speed = 0, phase = 0, perTick = 20.0 / fps;
        for (int f = 0; f < n; f++) {
            double d = f == 0 ? 0 : Math.hypot(poses[f][0] - poses[f - 1][0], poses[f][2] - poses[f - 1][2]);
            double target = shot.glide ? 0 : Math.min(1, 4 * d / perTick);
            speed += (target - speed) * Math.min(1, 0.4 * perTick);
            phase += speed * perTick;
            poses[f][9] = speed;
            poses[f][10] = phase;
        }
        // Unwrap yaw so it never spins the long way round.
        for (int f = 1; f < n; f++) {
            while (poses[f][3] - poses[f - 1][3] > 180) poses[f][3] -= 360;
            while (poses[f][3] - poses[f - 1][3] < -180) poses[f][3] += 360;
        }
        log("planned {}: {} frames from {} to {}", shot.name, n, fmt(poses[0]), fmt(poses[n - 1]));
        // Poses and marked places in block space, so an edit can pin labels to exactly where things are on screen.
        try {
            StringBuilder sb = new StringBuilder();
            for (double[] q : poses) sb.append(String.format(Locale.ROOT, "%.3f %.3f %.3f %.4f %.4f%n", q[0], q[1], q[2], q[3], q[4]));
            Files.writeString(out.resolve(shot.name + ".poses"), sb);
            StringBuilder mk = new StringBuilder();
            for (int i = 0; i < shot.marks.size(); i++) {
                double[] m = shot.marks.get(i);
                double[] b = point(earth, new Key(0, m[0], m[1], m[2], m[2] > 0, false));
                if (m[2] <= 0) b[1] = earth.surfaceY(Mth.floor(b[0]), Mth.floor(b[2]));
                mk.append(String.format(Locale.ROOT, "%s %.3f %.3f %.3f%n", shot.markNames.get(i), b[0], b[1], b[2]));
            }
            if (!shot.marks.isEmpty()) Files.writeString(out.resolve(shot.name + ".marks"), mk);
        } catch (IOException e) { log("could not save poses ({})", e.toString()); }
        return poses;
    }

    private static String fmt(double[] p) { return String.format(Locale.ROOT, "%.0f %.0f %.0f yaw %.1f pitch %.1f", p[0], p[1], p[2], p[3], p[4]); }

    // Running -----------------------------------------------------------------------------------------

    private static void enter(int s) { state = s; stateAt = System.currentTimeMillis(); }

    @SubscribeEvent public static void tick(ClientTickEvent.Post event) {
        if (SCRIPT == null || state == 5) return;
        Minecraft mc = Minecraft.getInstance();
        if (!loaded) load();
        if (mc.player == null || ClientCubeWorld.current() == null || earth() == null) return;
        if (startedAt == 0) { startedAt = System.currentTimeMillis(); return; }
        if (System.currentTimeMillis() - startedAt < 5000) return;
        mc.options.hideGui = true;
        ClientCubeWorld world = ClientCubeWorld.current();
        if (state == 0) next();
        else if (state == 1 && planning.isDone()) {
            Shot shot = shots.get(shotIndex);
            try { shot.poses = planning.join(); } catch (Exception e) { EarthPlatformMod.LOGGER.error("cinema: plan failed", e); next(); return; }
            double[] p = shot.poses[0];
            teleport(p);
            enter(2);
        } else if (state == 2 || state == 3) {
            Shot shot = shots.get(shotIndex);
            if (shot.third) body(mc, shot);
            if (state == 3) return;
            double since = (System.currentTimeMillis() - stateAt) / 1000.0;
            int settled = world.far().settledPercent(), pending = world.renderer().pendingCount();
            if (since > 5 && (int) since % 10 == 0 && (System.currentTimeMillis() - stateAt) % 1000 < 50)
                log("warming {}: {} s, {}% settled, {} to compile", shot.name, (int) since, settled, pending);
            if (!shot.built && !shot.run.isEmpty() && since > 6) build(shot);
            if (since > 8 && settled >= shot.settle && pending < 30 || since > shot.warm) {
                log("recording {} after {} s ({}% settled, {} to compile)", shot.name, (int) since, settled, pending);
                frame = 0;
                enter(3);
            }
        } else if (state == 4) {
            if (!EarthFlight.flying() && mapFrames > 0) {
                Shot shot = shots.get(shotIndex);
                if (frame >= mapFrames + shot.tail) { log("map {} done, {} frames", shot.name, frame); next(); }
            }
        }
    }

    private static void teleport(double[] p) {
        Minecraft mc = Minecraft.getInstance();
        MinecraftServer server = mc.getSingleplayerServer();
        server.execute(() -> {
            ServerPlayer player = server.getPlayerList().getPlayers().get(0);
            ServerLevel level = server.getLevel(Cubic.LEVEL);
            player.teleportTo(level, p[0], p[1] - player.getEyeHeight(), p[2], (float) p[3], (float) p[4]);
        });
    }

    private static void next() {
        shotIndex++;
        if (shotIndex >= shots.size()) { log("all shots done"); enter(5); return; }
        Shot shot = shots.get(shotIndex);
        if (only != null && !only.contains(shot.name)) { next(); return; }
        Minecraft mc = Minecraft.getInstance();
        mc.options.bobView().set(false);
        if (shot.fov > 0) mc.options.fov().set((int) shot.fov);
        server("gamerule doDaylightCycle false");
        server("gamerule doWeatherCycle false");
        server("weather clear");
        server("time set " + (shot.time >= 0 ? shot.time : dayTime));
        server("gamerule sendCommandFeedback false");
        try { Files.createDirectories(out.resolve(shot.name)); } catch (IOException e) { throw new RuntimeException(e); }
        if (shot.map) {
            server("gamemode creative " + mc.player.getGameProfile().getName());
            EarthGenerator earth = earth();
            double[] start = point(earth, new Key(0, shot.fromLat, shot.fromLon, 1.7, false, false));
            teleport(new double[]{start[0], start[1], start[2], 0, 10});
            frame = 0;
            mapFrames = 0;
            // Give the start a while to load, then take off.
            CompletableFuture.delayedExecutor((long) (shot.warm * 1000), java.util.concurrent.TimeUnit.MILLISECONDS).execute(() -> mc.execute(() -> {
                log("map {} taking off", shot.name);
                enter(4);
                EarthFlight.start(shot.fromLat, shot.fromLon, shot.toLat, shot.toLon);
            }));
            state = 6;
            return;
        }
        String name = mc.player.getGameProfile().getName();
        mc.options.setCameraType(shot.third ? CameraType.THIRD_PERSON_BACK : CameraType.FIRST_PERSON);
        mc.player.setNoGravity(shot.third);
        server("gamemode " + (shot.third ? "creative " : "spectator ") + name);
        server("item replace entity " + name + " armor.chest with " + (shot.glide ? "minecraft:elytra" : "minecraft:air"));
        EarthGenerator earth = earth();
        planning = CompletableFuture.supplyAsync(() -> plan(earth, shot));
        enter(1);
    }

    /** Holds the filmed body in the air between renders and keeps the server's gliding flag in step. */
    private static void body(Minecraft mc, Shot shot) {
        var player = mc.player;
        player.setNoGravity(true);
        player.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);
        player.getAbilities().flying = false;
        MinecraftServer server = mc.getSingleplayerServer();
        server.execute(() -> {
            ServerPlayer sp = server.getPlayerList().getPlayers().get(0);
            if (sp.getAbilities().flying) { sp.getAbilities().flying = false; sp.onUpdateAbilities(); }
            sp.setNoGravity(true);
            if (shot.glide && !sp.isFallFlying()) sp.startFallFlying();
            if (!shot.glide && sp.isFallFlying()) sp.stopFallFlying();
        });
    }

    private static java.lang.reflect.Field walkSpeed, walkSpeedOld, walkPosition;

    /** Poses the body for third-person shots: facing, leg swing for its speed, gliding, typing taps. */
    private static void bodyPose(Shot shot, double[] p, int index, float partial) {
        var player = Minecraft.getInstance().player;
        player.yBodyRot = player.yBodyRotO = (float) p[3];
        player.yHeadRotO = (float) p[3];
        player.getAbilities().flying = false;
        player.setNoGravity(true);
        if (shot.glide) { if (!player.isFallFlying()) player.startFallFlying(); player.setOnGround(false); }
        else { if (player.isFallFlying()) player.stopFallFlying(); player.setOnGround(true); }
        try {
            if (walkSpeed == null) {
                Class<?> c = net.minecraft.world.entity.WalkAnimationState.class;
                walkSpeed = c.getDeclaredField("speed"); walkSpeedOld = c.getDeclaredField("speedOld"); walkPosition = c.getDeclaredField("position");
                walkSpeed.setAccessible(true); walkSpeedOld.setAccessible(true); walkPosition.setAccessible(true);
            }
            float speed = (float) p[9];
            walkSpeed.setFloat(player.walkAnimation, speed);
            walkSpeedOld.setFloat(player.walkAnimation, speed);
            // The renderer reads position - speed * (1 - partial); cancel that so the phase is exact.
            walkPosition.setFloat(player.walkAnimation, (float) p[10] + speed * (1 - partial));
        } catch (ReflectiveOperationException e) { log("no walk animation ({})", e.toString()); }
        if (shot.type) {
            int stroke = 6, k = index / stroke;
            double u = (index % stroke) / (double) stroke;
            // A short tap now and then a pause, alternating hands.
            float a = (float) (k % 5 == 4 ? 0 : 0.32 * Math.sin(Math.PI * u));
            player.swinging = a > 0;
            player.swingingArm = k % 2 == 0 ? net.minecraft.world.InteractionHand.MAIN_HAND : net.minecraft.world.InteractionHand.OFF_HAND;
            player.attackAnim = player.oAttackAnim = a;
        } else {
            player.swinging = false;
            player.attackAnim = player.oAttackAnim = 0;
        }
    }

    private static java.lang.reflect.Method camSetPosition, camSetRotation;

    /** Third-person shots place the camera behind the body themselves, aimed at the look point. */
    @SubscribeEvent public static void camera(ViewportEvent.ComputeCameraAngles event) {
        if (!posed || state != 3) return;
        Shot shot = shots.get(shotIndex);
        if (!shot.third) return;
        double[] p = shot.poses[poseIndex(shot)];
        double yaw = Math.toRadians(p[3]);
        double fx = -Math.sin(yaw), fz = Math.cos(yaw), rx = -Math.cos(yaw), rz = -Math.sin(yaw);
        double back = Double.isNaN(shot.back2) ? shot.back : Mth.lerp(p[8], shot.back, shot.back2);
        double cx = p[0] - fx * back + rx * shot.side, cy = p[1] + shot.up, cz = p[2] - fz * back + rz * shot.side;
        double dx = p[5] - cx, dy = p[6] + shot.aimy - cy, dz = p[7] - cz;
        float cyaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        float cpitch = (float) -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
        try {
            if (camSetPosition == null) {
                Class<?> c = net.minecraft.client.Camera.class;
                camSetPosition = c.getDeclaredMethod("setPosition", net.minecraft.world.phys.Vec3.class);
                camSetRotation = c.getDeclaredMethod("setRotation", float.class, float.class);
                camSetPosition.setAccessible(true); camSetRotation.setAccessible(true);
            }
            camSetRotation.invoke(event.getCamera(), cyaw, cpitch);
            camSetPosition.invoke(event.getCamera(), new net.minecraft.world.phys.Vec3(cx, cy, cz));
        } catch (ReflectiveOperationException e) { log("could not place camera ({})", e.toString()); }
        event.setYaw(cyaw);
        event.setPitch(cpitch);
        event.setRoll(0);
    }

    private static void pose(double[] p) {
        var player = Minecraft.getInstance().player;
        player.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);
        player.setPos(p[0], p[1] - player.getEyeHeight(), p[2]);
        player.setYRot((float) p[3]);
        player.setXRot((float) p[4]);
        player.setYHeadRot((float) p[3]);
        player.setOldPosAndRot();
        player.yRotO = player.getYRot();
        player.xRotO = player.getXRot();
    }

    private static int poseIndex(Shot shot) {
        if (preview <= 0) return Math.min(frame, shot.frames - 1);
        // Each preview frame is held for a few renders so Photon's history settles.
        int k = Math.min(frame / 4, preview - 1);
        return preview == 1 ? 0 : (int) Math.round(k * (shot.frames - 1) / (double) (preview - 1));
    }

    // Iris times shader animation (swaying plants, water, clouds) by the wall clock, which would run
    // several times too fast in footage rendered slower than real time. Pin each captured frame to 1/fps s.
    private static Object irisTimer;
    private static java.lang.reflect.Field irisLastStart;
    private static boolean irisTried;

    private static void pinShaderClock(boolean still) {
        if (!irisTried) {
            irisTried = true;
            try {
                irisTimer = Class.forName("net.irisshaders.iris.uniforms.SystemTimeUniforms").getField("TIMER").get(null);
                irisLastStart = irisTimer.getClass().getDeclaredField("lastStartTime");
                irisLastStart.setAccessible(true);
            } catch (Throwable t) { irisTimer = null; log("no Iris clock to pin ({})", t.toString()); }
        }
        if (irisTimer == null) return;
        try { irisLastStart.set(irisTimer, java.util.OptionalLong.of(net.minecraft.Util.getNanos() - (still ? 0 : 1_000_000_000L / fps))); }
        catch (Throwable t) { irisTimer = null; log("could not pin Iris clock ({})", t.toString()); }
    }

    @SubscribeEvent public static void frameStart(RenderFrameEvent.Pre event) {
        posed = false;
        if (SCRIPT == null) return;
        // Held renders of a pose don't advance shader time; only the saved one does.
        if (state == 3 || state == 4) pinShaderClock(state == 3 && held < shots.get(shotIndex).hold);
        if (state == 3) {
            Shot shot = shots.get(shotIndex);
            int index = poseIndex(shot);
            pose(shot.poses[index]);
            if (shot.third) bodyPose(shot, shot.poses[index], index, event.getPartialTick().getGameTimeDeltaPartialTick(true));
            posed = true;
        } else if (state == 4) posed = true;
    }

    @SubscribeEvent public static void frameEnd(RenderFrameEvent.Post event) {
        if (!posed) return;
        Shot shot = shots.get(shotIndex);
        if (state == 3 && preview <= 0 && held < shot.hold) { held++; return; }
        held = 0;
        Minecraft mc = Minecraft.getInstance();
        NativeImage image = Screenshot.takeScreenshot(mc.getMainRenderTarget());
        File file = out.resolve(shot.name).resolve(String.format(Locale.ROOT, "%05d.png", frame)).toFile();
        inFlight.acquireUninterruptibly();
        writers.execute(() -> {
            try { image.writeToFile(file); } catch (IOException e) { EarthPlatformMod.LOGGER.error("cinema: write", e); }
            finally { image.close(); inFlight.release(); }
        });
        frame++;
        if (state == 4 && EarthFlight.flying()) mapFrames++;
        if (frame % 30 == 0) log("{} frame {}", shot.name, frame);
        if (state == 3 && frame >= (preview > 0 ? preview * 4 : shot.frames)) { log("shot {} done", shot.name); next(); }
    }
}
