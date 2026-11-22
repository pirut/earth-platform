package io.earthplatform.minecraft.cubic.mixin.compat;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import io.earthplatform.minecraft.cubic.compat.BloodyBitsCompat;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;

/**
 * Bloody Bits converts absolute block coordinates to float32 inside {@code renderBlockDecal} and
 * subtracts {@code (float) cameraPos} in {@code getCornerVector}. At Earth coordinates (~12.5M)
 * float32 precision is 1.0 block, causing blood decals to slide with the player and snap back.
 * Rebasing the block position, extents, center and camera by the integer camera block during
 * {@code renderBlockDecal} keeps all float32 math near zero without touching world block lookups.
 */
@Pseudo
@Mixin(targets = "com.cravencraft.bloodybits.client.particle.spatter.BloodSpatterParticle", remap = false)
abstract class BloodyBitsSpatterMixin {
    @Shadow private Vec3 cameraPos;
    @Shadow private Vec3 worldExtentMin;
    @Shadow private Vec3 worldExtentMax;
    @Shadow private double centerX;
    @Shadow private double centerY;
    @Shadow private double centerZ;

    @WrapMethod(method = "renderBlockDecal(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/phys/shapes/VoxelShape;)Z", require = 0)
    private boolean earthplatform$rebaseBlockDecal(BlockPos pos, VoxelShape shape, Operation<Boolean> original) {
        Vec3 camera = this.cameraPos;
        if (camera == null || this.worldExtentMin == null || this.worldExtentMax == null || !BloodyBitsCompat.shouldRebase()) {
            return original.call(pos, shape);
        }
        int ox = BloodyBitsCompat.originX(camera);
        int oy = BloodyBitsCompat.originY(camera);
        int oz = BloodyBitsCompat.originZ(camera);
        if (BloodyBitsCompat.isZeroOrigin(ox, oy, oz)) {
            return original.call(pos, shape);
        }
        Vec3 savedMin = this.worldExtentMin;
        Vec3 savedMax = this.worldExtentMax;
        double savedCenterX = this.centerX;
        double savedCenterY = this.centerY;
        double savedCenterZ = this.centerZ;
        this.cameraPos = camera.subtract(ox, oy, oz);
        this.worldExtentMin = savedMin.subtract(ox, oy, oz);
        this.worldExtentMax = savedMax.subtract(ox, oy, oz);
        this.centerX = savedCenterX - ox;
        this.centerY = savedCenterY - oy;
        this.centerZ = savedCenterZ - oz;
        try {
            return original.call(pos.offset(-ox, -oy, -oz), shape);
        } finally {
            this.cameraPos = camera;
            this.worldExtentMin = savedMin;
            this.worldExtentMax = savedMax;
            this.centerX = savedCenterX;
            this.centerY = savedCenterY;
            this.centerZ = savedCenterZ;
        }
    }
}
