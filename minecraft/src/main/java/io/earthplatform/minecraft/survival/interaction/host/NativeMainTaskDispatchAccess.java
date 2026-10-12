package io.earthplatform.minecraft.survival.interaction.host;

import net.minecraft.util.thread.BlockableEventLoop;

/** Attachment only; the port exposes no Runnable and no invocation capability. */
public interface NativeMainTaskDispatchAccess {
    void earthplatform$bindTaskDispatch(BlockableEventLoop<?> exact, NativeMainTaskDispatchOwner owner);
}
