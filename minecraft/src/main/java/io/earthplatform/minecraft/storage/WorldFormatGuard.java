package io.earthplatform.minecraft.storage;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** World-level opt-in declaration. It cannot protect a world if this mod is removed. */
public final class WorldFormatGuard {
    public static final String FILE_NAME = "earth-platform-format.properties";
    public static final String WARNING_NAME = "DO_NOT_OPEN_WITHOUT_EARTH_PLATFORM.txt";
    private static final Set<Path> VERIFIED_WIDE_WORLDS = ConcurrentHashMap.newKeySet();
    private static final String CONTENT = "format=earthplatform:wide-section-y\nversion=1\nminecraft=1.21.1\nneoforge=21.1.256\nrequiresEarthPlatform=true\n";
    private static final String WARNING = "This world uses Earth Platform's experimental signed-int section-Y format.\n"
            + "NEVER open it in vanilla Minecraft or with Earth Platform removed. Vanilla ignores this declaration and may silently truncate vertical coordinates.\n"
            + "Keep a separate backup and use the matching Earth Platform format version with earthplatform.experimental.wideSectionY=true.\n"
            + "No vanilla export/migration is implemented. Disabling this mod is not an export.\n";
    private WorldFormatGuard() {}

    public static void preflight(Path worldDirectory, boolean optedIn) {
        VERIFIED_WIDE_WORLDS.remove(worldDirectory.toAbsolutePath().normalize());
        Path file = worldDirectory.resolve(FILE_NAME);
        try {
            if (Files.exists(file)) {
                if (Files.size(file) > 16 * 1024) throw new SectionYIntegrityError("Oversized Earth world-format declaration");
                var properties = new Properties();
                try (var input = Files.newBufferedReader(file, StandardCharsets.UTF_8)) { properties.load(input); }
                if (!"earthplatform:wide-section-y".equals(properties.getProperty("format"))
                        || !"1".equals(properties.getProperty("version"))
                        || !"1.21.1".equals(properties.getProperty("minecraft"))
                        || !"21.1.256".equals(properties.getProperty("neoforge"))
                        || !"true".equals(properties.getProperty("requiresEarthPlatform"))) {
                    throw new SectionYIntegrityError("Incompatible Earth world-format declaration at " + file);
                }
                if (!optedIn) throw new SectionYIntegrityError("This world requires Earth Platform wide-section mode; enable " + SectionYCodec.OPT_IN);
            } else if (optedIn) {
                Files.createDirectories(worldDirectory);
                durableCreate(file, CONTENT);
            } else return; // Default vanilla-format worlds are not modified.
            if (!Files.exists(worldDirectory.resolve(WARNING_NAME))) durableCreate(worldDirectory.resolve(WARNING_NAME), WARNING);
            // Wide-mode startup is allowed only on filesystems supporting directory synchronization.
            try (var directory = FileChannel.open(worldDirectory, StandardOpenOption.READ)) { directory.force(true); }
            VERIFIED_WIDE_WORLDS.add(worldDirectory.toAbsolutePath().normalize());
        } catch (IOException | IllegalArgumentException failure) {
            throw new SectionYIntegrityError("Cannot safely verify or declare Earth world format at " + file + ": " + failure.getMessage());
        }
    }

    public static void requireVerifiedWideWorld(Path worldDirectory) {
        if (!VERIFIED_WIDE_WORLDS.contains(worldDirectory.toAbsolutePath().normalize())) {
            throw new SectionYIntegrityError("Wide section write attempted before successful world-format preflight");
        }
    }

    private static void durableCreate(Path destination, String content) throws IOException {
        // CREATE_NEW is an atomic no-replace operation. A crash during this write leaves an invalid
        // declaration, which fails the next preflight; wide writes cannot start before this returns.
        try (var channel = FileChannel.open(destination, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            ByteBuffer bytes = StandardCharsets.UTF_8.encode(content);
            while (bytes.hasRemaining()) channel.write(bytes);
            channel.force(true);
        }
    }
}
