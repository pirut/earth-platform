package io.earthplatform.minecraft.survival.persistence;

import java.io.IOException;
import java.nio.file.Path;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.storage.PlayerDataStorage;

/** Mixin-only observations. This facade cannot open a scope, mint an authority, or return success. */
public final class NativeOrdinarySaveHooks {
    private NativeOrdinarySaveHooks() {}
    public static void enter(PlayerDataStorage storage,Player player) { NativeOrdinarySaveObserver.enter(storage,player); }
    public static CompoundTag serialize(PlayerDataStorage storage,Player receiver,CompoundTag input,Player invocation) {
        return NativeOrdinarySaveObserver.serialize(storage,receiver,input,invocation);
    }
    public static void write(PlayerDataStorage storage,Player player,CompoundTag tag,Path temporary) throws IOException {
        NativeOrdinarySaveObserver.write(storage,player,tag,temporary);
    }
    public static void replace(PlayerDataStorage storage,Player player,Path current,Path temporary,Path backup) {
        NativeOrdinarySaveObserver.replace(storage,player,current,temporary,backup);
    }
    public static void caught(PlayerDataStorage storage,Player player) { NativeOrdinarySaveObserver.caught(storage,player); }
    public static void exit(PlayerDataStorage storage,Player player) { NativeOrdinarySaveObserver.exit(storage,player); }
}
