package io.earthplatform.minecraft.cubic.mixin;

import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Every vanilla packet position goes through these two methods as BlockPos.asLong, whose 12-bit
 * Y wraps outside -2048..2047. Positions in that range keep the vanilla bytes exactly; any other
 * Y writes the reserved Y -2048 followed by the true Y as an int.
 */
@Mixin(FriendlyByteBuf.class)
abstract class CubicBlockPosWireMixin {
    private static final int ESCAPE = -2048;

    @Inject(method = "writeBlockPos(Lio/netty/buffer/ByteBuf;Lnet/minecraft/core/BlockPos;)V", at = @At("HEAD"), cancellable = true)
    private static void earthplatform$writeTall(ByteBuf buffer, BlockPos pos, CallbackInfo info) {
        int y = pos.getY();
        if (y > ESCAPE && y <= 2047) return;
        buffer.writeLong(BlockPos.asLong(pos.getX(), ESCAPE, pos.getZ()));
        buffer.writeInt(y);
        info.cancel();
    }

    @Inject(method = "readBlockPos(Lio/netty/buffer/ByteBuf;)Lnet/minecraft/core/BlockPos;", at = @At("HEAD"), cancellable = true)
    private static void earthplatform$readTall(ByteBuf buffer, CallbackInfoReturnable<BlockPos> result) {
        long packed = buffer.readLong();
        int x = BlockPos.getX(packed), y = BlockPos.getY(packed), z = BlockPos.getZ(packed);
        result.setReturnValue(new BlockPos(x, y == ESCAPE ? buffer.readInt() : y, z));
    }
}
