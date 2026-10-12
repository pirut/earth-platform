package io.earthplatform.compat.dh;

import java.io.IOException;
import java.net.URI;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Read-only, release-pinned borrowing seam. No upstream payload, fallback loader, shader
 * compilation, setting changes or resource ownership. Installation is attested before Iris
 * initialization. Mounted pack contents are attested once on admission, never per draw.
 */
public final class InstalledIrisRuntime {
    public static final String IRIS_VERSION = "1.8.14-beta.1+mc1.21.1";
    public static final String SODIUM_VERSION = "0.8.12-beta.1+mc1.21.1";
    public static final String IRIS_SHA256 = "60d5f8bf52f25e9986440f4a4270a6bd986d9cff994510e1108ac1547063b314";
    public static final String SODIUM_SHA256 = "dc2838d5538db39414171fe86586ab89d1a30d2fd97f59229cc44cf3777f569d";
    public static final String PHOTON_NAME = "photon_v1.3b.zip";
    public static final String PHOTON_SHA256 = "120897768eaa8cb1f14a3e98f88dff009ae3204ee3b96904a4fe8586edfc1ead";
    // SHA256 of sorted UTF-8 name + NUL + raw per-file SHA256. No upstream source is bundled.
    private static final String PHOTON_CONTENT_SHA256 = "781d431c93dffbe20f59a23cb67bbd2343f2b79da810669ab6c10e51be5124f6";
    static final Map<String, String> STRINGS = Map.of("DH_OVERDRAW_DISTANCE", "24.0", "DH_OVERDRAW_FADE_LENGTH", "4.0");
    static final Map<String, Boolean> BOOLEANS = Map.of("SHADOW", true, "TAA", false, "TAAU", false, "COLORED_LIGHTS", false, "SH_SKYLIGHT", false);
    static final List<String> IRIS_CLASSES = List.of(
            "net.irisshaders.iris.Iris", "net.irisshaders.iris.pipeline.PipelineManager",
            "net.irisshaders.iris.pipeline.IrisRenderingPipeline", "net.irisshaders.iris.compat.dh.DHCompat",
            "net.irisshaders.iris.compat.dh.DHCompatInternal", "net.irisshaders.iris.compat.dh.IrisLodRenderProgram",
            "net.irisshaders.iris.gl.framebuffer.GlFramebuffer", "net.irisshaders.iris.shaderpack.ShaderPack",
            "net.irisshaders.iris.shaderpack.option.ShaderPackOptions", "net.irisshaders.iris.shaderpack.option.OptionSet",
            "net.irisshaders.iris.shaderpack.option.values.OptionValues",
            "net.irisshaders.iris.shaderpack.option.values.MutableOptionValues", "net.irisshaders.iris.config.IrisConfig");
    static final String SODIUM_CLASS = "net.caffeinemc.mods.sodium.client.SodiumClientMod";
    private static final AtomicLong LIFECYCLE = new AtomicLong();

