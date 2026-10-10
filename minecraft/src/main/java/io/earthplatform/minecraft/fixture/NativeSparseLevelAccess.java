package io.earthplatform.minecraft.fixture;

/** Injected field access: null on all ordinary levels, populated only by a scoped native fixture constructor. */
public interface NativeSparseLevelAccess {
    NativeSparseBinding earthplatform$nativeSparseBinding();
}
