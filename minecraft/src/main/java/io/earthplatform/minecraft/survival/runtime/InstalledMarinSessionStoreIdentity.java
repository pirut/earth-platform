package io.earthplatform.minecraft.survival.runtime;

import io.earthplatform.core.WorldIdentity;
import io.earthplatform.minecraft.survival.persistence.NativeSurvivalCheckpoint.Binding;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Objects;
import java.util.UUID;
import net.minecraft.resources.ResourceLocation;
import static java.nio.file.StandardOpenOption.*;

/** Durable per-save/player/profile identity. No session UUID, temporary directory or escrow replay.
 * The caller retains the one checkpoint store lock separately. Interrupted creation is a visible
 * recovery-required state; an existing directory is never interpreted as a fresh empty world.
 */
public final class InstalledMarinSessionStoreIdentity {
    public static final String DIRECTORY = "earthplatform-survival";
    private static final int MAGIC = 0x45534944, VERSION = 1, MAX_BYTES = 16 * 1024;
    public record Spec(String profile, WorldIdentity world, String sourceIdentity, String registryIdentity,
                       String dataPackIdentity, UUID player, ResourceLocation dimension, long nativeBiomeSeed) {
        public Spec {
            Objects.requireNonNull(world); Objects.requireNonNull(player); Objects.requireNonNull(dimension);
            if (profile == null || !profile.matches("[a-z][a-z0-9-]{0,95}"))
                throw new IllegalArgumentException("Safe explicit installed gameplay profile required");
            for (String value : new String[]{sourceIdentity, registryIdentity, dataPackIdentity, dimension.toString()})
                if (value == null || value.isBlank() || value.length() > 1024)
                    throw new IllegalArgumentException("Bounded stable installed identity required");
            if (!world.blockPaletteVersion().equals(registryIdentity))
                throw new IllegalArgumentException("Gameplay world and registry identity differ");
        }
    }
    public record Opened(Path worldRoot, Path directory, Path checkpointDirectory, Binding binding,
                         long nativeBiomeSeed, boolean newlyCreated) {}
    private InstalledMarinSessionStoreIdentity() {}

