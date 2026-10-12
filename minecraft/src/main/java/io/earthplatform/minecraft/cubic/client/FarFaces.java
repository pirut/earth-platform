package io.earthplatform.minecraft.cubic.client;

import com.mojang.blaze3d.platform.NativeImage;
import io.earthplatform.minecraft.cubic.CubeWorld;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import javax.annotation.Nullable;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.ColorResolver;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.level.material.FluidState;
import net.neoforged.neoforge.client.extensions.common.IClientFluidTypeExtensions;
import net.neoforged.neoforge.client.model.data.ModelData;

/**
 * How a block face looks on a far voxel: the sprite its model shows on that side, that sprite's
 * average colour, and the biome tint the model asks for. Safe on mesher threads; results are cached.
 */
final class FarFaces {
    /** Sprite bounds in the block atlas, the sprite's mean colour (alpha over all pixels), and the tint index (-1 none, -2 fluid). */
    record Face(float u0, float v0, float u1, float v1, int rgb, int tint) {}
    private record TintKey(BlockState state, Holder<Biome> biome, int index) {}

    private final ConcurrentHashMap<BlockState, Face[]> faces = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<TintKey, Integer> tints = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<TextureAtlasSprite, Integer> averages = new ConcurrentHashMap<>();

    Face face(BlockState state, Direction direction) {
        return faces.computeIfAbsent(state, this::bake)[direction.get3DDataValue()];
    }

    /** The RGB multiplier the block's colour handler gives this face in a biome; white when untinted. */
    int tint(BlockState state, Holder<Biome> biome, Face face) {
        if (face.tint == -1) return 0xFFFFFF;
        return tints.computeIfAbsent(new TintKey(state, biome, face.tint), key -> {
            BiomeView view = new BiomeView(biome);
            int color = key.index == -2
                    ? IClientFluidTypeExtensions.of(state.getFluidState()).getTintColor(state.getFluidState(), view, BlockPos.ZERO)
                    : Minecraft.getInstance().getBlockColors().getColor(state, view, BlockPos.ZERO, key.index);
            return color == -1 ? 0xFFFFFF : color & 0xFFFFFF;
        });
    }

    private Face[] bake(BlockState state) {
        Face[] baked = new Face[6];
        FluidState fluid = state.getFluidState();
        if (!fluid.isEmpty() && state.getRenderShape() != RenderShape.MODEL) {
            ResourceLocation still = IClientFluidTypeExtensions.of(fluid).getStillTexture(fluid, new BiomeView(null), BlockPos.ZERO);
            Face face = face(sprite(still), -2);
            java.util.Arrays.fill(baked, face);
            return baked;
        }
        BakedModel model = Minecraft.getInstance().getBlockRenderer().getBlockModel(state);
        RandomSource random = RandomSource.create();
        for (Direction direction : Direction.values()) {
            random.setSeed(42L);
            List<BakedQuad> quads = model.getQuads(state, direction, random, ModelData.EMPTY, null);
            if (quads.isEmpty()) {
                random.setSeed(42L);
                quads = model.getQuads(state, null, random, ModelData.EMPTY, null).stream().filter(q -> q.getDirection() == direction).toList();
            }
            BakedQuad quad = quads.isEmpty() ? null : quads.get(0);
            baked[direction.get3DDataValue()] = quad == null
                    ? face(model.getParticleIcon(ModelData.EMPTY), -1)
                    : face(quad.getSprite(), quad.isTinted() ? quad.getTintIndex() : -1);
        }
        return baked;
    }

    private Face face(TextureAtlasSprite sprite, int tint) {
        return new Face(sprite.getU0(), sprite.getV0(), sprite.getU1(), sprite.getV1(), average(sprite), tint);
    }

    static TextureAtlasSprite sprite(ResourceLocation name) {
        return Minecraft.getInstance().getModelManager().getAtlas(InventoryMenu.BLOCK_ATLAS).getSprite(name);
    }

    /** Mean ARGB of a sprite's first frame: colour over its mostly opaque pixels, alpha over all of them. */
    private int average(TextureAtlasSprite sprite) {
        return averages.computeIfAbsent(sprite, unused -> {
            try {
                NativeImage image = sprite.contents().getOriginalImage();
                int width = sprite.contents().width(), height = sprite.contents().height();
                long r = 0, g = 0, b = 0, n = 0, a = 0;
                for (int y = 0; y < height; y++) for (int x = 0; x < width; x++) {
                    int abgr = image.getPixelRGBA(x, y);
                    a += abgr >>> 24;
                    if ((abgr >>> 24) < 128) continue;
                    r += abgr & 255; g += abgr >> 8 & 255; b += abgr >> 16 & 255; n++;
                }
                int alpha = (int) (a / Math.max(1, (long) width * height)) << 24;
                return n == 0 ? alpha | 0xFFFFFF : alpha | (int) (r / n) << 16 | (int) (g / n) << 8 | (int) (b / n);
            } catch (RuntimeException unreadable) {
                return 0xFF808080;
            }
        });
    }

    /** A one-biome world for block colour handlers: every position tints as that biome. */
    private record BiomeView(@Nullable Holder<Biome> biome) implements BlockAndTintGetter {
        @Override public int getBlockTint(BlockPos pos, ColorResolver resolver) {
            return biome == null ? 0xFFFFFF : resolver.getColor(biome.value(), pos.getX(), pos.getZ());
        }
        @Override public float getShade(Direction direction, boolean shade) { return 1.0F; }
        @Override public LevelLightEngine getLightEngine() { return Minecraft.getInstance().level.getLightEngine(); }
        @Override @Nullable public BlockEntity getBlockEntity(BlockPos pos) { return null; }
        @Override public BlockState getBlockState(BlockPos pos) { return Blocks.AIR.defaultBlockState(); }
        @Override public FluidState getFluidState(BlockPos pos) { return Blocks.AIR.defaultBlockState().getFluidState(); }
        @Override public int getHeight() { return CubeWorld.MAX_Y - CubeWorld.MIN_Y + 1; }
        @Override public int getMinBuildHeight() { return CubeWorld.MIN_Y; }
    }
}
