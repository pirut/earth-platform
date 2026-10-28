package io.earthplatform.minecraft.fabric.axiom.mixin;

import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.Heightmap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(Heightmap.class)
public interface HeightmapAccess {
    @Accessor("chunk") ChunkAccess earthplatform$chunk();
}