    record InstalledMod(String id, String version, Path path, ClassLoader loader, String moduleName, URI moduleLocation) {
        InstalledMod {
            Objects.requireNonNull(id); Objects.requireNonNull(version); Objects.requireNonNull(path);
            Objects.requireNonNull(loader); Objects.requireNonNull(moduleName); Objects.requireNonNull(moduleLocation);
        }
    }
    interface Catalogue {
        Optional<InstalledMod> installed(String id);
        int effectiveRenderDistance();
        // Defaults preserve legacy test/catalogue implementations. The installed catalogue
        // overrides every value using Minecraft's actual Options, never a caller radius.
        default int selectedRenderDistance() { return effectiveRenderDistance(); }
        default int serverRenderDistanceLimit() { return 0; }
        default boolean singleplayer() { return false; }
        void requireRenderThread();
    }
    @FunctionalInterface interface ArtifactCheck { void check(InstalledMod mod) throws Exception; }
    @FunctionalInterface interface ClassLookup { Class<?> load(String name, ClassLoader loader) throws Exception; }
    @FunctionalInterface interface OriginCheck { void check(InstalledMod mod, Class<?> type) throws Exception; }
    @FunctionalInterface interface AccessFactory { Access create(Map<String, Class<?>> classes, Catalogue catalogue) throws Exception; }
    @FunctionalInterface interface PackCheck { void check(Snapshot snapshot) throws Exception; }
    interface Access {
        Snapshot snapshot() throws Exception;
        BorrowedPass pass(Snapshot snapshot, boolean shadow, boolean opaque) throws Exception;
    }
    /** Only internal factories can construct admission snapshots. Object fields use identity. */
    record Snapshot(Object manager, Object pipeline, Object pack, Object dh, Object options,
                    FileSystem zip, int reloadVersion, String packName, Path directory, int renderDistance,
                    Map<String, String> strings, Map<String, Boolean> booleans,
                    int selectedRenderDistance, int serverRenderDistanceLimit, boolean singleplayer) {
        /** Source-compatible exact-baseline snapshot for existing internal tests. */
        Snapshot(Object manager, Object pipeline, Object pack, Object dh, Object options,
                 FileSystem zip, int reloadVersion, String packName, Path directory, int renderDistance,
                 Map<String, String> strings, Map<String, Boolean> booleans) {
            this(manager, pipeline, pack, dh, options, zip, reloadVersion, packName, directory,
                    renderDistance, strings, booleans, renderDistance, 0, false);
        }
        Snapshot {
            Objects.requireNonNull(manager); Objects.requireNonNull(pipeline); Objects.requireNonNull(pack);
            Objects.requireNonNull(dh); Objects.requireNonNull(options); Objects.requireNonNull(zip);
            Objects.requireNonNull(packName); Objects.requireNonNull(directory);
            directory = directory.toAbsolutePath().normalize();
            strings = Map.copyOf(strings); booleans = Map.copyOf(booleans);
        }
        boolean same(Snapshot that) {
            return manager == that.manager && pipeline == that.pipeline && pack == that.pack && dh == that.dh
                    && options == that.options && zip == that.zip && reloadVersion == that.reloadVersion
                    && packName.equals(that.packName) && directory.equals(that.directory)
                    && renderDistance == that.renderDistance && selectedRenderDistance == that.selectedRenderDistance
                    && serverRenderDistanceLimit == that.serverRenderDistanceLimit && singleplayer == that.singleplayer
                    && strings.equals(that.strings) && booleans.equals(that.booleans);
        }
    }

    private final Catalogue catalogue;
    private final Access access;
    private final PackCheck packCheck;
    private Lease active;
    private String lastFailure = "No shader lease has been admitted";
    private Snapshot failedAdmission;
    private long failedAdmissionEpoch = -1;
    private String failedAdmissionReason = "";

    private InstalledIrisRuntime(Catalogue catalogue, Access access, PackCheck packCheck) {
        this.catalogue = Objects.requireNonNull(catalogue);
        this.access = Objects.requireNonNull(access);
        this.packCheck = Objects.requireNonNull(packCheck);
    }

    /** Verify loaded catalogue entries, official bytes, module locations, then exact reflective ABI. */
    public static InstalledIrisRuntime resolveInstalled() {
        return resolve(new NeoForgeIrisCatalogue(), NeoForgeIrisCatalogue::verifyArtifact,
                (name, loader) -> Class.forName(name, false, loader), InstalledIrisRuntime::verifyOrigin,
                ReflectiveIrisAccess::new, InstalledIrisRuntime::verifyPack);
    }

    static InstalledIrisRuntime resolve(Catalogue catalogue, ArtifactCheck artifact, ClassLookup lookup,
                                        OriginCheck origin, AccessFactory factory, PackCheck packCheck) {
        try {
            InstalledMod iris = requireMod(catalogue, "iris", IRIS_VERSION);
            InstalledMod sodium = requireMod(catalogue, "sodium", SODIUM_VERSION);
            if (catalogue.installed("oculus").isPresent() || catalogue.installed("embeddium").isPresent())
                throw unsupported("Oculus and Embeddium are outside this exact installed Iris/Sodium baseline");
            artifact.check(iris); artifact.check(sodium);
            Map<String, Class<?>> classes = new LinkedHashMap<>();
            // Complete every origin check before factory construction can call any Iris static method.
            for (String name : IRIS_CLASSES) {
                Class<?> type = lookup.load(name, iris.loader());
                origin.check(iris, type); classes.put(name, type);
            }
            Class<?> sodiumType = lookup.load(SODIUM_CLASS, sodium.loader());
            origin.check(sodium, sodiumType); classes.put(SODIUM_CLASS, sodiumType);
            return new InstalledIrisRuntime(catalogue, factory.create(Map.copyOf(classes), catalogue), packCheck);
        } catch (Exception | LinkageError failure) {
            throw unsupported("Installed shader validation failed: " + readable(failure), failure);
        }
    }

