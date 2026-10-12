package io.earthplatform.minecraft.survival.source;

import io.earthplatform.core.CubePos;
import io.earthplatform.core.ImmutableCube;
import io.earthplatform.core.CancellationToken;
import io.earthplatform.geography.survival.*;
import io.earthplatform.minecraft.survival.world.SurvivalPalette;
import java.util.*;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;

/** Strict named-state adapter. Prepared after registry freeze; cube generation owns no scheduler. */
public final class MarinNativeCubeSource {
    private final MarinSurvivalDefinition definition;
    private final Map<RegisteredBlockState, Integer> nativeIds;
    public MarinNativeCubeSource(MarinSurvivalDefinition definition, SurvivalPalette palette) {
        this.definition = Objects.requireNonNull(definition); Objects.requireNonNull(palette);
        Map<RegisteredBlockState, Integer> states = new HashMap<>();
        for (RegisteredBlockState descriptor : SurvivalMaterials.supportedStates()) {
            // GRAVEL describes read-only distant cover80; it is not in the audited grass/tree simulation patch.
            if (descriptor.equals(SurvivalMaterials.GRAVEL)) continue;
            states.put(descriptor, palette.id(resolve(descriptor)));
        }
        nativeIds = Map.copyOf(states);
    }
    public MarinSurvivalDefinition definition() { return definition; }
    public ImmutableCube generate(CubePos position, CancellationToken cancellation) {
        Objects.requireNonNull(cancellation).throwIfCancelled();
        RegisteredBlockState[] generated = definition.generateCube(position);
        int[] values = new int[CubePos.VOLUME];
        for (int i=0;i<values.length;i++) {
            if ((i & 255)==0) cancellation.throwIfCancelled();
            Integer id = nativeIds.get(generated[i]);
            if (id == null) throw new IllegalStateException("Generated state has no admitted native survival semantics: " + generated[i].canonical());
            values[i] = id;
        }
        cancellation.throwIfCancelled(); return ImmutableCube.copyOf(values);
    }
    public static BlockState resolve(RegisteredBlockState descriptor) {
        Objects.requireNonNull(descriptor);
        ResourceLocation id = ResourceLocation.parse(descriptor.registryId());
        if (!BuiltInRegistries.BLOCK.containsKey(id)) throw new IllegalArgumentException("Unknown native block " + id);
        Block block = BuiltInRegistries.BLOCK.get(id); BlockState state = block.defaultBlockState();
        Set<String> required = new TreeSet<>(); for (Property<?> property : state.getProperties()) required.add(property.getName());
        if (!required.equals(descriptor.properties().keySet())) throw new IllegalArgumentException("All exact native state properties must be explicit: " + id);
        for (var entry : descriptor.properties().entrySet()) {
            Property<?> property = block.getStateDefinition().getProperty(entry.getKey());
            state = set(state, property, entry.getValue());
        }
        if (Block.BLOCK_STATE_REGISTRY.getId(state) < 0) throw new IllegalArgumentException("Resolved state is not registered");
        return state;
    }
    private static <T extends Comparable<T>> BlockState set(BlockState state, Property<T> property, String value) {
        T parsed = property.getValue(value).orElseThrow(() -> new IllegalArgumentException("Invalid native state property " + property.getName() + "=" + value));
        return state.setValue(property, parsed);
    }
}
