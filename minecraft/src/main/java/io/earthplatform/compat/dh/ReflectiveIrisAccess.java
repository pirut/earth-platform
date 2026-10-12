package io.earthplatform.compat.dh;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.FileSystem;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/** Exact Iris 1.8.14-beta.1 ABI. Every class was origin-checked before this object is built. */
final class ReflectiveIrisAccess implements InstalledIrisRuntime.Access {
    private final InstalledIrisRuntime.Catalogue catalogue;
    private final Class<?> managerType, pipelineType, dhType, internalType, programType, framebufferType,
            packType, packOptionsType, optionSetType, mutableOptionsType, configType;
    private final Method packInUse, fallback, getManager, getPack, getPackName, getDirectory, getConfig,
            shadersEnabled, selectedPack, getPipeline, getReloadVersion, getDh, getInstance,
            incompatible, getPackOptions, getOptionValues, getOptionSet, getBooleanOptions,
            getStringOptions, getBoolean, getString, solidProgram, translucentProgram, solidFb, translucentFb, framebufferId, uniformLocation;
    private final Field pipelinePack, destroyed, internalPipeline, override, overrideShadow, programId, modelOffset, modelView, zipFs;

    ReflectiveIrisAccess(Map<String, Class<?>> classes, InstalledIrisRuntime.Catalogue catalogue) throws ReflectiveOperationException {
        this.catalogue = catalogue;
        Class<?> iris = type(classes, "Iris");
        managerType = type(classes, "pipeline.PipelineManager");
        pipelineType = type(classes, "pipeline.IrisRenderingPipeline");
        dhType = type(classes, "compat.dh.DHCompat");
        internalType = type(classes, "compat.dh.DHCompatInternal");
        programType = type(classes, "compat.dh.IrisLodRenderProgram");
        framebufferType = type(classes, "gl.framebuffer.GlFramebuffer");
        packType = type(classes, "shaderpack.ShaderPack");
        packOptionsType = type(classes, "shaderpack.option.ShaderPackOptions");
        optionSetType = type(classes, "shaderpack.option.OptionSet");
        mutableOptionsType = type(classes, "shaderpack.option.values.MutableOptionValues");
        configType = type(classes, "config.IrisConfig");
        Class<?> values = type(classes, "shaderpack.option.values.OptionValues");
        packInUse = iris.getMethod("isPackInUseQuick"); fallback = iris.getMethod("isFallback");
        getManager = iris.getMethod("getPipelineManager"); getPack = iris.getMethod("getCurrentPack");
        getPackName = iris.getMethod("getCurrentPackName"); getDirectory = iris.getMethod("getShaderpacksDirectory");
        getConfig = iris.getMethod("getIrisConfig");
        shadersEnabled = configType.getMethod("areShadersEnabled"); selectedPack = configType.getMethod("getShaderPackName");
        getPipeline = managerType.getMethod("getPipelineNullable");
        getReloadVersion = managerType.getMethod("getVersionCounterForSodiumShaderReload");
        getDh = pipelineType.getMethod("getDHCompat"); getInstance = dhType.getMethod("getInstance");
        incompatible = internalType.getMethod("incompatiblePack");
        getPackOptions = packType.getMethod("getShaderPackOptions");
        getOptionValues = packOptionsType.getMethod("getOptionValues"); getOptionSet = packOptionsType.getMethod("getOptionSet");
        getBooleanOptions = optionSetType.getMethod("getBooleanOptions"); getStringOptions = optionSetType.getMethod("getStringOptions");
        getBoolean = values.getMethod("getBooleanValueOrDefault", String.class);
        getString = values.getMethod("getStringValueOrDefault", String.class);
        solidProgram = internalType.getMethod("getSolidShader"); translucentProgram = internalType.getMethod("getTranslucentShader");
        solidFb = internalType.getMethod("getSolidFB"); translucentFb = internalType.getMethod("getTranslucentFB");
        framebufferId = framebufferType.getMethod("getId");
        pipelinePack = field(pipelineType, "pack"); destroyed = field(pipelineType, "destroyed");
        internalPipeline = field(internalType, "pipeline");
        override = internalType.getField("shouldOverride"); overrideShadow = internalType.getField("shouldOverrideShadow");
        programId = field(programType, "id"); modelOffset = programType.getField("modelOffsetUniform");
        uniformLocation = programType.getMethod("tryGetUniformLocation2", CharSequence.class);
        modelView = programType.getField("modelViewUniform");
        zipFs = field(iris, "zipFileSystem");
    }

