package io.earthplatform.minecraft.cubic.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import io.earthplatform.minecraft.cubic.CubeWorld;
import io.earthplatform.minecraft.cubic.CubicLevel;
import io.earthplatform.minecraft.cubic.ServerCubeWorld;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.network.chat.Component;
import net.minecraft.server.commands.PlaceCommand;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * /place in a cubic level. Vanilla checks that each chunk is loaded at its Y 0, which in the
 * Earth is rock far under the ground and never loaded, so every /place said "That position is not
 * loaded". Here the place counts as loaded when its column has a cube loaded where the placement
 * reaches, and the rest of the cubes it reaches load at once: players load far fewer cubes than
 * vanilla loads chunks, and a block set in a cube not loaded is lost (vanilla's setBlock loads a
 * missing chunk).
 * /place structure lays its start out against the real ground and places it over the whole
 * height of the world rather than the 16-block carrier's.
 */
@Mixin(PlaceCommand.class)
abstract class CubicPlaceCommandMixin {
    @Shadow @Final private static SimpleCommandExceptionType ERROR_STRUCTURE_FAILED;

    /** How far a feature (a tree, a huge mushroom) reaches below and above where it is placed. */
    @Unique private static final int EARTHPLATFORM$FEATURE_BELOW = 16, EARTHPLATFORM$FEATURE_ABOVE = 47;

    @Inject(method = "placeStructure", at = @At("HEAD"), cancellable = true)
    private static void earthplatform$cubicStructure(CommandSourceStack source, Holder.Reference<Structure> structure, BlockPos pos,
                                                     CallbackInfoReturnable<Integer> result) throws CommandSyntaxException {
        ServerLevel level = source.getLevel();
        if (!(CubicLevel.of(level) instanceof ServerCubeWorld cubes)) return;
        ChunkGenerator view = cubes.generator().structureView();
        StructureStart start = cubes.generator().placedStructure(structure.value(), pos.getX() >> 4, pos.getZ() >> 4);
        if (view == null || start == null) throw ERROR_STRUCTURE_FAILED.create();
        BoundingBox box = start.getBoundingBox();
        ChunkPos from = new ChunkPos(box.minX() >> 4, box.minZ() >> 4), to = new ChunkPos(box.maxX() >> 4, box.maxZ() >> 4);
        earthplatform$load(cubes, pos, from, to, box.minY(), box.maxY());
        ChunkPos.rangeClosed(from, to).forEach(chunk -> start.placeInChunk(level, level.structureManager(), view, level.getRandom(),
                new BoundingBox(chunk.getMinBlockX(), CubeWorld.MIN_Y, chunk.getMinBlockZ(), chunk.getMaxBlockX(), CubeWorld.MAX_Y, chunk.getMaxBlockZ()), chunk));
        String id = structure.key().location().toString();
        source.sendSuccess(() -> Component.translatable("commands.place.structure.success", id, pos.getX(), pos.getY(), pos.getZ()), true);
        result.setReturnValue(1);
    }

    @WrapOperation(method = "placeFeature", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/commands/PlaceCommand;checkLoaded(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/level/ChunkPos;Lnet/minecraft/world/level/ChunkPos;)V"))
    private static void earthplatform$cubicFeature(ServerLevel level, ChunkPos from, ChunkPos to, Operation<Void> original,
                                                   @Local(argsOnly = true) BlockPos pos) throws CommandSyntaxException {
        if (CubicLevel.of(level) instanceof ServerCubeWorld cubes) {
            earthplatform$load(cubes, pos, from, to, pos.getY() - EARTHPLATFORM$FEATURE_BELOW, pos.getY() + EARTHPLATFORM$FEATURE_ABOVE);
        } else original.call(level, from, to);
    }

    @WrapOperation(method = "placeJigsaw", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/commands/PlaceCommand;checkLoaded(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/level/ChunkPos;Lnet/minecraft/world/level/ChunkPos;)V"))
    private static void earthplatform$cubicJigsaw(ServerLevel level, ChunkPos from, ChunkPos to, Operation<Void> original,
                                                  @Local(argsOnly = true) BlockPos pos) throws CommandSyntaxException {
        // Only the start's own cube here, as vanilla checks only its chunk; each piece loads its cubes as it is placed.
        if (CubicLevel.of(level) instanceof ServerCubeWorld cubes) earthplatform$load(cubes, pos, from, to, pos.getY(), pos.getY());
        else original.call(level, from, to);
    }

    @WrapOperation(method = "placeTemplate", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/commands/PlaceCommand;checkLoaded(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/level/ChunkPos;Lnet/minecraft/world/level/ChunkPos;)V"))
    private static void earthplatform$cubicTemplate(ServerLevel level, ChunkPos from, ChunkPos to, Operation<Void> original,
                                                    @Local(argsOnly = true) BlockPos pos,
                                                    @Local net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate template) throws CommandSyntaxException {
        if (CubicLevel.of(level) instanceof ServerCubeWorld cubes) earthplatform$load(cubes, pos, from, to, pos.getY(), pos.getY() + template.getSize().getY());
        else original.call(level, from, to);
    }

    /**
     * Vanilla's loaded check for a placement from a block over chunk columns between two heights:
     * the block's own cube, or its column somewhere in that span (a mineshaft deep under a player),
     * must be loaded, else "That position is not loaded". Every cube of the span then loads now.
     */
    @Unique private static void earthplatform$load(ServerCubeWorld cubes, BlockPos pos, ChunkPos from, ChunkPos to, int minY, int maxY) throws CommandSyntaxException {
        int bottom = Math.max(minY, CubeWorld.MIN_Y) >> 4, top = Math.min(maxY, CubeWorld.MAX_Y) >> 4;
        if (bottom > top) throw BlockPosArgument.ERROR_OUT_OF_WORLD.create();
        boolean loaded = CubeWorld.inRange(pos.getY()) && cubes.isLoaded(pos);
        for (int cy = bottom; cy <= top && !loaded; cy++) loaded = cubes.cubeAtBlock(pos.getX(), cy << 4, pos.getZ()) != null;
        if (!loaded) throw BlockPosArgument.ERROR_NOT_LOADED.create();
        for (int cz = from.z; cz <= to.z; cz++) for (int cx = from.x; cx <= to.x; cx++)
            for (int cy = bottom; cy <= top; cy++) cubes.loadNow(cx, cy, cz);
    }
}
