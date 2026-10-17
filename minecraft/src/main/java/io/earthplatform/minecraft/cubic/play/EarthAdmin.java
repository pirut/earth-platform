package io.earthplatform.minecraft.cubic.play;

import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import io.earthplatform.geography.GeoPoint;
import io.earthplatform.geography.globe.Downloads;
import io.earthplatform.minecraft.EarthPlatformMod;
import io.earthplatform.minecraft.cubic.Cubic;
import io.earthplatform.minecraft.cubic.CubicLevel;
import io.earthplatform.minecraft.cubic.ServerCubeWorld;
import io.earthplatform.minecraft.cubic.earth.EarthGenerator;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import javax.annotation.Nullable;
import net.minecraft.ChatFormatting;
import net.minecraft.Util;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;

/**
 * Tools for whoever runs the server: downloading an area ahead of time, a status page in chat,
 * and a live web map of the players over the globe for sharing outside the game.
 */
@EventBusSubscriber(modid = EarthPlatformMod.MOD_ID)
public final class EarthAdmin {
    /** How far apart preloaded points are, in blocks: each fetches what the cubes within 3.5 km read. */
    private static final int PRELOAD_STEP = 6000;
    private static final double PRELOAD_MOST_KM = 250;

    @Nullable private static volatile AtomicBoolean preloading;

    private EarthAdmin() {}

    static void register(LiteralArgumentBuilder<CommandSourceStack> root) {
        root.then(Commands.literal("preload").requires(EarthCommands::op)
                .then(Commands.literal("stop").executes(context -> {
                    AtomicBoolean running = preloading;
                    if (running == null) { context.getSource().sendFailure(Component.literal("Nothing is preloading.")); return 0; }
                    running.set(false);
                    context.getSource().sendSuccess(() -> Component.literal("Stopping the preload."), true);
                    return 1;
                }))
                .then(Commands.argument("km", DoubleArgumentType.doubleArg(1, PRELOAD_MOST_KM))
                        .executes(context -> preload(context.getSource(), DoubleArgumentType.getDouble(context, "km")))));
        root.then(Commands.literal("admin").requires(EarthCommands::op).executes(context -> status(context.getSource())));
    }

    // Preloading ---------------------------------------------------------------------------------

    /**
     * Downloads the Earth data within {@code km} of the one running it (or of the server spawn from
     * the console), so first visits there are instant. One point at a time, off the server thread.
     */
    private static int preload(CommandSourceStack source, double km) {
        if (preloading != null) { source.sendFailure(Component.literal("A preload is already running; /earth preload stop ends it.")); return 0; }
        EarthGenerator earth = EarthCommands.earth(source);
        if (earth == null) { source.sendFailure(Component.literal("This world has no Earth.")); return 0; }
        ServerPlayer player = source.getPlayer();
        GeoPoint here = player == null ? null : EarthRules.where(player);
        GeoPoint centre = here != null ? here : EarthCommands.spawnPoint(source, earth);
        int[] middle = earth.block(centre.latitude(), centre.longitude());
        // Blocks are about a meter; the grid is in blocks, so its reach is close enough to kilometers.
        double reach = km * 1000 / earth.grid().groundMetresPerBlock(middle[1]);
        List<int[]> points = new ArrayList<>();
        int steps = (int) Math.ceil(reach / PRELOAD_STEP);
        for (int i = -steps; i <= steps; i++) for (int j = -steps; j <= steps; j++) {
            if (Math.hypot(i, j) * PRELOAD_STEP > reach + PRELOAD_STEP / 2.0) continue;
            points.add(new int[]{middle[0] + i * PRELOAD_STEP, middle[1] + j * PRELOAD_STEP});
        }
        // Nearest first, so a stopped preload has done the middle.
        points.sort((a, b) -> Long.compare(dist(a, middle), dist(b, middle)));
        AtomicBoolean running = new AtomicBoolean(true);
        preloading = running;
        MinecraftServer server = source.getServer();
        source.sendSuccess(() -> Component.literal(String.format(Locale.ROOT, "Preloading %.0f km around %.4f, %.4f (%d areas). /earth preload stop ends it.",
                km, centre.latitude(), centre.longitude(), points.size())).withStyle(ChatFormatting.GREEN), true);
        Map<String, long[]> before = Downloads.totals();
        long start = Util.getMillis();
        AtomicInteger failed = new AtomicInteger();
        Thread worker = new Thread(() -> {
            int done = 0, told = 0;
            for (int[] point : points) {
                if (!running.get()) break;
                try { earth.prepareNear(point[0], point[1]); } catch (RuntimeException failure) { failed.incrementAndGet(); }
                done++;
                int tenth = done * 10 / points.size();
                if (tenth > told && done < points.size()) {
                    told = tenth;
                    int percent = tenth * 10;
                    server.execute(() -> source.sendSuccess(() -> Component.literal("Preload " + percent + "% done.").withStyle(ChatFormatting.GRAY), false));
                }
            }
            int finished = done;
            String fetched = Downloads.since(before);
            long seconds = (Util.getMillis() - start) / 1000;
            server.execute(() -> {
                preloading = null;
                source.sendSuccess(() -> Component.literal((running.get() ? "Preload finished" : "Preload stopped") + ": " + finished + " of " + points.size()
                        + " areas in " + seconds + " s" + (failed.get() > 0 ? ", " + failed.get() + " could not be fetched" : "") + ". " + fetched + ".")
                        .withStyle(ChatFormatting.GREEN), true);
            });
        }, "Earth preload");
        worker.setDaemon(true);
        worker.setPriority(Thread.MIN_PRIORITY);
        worker.start();
        return points.size();
    }

