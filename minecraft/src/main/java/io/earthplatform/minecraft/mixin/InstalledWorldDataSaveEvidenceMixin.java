package io.earthplatform.minecraft.mixin;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.serialization.*;
import io.earthplatform.minecraft.survival.persistence.NativeInstalledOrdinarySaveHooks;
import net.minecraft.core.RegistryAccess;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.storage.PrimaryLevelData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
/** Observe native partial/error results before resultOrPartial / ifError can log and continue. */
@Mixin(PrimaryLevelData.class)
abstract class InstalledWorldDataSaveEvidenceMixin {
 @WrapOperation(method="setTagData(Lnet/minecraft/core/RegistryAccess;Lnet/minecraft/nbt/CompoundTag;Lnet/minecraft/nbt/CompoundTag;)V",
   at=@At(value="INVOKE",target="Lnet/minecraft/world/level/levelgen/WorldGenSettings;encode(Lcom/mojang/serialization/DynamicOps;Lnet/minecraft/world/level/levelgen/WorldOptions;Lnet/minecraft/core/RegistryAccess;)Lcom/mojang/serialization/DataResult;"),require=1,allow=1)
 private DataResult<?> earthplatform$worldGeneration(DynamicOps<?> ops,WorldOptions options,RegistryAccess registries,Operation<DataResult<?>> original){
  DataResult<?> result=original.call(ops,options,registries);NativeInstalledOrdinarySaveHooks.levelCodec((PrimaryLevelData)(Object)this,result);return result;
 }
 @WrapOperation(method="setTagData(Lnet/minecraft/core/RegistryAccess;Lnet/minecraft/nbt/CompoundTag;Lnet/minecraft/nbt/CompoundTag;)V",
   at=@At(value="INVOKE",target="Lcom/mojang/serialization/Codec;encodeStart(Lcom/mojang/serialization/DynamicOps;Ljava/lang/Object;)Lcom/mojang/serialization/DataResult;"),require=2,allow=2)
 private DataResult<?> earthplatform$levelCodec(Codec<?> codec,DynamicOps<?> ops,Object value,Operation<DataResult<?>> original){
  DataResult<?> result=original.call(codec,ops,value);NativeInstalledOrdinarySaveHooks.levelCodec((PrimaryLevelData)(Object)this,result);return result;
 }
}
