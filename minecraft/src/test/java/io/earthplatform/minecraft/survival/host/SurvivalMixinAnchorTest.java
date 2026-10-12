package io.earthplatform.minecraft.survival.host;

import io.earthplatform.minecraft.mixin.EarthMixinPlugin;
import java.nio.file.Path;
import java.util.zip.ZipFile;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.tree.ClassNode;
import static org.junit.jupiter.api.Assertions.*;

/** Exercises the real plugin against original pinned bytecode, including both application orders. */
class SurvivalMixinAnchorTest {
    @Test void serializerPatchRunsOnlyForItsOriginalAnchor() throws Exception {
        check("net.minecraft.world.level.chunk.storage.ChunkSerializer", "ChunkSerializerMixin", "SurvivalCarrierSerializerMixin");
    }
    @Test void constructorPatchRunsOnlyForItsOriginalAnchor() throws Exception {
        check("net.minecraft.world.level.chunk.ChunkAccess", "SparseChunkAccessMixin", "SurvivalChunkAccessMixin");
    }
    @Test void dimensionPatchRunsOnlyForItsOriginalAnchor() throws Exception {
        check("net.minecraft.world.level.dimension.DimensionType", "SparseDimensionMixin", "UnrelatedDimensionMixin");
    }
    private static void check(String target, String anchor, String additional) throws Exception {
        ClassNode node = new ClassNode();
        try (var zip = new ZipFile(Path.of(System.getProperty("earthplatform.test.minecraftJar")).toFile());
             var input = zip.getInputStream(zip.getEntry(target.replace('.', '/') + ".class"))) {
            new ClassReader(input).accept(node, 0);
        }
        var plugin = new EarthMixinPlugin(); byte[] original = bytes(node);
        plugin.postApply(target, node, "io.earthplatform.minecraft.mixin." + additional, null);
        assertArrayEquals(original, bytes(node), "Non-anchor before original must be inert");
        plugin.postApply(target, node, "io.earthplatform.minecraft.mixin." + anchor, null);
        byte[] transformed = bytes(node); assertFalse(java.util.Arrays.equals(original, transformed));
        plugin.postApply(target, node, "io.earthplatform.minecraft.mixin." + additional, null);
        assertArrayEquals(transformed, bytes(node), "Non-anchor after original must not double-patch");
        plugin.postApply("wrong.target", node, "io.earthplatform.minecraft.mixin." + anchor, null);
        assertArrayEquals(transformed, bytes(node));
        assertThrows(IllegalStateException.class, () -> plugin.postApply(target, node,
                "io.earthplatform.minecraft.mixin." + anchor, null), "Duplicate original anchor still fails closed");
    }
    private static byte[] bytes(ClassNode node) { ClassWriter writer = new ClassWriter(0); node.accept(writer); return writer.toByteArray(); }
}
