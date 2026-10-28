package io.earthplatform.minecraft.cubic.compat;

import io.earthplatform.minecraft.EarthPlatformMod;
import io.earthplatform.minecraft.cubic.Cube;
import io.earthplatform.minecraft.cubic.CubeWorld;
import io.earthplatform.minecraft.cubic.CubicLevel;
import io.earthplatform.minecraft.cubic.ServerCubeWorld;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunkSection;

/**
 * WorldEdit in the Earth world. Its NeoForge build reads and writes blocks straight through the
 * LevelChunk under each column, which in the cubic level is the empty 16-block carrier, and takes
 * the world's height from that carrier, so every edit outside Y 0 to 15 failed or crashed. The
 * WorldEdit mixins send those reads and writes to the cubes here instead. A cube no player is near
 * is loaded first, so a paste, a fill or an undo reaches it rather than reading it as air; once it
 * unloads its save refreshes the far terrain over it.
 */
public final class WorldEditCompat {
    private static volatile boolean reflected;
    @Nullable private static Field sideEffects;
    @Nullable private static Method shouldApply;
    @Nullable private static Object update;

    private WorldEditCompat() {}

    /** The cube store for a block of a cubic level, with its cube loaded; null where vanilla chunks hold the blocks. */
    @Nullable public static CubeWorld cubes(Level level, BlockPos pos) {
        CubeWorld cubes = CubicLevel.at(level, pos.getX(), pos.getZ());
        if (cubes instanceof ServerCubeWorld server && CubeWorld.inRange(pos.getY()) && !server.isLoaded(pos) && server.isOwnerThread()) {
            server.loadNow(pos.getX() >> 4, pos.getY() >> 4, pos.getZ() >> 4);
        }
        return cubes;
    }

    /** Writes a block as WorldEdit's own chunk hook would: {@code access} is its native access, whose side effects say whether to run onPlace. */
    @Nullable public static BlockState write(CubeWorld cubes, BlockPos pos, BlockState state, Object access) {
        if (!CubeWorld.inRange(pos.getY())) return null;
        return cubes.setBlockState(pos, state, false, placeUpdates(access));
    }

    /** The section holding a block's biomes, for //setbiome; the cube is saved and sent to players again. */
    @Nullable public static LevelChunkSection biomeSection(CubeWorld cubes, BlockPos pos) {
        Cube cube = cubes.cubeAtBlock(pos.getX(), pos.getY(), pos.getZ());
        if (cube == null) return null;
        cubes.rewritten(cube);
        return cube.section;
    }

    /** WorldEdit's SideEffect.UPDATE for the edit in progress; on unless WorldEdit turned it off. */
    private static boolean placeUpdates(Object access) {
        if (!reflected) reflect(access.getClass());
        if (sideEffects == null || shouldApply == null || update == null) return true;
        try {
            Object set = sideEffects.get(access);
            return set == null || (Boolean) shouldApply.invoke(set, update);
        } catch (ReflectiveOperationException | RuntimeException failure) {
            return true;
        }
    }

    private static synchronized void reflect(Class<?> accessClass) {
        if (reflected) return;
        try {
            Field field = accessClass.getDeclaredField("sideEffectSet");
            field.setAccessible(true);
            Class<?> effect = Class.forName("com.sk89q.worldedit.util.SideEffect", false, accessClass.getClassLoader());
            Object value = null;
            for (Object constant : effect.getEnumConstants()) if (((Enum<?>) constant).name().equals("UPDATE")) value = constant;
            Method method = field.getType().getMethod("shouldApply", effect);
            sideEffects = field;
            shouldApply = method;
            update = value;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            EarthPlatformMod.LOGGER.warn("WorldEdit side effects not found; its edits in the Earth world will always run block updates", failure);
        }
        reflected = true;
    }
}
