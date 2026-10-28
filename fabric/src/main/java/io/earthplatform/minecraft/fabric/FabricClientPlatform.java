package io.earthplatform.minecraft.fabric;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import io.earthplatform.minecraft.EarthPlatformMod;
import io.earthplatform.minecraft.fabric.mixin.SpriteContentsAccess;
import io.earthplatform.minecraft.platform.ClientPlatform;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;
import net.fabricmc.fabric.api.client.render.fluid.v1.FluidRenderHandler;
import net.fabricmc.fabric.api.client.render.fluid.v1.FluidRenderHandlerRegistry;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.ItemBlockRenderTypes;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.texture.SpriteContents;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;

/**
 * {@link ClientPlatform} on Fabric: vanilla block models (one render layer per block, no model
 * data) and Fabric API's fluid render handlers, which Sodium and modded fluids use too.
 */
public final class FabricClientPlatform implements ClientPlatform {
    /** One unchanging list per chunk layer, so meshing a block allocates nothing. */
    private final Map<RenderType, List<RenderType>> layers = new IdentityHashMap<>();

    public FabricClientPlatform() {
        for (RenderType type : RenderType.chunkBufferLayers()) layers.put(type, List.of(type));
    }

    @Override @Nullable public Object blockEntityModelData(BlockEntity entity) { return null; }

    @Override @Nullable public Object modelData(BakedModel model, BlockAndTintGetter level, BlockPos pos, BlockState state, @Nullable Object fromEntity) { return null; }

    @Override public Iterable<RenderType> renderTypes(BakedModel model, BlockState state, RandomSource random, @Nullable Object data) {
        return layers.get(ItemBlockRenderTypes.getChunkRenderType(state));
    }

    @Override public List<BakedQuad> quads(BakedModel model, BlockState state, @Nullable Direction side, RandomSource random, @Nullable Object data, @Nullable RenderType type) {
        return model.getQuads(state, side, random);
    }

    @Override public TextureAtlasSprite particleIcon(BakedModel model) { return model.getParticleIcon(); }

    @Override public void renderBatched(BlockRenderDispatcher blocks, BlockState state, BlockPos pos, BlockAndTintGetter level, PoseStack pose,
                                        VertexConsumer builder, RandomSource random, @Nullable Object data, RenderType type) {
        blocks.renderBatched(state, pos, level, pose, builder, true, random);
    }

    @Override public int fluidTint(FluidState fluid, BlockAndTintGetter level, BlockPos pos) {
        FluidRenderHandler handler = FluidRenderHandlerRegistry.INSTANCE.get(fluid.getType());
        return handler == null ? -1 : handler.getFluidColor(level, pos, fluid);
    }

    @Override public TextureAtlasSprite fluidStill(FluidState fluid, BlockAndTintGetter level, BlockPos pos) {
        FluidRenderHandler handler = FluidRenderHandlerRegistry.INSTANCE.get(fluid.getType());
        return handler.getFluidSprites(level, pos, fluid)[0];
    }

    @Override public NativeImage originalImage(SpriteContents contents) { return ((SpriteContentsAccess) contents).earthplatform$originalImage(); }

    @Override public Screen configScreen(Screen parent) { return new ConfigurationScreen(EarthPlatformMod.MOD_ID, parent); }
}
