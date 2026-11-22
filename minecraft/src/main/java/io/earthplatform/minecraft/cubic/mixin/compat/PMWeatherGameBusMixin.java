package io.earthplatform.minecraft.cubic.mixin.compat;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Share;
import com.llamalad7.mixinextras.sugar.ref.LocalRef;
import io.earthplatform.minecraft.cubic.CubeWorld;
import io.earthplatform.minecraft.cubic.Cubic;
import io.earthplatform.minecraft.cubic.CubicLevel;
import io.earthplatform.minecraft.cubic.compat.EarthWeatherProfile;
import io.earthplatform.minecraft.cubic.compat.PMWeatherCompat;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Enables PM Weather's server tick, chunk moisture sync, cubic spawn/loaded checks, and regional
 * cyclone/storm spawn rates in Earth's cubic dimension.
 */
@Pseudo
@Mixin(targets = "dev.protomanly.pmweather.event.GameBusEvents", remap = false)
abstract class PMWeatherGameBusMixin {
    @Unique
    private static long earthplatform$cubicLastTick = 0L;

    @ModifyExpressionValue(method = "onLevelTick", require = 0,
            at = @At(value = "INVOKE", target = "Lnet/neoforged/neoforge/event/tick/LevelTickEvent$Post;getLevel()Lnet/minecraft/world/level/Level;"))
    private static Level earthplatform$captureTickLevel(Level level, @Share("level") LocalRef<Level> shared) {
        shared.set(level);
        return level;
    }

    @ModifyExpressionValue(method = "onLevelTick", require = 0,
            at = @At(value = "FIELD", target = "Lnet/minecraft/world/level/Level;OVERWORLD:Lnet/minecraft/resources/ResourceKey;"))
    private static ResourceKey<Level> earthplatform$allowEarthTick(ResourceKey<Level> original,
                                                                   @Share("level") LocalRef<Level> shared) {
        Level level = shared.get();
        return level != null && Cubic.isCubic(level.dimension()) ? Cubic.LEVEL : original;
    }

    @WrapOperation(method = "onLevelTick", require = 0,
            at = @At(value = "FIELD", target = "Ldev/protomanly/pmweather/event/GameBusEvents;lastTick:J", opcode = Opcodes.GETSTATIC))
    private static long earthplatform$getCubicLastTick(Operation<Long> original,
                                                       @Share("level") LocalRef<Level> shared) {
        Level level = shared.get();
        return level != null && Cubic.isCubic(level.dimension()) ? earthplatform$cubicLastTick : original.call();
    }

    @WrapOperation(method = "onLevelTick", require = 0,
            at = @At(value = "FIELD", target = "Ldev/protomanly/pmweather/event/GameBusEvents;lastTick:J", opcode = Opcodes.PUTSTATIC))
    private static void earthplatform$setCubicLastTick(long value, Operation<Void> original,
                                                       @Share("level") LocalRef<Level> shared) {
        Level level = shared.get();
        if (level != null && Cubic.isCubic(level.dimension())) earthplatform$cubicLastTick = value;
        else original.call(value);
    }

    @WrapOperation(method = "onLevelTick", require = 0,
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;isLoaded(Lnet/minecraft/core/BlockPos;)Z"))
    private static boolean earthplatform$cubicLoaded(Level level, BlockPos pos, Operation<Boolean> original) {
        return CubicLevel.at(level, pos.getX(), pos.getZ()) != null
                ? PMWeatherCompat.isColumnSurfaceLoaded(level, pos.getX(), pos.getZ())
                : original.call(level, pos);
    }

    @WrapOperation(method = "onLevelTick", require = 0,
            at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerPlayer;getX()D"))
    private static double earthplatform$captureSpawnPlayer(ServerPlayer player, Operation<Double> original,
                                                           @Share("player") LocalRef<ServerPlayer> shared) {
        shared.set(player);
        return original.call(player);
    }

    @WrapOperation(method = "onLevelTick", require = 0,
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;getMaxBuildHeight()I", ordinal = 0))
    private static int earthplatform$spawnSurfaceHeight(Level level, Operation<Integer> original,
                                                        @Share("player") LocalRef<ServerPlayer> shared) {
        if (CubicLevel.of(level) == null) return original.call(level);
        ServerPlayer player = shared.get();
        return player != null
                ? PMWeatherCompat.surfaceY(level, player.getBlockX(), player.getBlockZ()) + 1
                : original.call(level);
    }

    @WrapOperation(method = "onLevelTick", require = 0,
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;getMaxBuildHeight()I", ordinal = 1))
    private static int earthplatform$steeringWindHeight(Level level, Operation<Integer> original) {
        return CubicLevel.of(level) != null ? CubeWorld.MAX_Y + 1 : original.call(level);
    }

    @ModifyExpressionValue(method = "onLevelTick", require = 0,
            at = @At(value = "FIELD", target = "Ldev/protomanly/pmweather/config/ServerConfig;chanceInOneCyclone:I"))
    private static int earthplatform$regionalCycloneChance(int original,
                                                           @Share("level") LocalRef<Level> sharedLevel,
                                                           @Share("player") LocalRef<ServerPlayer> sharedPlayer) {
        Level level = sharedLevel.get();
        ServerPlayer player = sharedPlayer.get();
        if (level == null || player == null) return original;
        EarthWeatherProfile profile = PMWeatherCompat.profile(level, player.getX(), player.getY(), player.getZ());
        return profile != null ? PMWeatherCompat.cycloneChanceInOne(original, profile) : original;
    }

    @ModifyExpressionValue(method = "onLevelTick", require = 0,
            at = @At(value = "FIELD", target = "Ldev/protomanly/pmweather/config/ServerConfig;stormSpawnChancePerMinute:D"))
    private static double earthplatform$regionalStormChance(double original,
                                                            @Share("level") LocalRef<Level> sharedLevel,
                                                            @Share("player") LocalRef<ServerPlayer> sharedPlayer) {
        Level level = sharedLevel.get();
        ServerPlayer player = sharedPlayer.get();
        if (level == null || player == null) return original;
        EarthWeatherProfile profile = PMWeatherCompat.profile(level, player.getX(), player.getY(), player.getZ());
        return profile != null ? PMWeatherCompat.stormSpawnChancePerMinute(original, profile) : original;
    }

    @ModifyExpressionValue(method = "onChunkSent", require = 0,
            at = @At(value = "INVOKE", target = "Lnet/neoforged/neoforge/event/level/ChunkWatchEvent$Sent;getLevel()Lnet/minecraft/server/level/ServerLevel;"))
    private static ServerLevel earthplatform$captureChunkLevel(ServerLevel level,
                                                               @Share("chunkLevel") LocalRef<ServerLevel> shared) {
        shared.set(level);
        return level;
    }

    @ModifyExpressionValue(method = "onChunkSent", require = 0,
            at = @At(value = "FIELD", target = "Lnet/minecraft/server/level/ServerLevel;OVERWORLD:Lnet/minecraft/resources/ResourceKey;"))
    private static ResourceKey<Level> earthplatform$allowEarthChunkSync(ResourceKey<Level> original,
                                                                        @Share("chunkLevel") LocalRef<ServerLevel> shared) {
        ServerLevel level = shared.get();
        return level != null && Cubic.isCubic(level.dimension()) ? Cubic.LEVEL : original;
    }
}
