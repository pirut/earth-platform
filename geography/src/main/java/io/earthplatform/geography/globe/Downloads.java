package io.earthplatform.geography.globe;

import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/**
 * Every download the globe's sources make, by source: files, bytes and the time spent waiting on
 * them, so a slow first visit can be told apart into the network and the work after it.
 */
public final class Downloads {
    private record Tally(LongAdder files, LongAdder bytes, LongAdder nanos) {}

    private static final Map<String, Tally> SOURCES = new ConcurrentHashMap<>();

    private Downloads() {}

    static void count(String source, long bytes, long nanos) {
        Tally tally = SOURCES.computeIfAbsent(source, ignored -> new Tally(new LongAdder(), new LongAdder(), new LongAdder()));
        tally.files.increment();
        tally.bytes.add(bytes);
        tally.nanos.add(nanos);
    }

    /** The totals so far by source: files, bytes, nanoseconds. */
    public static Map<String, long[]> totals() {
        Map<String, long[]> totals = new TreeMap<>();
        SOURCES.forEach((source, tally) -> totals.put(source, new long[] {tally.files.sum(), tally.bytes.sum(), tally.nanos.sum()}));
        return totals;
    }

    /**
     * What was downloaded since {@code before}, one source after another, as "Land cover 12 files
     * 1.4 MB, 3.1 s waiting (260 ms each)"; the waits of files fetched together add up.
     */
    public static String since(Map<String, long[]> before) {
        StringBuilder text = new StringBuilder();
        long files = 0, bytes = 0;
        for (Map.Entry<String, long[]> entry : totals().entrySet()) {
            long[] now = entry.getValue(), then = before.getOrDefault(entry.getKey(), new long[3]);
            long count = now[0] - then[0], size = now[1] - then[1], nanos = now[2] - then[2];
            if (count == 0) continue;
            files += count;
            bytes += size;
            if (!text.isEmpty()) text.append("; ");
            text.append(String.format("%s %d files %.1f MB, %.1f s waiting (%d ms each)",
                    entry.getKey(), count, size / 1e6, nanos / 1e9, nanos / count / 1_000_000));
        }
        if (files == 0) return "nothing downloaded";
        return String.format("%d files %.1f MB: %s", files, bytes / 1e6, text);
    }
}