    private static InstalledMod requireMod(Catalogue catalogue, String id, String version) {
        InstalledMod mod = catalogue.installed(id).orElseThrow(() -> unsupported(
                "Install " + id + " " + version + " separately; the required loaded mod is absent"));
        if (!id.equals(mod.id()) || !version.equals(mod.version()))
            throw unsupported("Unsupported installed " + id + " version " + mod.version() + "; require " + version);
        if (mod.moduleName().isBlank()) throw unsupported("Loaded " + id + " has no authoritative SecureJar module");
        return mod;
    }

    /** True only on a successfully constructed, byte/origin-attested installation. Not pack readiness. */
    public boolean installationVerified() { return true; }

    /** Called at lifecycle HEAD, before upstream destroys or changes any borrowed resource. */
    public static void invalidateLifecycle(String reason) {
        Objects.requireNonNull(reason);
        LIFECYCLE.updateAndGet(value -> Math.incrementExact(value));
    }

    /** Legacy render-thread admission: the effective native distance must remain exactly 2 chunks. */
    public synchronized Lease lease() { return lease(false); }

    /**
     * Explicit setting-driven admission for the actual Minecraft/Sodium selection (2..32).
     * No radius argument is accepted and no setting is changed. A singleplayer server
     * still catching up with a larger selection refuses admission instead of shrinking it.
     */
    public synchronized Lease leaseDistance() { return lease(true); }

    /** Reads/hashes a newly admitted loaded pack once, with settings and lifecycle race checks. */
    private Lease lease(boolean distanceMode) {
        if (active != null && current(active) && active.distanceMode == distanceMode) return active;
        active = null;
        try {
            long epoch = LIFECYCLE.get();
            Snapshot snapshot = checkedSnapshot();
            validateSettings(snapshot, distanceMode);
            if (failedAdmissionEpoch == epoch && failedAdmission != null && failedAdmission.same(snapshot))
                throw unsupported("This loaded pack generation already failed attestation; reload after correcting it: " + failedAdmissionReason);
            failedAdmission = snapshot; failedAdmissionEpoch = epoch; failedAdmissionReason = "";
            packCheck.check(snapshot);
            Snapshot after = checkedSnapshot();
            validateSettings(after, distanceMode);
            if (epoch != LIFECYCLE.get() || !snapshot.same(after))
                throw unsupported("Shader pipeline, pack or lifecycle changed during attestation");
            Lease result = new Lease(this, snapshot, epoch, distanceMode);
            active = result;
            failedAdmission = null; failedAdmissionEpoch = -1; failedAdmissionReason = "";
            lastFailure = "";
            return result;
        } catch (Exception | LinkageError failure) {
            lastFailure = readable(failure);
            if (failedAdmission != null && failedAdmissionReason.isEmpty()) failedAdmissionReason = lastFailure;
            throw unsupported("Installed shader lease unavailable: " + lastFailure, failure);
        }
    }

    private synchronized boolean current(Lease lease) {
        if (active != lease || lease.epoch != LIFECYCLE.get()) { if (active == lease) active = null; return false; }
        try {
            Snapshot now = checkedSnapshot();
            validateSettings(now, lease.distanceMode);
            if (lease.epoch == LIFECYCLE.get() && lease.snapshot.same(now)) return true;
            lastFailure = "Shader pipeline, pack, options or reload identity changed";
        } catch (Exception | LinkageError failure) { lastFailure = readable(failure); }
        // Once observed invalid, the lease never becomes current again, even if objects revert (ABA).
        active = null;
        invalidateLifecycle("Observed installed shader identity or options change");
        return false;
    }

    private record Distances(int selected, int effective, int serverLimit, boolean singleplayer) {
        boolean matches(Snapshot snapshot) {
            return selected == snapshot.selectedRenderDistance() && effective == snapshot.renderDistance()
                    && serverLimit == snapshot.serverRenderDistanceLimit() && singleplayer == snapshot.singleplayer();
        }
    }

    private Distances distances() {
        return new Distances(catalogue.selectedRenderDistance(), catalogue.effectiveRenderDistance(),
                catalogue.serverRenderDistanceLimit(), catalogue.singleplayer());
    }

    /** Bind the loaded snapshot to actual catalogue values on both sides of every read. */
    private Snapshot checkedSnapshot() throws Exception {
        catalogue.requireRenderThread();
        Distances before = distances();
        Snapshot snapshot = access.snapshot();
        Distances after = distances();
        if (!before.equals(after) || !before.matches(snapshot))
            throw unsupported("Actual selected/effective/server render distance changed or differs from the shader snapshot");
        return snapshot;
    }

