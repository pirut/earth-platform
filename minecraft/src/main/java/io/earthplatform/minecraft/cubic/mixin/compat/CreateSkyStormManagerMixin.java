package io.earthplatform.minecraft.cubic.mixin.compat;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import io.earthplatform.minecraft.cubic.Cubic;
import java.util.List;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Create Sky tracks storms per dimension; use Earth's manager for Earth players and commands. */
@Pseudo
@Mixin(targets = "com.createskys.weather.StormManager", remap = false)
abstract class CreateSkyStormManagerMixin {
    @Inject(method = "hasWeather", at = @At("HEAD"), cancellable = true, require = 0)
    private static void earthplatform$earthHasWeather(Level level, CallbackInfoReturnable<Boolean> result) {
        if (Cubic.isCubic(level.dimension())) result.setReturnValue(true);
    }

    @WrapOperation(method = "weatherCommand", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/MinecraftServer;overworld()Lnet/minecraft/server/level/ServerLevel;"), require = 0)
    private static ServerLevel earthplatform$commandLevel(MinecraftServer server, Operation<ServerLevel> original,
                                                         @Local(argsOnly = true) CommandSourceStack source) {
        ServerLevel level = source.getLevel();
        return Cubic.isCubic(level.dimension()) ? level : original.call(server);
    }

    @WrapOperation(method = "sendTo", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/MinecraftServer;overworld()Lnet/minecraft/server/level/ServerLevel;"), require = 0)
    private static ServerLevel earthplatform$syncLevel(MinecraftServer server, Operation<ServerLevel> original,
                                                      @Local(argsOnly = true) ServerPlayer player) {
        ServerLevel level = player.serverLevel();
        return Cubic.isCubic(level.dimension()) ? level : original.call(server);
    }

    @ModifyExpressionValue(method = "tick", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/players/PlayerList;getPlayers()Ljava/util/List;"), require = 0)
    private List<ServerPlayer> earthplatform$syncDimension(List<ServerPlayer> players,
                                                           @Local(argsOnly = true) ServerLevel level) {
        // Earth exists in normal saves too; never broadcast another dimension's storms to its players.
        return level.players();
    }
}
