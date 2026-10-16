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

/**
 * Dev-only trailer camera, on with {@code -Dearthplatform.cinema=SCRIPT}. Flies the camera along
 * scripted keyframes one rendered frame at a time and saves every frame, so a slow software
 * renderer still gives smooth footage. Script lines:
 * <pre>
 * out DIR | fps N | time TICKS
 * shot NAME frames=N settle=PCT warm=SECONDS clear=BLOCKS
 *   cam T LAT LON H     (H: metres above the ground there, or =Y for an absolute height)
 *   look T LAT LON H
 * map NAME FROMLAT FROMLON TOLAT TOLON tail=FRAMES
 *   at NAME LAT LON     (a ground anchor for run lines: {NAME} or {NAME:DX:DY:DZ} becomes block X Y Z)
 *   run COMMAND         (server command, run once while the shot warms, e.g. to build a set)
 * </pre>
 * Shots also take fov=DEGREES.
 */
@EventBusSubscriber(modid = EarthPlatformMod.MOD_ID, value = Dist.CLIENT)
public final class Cinema {
    private static final String SCRIPT = System.getProperty("earthplatform.cinema");

    private record Key(double t, double lat, double lon, double h, boolean absolute) {}
    private static final class Shot {
        String name; boolean map; int frames = 150, tail = 45; long time = -1; double settle = 25, warm = 150, clear = 40, ease = 1, fov = -1;
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
                                default -> throw new IllegalArgumentException("unknown " + kv[0]);
                            }
                        }
                        shots.add(shot);
                    }
                    case "cam", "look" -> {
                        boolean absolute = p[4].startsWith("=");
                        Key key = new Key(Double.parseDouble(p[1]), Double.parseDouble(p[2]), Double.parseDouble(p[3]),
                                Double.parseDouble(absolute ? p[4].substring(1) : p[4]), absolute);
                        (p[0].equals("cam") ? shot.cam : shot.look).add(key);
                    }
                    case "at" -> shot.at.put(p[1], new double[]{Double.parseDouble(p[2]), Double.parseDouble(p[3])});
                    case "run" -> shot.run.add(line.substring(4).trim());
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

    private static double[] point(EarthGenerator earth, Key key) {
        GeoPoint g = new GeoPoint(key.lat, key.lon);
        double x = earth.grid().blockX(g), z = earth.grid().blockZ(g);
        double y = key.absolute ? key.h : earth.surfaceY(Mth.floor(x), Mth.floor(z)) + key.h;
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
        for (Key k : shot.cam) cams.add(point(earth, k));
        for (Key k : shot.look) looks.add(point(earth, k));
        int n = shot.frames;
        double[][] pos = new double[n][];
        double[] floor = new double[n];
        for (int f = 0; f < n; f++) {
            double t = eased(n == 1 ? 0 : f / (double) (n - 1), shot.ease);
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
            double t = eased(n == 1 ? 0 : f / (double) (n - 1), shot.ease);
            double[] c = pos[f];
            c[1] += lift[f];
            double[] l = along(shot.look, looks, t);
            double dx = l[0] - c[0], dy = l[1] - c[1], dz = l[2] - c[2];
            double yaw = Math.toDegrees(Math.atan2(-dx, dz));
            double pitch = -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
            poses[f] = new double[]{c[0], c[1], c[2], yaw, pitch};
        }
        // Unwrap yaw so it never spins the long way round.
        for (int f = 1; f < n; f++) {
            while (poses[f][3] - poses[f - 1][3] > 180) poses[f][3] -= 360;
            while (poses[f][3] - poses[f - 1][3] < -180) poses[f][3] += 360;
        }
        log("planned {}: {} frames from {} to {}", shot.name, n, fmt(poses[0]), fmt(poses[n - 1]));
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
        } else if (state == 2) {
            Shot shot = shots.get(shotIndex);
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
            double[] start = point(earth, new Key(0, shot.fromLat, shot.fromLon, 1.7, false));
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
        server("gamemode spectator " + mc.player.getGameProfile().getName());
        EarthGenerator earth = earth();
        planning = CompletableFuture.supplyAsync(() -> plan(earth, shot));
        enter(1);
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

    private static void pinShaderClock() {
        if (!irisTried) {
            irisTried = true;
            try {
                irisTimer = Class.forName("net.irisshaders.iris.uniforms.SystemTimeUniforms").getField("TIMER").get(null);
                irisLastStart = irisTimer.getClass().getDeclaredField("lastStartTime");
                irisLastStart.setAccessible(true);
            } catch (Throwable t) { irisTimer = null; log("no Iris clock to pin ({})", t.toString()); }
        }
        if (irisTimer == null) return;
        try { irisLastStart.set(irisTimer, java.util.OptionalLong.of(net.minecraft.Util.getNanos() - 1_000_000_000L / fps)); }
        catch (Throwable t) { irisTimer = null; log("could not pin Iris clock ({})", t.toString()); }
    }

    @SubscribeEvent public static void frameStart(RenderFrameEvent.Pre event) {
        posed = false;
        if (SCRIPT == null) return;
        if (state == 3 || state == 4) pinShaderClock();
        if (state == 3) {
            Shot shot = shots.get(shotIndex);
            pose(shot.poses[poseIndex(shot)]);
            posed = true;
        } else if (state == 4) posed = true;
    }

    @SubscribeEvent public static void frameEnd(RenderFrameEvent.Post event) {
        if (!posed) return;
        Shot shot = shots.get(shotIndex);
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
