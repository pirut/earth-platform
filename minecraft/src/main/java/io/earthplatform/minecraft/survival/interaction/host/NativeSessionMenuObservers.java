package io.earthplatform.minecraft.survival.interaction.host;
import net.minecraft.server.level.ServerPlayer;
/** Narrow reuse of the existing exact initialized menu observer graph. No menu hydration/click authority. */
public final class NativeSessionMenuObservers {
 private final NativeInventoryMenuBroadcastAdmission admission;
 private NativeSessionMenuObservers(ServerPlayer player){admission=NativeInventoryMenuBroadcastAdmission.capture(player);}
 public static NativeSessionMenuObservers capture(ServerPlayer player){return new NativeSessionMenuObservers(player);}
 public void require(){admission.require();}
}
