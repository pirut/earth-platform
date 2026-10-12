package io.earthplatform.minecraft.mixin;

import io.earthplatform.minecraft.transform.SectionYTransformer;
import io.earthplatform.minecraft.transform.SparseConstructorTransformer;
import io.earthplatform.minecraft.transform.SparseDimensionTransformer;
import java.util.List;
import java.util.Set;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

/** No Minecraft imports: loading this plugin must not initialize game classes before transformation. */
public final class EarthMixinPlugin implements IMixinConfigPlugin {
    @Override public void onLoad(String mixinPackage) {}
    @Override public String getRefMapperConfig() { return null; }
    @Override public boolean shouldApplyMixin(String targetClassName, String mixinClassName) { return true; }
    @Override public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {}
    @Override public List<String> getMixins() { return null; }
    @Override public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {}
    // These pinned transformers are deliberately non-idempotent. Multiple additive mixins
    // may share a target; only the original anchor may invoke its transformer.
    @Override public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
        if ("io.earthplatform.minecraft.mixin.ChunkSerializerMixin".equals(mixinClassName)
                && "net.minecraft.world.level.chunk.storage.ChunkSerializer".equals(targetClassName)) SectionYTransformer.apply(targetClass);
        if ("io.earthplatform.minecraft.mixin.SparseChunkAccessMixin".equals(mixinClassName)
                && "net.minecraft.world.level.chunk.ChunkAccess".equals(targetClassName)) SparseConstructorTransformer.apply(targetClass);
        if ("io.earthplatform.minecraft.mixin.SparseDimensionMixin".equals(mixinClassName)
                && "net.minecraft.world.level.dimension.DimensionType".equals(targetClassName)) SparseDimensionTransformer.apply(targetClass);
    }
}
