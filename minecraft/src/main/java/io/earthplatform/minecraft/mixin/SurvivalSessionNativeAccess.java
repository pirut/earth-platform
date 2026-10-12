package io.earthplatform.minecraft.mixin;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
@Mixin(ServerGamePacketListenerImpl.class)
public interface SurvivalSessionNativeAccess {
 @Accessor("awaitingPositionFromClient") Vec3 earthplatform$awaitingPosition();
 @Accessor("awaitingTeleport") int earthplatform$awaitingTeleport();
 @Accessor("firstGoodX") double earthplatform$firstGoodX();
 @Accessor("firstGoodY") double earthplatform$firstGoodY();
 @Accessor("firstGoodZ") double earthplatform$firstGoodZ();
 @Accessor("lastGoodX") double earthplatform$lastGoodX();
 @Accessor("lastGoodY") double earthplatform$lastGoodY();
 @Accessor("lastGoodZ") double earthplatform$lastGoodZ();
}
