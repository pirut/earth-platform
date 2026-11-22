package io.earthplatform.minecraft.cubic.compat;

import io.earthplatform.minecraft.EarthPlatformMod;
import io.earthplatform.minecraft.cubic.CubeWorld;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.ArrayList;
import java.util.Queue;
import javax.annotation.Nullable;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.world.level.Level;

/**
 * Sparse Y-indexed storage and reflection bridge for Wakes Reforged.
 *
 * <p>{@code WakeHandler} indexes its {@code QuadTree[]} and {@code QueueSet[]} arrays by
 * {@code y - minBuildHeight} over {@code [minBuildHeight, maxBuildHeight)}. Against the cubic
 * level's 0..16 carrier, {@code getArrayIndex(y)} returns {@code -1} at sea level (Y 62) and every
 * real water elevation on Earth. Keeping only active Y layers in a sparse map supports any Y in
 * {@link CubeWorld#inRange(int)} without allocating or iterating 32,768 empty slots per tick.
 */
public final class WakesCompat {
    private static final String QUAD_TREE = "com.leclowndu93150.wakes.simulation.QuadTree";
    private static final String QUEUE_SET = "com.leclowndu93150.wakes.simulation.QueueSet";
    private static final String WAKE_NODE = "com.leclowndu93150.wakes.simulation.WakeNode";
    private static final String WAKE_HANDLER = "com.leclowndu93150.wakes.simulation.WakeHandler";
    private static final String SPLASH_PLANE = "com.leclowndu93150.wakes.particle.custom.SplashPlaneParticle";

    private static volatile boolean loggedFailure;

    private WakesCompat() {}

    /** Operations needed to manage sparse per-Y wake trees without compile-time mod dependencies. */
    public interface Ops<T, N> {
        T newTree(int y);
        Queue<N> newQueue();
        int nodeY(N node);
        boolean validPos(N node, Level world);
        void tickAdvance(T tree, Object handler);
        boolean tickSolve(T tree, Object handler);
        void insertNode(T tree, N node);
        void query(T tree, Frustum frustum, ArrayList<?> out, Class<?> type);
        void recolor(T tree);
        void prune(T tree);
        void cleanupArea(T tree, int minX, int minZ, int maxX, int maxZ);
        boolean isSplashPlaneClass(Class<?> type);
    }

    /** Sparse per-Y layer table backing one cubic {@code WakeHandler}. */
    public static final class SparseLayers<T, N> {
        private final Ops<T, N> ops;
        private final Int2ObjectOpenHashMap<T> trees = new Int2ObjectOpenHashMap<>();
        private final Int2ObjectOpenHashMap<Queue<N>> pending = new Int2ObjectOpenHashMap<>();

        public SparseLayers(Ops<T, N> ops) {
            this.ops = ops;
        }

        public void insert(N node, Level world, boolean resolutionResetScheduled) {
            if (resolutionResetScheduled) return;
            int y = ops.nodeY(node);
            if (!CubeWorld.inRange(y)) return;
            trees.computeIfAbsent(y, ops::newTree);
            if (ops.validPos(node, world)) {
                pending.computeIfAbsent(y, ignored -> ops.newQueue()).add(node);
            }
        }

        public void tick(Object handler, boolean resolutionResetScheduled) {
            if (resolutionResetScheduled) {
                for (Queue<N> queue : pending.values()) queue.clear();
                return;
            }
            if (trees.isEmpty()) return;
            // Snapshot keys so flood-fill inserts cannot mutate the map while iterating it.
            int[] ys = trees.keySet().toIntArray();
            for (int y : ys) {
                T tree = trees.get(y);
                if (tree == null) continue;
                ops.tickAdvance(tree, handler);
                boolean alive = ops.tickSolve(tree, handler);
                Queue<N> queue = pending.get(y);
                boolean inserted = false;
                if (queue != null) {
                    N next;
                    while ((next = queue.poll()) != null) {
                        ops.insertNode(tree, next);
                        inserted = true;
                    }
                }
                if (!alive && !inserted && (queue == null || queue.isEmpty())) {
                    trees.remove(y);
                    pending.remove(y);
                }
            }
        }