    private static long dist(int[] a, int[] b) { long dx = a[0] - b[0], dz = a[1] - b[1]; return dx * dx + dz * dz; }

    // Status -------------------------------------------------------------------------------------

    private static int status(CommandSourceStack source) {
        MinecraftServer server = source.getServer();
        double mspt = server.getAverageTickTimeNanos() / 1e6;
        long[] cubicTimes = server.getTickTime(Cubic.LEVEL);
        double cubicMspt = cubicTimes == null || cubicTimes.length == 0 ? 0 : java.util.Arrays.stream(cubicTimes).average().orElse(0) / 1e6;
        double tps = Math.min(20, 1000 / Math.max(1e-3, mspt));
        line(source, String.format(Locale.ROOT, "TPS %.1f, %.1f ms a tick (Earth %.1f ms)", tps, mspt, cubicMspt), mspt > 50 ? ChatFormatting.RED : ChatFormatting.GREEN);
        ServerLevel level = server.getLevel(Cubic.LEVEL);
        if (level != null && CubicLevel.of(level) instanceof ServerCubeWorld cubes) line(source, "Earth: " + cubes.describe(), ChatFormatting.GRAY);
        Runtime runtime = Runtime.getRuntime();
        line(source, String.format(Locale.ROOT, "Memory: %d of %d MB used", (runtime.totalMemory() - runtime.freeMemory()) >> 20, runtime.maxMemory() >> 20), ChatFormatting.GRAY);
        long files = 0, bytes = 0;
        for (long[] tally : Downloads.totals().values()) { files += tally[0]; bytes += tally[1]; }
        line(source, String.format(Locale.ROOT, "Downloaded since start: %d files, %.1f MB%s", files, bytes / 1e6,
                EarthStatus.offlineMode() ? " (offline mode)" : ""), ChatFormatting.GRAY);
        EarthSpawns spawns = EarthSpawns.of(server);
        line(source, "Border: " + spawns.border().map(EarthSpawns.Border::label).orElse("none") + "; warps: " + spawns.warps().size()
                + "; preload: " + (preloading != null ? "running" : "idle"), ChatFormatting.GRAY);
        int port = EarthConfig.WEB_MAP_PORT.get();
        if (port > 0) line(source, "Web map on port " + port + (web == null ? " (not running)" : ""), ChatFormatting.GRAY);
        // The cache size walks the whole folder, so it comes in after.
        Path cache = FMLPaths.GAMEDIR.get().resolve("earthplatform").resolve("cache");
        CompletableFuture.supplyAsync(() -> size(cache), Util.ioPool()).thenAcceptAsync(size -> line(source,
                String.format(Locale.ROOT, "Data cache: %.2f GB (limit %d GB)", size / 1e9, EarthConfig.CACHE_LIMIT_GB.get()), ChatFormatting.GRAY), server);
        List<ServerPlayer> players = server.getPlayerList().getPlayers();
        line(source, players.size() + " players online (click one to go there):", ChatFormatting.GREEN);
        for (ServerPlayer player : players) {
            GeoPoint point = EarthRules.where(player);
            String name = player.getGameProfile().getName();
            if (point == null) { line(source, "  " + name + ": not on the Earth", ChatFormatting.GRAY); continue; }
            String country = EarthRules.country(player);
            String text = String.format(Locale.ROOT, "  %s: %.4f, %.4f%s, Y %d", name, point.latitude(), point.longitude(),
                    country.isEmpty() ? "" : " in " + country, player.getBlockY());
            String command = String.format(Locale.ROOT, "/earth send @s to %.6f %.6f", point.latitude(), point.longitude());
            Component entry = Component.literal(text).withStyle(style -> style.withColor(ChatFormatting.AQUA)
                    .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, command))
                    .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.literal(command))));
            source.sendSuccess(() -> entry, false);
        }
        return 1;
    }

    private static void line(CommandSourceStack source, String text, ChatFormatting color) {
        source.sendSuccess(() -> Component.literal(text).withStyle(color), false);
    }

    private static long size(Path folder) {
        if (!Files.isDirectory(folder)) return 0;
        try (Stream<Path> files = Files.walk(folder)) {
            return files.filter(Files::isRegularFile).mapToLong(file -> { try { return Files.size(file); } catch (IOException gone) { return 0; } }).sum();
        } catch (IOException | java.io.UncheckedIOException failure) {
            return 0;
        }
    }

    // The web map --------------------------------------------------------------------------------

    @Nullable private static com.sun.net.httpserver.HttpServer web;
    /** What the web map shows, made on the server thread each second and served from the web map's own thread. */
    private static volatile String snapshot = "{\"players\":[]}";

    @SubscribeEvent public static void started(ServerStartedEvent event) {
        int port = EarthConfig.WEB_MAP_PORT.get();
        if (port <= 0 || !EarthWorlds.isEarth(event.getServer())) return;
        try {
            var server = com.sun.net.httpserver.HttpServer.create(new InetSocketAddress(port), 16);
            server.createContext("/", exchange -> {
                String path = exchange.getRequestURI().getPath();
                try (exchange) {
                    switch (path) {
                        case "/", "/index.html" -> send(exchange, "text/html; charset=utf-8", page());
                        case "/players.json" -> send(exchange, "application/json", snapshot.getBytes(StandardCharsets.UTF_8));
                        case "/earth.jpg" -> send(exchange, "image/jpeg", globe());
                        default -> { exchange.sendResponseHeaders(404, -1); }
                    }
                }
            });
            server.setExecutor(java.util.concurrent.Executors.newSingleThreadExecutor(runnable -> {
                Thread thread = new Thread(runnable, "Earth web map");
                thread.setDaemon(true);
                return thread;
            }));
            server.start();
            web = server;
            EarthPlatformMod.LOGGER.info("Earth web map at http://localhost:{}/", port);
        } catch (IOException | RuntimeException | LinkageError failure) {
            EarthPlatformMod.LOGGER.warn("The Earth web map could not start on port {}: {}", port, failure.toString());
        }
    }

    @SubscribeEvent public static void stopping(ServerStoppingEvent event) {
        AtomicBoolean running = preloading;
        if (running != null) running.set(false);
        preloading = null;
        if (web != null) web.stop(0);
        web = null;
        EarthRace.stopped();
        EarthGuess.stopped();
    }

    /** Refreshes what the web map shows. */
    static void second(MinecraftServer server) {
        if (web == null) return;
        StringBuilder json = new StringBuilder("{\"players\":[");
        boolean first = true;
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            GeoPoint point = EarthRules.where(player);
            if (point == null) continue;
            if (!first) json.append(',');
            first = false;
            json.append(String.format(Locale.ROOT, "{\"name\":%s,\"lat\":%.5f,\"lon\":%.5f,\"country\":%s}",
                    quote(player.getGameProfile().getName()), point.latitude(), point.longitude(), quote(EarthRules.country(player))));
        }
        json.append("],\"warps\":[");
        first = true;
        for (Map.Entry<String, EarthSpawns.Spot> warp : EarthSpawns.of(server).warps().entrySet()) {
            if (!first) json.append(',');
            first = false;
            json.append(String.format(Locale.ROOT, "{\"name\":%s,\"lat\":%.5f,\"lon\":%.5f}", quote(warp.getKey()), warp.getValue().latitude(), warp.getValue().longitude()));
        }
        json.append(']');
        EarthGenerator earth = EarthCommands.earth(server.createCommandSourceStack());
        if (earth != null) {
            GeoPoint spawn = EarthCommands.spawnPoint(server.createCommandSourceStack(), earth);
            json.append(String.format(Locale.ROOT, ",\"spawn\":{\"lat\":%.5f,\"lon\":%.5f}", spawn.latitude(), spawn.longitude()));
        }
        json.append(String.format(Locale.ROOT, ",\"motd\":%s}", quote(server.getMotd())));
        snapshot = json.toString();
    }

    private static String quote(String text) {
        StringBuilder out = new StringBuilder("\"");
        for (char c : text.toCharArray()) {
            if (c == '"' || c == '\\') out.append('\\').append(c);
            else if (c < 0x20 || c == '<' || c == '>' || c == '&') out.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
            else out.append(c);
        }
        return out.append('"').toString();
    }

    private static void send(com.sun.net.httpserver.HttpExchange exchange, String type, byte[] body) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", type);
        exchange.getResponseHeaders().set("Cache-Control", type.startsWith("image") ? "max-age=86400" : "no-store");
        exchange.sendResponseHeaders(200, body.length);
        try (OutputStream out = exchange.getResponseBody()) { out.write(body); }
    }

    private static byte[] page;
    private static byte[] globe;

    private static synchronized byte[] page() throws IOException { return page != null ? page : (page = resource("/earthplatform/webmap.html")); }
    private static synchronized byte[] globe() throws IOException { return globe != null ? globe : (globe = resource("/assets/earthplatform/textures/globe/blue_marble.jpg")); }

    private static byte[] resource(String name) throws IOException {
        try (InputStream in = EarthAdmin.class.getResourceAsStream(name)) {
            if (in == null) throw new IOException(name + " is missing from the jar");
            return in.readAllBytes();
        }
    }
}
