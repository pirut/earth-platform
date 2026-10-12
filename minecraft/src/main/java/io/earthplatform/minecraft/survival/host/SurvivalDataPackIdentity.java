package io.earthplatform.minecraft.survival.host;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.Map;
import java.util.List;
import net.minecraft.core.registries.Registries;
import net.neoforged.neoforge.registries.DataPackRegistriesHooks;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;

/**
 * Setup-time content identity for the admitted native gameplay resource domains. Hashes all
 * stack layers in those domains (including lower tag layers), pack ordering, registry identity and executable
 * gameplay profile identity. It is change detection, never callback or publication authority.
 * The caller must hold the server resource-reload boundary while capturing and retire a session
 * when that resource generation changes. Custom resource consumers outside these explicit
 * domains require their own admission; this is not a generic all-pack-file hash.
 * No ResourceManager or opened stream is retained.
 */
public final class SurvivalDataPackIdentity {
    public record Limits(int maxPacks, int maxPaths, int maxLayers, long maxResourceBytes, long maxTotalBytes) {
        public Limits {
            if (maxPacks < 1 || maxPaths < 1 || maxLayers < 1 || maxResourceBytes < 1 || maxTotalBytes < 1)
                throw new IllegalArgumentException("Explicit positive server-resource limits required");
        }
    }
    public record Capture(String digest, int packs, int paths, int layers, long bytes, Map<String,Integer> domainPaths) {
        public Capture { domainPaths = Map.copyOf(domainPaths); }
    }
    /** Matches native reload listeners plus the actual registered datapack registry directories. */
    public static List<String> admittedDomains() {
        var roots = new TreeSet<>(List.of("recipe", "advancement", "function", "loot_table", "predicate",
                "item_modifier", "tags", "structure", "loot_modifiers", "data_maps"));
        DataPackRegistriesHooks.getDataPackRegistriesWithDimensions().forEach(data -> {
            roots.add(Registries.elementsDirPath(data.key()));
            if (roots.size() > 128) throw new IllegalArgumentException("Admitted native resource domain bound exceeded");
        });
        // Keep a nonoverlapping set. A parent query includes every child path, exactly once.
        roots.removeIf(root -> roots.stream().anyMatch(parent -> !parent.equals(root) && root.startsWith(parent + "/")));
        for (String root : roots) if (root.isBlank() || root.startsWith("/") || root.endsWith("/") || root.contains(".."))
            throw new IllegalArgumentException("Invalid native resource domain");
        return List.copyOf(roots);
    }
    private SurvivalDataPackIdentity() {}
    public static Capture capture(ResourceManager resources, String nativeRegistryIdentity,
            String executableProfileIdentity, Limits limits) throws IOException {
        Objects.requireNonNull(resources); Objects.requireNonNull(limits);
        MessageDigest digest = digest(); text(digest, "earth-survival-admitted-server-resource-domains-039-v2");
        text(digest, identity(nativeRegistryIdentity)); text(digest, identity(executableProfileIdentity));
        int packs = 0;
        try (var stream = resources.listPacks()) {
            var iterator = stream.iterator();
            while (iterator.hasNext()) {
                if (++packs > limits.maxPacks) throw new IOException("Server-resource pack count exceeds admission");
                text(digest, identity(iterator.next().packId()));
            }
        }
        number(digest, packs);
        // The resource manager owns enumeration. This helper bounds its own sorted metadata copy
        // before copying and streams each admitted resource through one fixed 8KiB buffer.
        var sorted = new TreeMap<ResourceLocation, java.util.List<net.minecraft.server.packs.resources.Resource>>();
        var domains = new TreeMap<String,Integer>();
        for (String domain : admittedDomains()) {
            text(digest, domain);
            var listed = resources.listResourceStacks(domain, location -> true);
            if (listed.size() > limits.maxPaths) throw new IOException("Server-resource path count exceeds admission");
            for (var entry : listed.entrySet()) {
                if (!entry.getKey().getPath().startsWith(domain + "/"))
                    throw new IOException("Resource manager returned an out-of-domain path");
                if (!sorted.containsKey(entry.getKey()) && sorted.size() >= limits.maxPaths)
                    throw new IOException("Server-resource union path count exceeds admission");
                sorted.putIfAbsent(entry.getKey(), entry.getValue());
            }
            domains.put(domain, listed.size());
        }
        number(digest, domains.size());
        int layers = 0; long total = 0; byte[] buffer = new byte[8192];
        for (var entry : sorted.entrySet()) {
            text(digest, entry.getKey().toString());
            var stack = Objects.requireNonNull(entry.getValue());
            if (stack.isEmpty() || stack.size() > limits.maxLayers - layers)
                throw new IOException("Server-resource layer count exceeds admission");
            number(digest, stack.size());
            for (var resource : stack) {
                layers++; text(digest, identity(resource.sourcePackId()));
                MessageDigest content = digest(); long length = 0;
                try (InputStream input = resource.open()) {
                    while (true) {
                        // At most one byte beyond the admitted resource/aggregate limit is read.
                        long remaining = Math.min(limits.maxResourceBytes - length, limits.maxTotalBytes - total);
                        int request = remaining >= buffer.length ? buffer.length : Math.toIntExact(remaining) + 1;
                        int read = input.read(buffer, 0, request);
                        if (read < 0) break;
                        if (read == 0) { int one = input.read(); if (one < 0) break; buffer[0] = (byte)one; read = 1; }
                        if (read > remaining) throw new IOException("Server-resource byte limit exceeded");
                        length += read; total += read; content.update(buffer, 0, read);
                    }
                }
                number(digest, length); digest.update(content.digest());
            }
        }
        number(digest, sorted.size()); number(digest, layers); number(digest, total);
        return new Capture(HexFormat.of().formatHex(digest.digest()), packs, sorted.size(), layers, total, domains);
    }
    private static String identity(String value) {
        if (value == null || value.isBlank() || value.length() > 4096)
            throw new IllegalArgumentException("Bounded nonblank identity required");
        return value;
    }
    private static void text(MessageDigest digest, String text) {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8); number(digest, bytes.length); digest.update(bytes);
    }
    private static void number(MessageDigest digest, long value) { for (int shift = 56; shift >= 0; shift -= 8) digest.update((byte)(value >>> shift)); }
    private static MessageDigest digest() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
}