    /** Must run before player hold. Opening an old identity requires an already-present CURRENT.
     * Missing CURRENT is never repaired by constructing SurvivalCheckpointStore over old data.
     * A newly-created result authorizes precisely one initial store construction in this process.
     */
    public static Opened open(Path actualWorldRoot, Spec expected) throws IOException {
        Objects.requireNonNull(expected);
        Path root = Objects.requireNonNull(actualWorldRoot).toAbsolutePath().normalize();
        requirePlainAncestors(root);
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Actual saved world root is missing");
        root = root.toRealPath(LinkOption.NOFOLLOW_LINKS);
        Path namespace = directoryChild(root, DIRECTORY);
        Path profile = directoryChild(namespace, expected.profile());
        Path directory = profile.resolve(expected.player().toString());
        boolean fresh = !Files.exists(directory, LinkOption.NOFOLLOW_LINKS);
        if (fresh) {
            // Exclusive creation: a racing owner or interrupted prior attempt cannot be replaced.
            Files.createDirectory(directory); sync(profile);
            UUID saveId = UUID.randomUUID();
            writeNew(directory.resolve("IDENTITY"), encode(saveId, expected));
            sync(directory); sync(profile);
            return opened(root, directory, saveId, expected, true);
        }
        requirePlainDirectory(directory);
        var stored = decode(readBounded(directory.resolve("IDENTITY")));
        if (!stored.spec.equals(expected)) throw new IOException("Installed save policy, seed, player or registry identity differs; explicit recovery or migration required");
        Path checkpoint = directory.resolve("checkpoint");
        requirePlainDirectory(checkpoint);
        if (!Files.isRegularFile(checkpoint.resolve("CURRENT"), LinkOption.NOFOLLOW_LINKS))
            throw new IOException("Existing installed save lacks CURRENT; refusing empty-store fallback");
        return opened(root, directory, stored.saveId, expected, false);
    }
    /** Read-only startup fence. Presence requires phase-aware recovery before ordinary login.
     * This conservative first slice refuses even RETURNED restart until native world/actor high-water
     * restoration is implemented. Same-process reentry uses the retained graph and needs no reopen.
     */
    public static boolean requiresStartupRecovery(Path actualWorldRoot, String profile, UUID player) throws IOException {
        if (profile == null || !profile.matches("[a-z][a-z0-9-]{0,95}")) throw new IOException("Invalid installed profile");
        Path root = Objects.requireNonNull(actualWorldRoot).toAbsolutePath().normalize();
        Path namespace = root.resolve(DIRECTORY);
        if (!Files.exists(namespace, LinkOption.NOFOLLOW_LINKS)) return false;
        // The always-on login fence leaves unrelated ordinary worlds unchanged, including worlds
        // with linked ancestors. Once installed authority exists, links cannot bypass its fence.
        requirePlainAncestors(root); requirePlainDirectory(namespace);
        Path profileRoot = namespace.resolve(profile);
        if (!Files.exists(profileRoot, LinkOption.NOFOLLOW_LINKS)) return false;
        requirePlainDirectory(profileRoot);
        Path directory = profileRoot.resolve(Objects.requireNonNull(player).toString());
        if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) return false;
        requirePlainDirectory(directory);
        // Corrupt/missing identity is still recovery-required, not an empty-save permission.
        return true;
    }
    private record Stored(UUID saveId, Spec spec) {}
    private static Opened opened(Path root, Path directory, UUID saveId, Spec spec, boolean fresh) {
        Binding binding = new Binding(saveId, spec.profile(), spec.world(), spec.sourceIdentity(),
                spec.registryIdentity(), spec.dataPackIdentity(), spec.player(), spec.dimension());
        return new Opened(root, directory, directory.resolve("checkpoint"), binding, spec.nativeBiomeSeed(), fresh);
    }
    private static byte[] encode(UUID saveId, Spec spec) throws IOException {
        var bytes = new ByteArrayOutputStream();
        try (var out = new DataOutputStream(bytes)) {
            out.writeInt(MAGIC); out.writeInt(VERSION); uuid(out, saveId);
            out.writeUTF(spec.profile()); uuid(out, spec.player()); out.writeUTF(spec.dimension().toString());
            out.writeLong(spec.nativeBiomeSeed());
            WorldIdentity world = spec.world(); out.writeLong(world.seed());
            for (String value : new String[]{world.generatorId(), world.generatorVersion(), world.datasetVersion(),
                    world.blockPaletteVersion(), spec.sourceIdentity(), spec.registryIdentity(), spec.dataPackIdentity()}) out.writeUTF(value);
        }
        if (bytes.size() > MAX_BYTES) throw new IOException("Installed identity exceeds its fixed metadata budget");
        return bytes.toByteArray();
    }
    private static Stored decode(byte[] bytes) throws IOException {
        try (var in = new DataInputStream(new ByteArrayInputStream(bytes))) {
            if (in.readInt() != MAGIC || in.readInt() != VERSION) throw new IOException("Unsupported installed identity format");
            UUID saveId = uuid(in); String profile = in.readUTF(); UUID player = uuid(in);
            ResourceLocation dimension = ResourceLocation.parse(in.readUTF()); long nativeSeed = in.readLong();
            WorldIdentity world = new WorldIdentity(in.readLong(), in.readUTF(), in.readUTF(), in.readUTF(), in.readUTF());
            Spec spec = new Spec(profile, world, in.readUTF(), in.readUTF(), in.readUTF(), player, dimension, nativeSeed);
            if (in.available() != 0) throw new IOException("Trailing installed identity data");
            if (!java.util.Arrays.equals(bytes, encode(saveId, spec))) throw new IOException("Noncanonical installed identity");
            return new Stored(saveId, spec);
        } catch (IllegalArgumentException failure) { throw new IOException("Malformed installed save identity", failure); }
    }
    private static void uuid(DataOutputStream out, UUID id) throws IOException { out.writeLong(id.getMostSignificantBits()); out.writeLong(id.getLeastSignificantBits()); }
    private static UUID uuid(DataInputStream in) throws IOException { return new UUID(in.readLong(), in.readLong()); }
    private static Path directoryChild(Path parent, String name) throws IOException {
        Path child = parent.resolve(name);
        if (!Files.exists(child, LinkOption.NOFOLLOW_LINKS)) { Files.createDirectory(child); sync(parent); }
        requirePlainDirectory(child); return child;
    }
    private static void requirePlainAncestors(Path path) throws IOException {
        for (Path parent = path; parent != null; parent = parent.getParent())
            if (Files.isSymbolicLink(parent)) throw new IOException("Installed save path contains a symbolic link");
    }
    private static void requirePlainDirectory(Path path) throws IOException {
        if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Installed save directory is missing or nonordinary: " + path.getFileName());
    }
    private static byte[] readBounded(Path path) throws IOException {
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Installed IDENTITY is missing or nonordinary");
        try (var in = Files.newInputStream(path, READ, LinkOption.NOFOLLOW_LINKS)) {
            byte[] bytes = in.readNBytes(MAX_BYTES + 1);
            if (bytes.length > MAX_BYTES) throw new IOException("Installed IDENTITY exceeds fixed metadata budget");
            return bytes;
        }
    }
    private static void writeNew(Path path, byte[] bytes) throws IOException {
        try (var channel = FileChannel.open(path, CREATE_NEW, WRITE, LinkOption.NOFOLLOW_LINKS)) {
            ByteBuffer buffer = ByteBuffer.wrap(bytes); while (buffer.hasRemaining()) channel.write(buffer); channel.force(true);
        }
    }
    private static void sync(Path path) throws IOException { try (var channel = FileChannel.open(path, READ, LinkOption.NOFOLLOW_LINKS)) { channel.force(true); } }
}
