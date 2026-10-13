package io.earthplatform.minecraft.cubic.mixin;

import io.earthplatform.minecraft.cubic.CubicPoiStore;
import java.util.function.Predicate;
import java.util.stream.Stream;
import net.minecraft.core.Holder;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.entity.ai.village.poi.PoiRecord;
import net.minecraft.world.entity.ai.village.poi.PoiSection;
import net.minecraft.world.entity.ai.village.poi.PoiType;
import net.minecraft.world.level.ChunkPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Point-of-interest queries over a chunk column also visit the sections held at cubic heights. */
@Mixin(PoiManager.class)
abstract class CubicPoiQueryMixin {
    @Inject(method = "getInChunk", at = @At("RETURN"), cancellable = true)
    private void earthplatform$cubicSections(Predicate<Holder<PoiType>> types, ChunkPos chunk, PoiManager.Occupancy occupancy,
                                             CallbackInfoReturnable<Stream<PoiRecord>> result) {
        Stream<PoiRecord> cubic = ((CubicPoiStore) this).earthplatform$cubicSections(chunk.toLong())
                .flatMap(section -> ((PoiSection) section).getRecords(types, occupancy));
        result.setReturnValue(Stream.concat(result.getReturnValue(), cubic));
    }
}
