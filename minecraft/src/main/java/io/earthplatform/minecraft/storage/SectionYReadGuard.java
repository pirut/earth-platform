package io.earthplatform.minecraft.storage;

import com.mojang.logging.LogUtils;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.storage.LevelResource;

/** An owned-format rejection poisons only that world's chunk against writes until process restart. */
public final class SectionYReadGuard {
    private record Key(String world, int x, int z) {}
    private static final Set<Key> REJECTED = ConcurrentHashMap.newKeySet();
    private SectionYReadGuard() {}

    public static String identity(ServerLevel level) {
        return level.getServer().getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize()
                + "|" + level.dimension().location();
    }
    public static void beforeRead(ServerLevel level, ChunkPos pos, CompoundTag chunk) {
        beforeRead(identity(level), pos.x, pos.z, chunk);
    }
    public static void beforeWrite(ServerLevel level, ChunkPos pos) {
        if (Boolean.getBoolean(SectionYCodec.OPT_IN)) WorldFormatGuard.requireVerifiedWideWorld(level.getServer().getWorldPath(LevelResource.ROOT));
        beforeWrite(identity(level), pos.x, pos.z);
    }

    public static void beforeRead(String world, int x, int z, CompoundTag chunk) {
        Key key = new Key(world, x, z);
        if (REJECTED.contains(key)) throw new SectionYIntegrityError("Chunk " + x + "," + z + " was already rejected in this session");
        var sections = chunk.getList("sections", Tag.TAG_COMPOUND);
        try {
            for (int index = 0; index < sections.size(); index++) {
                CompoundTag section = sections.getCompound(index);
                // Ordinary unmarked vanilla data is left to vanilla unless wide mode is explicitly chosen.
                if (section.contains(SectionYCodec.FORMAT_TAG) || Boolean.getBoolean(SectionYCodec.OPT_IN)) SectionYCodec.read(section, "Y");
            }
        } catch (SectionYIntegrityError failure) {
            REJECTED.add(key);
            LogUtils.getLogger().error("Earth section-Y integrity rejection for world {} chunk {},{}; saved chunk writes blocked until restart: {}", world, x, z, failure.getMessage());
            throw failure;
        }
    }
    public static void beforeWrite(String world, int x, int z) {
        if (REJECTED.contains(new Key(world, x, z))) {
            throw new SectionYIntegrityError("Refusing to overwrite rejected chunk " + x + "," + z + " in " + world);
        }
    }
}
