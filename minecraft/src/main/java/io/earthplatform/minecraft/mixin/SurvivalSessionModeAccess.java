package io.earthplatform.minecraft.mixin;
import net.minecraft.server.level.ServerPlayerGameMode;
import net.minecraft.world.level.GameType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;
@Mixin(ServerPlayerGameMode.class)
public interface SurvivalSessionModeAccess {
 @Invoker("setGameModeForPlayer") void earthplatform$restoreExactModes(GameType current,GameType previous);
}
