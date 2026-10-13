package io.earthplatform.minecraft.cubic.play;

import io.earthplatform.geography.globe.TerrariumTileSource;
import io.earthplatform.minecraft.EarthPlatformMod;
import io.earthplatform.minecraft.cubic.CubicSettings;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * What the Earth's data downloads are doing, told to each player so their screen can say so:
 * how many files are on their way, and whether the data servers can be reached at all.
 */
public final class EarthStatus {
    /** Normal play. */
    public static final byte PLAYING = 0;
    /** Waiting for the data around the world's home before the player can arrive. */
    public static final byte PREPARING = 1;

    /** Nothing wrong. */
    public static final byte OK = 0;
    /** The data servers can't be reached. */
    public static final byte UNREACHABLE = 1;
    /** Offline mode is on and the place is not in the cache. */
    public static final byte OFFLINE_MODE = 2;

    public record Payload(byte phase, byte problem, int downloading, int recent, int waitedSeconds, String place) implements CustomPacketPayload {
        public static final Type<Payload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(EarthPlatformMod.MOD_ID, "earth_status"));
        public static final StreamCodec<FriendlyByteBuf, Payload> CODEC = StreamCodec.of((buf, p) -> {
            buf.writeByte(p.phase); buf.writeByte(p.problem); buf.writeVarInt(p.downloading); buf.writeVarInt(p.recent);
            buf.writeVarInt(p.waitedSeconds); buf.writeUtf(p.place, 256);
        }, buf -> new Payload(buf.readByte(), buf.readByte(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readUtf(256)));
        @Override public Type<Payload> type() { return TYPE; }
    }

    private static final int WINDOW = 15;
    private final long[] downloads = new long[WINDOW], failures = new long[WINDOW];
    private int second;
    private int downloading, recent;
    private volatile boolean unreachable;
    private CompletableFuture<Boolean> probe;
    private long nextProbe;
    private final Map<UUID, Payload> sent = new HashMap<>();
    private final Map<UUID, Long> sentAt = new HashMap<>();

    /** Called once a second on the server thread while an Earth level runs. */
    void sample(long now) {
        TerrariumTileSource.Stats tiles = CubicSettings.tiles().stats();
        var cover = CubicSettings.cover().stats();
        var climate = CubicSettings.climate().stats();
        long totalDownloads = tiles.downloads() + cover.downloads() + climate.downloads();
        long totalFailures = tiles.failures() + cover.failures() + climate.failures();
        int slot = second++ % WINDOW, last = (slot + WINDOW - 2) % WINDOW, oldest = (slot + 1) % WINDOW;
        downloads[slot] = totalDownloads;
        failures[slot] = totalFailures;
        downloading = tiles.inFlight();
        recent = second > 2 ? (int) (totalDownloads - downloads[last]) : 0;
        boolean full = second > WINDOW;
        boolean failing = full && totalFailures - failures[oldest] >= 3 && totalDownloads == downloads[oldest];
        if (probe != null && probe.isDone()) {
            unreachable = !probe.getNow(true);
            probe = null;
        }
        // Check for real before telling anyone they're offline, and keep checking while they are.
        if (probe == null && (failing || unreachable) && now >= nextProbe && !offlineMode()) {
            probe = reach();
            nextProbe = now + 15_000;
        }
        if (unreachable && totalDownloads > downloads[(slot + WINDOW - 1) % WINDOW]) unreachable = false;
    }

    static boolean offlineMode() { return Boolean.getBoolean("earthplatform.earth.offline"); }

    boolean unreachable() { return unreachable; }

    /** Asks the elevation server for its smallest tile, off the server thread. */
    private static CompletableFuture<Boolean> reach() {
        return CompletableFuture.supplyAsync(() -> {
            try {
                HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();
                HttpRequest request = HttpRequest.newBuilder(URI.create(TerrariumTileSource.AWS_TERRAIN_TILES + "0/0/0.png"))
                        .timeout(Duration.ofSeconds(10)).method("HEAD", HttpRequest.BodyPublishers.noBody()).build();
                return http.send(request, HttpResponse.BodyHandlers.discarding()).statusCode() < 500;
            } catch (Exception failure) {
                return false;
            }
        });
    }

    /** Forces a reachability check now, as when the home area could not be fetched. */
    void suspect(long now) {
        if (probe == null && !offlineMode() && now >= nextProbe) { probe = reach(); nextProbe = now + 15_000; }
    }

    Payload payload(byte phase, byte problem, int waitedSeconds, String place) {
        return new Payload(phase, problem, downloading, recent, waitedSeconds, place);
    }

    /** Sends a player their status when it changed, and every few seconds anyway. */
    void send(ServerPlayer player, Payload payload, long now) {
        UUID id = player.getUUID();
        if (payload.equals(sent.get(id)) && now - sentAt.getOrDefault(id, 0L) < 5_000) return;
        sent.put(id, payload);
        sentAt.put(id, now);
        PacketDistributor.sendToPlayer(player, payload);
    }

    void forget(UUID player) { sent.remove(player); sentAt.remove(player); }
}
