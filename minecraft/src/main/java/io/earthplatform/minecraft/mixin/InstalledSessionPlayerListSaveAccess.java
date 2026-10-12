package io.earthplatform.minecraft.mixin;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;
/** Calls the original virtual save, preserving IntegratedPlayerList's override. */
@Mixin(PlayerList.class)
public interface InstalledSessionPlayerListSaveAccess {
 @Invoker("save") void earthplatform$savePlayer(ServerPlayer player);
}
