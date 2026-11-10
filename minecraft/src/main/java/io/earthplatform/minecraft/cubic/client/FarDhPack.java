package io.earthplatform.minecraft.cubic.client;

import io.earthplatform.minecraft.EarthPlatformMod;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.WeakHashMap;
import java.util.stream.Stream;
import java.util.zip.ZipFile;
import javax.annotation.Nullable;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.gl.framebuffer.GlFramebuffer;
import net.irisshaders.iris.pipeline.IrisRenderingPipeline;
import net.irisshaders.iris.pipeline.WorldRenderingPipeline;
import net.irisshaders.iris.pipeline.transform.PatchShaderType;
import net.irisshaders.iris.pipeline.transform.TransformPatcher;
import net.irisshaders.iris.shaderpack.loading.ProgramId;
import net.irisshaders.iris.shaderpack.programs.ProgramSet;
import net.irisshaders.iris.shaderpack.programs.ProgramSource;

/**
 * Far terrain under a shader pack that supports Distant Horizons but not Voxy. Such a pack has no
 * small piece of code to shade one far fragment with; it has a whole program for Distant Horizons'
 * terrain ({@code dh_terrain}), and reads Distant Horizons' depth and projection in its later
 * passes. So the pack is compiled as if Distant Horizons were drawing ({@code DISTANT_HORIZONS}),
 * far terrain is drawn with the pack's program into the targets the program names, each far face
 * handed to it as a Distant Horizons vertex (see {@link FarShaders#dhPatch}), and far terrain's own
 * depth and projection ({@link FarLod}) are given out under Distant Horizons' names. Nothing of
 * Distant Horizons itself is used or needed. Only loaded with Iris.
 */
public final class FarDhPack {
    /** {@code -Dearthplatform.far.dhPacks=false} leaves such packs as they were: far terrain in plain colours. */
    static final boolean ENABLED = !"false".equals(System.getProperty("earthplatform.far.dhPacks"));

    private static final Map<ProgramSet, Optional<FarShaders.PackPatch>> PATCHES = Collections.synchronizedMap(new WeakHashMap<>());
    private static final Map<Object, Optional<GlFramebuffer>> TARGETS = new WeakHashMap<>();

    /** The pack last noted in the log, so loading it again doesn't note it again. */
    @Nullable private static String noted;

    private FarDhPack() {}

    /**
     * Whether the pack about to be loaded has a Distant Horizons terrain program and no Voxy code,
     * from the names of its files: Iris decides a pack's defines before it reads the pack.
     */
    static boolean wanted() {
        if (!ENABLED) return false;
        try {
            String name = Iris.getIrisConfig().getShaderPackName().orElse(null);
            if (name == null) return false;
            Path root = Iris.getShaderpacksDirectory().resolve(name);
            boolean dh, voxy;
            if (Files.isDirectory(root)) {
                try (Stream<Path> files = Files.walk(root, 6)) {
                    java.util.Set<String> names = new java.util.HashSet<>();
                    files.forEach(file -> names.add(file.getFileName().toString()));
                    dh = names.contains("dh_terrain.fsh");
                    voxy = names.contains("voxy_opaque.glsl");
                }
            } else if (Files.isRegularFile(root)) {
                try (ZipFile zip = new ZipFile(root.toFile())) {
                    dh = zip.stream().anyMatch(entry -> named(entry.getName(), "dh_terrain.fsh"));
                    voxy = zip.stream().anyMatch(entry -> named(entry.getName(), "voxy_opaque.glsl"));
                }
            } else return false;
            boolean wanted = dh && !voxy;
            if (wanted && !name.equals(noted)) EarthPlatformMod.LOGGER.info("Shader pack {} supports Distant Horizons and not Voxy; far terrain is drawn with its Distant Horizons program", name);
            noted = wanted ? name : null;
            return wanted;
        } catch (IOException | RuntimeException failure) {
            EarthPlatformMod.LOGGER.warn("Could not look for the shader pack's Distant Horizons program", failure);
            return false;
        }
    }

    private static boolean named(String entry, String file) {
        return entry.equals(file) || entry.endsWith("/" + file);
    }

    /** The pack code for a program set from its Distant Horizons terrain program, or null when it has none that can be used. */
    @Nullable static FarShaders.PackPatch patch(ProgramSet programs) {
        return PATCHES.computeIfAbsent(programs, FarDhPack::build).orElse(null);
    }

    private static Optional<FarShaders.PackPatch> build(ProgramSet programs) {
        try {
            ProgramSource source = programs.get(ProgramId.DhTerrain).orElse(null);
            if (source == null) return Optional.empty();
            if (source.getGeometrySource().isPresent() || source.getTessControlSource().isPresent() || source.getTessEvalSource().isPresent()) {
                EarthPlatformMod.LOGGER.warn("The shader pack's Distant Horizons program has geometry or tessellation shaders, which far terrain can't run; using plain colors");
                return Optional.empty();
            }
            Map<PatchShaderType, String> patched = TransformPatcher.patchDHTerrain(source.getName() + " (far terrain)",
                    source.getVertexSource().orElseThrow(), null, null, null, source.getFragmentSource().orElseThrow(),
                    programs.getPackDirectives().getTextureMap());
            int outputs = source.getDirectives().getDrawBuffers().length;
            FarShaders.PackPatch patch = outputs == 0 ? null
                    : FarShaders.dhPatch(patched.get(PatchShaderType.VERTEX), patched.get(PatchShaderType.FRAGMENT), outputs);
            if (patch == null) EarthPlatformMod.LOGGER.warn("The shader pack's Distant Horizons program is not in a form far terrain can use; using plain colors");
            return Optional.ofNullable(patch);
        } catch (RuntimeException failure) {
            EarthPlatformMod.LOGGER.warn("Could not read the shader pack's Distant Horizons program for far terrain; using plain colors", failure);
            return Optional.empty();
        }
    }

    /** Called when a pack's Distant Horizons program doesn't build for far terrain: it then draws as under a pack with neither. */
    static void failed(FarShaders.PackPatch patch) {
        synchronized (PATCHES) {
            PATCHES.replaceAll((programs, held) -> held.isPresent() && held.get() == patch ? Optional.empty() : held);
        }
    }

    /**
     * The pack's targets its Distant Horizons terrain program draws to, by fragment output: their
     * textures as they stand when terrain is drawn, or null when there is no such program.
     */
    @Nullable static int[] targets() {
        WorldRenderingPipeline current = Iris.getPipelineManager().getPipelineNullable();
        if (!(current instanceof IrisRenderingPipeline pipeline)) return null;
        GlFramebuffer framebuffer = TARGETS.computeIfAbsent(pipeline, key -> {
            try {
                ProgramSet programs = Iris.getCurrentPack().map(pack -> pack.getProgramSet(Iris.getCurrentDimension())).orElse(null);
                ProgramSource source = programs == null ? null : programs.get(ProgramId.DhTerrain).orElse(null);
                return source == null ? Optional.<GlFramebuffer>empty() : Optional.of(pipeline.createDHFramebuffer(source, false));
            } catch (RuntimeException failure) {
                EarthPlatformMod.LOGGER.warn("Could not find the shader pack's targets for its Distant Horizons program", failure);
                return Optional.empty();
            }
        }).orElse(null);
        FarShaders.PackPatch patch = FarPackPatch.current();
        if (framebuffer == null || patch == null || !patch.dh()) return null;
        int[] textures = new int[patch.outputs()];
        for (int i = 0; i < textures.length; i++) textures[i] = framebuffer.getColorAttachment(i);
        return textures;
    }
}
