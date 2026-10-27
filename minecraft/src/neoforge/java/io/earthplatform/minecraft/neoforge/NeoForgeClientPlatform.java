package io.earthplatform.minecraft.neoforge;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import io.earthplatform.minecraft.EarthPlatformMod;
import io.earthplatform.minecraft.platform.ClientPlatform;
import java.util.List;
import javax.annotation.Nullable;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.texture.SpriteContents;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.client.extensions.common.IClientFluidTypeExtensions;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
import net.neoforged.neoforge.client.model.data.ModelData;

/** {@link ClientPlatform} on NeoForge: model data, model render types and fluid type extensions. */
public final class NeoForgeClientPlatform implements ClientPlatform {
    private static ModelData data(@Nullable Object data) { return data == null ? ModelData.EMPTY : (ModelData) data; }

    @Override @Nullable public Object blockEntityModelData(BlockEntity entity) {
        ModelData model = entity.getModelData();
        return model == ModelData.EMPTY ? null : model;
    }

    @Override public Object modelData(BakedModel model, BlockAndTintGetter level, BlockPos pos, BlockState state, @Nullable Object fromEntity) {
        return model.getModelData(level, pos, state, data(fromEntity));
    }

    @Override public Iterable<RenderType> renderTypes(BakedModel model, BlockState state, RandomSource random, @Nullable Object data) {
        return model.getRenderTypes(state, random, data(data));
    }

    @Override public List<BakedQuad> quads(BakedModel model, BlockState state, @Nullable Direction side, RandomSource random, @Nullable Object data, @Nullable RenderType type) {
        return model.getQuads(state, side, random, data(data), type);
    }

    @Override public TextureAtlasSprite particleIcon(BakedModel model) { return model.getParticleIcon(ModelData.EMPTY); }

    @Override public void renderBatched(BlockRenderDispatcher blocks, BlockState state, BlockPos pos, BlockAndTintGetter level, PoseStack pose,
                                        VertexConsumer builder, RandomSource random, @Nullable Object data, RenderType type) {
        blocks.renderBatched(state, pos, level, pose, builder, true, random, data(data), type);
    }

    @Override public int fluidTint(FluidState fluid, BlockAndTintGetter level, BlockPos pos) {
        return IClientFluidTypeExtensions.of(fluid).getTintColor(fluid, level, pos);
    }

    @Override public TextureAtlasSprite fluidStill(FluidState fluid, BlockAndTintGetter level, BlockPos pos) {
        return Minecraft.getInstance().getModelManager().getAtlas(InventoryMenu.BLOCK_ATLAS)
                .getSprite(IClientFluidTypeExtensions.of(fluid).getStillTexture(fluid, level, pos));
    }

    @Override public NativeImage originalImage(SpriteContents contents) { return contents.getOriginalImage(); }

    @Override public Screen configScreen(Screen parent) {
        return new ConfigurationScreen(ModList.get().getModContainerById(EarthPlatformMod.MOD_ID).orElseThrow(), parent);
    }
}
