package io.earthplatform.minecraft.mixin;

import io.earthplatform.minecraft.survival.host.SurvivalCarrierTransport;
import java.util.Collection;
import java.util.Map;
import net.minecraft.network.protocol.game.ClientboundLevelChunkPacketData;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.levelgen.Heightmap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Only these actual serialization sites may read a verified empty carrier payload. */
@Mixin(ClientboundLevelChunkPacketData.class)
abstract class SurvivalCarrierPacketMixin {
    @Redirect(method="<init>(Lnet/minecraft/world/level/chunk/LevelChunk;)V",at=@At(value="INVOKE",target="Lnet/minecraft/world/level/chunk/LevelChunk;getHeightmaps()Ljava/util/Collection;"))
    private Collection<Map.Entry<Heightmap.Types,Heightmap>> earthplatform$survivalMaps(LevelChunk chunk) {
        return SurvivalCarrierTransport.heightmaps(chunk);
    }
    @Redirect(method={"calculateChunkSize(Lnet/minecraft/world/level/chunk/LevelChunk;)I",
            "extractChunkData(Lnet/minecraft/network/FriendlyByteBuf;Lnet/minecraft/world/level/chunk/LevelChunk;)V"},
            at=@At(value="INVOKE",target="Lnet/minecraft/world/level/chunk/LevelChunk;getSections()[Lnet/minecraft/world/level/chunk/LevelChunkSection;"))
    private static LevelChunkSection[] earthplatform$survivalSections(LevelChunk chunk) {
        return SurvivalCarrierTransport.sections(chunk);
    }
}
