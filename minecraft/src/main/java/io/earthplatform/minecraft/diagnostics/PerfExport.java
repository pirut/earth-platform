package io.earthplatform.minecraft.diagnostics;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Packs the local performance log into one zip a player can attach to a bug report. Nothing is
 * sent anywhere: the player chooses what to do with the file. The zip holds exactly the
 * {@code latest*.jsonl} files {@link LocalPerfLog} writes, plus a note saying what they hold.
 */
public final class PerfExport {
    static final int KEEP = 5;
    private static final DateTimeFormatter NAME = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC);
    private static final String README = """
            Earth Platform performance report

            The latest*.jsonl files are the game's local performance log, newest first: the mod version,
            Java, OS, CPU thread count, GPU and driver, graphics and distance settings, whether a shader
            pack is on (not which one), frame times, memory use and far terrain counts, every few seconds.
            They hold no player or world names, coordinates, server addresses, chat, file paths or error
            text. Attach this zip to an issue at https://github.com/pirut/earth-platform/issues
            """;
    private PerfExport() {}

    /** Writes {@code exports/earth-performance-<utc>.zip} under the log folder and keeps the newest five. */
    public static Path export(Path perfDirectory, Instant now) throws IOException {
        List<Path> logs;
        try (var paths = Files.list(perfDirectory)) {
            logs = paths.filter(p -> p.getFileName().toString().matches("latest(\\.[0-9])?\\.jsonl"))
                    .sorted(Comparator.comparing(p -> p.getFileName().toString())).toList();
        } catch (NoSuchFileException none) { logs = List.of(); }
        if (logs.isEmpty()) throw new NoSuchFileException(perfDirectory.toString(), null, "no performance log yet");
        Path exports = Files.createDirectories(perfDirectory.resolve("exports"));
        Path zip = exports.resolve("earth-performance-" + NAME.format(now) + ".zip");
        try (OutputStream file = Files.newOutputStream(zip); ZipOutputStream out = new ZipOutputStream(file)) {
            out.putNextEntry(new ZipEntry("README.txt")); out.write(README.getBytes(StandardCharsets.UTF_8)); out.closeEntry();
            for (Path log : logs) {
                out.putNextEntry(new ZipEntry(log.getFileName().toString()));
                // The log is still being appended; a half-written last line is harmless to a reader.
                out.write(Files.readAllBytes(log)); out.closeEntry();
            }
        }
        try (var paths = Files.list(exports)) {
            var old = paths.filter(p -> p.getFileName().toString().matches("earth-performance-[0-9-]+\\.zip"))
                    .sorted(Comparator.comparing((Path p) -> p.getFileName().toString()).reversed()).skip(KEEP).toList();
            for (Path p : old) Files.deleteIfExists(p);
        }
        return zip;
    }
}
