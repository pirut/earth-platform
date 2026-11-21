package io.earthplatform.minecraft.bug;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** The end of {@code logs/latest.log}, trimmed and scrubbed. Missing logs stay a short note. */
public final class LogExcerpt {
    private LogExcerpt() {}

    public static String tail(Path gameDir, int maxLines, int maxChars, String... hide) {
        if (gameDir == null) return "no log yet";
        Path log = gameDir.resolve("logs").resolve("latest.log");
        if (!Files.isRegularFile(log)) return "no log yet";
        int bytes = Math.min(Math.max(maxChars * 4, 2048), 64 * 1024);
        try (RandomAccessFile file = new RandomAccessFile(log.toFile(), "r")) {
            long length = file.length();
            long start = Math.max(0, length - bytes);
            byte[] raw = new byte[(int) (length - start)];
            file.seek(start);
            file.readFully(raw);
            String text = new String(raw, StandardCharsets.UTF_8);
            if (start > 0) {
                int newline = text.indexOf('\n');
                text = newline >= 0 ? text.substring(newline + 1) : text;
            }
            return trim(ReportText.scrub(text, hide), maxLines, maxChars);
        } catch (IOException e) {
            return "log could not be read";
        }
    }

    static String trim(String text, int maxLines, int maxChars) {
        if (text == null || text.isBlank()) return "no log yet";
        String[] lines = text.strip().split("\\R");
        int from = Math.max(0, lines.length - Math.max(1, maxLines));
        StringBuilder out = new StringBuilder();
        for (int i = from; i < lines.length; i++) {
            String line = lines[i].length() > 300 ? lines[i].substring(0, 300) + "…" : lines[i];
            if (out.length() > 0) out.append('\n');
            if (out.length() + line.length() > maxChars) {
                int room = maxChars - out.length();
                if (room > 20) out.append(line, 0, room).append('…');
                break;
            }
            out.append(line);
        }
        String excerpt = out.toString().strip();
        return excerpt.isEmpty() ? "no log yet" : excerpt;
    }
}