    @Override public InstalledIrisRuntime.Snapshot snapshot() throws Exception {
        catalogue.requireRenderThread();
        if (!(boolean) packInUse.invoke(null) || (boolean) fallback.invoke(null))
            throw InstalledIrisRuntime.unsupported("Iris has no active supported shader pack or is in fallback mode");
        Object config = exact(configType, getConfig.invoke(null));
        if (!(boolean) shadersEnabled.invoke(config)) throw InstalledIrisRuntime.unsupported("Iris shaders are disabled");
        String name = (String) getPackName.invoke(null);
        if (!Optional.of(InstalledIrisRuntime.PHOTON_NAME).equals(selectedPack.invoke(config))
                || !InstalledIrisRuntime.PHOTON_NAME.equals(name))
            throw InstalledIrisRuntime.unsupported("Selected and loaded shaderpack must both be " + InstalledIrisRuntime.PHOTON_NAME);
        Object manager = exact(managerType, getManager.invoke(null));
        Object pipeline = exact(pipelineType, getPipeline.invoke(manager));
        Object pack = exact(packType, ((Optional<?>) getPack.invoke(null)).orElse(null));
        if (destroyed.getBoolean(pipeline) || pipelinePack.get(pipeline) != pack)
            throw InstalledIrisRuntime.unsupported("The live pipeline is destroyed or belongs to a different loaded pack");
        Object dh = exact(internalType, getInstance.invoke(exact(dhType, getDh.invoke(pipeline))));
        if (internalPipeline.get(dh) != pipeline || !override.getBoolean(dh) || overrideShadow.getBoolean(dh)
                || (boolean) incompatible.invoke(dh))
            throw InstalledIrisRuntime.unsupported("The live Iris DH override is absent, incompatible or has unsupported DH shadows");
        Object packOptions = exact(packOptionsType, getPackOptions.invoke(pack));
        Object options = exact(mutableOptionsType, getOptionValues.invoke(packOptions));
        Object optionSet = exact(optionSetType, getOptionSet.invoke(packOptions));
        Map<?, ?> stringSet = (Map<?, ?>) getStringOptions.invoke(optionSet);
        Map<?, ?> booleanSet = (Map<?, ?>) getBooleanOptions.invoke(optionSet);
        Map<String, String> strings = new HashMap<>();
        Map<String, Boolean> booleans = new HashMap<>();
        for (String option : InstalledIrisRuntime.STRINGS.keySet()) {
            requireOption(stringSet, option);
            strings.put(option, (String) getString.invoke(options, option));
        }
        for (String option : InstalledIrisRuntime.BOOLEANS.keySet()) {
            requireOption(booleanSet, option);
            booleans.put(option, (Boolean) getBoolean.invoke(options, option));
        }
        Object zip = zipFs.get(null);
        if (!(zip instanceof FileSystem filesystem) || !filesystem.isOpen())
            throw InstalledIrisRuntime.unsupported("Iris has no open external ZIP for the loaded pack");
        return new InstalledIrisRuntime.Snapshot(manager, pipeline, pack, dh, options, filesystem,
                (int) getReloadVersion.invoke(manager), name, (Path) getDirectory.invoke(null),
                catalogue.effectiveRenderDistance(), strings, booleans,
                catalogue.selectedRenderDistance(), catalogue.serverRenderDistanceLimit(), catalogue.singleplayer());
    }

    @Override public InstalledIrisRuntime.BorrowedPass pass(InstalledIrisRuntime.Snapshot snapshot, boolean shadow, boolean opaque) throws Exception {
        catalogue.requireRenderThread();
        if (shadow) throw InstalledIrisRuntime.unsupported("Pinned Photon disables DH shadow passes");
        Object dh = snapshot.dh();
        Object program = exact(programType, (opaque ? solidProgram : translucentProgram).invoke(dh));
        Object framebuffer = exact(framebufferType, (opaque ? solidFb : translucentFb).invoke(dh));
        return new InstalledIrisRuntime.BorrowedPass(program, programId.getInt(program), modelOffset.getInt(program),
                (int) framebufferId.invoke(framebuffer), false, opaque, photonProjectionLocation(program, uniformLocation), modelView.getInt(program));
    }

    /** Photon uses its explicit dhProjection matrix. Iris's generic projectionUniform
     * points to iris_ProjectionMatrix, which is unused/optimized out in this exact pack.
     * Query the exact borrowed program through Iris's read-only lookup; never fall back
     * to a CPU matrix or an unrelated generic uniform when the used matrix is absent. */
    static int photonProjectionLocation(Object program, Method uniformLocation) throws ReflectiveOperationException {
        int location = (int) uniformLocation.invoke(program, "dhProjection");
        if (location < 0) throw InstalledIrisRuntime.unsupported("Required active Photon dhProjection uniform is unavailable");
        return location;
    }

    static void requireOption(Map<?, ?> catalogue, String name) {
        // Unknown Iris boolean options otherwise silently default to true. Never call that fallback.
        if (!catalogue.containsKey(name) || catalogue.get(name) == null)
            throw InstalledIrisRuntime.unsupported("Required loaded shader option does not exist: " + name);
    }
    private static Object exact(Class<?> type, Object object) {
        if (object == null || object.getClass() != type)
            throw InstalledIrisRuntime.unsupported("Expected current exact " + type.getName() + ", got "
                    + (object == null ? "null" : object.getClass().getName()));
        return object;
    }
    private static Class<?> type(Map<String, Class<?>> classes, String name) {
        return java.util.Objects.requireNonNull(classes.get("net.irisshaders.iris." + name));
    }
    private static Field field(Class<?> type, String name) throws ReflectiveOperationException {
        Field field = type.getDeclaredField(name);
        if (!field.trySetAccessible()) throw new IllegalAccessException("Required read-only pinned Iris field is inaccessible: " + type.getName() + "." + name);
        return field;
    }
}
