package io.earthplatform.minecraft.mixin;

import io.earthplatform.minecraft.survival.gametest.NativePlayerTickProbeScope;
import io.earthplatform.minecraft.survival.host.SurvivalLevelBinding;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderSet;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Only the exact native LOCATION holder-set query receives scoped generated-absence authority. */
@Mixin(StructureManager.class)
abstract class NativePlayerTickEnvironmentStructureMixin {
    @Shadow @Final private LevelAccessor level;
    @Inject(method="getStructureWithPieceAt(Lnet/minecraft/core/BlockPos;Lnet/minecraft/core/HolderSet;)Lnet/minecraft/world/level/levelgen/structure/StructureStart;",
            at=@At("HEAD"),cancellable=true,require=1)
    private void earthplatform$noGeneratedStructures(BlockPos pos,HolderSet<Structure> structures,CallbackInfoReturnable<StructureStart> result) {
        if(level instanceof Level nativeLevel && SurvivalLevelBinding.find(nativeLevel)!=null) {
            var structure=NativePlayerTickProbeScope.environmentStructure(level,pos);
            if(structure!=null)result.setReturnValue(structure);
            else SurvivalLevelBinding.rejectTerrain(nativeLevel,"getStructureWithPieceAt");
        }
    }
}