        public void queryVisible(Frustum frustum, ArrayList<?> out, Class<?> type) {
            if (ops.isSplashPlaneClass(type) || trees.isEmpty()) return;
            for (T tree : trees.values()) {
                ops.query(tree, frustum, out, type);
            }
        }

        public void recolorWakes() {
            for (T tree : trees.values()) ops.recolor(tree);
        }

        public void reset() {
            for (T tree : trees.values()) ops.prune(tree);
            trees.clear();
            for (Queue<N> queue : pending.values()) queue.clear();
            pending.clear();
        }

        public void cleanupChunk(int minX, int minZ, int maxX, int maxZ) {
            for (T tree : trees.values()) ops.cleanupArea(tree, minX, minZ, maxX, maxZ);
        }

        public int activeLevels() { return trees.size(); }
        public boolean hasLevel(int y) { return trees.containsKey(y); }
    }

    @Nullable
    public static SparseLayers<Object, Object> newRuntimeLayers() {
        RuntimeOps ops = RuntimeOps.INSTANCE;
        return ops == null ? null : new SparseLayers<>(ops);
    }

    /** Lazily resolved MethodHandles for Wakes' public simulation classes. */
    private static final class RuntimeOps implements Ops<Object, Object> {
        @Nullable static final RuntimeOps INSTANCE = resolve();

        private final MethodHandle quadTreeCtor;
        private final MethodHandle queueSetCtor;
        private final MethodHandle nodeYGetter;
        private final MethodHandle validPos;
        private final MethodHandle tickAdvance;
        private final MethodHandle tickSolve;
        private final MethodHandle treeInsert;
        private final MethodHandle treeQuery;
        private final MethodHandle recolorWakes;
        private final MethodHandle prune;
        private final MethodHandle cleanupArea;
        private final Class<?> splashPlaneClass;

        private RuntimeOps(MethodHandle quadTreeCtor, MethodHandle queueSetCtor, MethodHandle nodeYGetter,
                MethodHandle validPos, MethodHandle tickAdvance, MethodHandle tickSolve,
                MethodHandle treeInsert, MethodHandle treeQuery, MethodHandle recolorWakes,
                MethodHandle prune, MethodHandle cleanupArea, Class<?> splashPlaneClass) {
            this.quadTreeCtor = quadTreeCtor;
            this.queueSetCtor = queueSetCtor;
            this.nodeYGetter = nodeYGetter;
            this.validPos = validPos;
            this.tickAdvance = tickAdvance;
            this.tickSolve = tickSolve;
            this.treeInsert = treeInsert;
            this.treeQuery = treeQuery;
            this.recolorWakes = recolorWakes;
            this.prune = prune;
            this.cleanupArea = cleanupArea;
            this.splashPlaneClass = splashPlaneClass;
        }

        @Nullable
        private static RuntimeOps resolve() {
            try {
                ClassLoader cl = WakesCompat.class.getClassLoader();
                Class<?> quadTree = Class.forName(QUAD_TREE, false, cl);
                Class<?> queueSet = Class.forName(QUEUE_SET, false, cl);
                Class<?> wakeNode = Class.forName(WAKE_NODE, false, cl);
                Class<?> wakeHandler = Class.forName(WAKE_HANDLER, false, cl);
                Class<?> splashPlane = Class.forName(SPLASH_PLANE, false, cl);
                MethodHandles.Lookup lookup = MethodHandles.publicLookup();
                return new RuntimeOps(
                        lookup.findConstructor(quadTree, MethodType.methodType(void.class, float.class))
                                .asType(MethodType.methodType(Object.class, float.class)),
                        lookup.findConstructor(queueSet, MethodType.methodType(void.class))
                                .asType(MethodType.methodType(Queue.class)),
                        lookup.findGetter(wakeNode, "y", int.class)
                                .asType(MethodType.methodType(int.class, Object.class)),
                        lookup.findVirtual(wakeNode, "validPos", MethodType.methodType(boolean.class, Level.class))
                                .asType(MethodType.methodType(boolean.class, Object.class, Level.class)),
                        lookup.findVirtual(quadTree, "tickAdvance", MethodType.methodType(boolean.class, wakeHandler))
                                .asType(MethodType.methodType(boolean.class, Object.class, Object.class)),
                        lookup.findVirtual(quadTree, "tickSolve", MethodType.methodType(boolean.class, wakeHandler))
                                .asType(MethodType.methodType(boolean.class, Object.class, Object.class)),
                        lookup.findVirtual(quadTree, "insert", MethodType.methodType(boolean.class, wakeNode))
                                .asType(MethodType.methodType(boolean.class, Object.class, Object.class)),
                        lookup.findVirtual(quadTree, "query",
                                MethodType.methodType(void.class, Frustum.class, ArrayList.class, Class.class))
                                .asType(MethodType.methodType(void.class, Object.class, Frustum.class, ArrayList.class, Class.class)),
                        lookup.findVirtual(quadTree, "recolorWakes", MethodType.methodType(void.class))
                                .asType(MethodType.methodType(void.class, Object.class)),
                        lookup.findVirtual(quadTree, "prune", MethodType.methodType(void.class))
                                .asType(MethodType.methodType(void.class, Object.class)),
                        lookup.findVirtual(quadTree, "cleanupArea",
                                MethodType.methodType(void.class, int.class, int.class, int.class, int.class))
                                .asType(MethodType.methodType(void.class, Object.class, int.class, int.class, int.class, int.class)),
                        splashPlane);
            } catch (Throwable t) {
                logOnce("Wakes reflection binding failed", t);
                return null;
            }
        }

