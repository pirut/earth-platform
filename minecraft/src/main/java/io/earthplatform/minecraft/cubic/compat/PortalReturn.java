package io.earthplatform.minecraft.cubic.compat;

import io.earthplatform.minecraft.cubic.Cubic;
import io.earthplatform.minecraft.cubic.CubicLevel;
import io.earthplatform.minecraft.cubic.ServerCubeWorld;
import io.earthplatform.minecraft.platform.Platform;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.portal.DimensionTransition;
import net.minecraft.world.phys.Vec3;

/**
 * Round trips through other mods' dimension portals that start in the Earth world. Those mods send
 * players home to the Overworld and search for or build a return portal, which can't work in the cubic
 * world (its carrier is only 16 blocks tall). Instead the Earth portal a player used is remembered on
 * the way in, and the way out lands beside it.
 */
public final class PortalReturn {
    public static final String AETHER_RETURN = "earthplatform:aether_return";
    private static final ResourceLocation AETHER = ResourceLocation.fromNamespaceAndPath("aether", "the_aether");

    private PortalReturn() {}

    /**
     * Falling out of the bottom of the Aether drops you into the Overworld. Someone who came from the
     * Earth world lands back beside their Earth portal instead, rather than in another dimension.
     * Called as an entity is about to change dimension; true cancels that trip.
     */
    public static boolean fallFromAether(Entity entity, ResourceKey<Level> dimension) {
        if (!(entity.level() instanceof ServerLevel level) || dimension != Level.OVERWORLD
                || !level.dimension().location().equals(AETHER) || !Platform.INSTANCE.persistentData(entity).contains(AETHER_RETURN)) return false;
        level.getServer().execute(() -> {
            if (entity.isRemoved() || entity.level() != level) return;
            DimensionTransition back = back(AETHER_RETURN, level, entity);
            if (back != null) entity.changeDimension(back);
        });
        return true;
    }

    /** Call when an entity uses a portal in {@code level}; remembers it if that is the Earth world. */
    public static boolean rememberIfEarth(String key, ServerLevel level, Entity entity, BlockPos portal) {
        if (!Cubic.isCubic(level.dimension())) return false;
        Platform.INSTANCE.persistentData(entity).put(key, NbtUtils.writeBlockPos(portal));
        return true;
    }

    /** The trip back to the remembered Earth portal, or null when the entity didn't come from Earth. */
    @Nullable
    public static DimensionTransition back(String key, ServerLevel level, Entity entity) {
        CompoundTag data = Platform.INSTANCE.persistentData(entity);
        if (!data.contains(key)) return null;
        ServerLevel earth = level.getServer().getLevel(Cubic.LEVEL);
        BlockPos portal = NbtUtils.readBlockPos(data, key).orElse(null);
        data.remove(key);
        if (earth == null || portal == null || !(CubicLevel.of(earth) instanceof ServerCubeWorld cubes)) return null;
        cubes.loadAround(portal);
        return new DimensionTransition(earth, Vec3.atBottomCenterOf(beside(earth, portal)), Vec3.ZERO,
                entity.getYRot(), entity.getXRot(), DimensionTransition.PLAY_PORTAL_SOUND);
    }

    /** A free spot next to the portal, so the player doesn't land back in it. */
    static BlockPos beside(ServerLevel earth, BlockPos portal) {
        for (int r = 1; r <= 4; r++) {
            for (int dx = -r; dx <= r; dx++) for (int dz = -r; dz <= r; dz++) {
                if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;
                for (int dy = 1; dy >= -1; dy--) {
                    BlockPos feet = portal.offset(dx, dy, dz);
                    if (earth.getBlockState(feet.below()).isSolid() && earth.getBlockState(feet).getCollisionShape(earth, feet).isEmpty()
                            && earth.getBlockState(feet.above()).getCollisionShape(earth, feet.above()).isEmpty()
                            && !earth.getBlockState(feet).is(earth.getBlockState(portal).getBlock())) {
                        return feet;
                    }
                }
            }
        }
        return portal.above();
    }
}
