package io.earthplatform.minecraft.cubic.mixin;

import io.earthplatform.minecraft.cubic.CubicLevel;
import io.earthplatform.minecraft.cubic.CubicPoiStore;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.ints.IntSet;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import java.util.Optional;
import java.util.function.Function;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.chunk.storage.SectionStorage;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Lets the point-of-interest store of the cubic world hold sections at any cubic height, in
 * memory only: vanilla would refuse them ("sectionPos out of bounds") or look for them on disk.
 * See {@link CubicPoiStore}.
 */
@Mixin(SectionStorage.class)
abstract class CubicPoiStorageMixin<R> implements CubicPoiStore {
    @Shadow @Final protected LevelHeightAccessor levelHeightAccessor;
    @Shadow @Final private Long2ObjectMap<Optional<R>> storage;
    @Shadow @Final private Function<Runnable, R> factory;

    /** Cubic section Ys held per chunk column, for queries to visit. */
    @Unique private final Long2ObjectOpenHashMap<IntSet> earthplatform$columns = new Long2ObjectOpenHashMap<>();

    @Unique private boolean earthplatform$cubicOnly(long section) {
        return levelHeightAccessor instanceof Level level && CubicLevel.of(level) != null
                && levelHeightAccessor.isOutsideBuildHeight(SectionPos.sectionToBlockCoord(SectionPos.y(section)));
    }

    @Inject(method = "getOrLoad", at = @At("HEAD"), cancellable = true)
    private void earthplatform$cubicLoad(long section, CallbackInfoReturnable<Optional<R>> result) {
        if (!earthplatform$cubicOnly(section)) return;
        Optional<R> held = storage.get(section);
        result.setReturnValue(held != null ? held : Optional.empty());
    }

    @Inject(method = "getOrCreate", at = @At("HEAD"), cancellable = true)
    private void earthplatform$cubicCreate(long section, CallbackInfoReturnable<R> result) {
        if (!earthplatform$cubicOnly(section)) return;
        Optional<R> held = storage.get(section);
        if (held != null && held.isPresent()) { result.setReturnValue(held.get()); return; }
        // Not saved: the section is rebuilt from its cube's blocks whenever the cube loads.
        R made = factory.apply(() -> {});
        storage.put(section, Optional.of(made));
        earthplatform$columns.computeIfAbsent(ChunkPos.asLong(SectionPos.x(section), SectionPos.z(section)), k -> new IntOpenHashSet()).add(SectionPos.y(section));
        result.setReturnValue(made);
    }

    @Override public Stream<R> earthplatform$cubicSections(long chunk) {
        IntSet sections = earthplatform$columns.get(chunk);
        if (sections == null || sections.isEmpty()) return Stream.empty();
        int x = ChunkPos.getX(chunk), z = ChunkPos.getZ(chunk);
        return IntStream.of(sections.toIntArray()).mapToObj(y -> storage.get(SectionPos.asLong(x, y, z)))
                .filter(held -> held != null && held.isPresent()).map(Optional::get);
    }

    @Override public void earthplatform$forget(long section) {
        if (!earthplatform$cubicOnly(section)) return;
        storage.remove(section);
        long chunk = ChunkPos.asLong(SectionPos.x(section), SectionPos.z(section));
        IntSet sections = earthplatform$columns.get(chunk);
        if (sections != null && sections.remove(SectionPos.y(section)) && sections.isEmpty()) earthplatform$columns.remove(chunk);
    }
}
