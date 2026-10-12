package io.earthplatform.minecraft.survival.interaction.host;
import net.minecraft.network.Connection;
/** Exact original Connection observer attachment; it grants no survival/Host authority. */
public interface NativeOriginalConnectionAccess {
    void earthplatform$bindOriginalOutputs(Connection exact,NativeOriginalConnectionOutputs owner);
}
