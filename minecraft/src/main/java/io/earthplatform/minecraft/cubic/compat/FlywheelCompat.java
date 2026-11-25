package io.earthplatform.minecraft.cubic.compat;

import io.earthplatform.minecraft.EarthPlatformMod;
import io.earthplatform.minecraft.cubic.Cube;
import io.earthplatform.minecraft.cubic.CubeKey;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.ref.WeakReference;
import java.util.function.Consumer;
import javax.annotation.Nullable;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.LightLayer;
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
 * <p>So each block entity a cube stores is queued, as a chunk's is, and all of them again whenever
 * Flywheel's manager for the level is a new one. A cube whose light changed is passed on as vanilla's light
 * updates are, so the machines in it relight. Queuing one Flywheel already draws does nothing. Every call goes
 * through method handles, so nothing here loads a Flywheel class, and a Flywheel that no longer matches turns
 * this off with one log line. Render thread only.
 */
public final class FlywheelCompat {
    private static final String MANAGER = "dev.engine_room.flywheel.api.visualization.VisualizationManager";
    private static final String VISUAL_MANAGER = "dev.engine_room.flywheel.api.visualization.VisualManager";
    private static final String HELPER = "dev.engine_room.flywheel.lib.visualization.VisualizationHelper";
    /** Its light updates are not in Flywheel's API; vanilla's light updates reach it through a mixin. */
    private static final String MANAGER_IMPL = "dev.engine_room.flywheel.impl.visualization.VisualizationManagerImpl";

    @Nullable private static final Handles HANDLES = Handles.find();
    private static final Sync SYNC = new Sync();
    /**
     * Cubes whose light changed since the last level pass. Cube light lands while the terrain draws, when
     * Flywheel's frame plan may be reading its light sets on worker threads, so they are passed on only at the
     * start of the next pass, before the plan starts (see {@link #flushLight}).
     */
    private static final LightQueue PENDING_LIGHT = new LightQueue();
    private static boolean failed;

    private FlywheelCompat() {}

    private record Handles(MethodHandle get, MethodHandle blockEntities, MethodHandle queueAdd, MethodHandle skipVanillaRender,
            @Nullable MethodHandle onLightUpdate) {
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
                        lookup.findStatic(helper, "skipVanillaRender", MethodType.methodType(boolean.class, BlockEntity.class)),
                        lightUpdates(lookup, loader));
            } catch (ClassNotFoundException | LinkageError absent) {
                return null;
            } catch (ReflectiveOperationException changed) {
                EarthPlatformMod.LOGGER.warn("Flywheel is installed but its API changed; machines it draws may not show in Earth", changed);
                return null;
            }
        }

        @Nullable private static MethodHandle lightUpdates(MethodHandles.Lookup lookup, ClassLoader loader) {
            try {
                Class<?> impl = Class.forName(MANAGER_IMPL, false, loader);
                return lookup.findVirtual(impl, "onLightUpdate", MethodType.methodType(void.class, SectionPos.class, LightLayer.class));
            } catch (ReflectiveOperationException | LinkageError changed) {
                EarthPlatformMod.LOGGER.warn("Flywheel's light updates changed; machines in Earth may not relight when a light is placed", changed);
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
        SYNC.clear();
        EarthPlatformMod.LOGGER.warn("Flywheel compatibility turned off after an error; machines it draws may not show in Earth", failure);
    }

    /** A cube stored a block entity on the client: new, arrived with its cube, or replacing another in the same block. */
    public static void blockEntityStored(Level level, BlockEntity entity) {
        Object manager = manager(level);
        if (manager != null) SYNC.stored(manager, entity, adder(manager));
    }

    /** Once a frame: queues every block entity again when Flywheel's manager for the level is a new one. */
    public static void sync(Level level, Iterable<Cube> cubes) {
        Object manager = manager(level);
        SYNC.frame(manager, () -> blockEntities(cubes), manager == null ? null : adder(manager));
    }

    /** A cube's light changed or went: queued until the next {@link #flushLight}. */
    public static void lightChanged(long cubeKey) {
        if (HANDLES == null || HANDLES.onLightUpdate == null || failed || SYNC.current() == null) return;
        PENDING_LIGHT.add(cubeKey);
    }

    /**
     * At the start of a level pass, before LevelRenderer.renderLevel starts Flywheel's frame plan and after the last
     * one finished: the machines in each queued cube relight, as for a vanilla section's light update.
     */
    public static void flushLight(Level level) {
        if (PENDING_LIGHT.isEmpty()) return;
        Object manager = manager(level);
        if (manager == null || manager != SYNC.current() || HANDLES.onLightUpdate == null) { PENDING_LIGHT.clear(); return; }
        PENDING_LIGHT.flush(key -> {
            if (failed) return;
            try {
                HANDLES.onLightUpdate.invoke(manager, SectionPos.of(CubeKey.x(key), CubeKey.y(key), CubeKey.z(key)), LightLayer.BLOCK);
            } catch (Throwable failure) {
                fail(failure);
            }
        });
    }

    /** The client's cubic level is going (leaving the Earth, disconnecting): nothing of it is kept. */
    public static void levelClosed() {
        SYNC.clear();
        PENDING_LIGHT.clear();
    }

    /** Cube keys waiting for their light update: each once, however often its light changed. Render thread. */
    static final class LightQueue {
        private final LongOpenHashSet keys = new LongOpenHashSet();

        void add(long key) { keys.add(key); }
        boolean isEmpty() { return keys.isEmpty(); }
        int size() { return keys.size(); }
        void clear() { keys.clear(); }

        /** Sends each queued key and empties the queue, even if sending fails part way. */
        void flush(java.util.function.LongConsumer send) {
            try {
                for (LongIterator it = keys.iterator(); it.hasNext(); ) send.accept(it.nextLong());
            } finally {
                keys.clear();
            }
        }
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
     * When block entities go to Flywheel, apart from Flywheel itself: the manager they were last given to, and
     * everything again when that manager changes. Flywheel makes a new one each time it resets. The manager is
     * held weakly, so it, and the level it holds, never outlive Flywheel's own hold on them.
     */
    static final class Sync {
        private WeakReference<Object> current = new WeakReference<>(null);

        @Nullable Object current() { return current.get(); }

        /** Forgets the manager; the next frame with one queues everything again. */
        void clear() { current = new WeakReference<>(null); }

        <T> void stored(Object manager, T entity, Consumer<T> add) {
            if (manager == current.get()) add.accept(entity); // otherwise the next frame queues everything for it
        }

        /** {@code all} is only walked when the manager changed. */
        <T> void frame(@Nullable Object manager, java.util.function.Supplier<Iterable<T>> all, @Nullable Consumer<T> add) {
            if (manager == current.get()) return;
            current = new WeakReference<>(manager);
            if (manager == null || add == null) return;
            for (T entity : all.get()) add.accept(entity);
        }
    }
}