    /** Release only our strong reference; never closes or otherwise mutates any upstream object. */
    public synchronized void release(Lease exact) {
        if (exact != null && active == exact) active = null;
    }

    /** Session retirement, including failed admission references. Old leases remain permanently stale. */
    public synchronized void releaseAllReferences() {
        active = null; failedAdmission = null; failedAdmissionEpoch = -1; failedAdmissionReason = "";
        lastFailure = "Shader session references released";
    }

    public synchronized String lastFailure() { return lastFailure; }

    /** Immutable validated identities. No close/free/destroy API exists on this borrowed lease. */
    public static final class Lease {
        private final InstalledIrisRuntime owner;
        private final Snapshot snapshot;
        private final long epoch;
        private final boolean distanceMode;
        private Lease(InstalledIrisRuntime owner, Snapshot snapshot, long epoch, boolean distanceMode) {
            this.owner = owner; this.snapshot = snapshot; this.epoch = epoch; this.distanceMode = distanceMode;
        }
        public Object pipelineIdentity() { return snapshot.pipeline(); }
        public Object packIdentity() { return snapshot.pack(); }
        public long lifecycleEpoch() { return epoch; }
        public int reloadVersion() { return snapshot.reloadVersion(); }
        public boolean distanceMode() { return distanceMode; }
        public int selectedRenderDistance() { return snapshot.selectedRenderDistance(); }
        public int effectiveRenderDistance() { return snapshot.renderDistance(); }
        /** Actual Options server limit; a nonpositive value means no limit in the pinned client. */
        public int serverRenderDistanceLimit() { return snapshot.serverRenderDistanceLimit(); }
        public boolean current() { return owner.current(this); }
        public BorrowedPass dhPass(boolean shadow, boolean opaque) {
            synchronized (owner) {
                if (!current()) throw unsupported("The borrowed Iris pipeline lease has been revoked");
                if (shadow) throw unsupported("Photon v1.3b disables DH shadows; no shadow DH pass may be borrowed");
                try {
                    BorrowedPass pass = owner.access.pass(snapshot, false, opaque);
                    if (pass.shadow() || pass.opaque() != opaque || !current())
                        throw unsupported("DH pass or shader generation changed while borrowing");
                    return pass;
                } catch (Exception | LinkageError failure) {
                    owner.lastFailure = readable(failure); owner.active = null;
                    throw unsupported("Cannot borrow the installed DH shader pass: " + owner.lastFailure, failure);
                }
            }
        }
    }

    /** Upstream object/GL names are borrowed; callers must not free, close, clear or delete them. */
    public record BorrowedPass(Object programIdentity, int programId, int modelOffsetLocation,
                               int framebufferId, boolean shadow, boolean opaque, int projectionLocation, int modelViewLocation) {
        public BorrowedPass {
            Objects.requireNonNull(programIdentity);
            if (programId <= 0 || modelOffsetLocation < 0 || framebufferId <= 0)
                throw unsupported("Invalid or unavailable upstream program, modelOffset uniform or framebuffer");
            if (projectionLocation < 0 || modelViewLocation < 0)
                throw unsupported("Required active Photon dhProjection/iris_ModelViewMatrix uniform is unavailable");
        }
    }

    static void validateSettings(Snapshot snapshot) { validateSettings(snapshot, false); }

    private static void validateSettings(Snapshot snapshot, boolean distanceMode) {
        if (!PHOTON_NAME.equals(snapshot.packName()) || !snapshot.zip().isOpen())
            throw unsupported("Only the current open, unchanged " + PHOTON_NAME + " ZIP is supported");
        if (distanceMode) {
            int selected = snapshot.selectedRenderDistance();
            int effective = snapshot.renderDistance();
            int server = snapshot.serverRenderDistanceLimit();
            if (selected < 2 || selected > 32)
                throw unsupported("Selected native render distance must be between 2 and 32 chunks; actual selection is " + selected);
            int expected = server > 0 ? Math.min(selected, server) : selected;
            if (effective != expected || effective < 2 || effective > 32)
                throw unsupported("Actual effective native render distance " + effective + " does not match selected "
                        + selected + " and server limit " + server + " under the pinned Options rule");
            if (snapshot.singleplayer() && effective != selected)
                throw unsupported("Native render distance is pending: selected " + selected + " chunks, effective "
                        + effective + ", server limit " + server + "; waiting for the singleplayer server to catch up; settings were not changed");
        } else if (snapshot.renderDistance() != 2) {
            throw unsupported("Effective native render distance must be 2 chunks");
        }
        if (!snapshot.strings().equals(STRINGS) || !snapshot.booleans().equals(BOOLEANS))
            throw unsupported("Loaded Photon options must exist and equal D24/L4, SHADOW on, TAA/TAAU/COLORED_LIGHTS/SH_SKYLIGHT off; settings were not changed");
    }

