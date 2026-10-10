package io.earthplatform.minecraft.mixin;

import net.minecraft.world.level.chunk.storage.ChunkSerializer;
import io.earthplatform.minecraft.storage.SectionYReadGuard;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.storage.RegionStorageInfo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Marker for the strictly checked post-apply serializer repair. See SectionYTransformer. */
@Mixin(ChunkSerializer.class)
public abstract class ChunkSerializerMixin {
    @Inject(method = "read", at = @At("HEAD"))
    private static void earthplatform$checkBeforeRead(ServerLevel level, PoiManager poi, RegionStorageInfo storage, ChunkPos pos, CompoundTag tag, CallbackInfoReturnable<ProtoChunk> callback) {
        SectionYReadGuard.beforeRead(level, pos, tag);
    }
    @Inject(method = "write", at = @At("HEAD"))
    private static void earthplatform$protectRejectedSave(ServerLevel level, ChunkAccess chunk, CallbackInfoReturnable<CompoundTag> callback) {
        SectionYReadGuard.beforeWrite(level, chunk.getPos());
    }
}
