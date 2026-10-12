package io.earthplatform.minecraft.mixin;

import io.earthplatform.minecraft.survival.host.SurvivalCarrierTransport;
import java.util.Collection;
import java.util.Map;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.storage.ChunkSerializer;
import net.minecraft.world.level.levelgen.Heightmap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ChunkSerializer.class)
abstract class SurvivalCarrierSerializerMixin {
    @Inject(method="write(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/level/chunk/ChunkAccess;)Lnet/minecraft/nbt/CompoundTag;", at=@At("HEAD"))
    private static void earthplatform$survivalValidateWrite(ServerLevel level, ChunkAccess chunk,
            CallbackInfoReturnable<CompoundTag> result) {
        SurvivalCarrierTransport.validateWrite(level, chunk);
    }
    @Redirect(method="write(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/level/chunk/ChunkAccess;)Lnet/minecraft/nbt/CompoundTag;",
            at=@At(value="INVOKE",target="Lnet/minecraft/world/level/chunk/ChunkAccess;getSections()[Lnet/minecraft/world/level/chunk/LevelChunkSection;"))
    private static LevelChunkSection[] earthplatform$survivalSections(ChunkAccess receiver, ServerLevel level, ChunkAccess chunk) {
        return SurvivalCarrierTransport.serializationSections(level, receiver, chunk);
    }
    @Redirect(method="write(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/level/chunk/ChunkAccess;)Lnet/minecraft/nbt/CompoundTag;",
            at=@At(value="INVOKE",target="Lnet/minecraft/world/level/chunk/ChunkAccess;getHeightmaps()Ljava/util/Collection;"))
    private static Collection<Map.Entry<Heightmap.Types,Heightmap>> earthplatform$survivalMaps(ChunkAccess receiver, ServerLevel level, ChunkAccess chunk) {
        return SurvivalCarrierTransport.serializationHeightmaps(level, receiver, chunk);
    }
    @Redirect(method="write(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/level/chunk/ChunkAccess;)Lnet/minecraft/nbt/CompoundTag;",
            at=@At(value="INVOKE",target="Lnet/minecraft/world/level/chunk/ChunkAccess;getTicksForSerialization()Lnet/minecraft/world/level/chunk/ChunkAccess$TicksToSave;"))
    private static ChunkAccess.TicksToSave earthplatform$survivalTicks(ChunkAccess receiver, ServerLevel level, ChunkAccess chunk) {
        return SurvivalCarrierTransport.serializationTicks(level, receiver, chunk);
    }
}