        private static void logOnce(String msg, Throwable t) {
            if (!loggedFailure) {
                loggedFailure = true;
                EarthPlatformMod.LOGGER.warn("Wakes compat disabled: {}", msg, t);
            }
        }

        @Override public Object newTree(int y) {
            try { return quadTreeCtor.invokeExact((float) y); }
            catch (Throwable t) { throw new IllegalStateException("QuadTree.<init>", t); }
        }

        @SuppressWarnings("unchecked")
        @Override public Queue<Object> newQueue() {
            try { return (Queue<Object>) queueSetCtor.invokeExact(); }
            catch (Throwable t) { throw new IllegalStateException("QueueSet.<init>", t); }
        }

        @Override public int nodeY(Object node) {
            try { return (int) nodeYGetter.invokeExact(node); }
            catch (Throwable t) { throw new IllegalStateException("WakeNode.y", t); }
        }

        @Override public boolean validPos(Object node, Level world) {
            try { return (boolean) validPos.invokeExact(node, world); }
            catch (Throwable t) { throw new IllegalStateException("WakeNode.validPos", t); }
        }

        @Override public void tickAdvance(Object tree, Object handler) {
            try { boolean ignored = (boolean) tickAdvance.invokeExact(tree, handler); }
            catch (Throwable t) { throw new IllegalStateException("QuadTree.tickAdvance", t); }
        }

        @Override public boolean tickSolve(Object tree, Object handler) {
            try { return (boolean) tickSolve.invokeExact(tree, handler); }
            catch (Throwable t) { throw new IllegalStateException("QuadTree.tickSolve", t); }
        }

        @Override public void insertNode(Object tree, Object node) {
            try { boolean ignored = (boolean) treeInsert.invokeExact(tree, node); }
            catch (Throwable t) { throw new IllegalStateException("QuadTree.insert", t); }
        }

        @Override public void query(Object tree, Frustum frustum, ArrayList<?> out, Class<?> type) {
            try { treeQuery.invokeExact(tree, frustum, out, type); }
            catch (Throwable t) { throw new IllegalStateException("QuadTree.query", t); }
        }

        @Override public void recolor(Object tree) {
            try { recolorWakes.invokeExact(tree); }
            catch (Throwable t) { throw new IllegalStateException("QuadTree.recolorWakes", t); }
        }

        @Override public void prune(Object tree) {
            try { prune.invokeExact(tree); }
            catch (Throwable t) { throw new IllegalStateException("QuadTree.prune", t); }
        }

        @Override public void cleanupArea(Object tree, int minX, int minZ, int maxX, int maxZ) {
            try { cleanupArea.invokeExact(tree, minX, minZ, maxX, maxZ); }
            catch (Throwable t) { throw new IllegalStateException("QuadTree.cleanupArea", t); }
        }

        @Override public boolean isSplashPlaneClass(Class<?> type) {
            return splashPlaneClass.equals(type);
        }
    }
}
