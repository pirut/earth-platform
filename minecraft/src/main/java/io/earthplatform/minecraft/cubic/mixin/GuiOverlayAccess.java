package io.earthplatform.minecraft.cubic.mixin;

import net.minecraft.client.gui.Gui;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The action bar's message and how long it has left, so a compass line waits for another message's turn to end. */
@Mixin(Gui.class)
public interface GuiOverlayAccess {
    @Accessor("overlayMessageString") Component earthplatform$overlay();
    @Accessor("overlayMessageTime") int earthplatform$overlayTime();
}
