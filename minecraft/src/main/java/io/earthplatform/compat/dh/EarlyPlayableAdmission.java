package io.earthplatform.compat.dh;

import io.earthplatform.compat.PlayableProfile;
import io.earthplatform.compat.neoforge.TempFileShimActivation;
import net.neoforged.fml.loading.LoadingModList;

/** Exact metadata and artifact checks before optional transforms, without loading their targets. */
public final class EarlyPlayableAdmission {
    private static Boolean admitted;
    private static String explanation = "Playable profile has not been selected";
    private EarlyPlayableAdmission() {}
    public static synchronized boolean enabled() {
        var choice = PlayableProfile.selection();
        if (!choice.selected()) { explanation = choice.reason(); return false; }
        if (admitted != null) return admitted;
        try {
            LoadingModList mods = LoadingModList.get();
            if (mods == null) throw new IllegalStateException("Installed mod metadata is unavailable");
            requireVersion(mods, "neoforge", TempFileShimActivation.NEOFORGE_VERSION);
            requireVersion(mods, "distanthorizons", TempFileShimActivation.DH_VERSION);
            requireVersion(mods, "iris", InstalledIrisRuntime.IRIS_VERSION);
            requireVersion(mods, "sodium", InstalledIrisRuntime.SODIUM_VERSION);
            if (mods.getModFileById("oculus") != null || mods.getModFileById("embeddium") != null)
                throw new IllegalStateException("This profile requires the pinned Iris and Sodium combination");
            TempFileShimActivation.verifyInstalledDh(mods.getModFileById("distanthorizons").getFile().getFilePath());
            InstalledIrisRuntime.verifyBytes(mods.getModFileById("iris").getFile().getFilePath(),
                    2_540_723L, InstalledIrisRuntime.IRIS_SHA256, "Iris");
            var sodium = mods.getModFileById("sodium").getFile();
            var module = sodium.getSecureJar().moduleDataProvider();
            InstalledSodiumOrigin.verify(new InstalledIrisRuntime.InstalledMod("sodium", InstalledIrisRuntime.SODIUM_VERSION,
                    sodium.getFilePath(), EarlyPlayableAdmission.class.getClassLoader(), module.name(), module.uri()));
            admitted = true;
            explanation = "Pinned installed artifacts admitted; live pack and render-thread entry checks remain required";
        } catch (Exception | LinkageError failure) {
            admitted = false;
            String detail = failure.getMessage();
            explanation = "Playable profile blocked: " + (detail == null ? failure.getClass().getSimpleName() : detail.substring(0, Math.min(detail.length(), 400)));
        }
        return admitted;
    }
    public static synchronized String explanation() { enabled(); return explanation; }
    private static void requireVersion(LoadingModList mods, String id, String expected) {
        var matches = mods.getMods().stream().filter(value -> id.equals(value.getModId())).toList();
        if (matches.size() != 1 || !expected.equals(matches.getFirst().getVersion().toString()) || mods.getModFileById(id) == null)
            throw new IllegalStateException("Install exactly " + id + " " + expected + " separately, then restart");
    }
}
