package io.earthplatform.minecraft.cubic.compat;

import io.earthplatform.minecraft.EarthPlatformMod;
import io.earthplatform.minecraft.cubic.Cube;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.function.Consumer;
import javax.annotation.Nullable;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.entity.BlockEntity;

/**
 * Hands the cubic level's block entities to Flywheel (bundled with Create), which draws machines such as
 * Create's shafts and cogwheels on the GPU.
 *
 * <p>Flywheel learns of a block entity only from vanilla: when a chunk stores it
 * ({@code LevelChunk.setBlockEntity}), when a chunk section is meshed ({@code SectionCompiler}), and when a
 * block changes in view ({@code LevelRenderer.setBlockDirty}). The cubic level keeps its block entities in
 * cubes, so only the last of these ever fires: a machine placed while you watch gets drawn, and every one
 * that arrives with its cube (joining, travelling, a cube coming back into range) does not. Flywheel also
 * throws all its visuals away whenever the level renderer reloads (F3+A, a backend or resource change) and
 * expects the chunk sections to bring them back. Meanwhile Create's own renderer stays blank whenever
 * Flywheel is on, leaving those machines invisible.
 *
 * <p>So each cube's block entities are queued when the cube arrives, and all of them again whenever
 * Flywheel's manager for the level is a new one. Queuing one Flywheel already draws does nothing. Every
 * call goes through method handles, so nothing here loads a Flywheel class, and a Flywheel that no longer
 * matches turns this off with one log line.
 */
public final class FlywheelCompat {
    private static final String MANAGER = "dev.engine_room.flywheel.api.visualization.VisualizationManager";
    private static final String VISUAL_MANAGER = "dev.engine_room.flywheel.api.visualization.VisualManager";
    private static final String HELPER = "dev.engine_room.flywheel.lib.visualization.VisualizationHelper";

    @Nullable private static final Handles HANDLES = Handles.find();
    private static final Sync SYNC = new Sync();
    private static boolean failed;

    private FlywheelCompat() {}

    private record Handles(MethodHandle get, MethodHandle blockEntities, MethodHandle queueAdd, MethodHandle skipVanillaRender) {
        @Nullable static Handles find() {
            try {
                ClassLoader loader = FlywheelCompat.class.getClassLoader();
                Class<?> manager = Class.forName(MANAGER, false, loader);
                Class<?> visuals = Class.forName(VISUAL_MANAGER, false, loader);
                Class<?> helper = Class.forName(HELPER, false, loader);
                MethodHandles.Lookup lookup = MethodHandles.publicLookup();
                return new Handles(
                        lookup.findStatic(manager, "get", MethodType.methodType(manager, LevelAccessor.class)),
                        lookup.findVirtual(manager, "blockEntities", MethodType.methodType(visuals)),
                        lookup.findVirtual(visuals, "queueAdd", MethodType.methodType(void.class, Object.class)),
                        lookup.findStatic(helper, "skipVanillaRender", MethodType.methodType(boolean.class, BlockEntity.class)));
            } catch (ClassNotFoundException | LinkageError absent) {
                return null;
            } catch (ReflectiveOperationException changed) {
                EarthPlatformMod.LOGGER.warn("Flywheel is installed but its API changed; machines it draws may not show in Earth", changed);
                return null;
            }
        }
    }

    /** Flywheel's manager for the level, or null when Flywheel is absent, off or failed. */
    @Nullable private static Object manager(Level level) {
        if (HANDLES == null || failed) return null;
        try {
            return HANDLES.get.invoke((LevelAccessor) level);
        } catch (Throwable failure) {
            fail(failure);
            return null;
        }
    }

    private static Consumer<BlockEntity> adder(Object manager) {
        return entity -> {
            if (failed) return;
            try {
                Object visuals = HANDLES.blockEntities.invoke(manager);
                HANDLES.queueAdd.invoke(visuals, (Object) entity);
            } catch (Throwable failure) {
                fail(failure);
            }
        };
    }

    private static void fail(Throwable failure) {
        if (failed) return;
        failed = true;
        EarthPlatformMod.LOGGER.warn("Flywheel compatibility turned off after an error; machines it draws may not show in Earth", failure);
    }

    /** A cube's block entities have arrived on the client. */
    public static void cubeArrived(Level level, Iterable<BlockEntity> entities) {
        Object manager = manager(level);
        if (manager != null) SYNC.arrived(manager, entities, adder(manager));
    }

    /** Once a frame: queues every block entity again when Flywheel's manager for the level is a new one. */
    public static void sync(Level level, Iterable<Cube> cubes) {
        Object manager = manager(level);
        SYNC.frame(manager, () -> blockEntities(cubes), manager == null ? null : adder(manager));
    }

    /**
     * Whether Flywheel draws this block entity in place of its renderer, as vanilla asks when it meshes a
     * section; false whenever Flywheel isn't drawing in this level.
     */
    public static boolean replacesRenderer(BlockEntity entity) {
        if (HANDLES == null || failed || SYNC.current() == null) return false;
        try {
            return (boolean) HANDLES.skipVanillaRender.invoke(entity);
        } catch (Throwable failure) {
            fail(failure);
            return false;
        }
    }

    private static Iterable<BlockEntity> blockEntities(Iterable<Cube> cubes) {
        return () -> new java.util.Iterator<>() {
            private final java.util.Iterator<Cube> outer = cubes.iterator();
            private java.util.Iterator<BlockEntity> inner = java.util.Collections.emptyIterator();

            @Override public boolean hasNext() {
                while (!inner.hasNext() && outer.hasNext()) inner = outer.next().blockEntities().values().iterator();
                return inner.hasNext();
            }

            @Override public BlockEntity next() {
                if (!hasNext()) throw new java.util.NoSuchElementException();
                return inner.next();
            }
        };
    }

    /**
     * When block entities go to Flywheel, apart from Flywheel itself: the manager they were last given to,
     * and everything again when that manager changes. Flywheel makes a new one each time it resets.
     */
    static final class Sync {
        @Nullable private Object current;

        @Nullable Object current() { return current; }

        <T> void arrived(Object manager, Iterable<T> entities, Consumer<T> add) {
            if (manager != current) return; // the next frame queues everything for the new manager
            for (T entity : entities) add.accept(entity);
        }

        /** {@code all} is only walked when the manager changed. */
        <T> void frame(@Nullable Object manager, java.util.function.Supplier<Iterable<T>> all, @Nullable Consumer<T> add) {
            if (manager == current) return;
            current = manager;
            if (manager == null || add == null) return;
            for (T entity : all.get()) add.accept(entity);
        }
    }
}