    static void verifyOrigin(InstalledMod mod, Class<?> type) throws IOException {
        Module module = type.getModule();
        if (!module.isNamed() || !mod.moduleName().equals(module.getName()) || module.getLayer() == null)
            throw new IOException(type.getName() + " is outside the authoritative installed " + mod.id() + " SecureJar module");
        var resolved = module.getLayer().configuration().findModule(module.getName())
                .orElseThrow(() -> new IOException("Missing installed module reference for " + type.getName()));
        URI location = resolved.reference().location().orElseThrow(() -> new IOException("Missing installed module origin"));
        if (!mod.moduleLocation().equals(location)) throw new IOException("Foreign module location for " + type.getName());
    }

    static void verifyPack(Snapshot snapshot) throws IOException {
        Path directory = snapshot.directory().toRealPath();
        Path path = snapshot.directory().resolve(PHOTON_NAME).toRealPath();
        if (!directory.equals(path.getParent())) throw new IOException("Selected Photon ZIP escapes the shaderpacks directory");
        verifyBytes(path, 3_800_987L, PHOTON_SHA256, "Photon v1.3b");
        URI mounted = snapshot.zip().getPath("/").toUri();
        if (!"jar".equals(mounted.getScheme())) throw new IOException("Selected pack is not a standard mounted ZIP");
        String part = mounted.getRawSchemeSpecificPart();
        int separator = part.lastIndexOf("!/");
        if (separator < 0 || !Path.of(URI.create(part.substring(0, separator))).toRealPath().equals(path))
            throw new IOException("Loaded Photon ZIP filesystem has a different artifact origin");
        // Verify mounted bytes as well: a path/hash alone cannot attest an older open ZIP after rename.
        Path root = snapshot.zip().getPath("/");
        List<Path> files;
        try (var walk = Files.walk(root)) {
            files = walk.filter(Files::isRegularFile).sorted((a, b) -> root.relativize(a).toString().compareTo(root.relativize(b).toString())).toList();
        }
        if (files.size() != 531) throw new IOException("The mounted Photon ZIP has an unsupported file catalogue");
        MessageDigest digest = sha256();
        long bytes = 0;
        for (Path file : files) {
            bytes = Math.addExact(bytes, Files.size(file));
            if (bytes > 4_935_038L) throw new IOException("The mounted Photon ZIP exceeds its pinned content size");
            digest.update(root.relativize(file).toString().getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0); digest.update(hash(file));
        }
        if (bytes != 4_935_038L || !PHOTON_CONTENT_SHA256.equals(HexFormat.of().formatHex(digest.digest())))
            throw new IOException("The mounted Photon ZIP content differs from the unchanged official release");
    }

    static void verifyBytes(Path path, long length, String expected, String label) throws IOException {
        if (!Files.isRegularFile(path) || Files.size(path) != length || !expected.equals(HexFormat.of().formatHex(hash(path))))
            throw new IOException("Unsupported " + label + " artifact bytes/hash");
    }
    static byte[] hash(Path path) throws IOException {
        MessageDigest digest = sha256();
        try (var input = Files.newInputStream(path)) {
            byte[] bytes = new byte[65_536]; int read;
            while ((read = input.read(bytes)) != -1) digest.update(bytes, 0, read);
        }
        return digest.digest();
    }
    static MessageDigest sha256() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
    static IllegalStateException unsupported(String reason) { return new IllegalStateException(reason); }
    static IllegalStateException unsupported(String reason, Throwable cause) { return new IllegalStateException(reason, cause); }
    private static String readable(Throwable failure) {
        if (failure instanceof java.lang.reflect.InvocationTargetException && failure.getCause() != null) failure = failure.getCause();
        String text = failure.getClass().getSimpleName() + ": " + String.valueOf(failure.getMessage());
        return text.substring(0, Math.min(768, text.length()));
    }
}
