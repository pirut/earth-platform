package io.earthplatform.minecraft.mixin;

/** Read-only identity observation; grants no execution, drain or ownership authority.
 * Admit exact TickTask.class, this transform marker and original constructor lineage first.
 * No raw task reference escapes through this port.
 */
public interface NativeTickTaskPayloadAccess {
    boolean earthplatform$matchesPayload(Object expected);
    int earthplatform$nativeTick();
}
