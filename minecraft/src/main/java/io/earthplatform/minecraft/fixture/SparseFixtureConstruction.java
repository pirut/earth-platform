package io.earthplatform.minecraft.fixture;

import io.earthplatform.minecraft.residency.SparseFacadeException;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.dimension.DimensionType;

/** One-shot constructor capability, consumed only by exact native ServerLevel/ClientLevel classes. */
public final class SparseFixtureConstruction {
    public static final ResourceKey<Level> DIMENSION = ResourceKey.create(Registries.DIMENSION,
            ResourceLocation.fromNamespaceAndPath("earthplatform", "native_sparse_fixture"));
    public enum Endpoint {
        SERVER("net.minecraft.server.level.ServerLevel"), CLIENT("net.minecraft.client.multiplayer.ClientLevel");
        final String className;
        Endpoint(String className) { this.className = className; }
    }
    private static final ThreadLocal<Scope> CURRENT = new ThreadLocal<>();
    private SparseFixtureConstruction() {}
    public static Scope open(Endpoint endpoint, DimensionType ordinaryTemplate) {
        if (CURRENT.get() != null) throw new SparseFacadeException("nested native fixture scope rejected");
        Scope scope = new Scope(endpoint, SparseFixtureDimensions.create(ordinaryTemplate)); CURRENT.set(scope); return scope;
    }
    public static NativeSparseBinding claim(Level level) {
        Scope scope = CURRENT.get();
        if (scope == null) return null;
        if (scope.claimed != null || !scope.endpoint.className.equals(level.getClass().getName())
                || !DIMENSION.equals(level.dimension()) || level.dimensionType() != scope.type) {
            throw new SparseFacadeException("native constructor capability does not match exact endpoint, dimension and type");
        }
        scope.claimed = level;
        return new NativeSparseBinding(level);
    }
    public static final class Scope implements AutoCloseable {
        private final Thread owner = Thread.currentThread();
        private final Endpoint endpoint;
        private final DimensionType type;
        private Level claimed;
        private boolean verified;
        private Scope(Endpoint endpoint, DimensionType type) { this.endpoint = java.util.Objects.requireNonNull(endpoint); this.type = type; }
        public DimensionType type() { return type; }
        public void verify(Level constructed) {
            check();
            if (claimed != constructed || NativeSparseBinding.find(constructed) == null) throw new SparseFacadeException("native level was not bound during construction");
            verified = true;
        }
        private void check() { if (owner != Thread.currentThread() || CURRENT.get() != this) throw new SparseFacadeException("invalid native constructor scope owner"); }
        @Override public void close() {
            check(); CURRENT.remove();
            if (!verified && claimed != null) {
                NativeSparseBinding binding = NativeSparseBinding.find(claimed);
                if (binding != null) binding.beginDisposableClose();
            }
        }
    }
}
