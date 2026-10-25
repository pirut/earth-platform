package io.earthplatform.minecraft.diagnostics;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.zip.GZIPOutputStream;

/**
 * Opt-in upload of the local performance log. Off unless the player turns it on, and inert unless
 * the build (or {@code -Dearthplatform.perfReports.url}) names a receiver. It sends exactly the
 * window lines {@link LocalPerfLog} already writes, batched, with the build line and a random id
 * made fresh each launch; nothing else. Sending never runs on the render thread, never retries a
 * failed batch, and backs off after a failure, so a dead receiver costs nothing.
 */
public final class PerfReports implements AutoCloseable {
    /** No receiver is baked in yet: reports stay off until one is chosen. */
    private static final String BUILT_IN_URL = "";
    private static final int MAX_WINDOWS = 120, MAX_BODY_BYTES = 512 << 10;
    private static final long MAX_BACKOFF_MS = 3_600_000L;

    /** {@code -Dearthplatform.perfReports=false} forces reports off whatever the setting says. */
    public static boolean allowed() { return Boolean.parseBoolean(System.getProperty("earthplatform.perfReports", "true")); }

    /** The receiver, or null when none is set or it isn't https (plain http only to this computer). */
    public static URI endpoint() { return endpoint(System.getProperty("earthplatform.perfReports.url", BUILT_IN_URL)); }
    static URI endpoint(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            URI uri = URI.create(value.trim());
            String host = uri.getHost();
            if (host == null || uri.getUserInfo() != null) return null;
            if ("https".equals(uri.getScheme())) return uri;
            boolean local = host.equals("localhost") || host.equals("127.0.0.1") || host.equals("[::1]");
            return "http".equals(uri.getScheme()) && local ? uri : null;
        } catch (IllegalArgumentException bad) { return null; }
    }

    private final URI endpoint;
    private final BooleanSupplier enabled;
    private final String session = UUID.randomUUID().toString(), header;
    private final long periodMs;
    private final List<String> windows = new ArrayList<>();
    private final Thread worker;
    private final HttpClient http;
    private volatile boolean closed;
    private volatile int sent, failed;
    private long backoffMs;

    public PerfReports(URI endpoint, BooleanSupplier enabled, String header, long periodMs) {
        this.endpoint = endpoint; this.enabled = enabled; this.header = header; this.periodMs = periodMs;
        http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NEVER).build();
        worker = new Thread(this::sendLoop, "Earth performance reports"); worker.setDaemon(true); worker.start();
    }

    /** Off: the batch is thrown away at once, so turning reports off never sends what was held. */
    public void offer(String window) {
        boolean on = on();
        synchronized (windows) {
            if (!on) { windows.clear(); return; }
            if (windows.size() >= MAX_WINDOWS) windows.removeFirst();
            windows.add(window);
        }
    }
    public boolean on() {
        try { return !closed && allowed() && enabled.getAsBoolean(); } catch (RuntimeException notLoaded) { return false; }
    }
    /** The id shown on F3, so a player can ask for their reports to be deleted. */
    public String session() { return session; }
    public int sent() { return sent; }
    public int failed() { return failed; }

    String body(List<String> batch) {
        return "{\"schema\":1,\"session\":\"" + session + "\",\"build\":" + header + ",\"windows\":[" + String.join(",", batch) + "]}";
    }
    private void sendLoop() {
        while (!closed) {
            try { Thread.sleep(periodMs + backoffMs); } catch (InterruptedException interrupted) { continue; }
            List<String> batch;
            synchronized (windows) { batch = new ArrayList<>(windows); windows.clear(); }
            if (batch.isEmpty() || !on()) continue;
            try {
                byte[] gzip = gzip(body(batch));
                if (gzip.length > MAX_BODY_BYTES) { failed++; continue; }
                HttpRequest request = HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(20))
                        .header("Content-Type", "application/json").header("Content-Encoding", "gzip")
                        .POST(HttpRequest.BodyPublishers.ofByteArray(gzip)).build();
                int status = http.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
                if (status / 100 == 2) { sent++; backoffMs = 0; } else fail();
            } catch (IOException | RuntimeException failure) { fail(); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); return; }
        }
    }
    private void fail() { failed++; backoffMs = Math.min(MAX_BACKOFF_MS, Math.max(periodMs, backoffMs * 2)); }
    static byte[] gzip(String text) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (GZIPOutputStream zip = new GZIPOutputStream(out)) { zip.write(text.getBytes(StandardCharsets.UTF_8)); }
        return out.toByteArray();
    }
    /** Held windows are dropped, not flushed: quitting never waits on the network. */
    @Override public void close() { closed = true; worker.interrupt(); synchronized (windows) { windows.clear(); } }
}
