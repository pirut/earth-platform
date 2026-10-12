package io.earthplatform.geography.survival;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Native resource-loop descriptors. Crafting outputs are supported identities, never starter inventory. */
public final class SurvivalMaterials {
    private SurvivalMaterials() {}
    public static final RegisteredBlockState AIR = RegisteredBlockState.of("minecraft:air");
    public static final RegisteredBlockState STONE = RegisteredBlockState.of("minecraft:stone");
    public static final RegisteredBlockState DIRT = RegisteredBlockState.of("minecraft:dirt");
    public static final RegisteredBlockState GRASS = RegisteredBlockState.of("minecraft:grass_block",Map.of("snowy","false"));
    public static final RegisteredBlockState GRAVEL = RegisteredBlockState.of("minecraft:gravel");
    public static final RegisteredBlockState OAK_LOG = RegisteredBlockState.of("minecraft:oak_log",Map.of("axis","y"));
    public static final RegisteredBlockState OAK_PLANKS = RegisteredBlockState.of("minecraft:oak_planks");
    public static final RegisteredBlockState COBBLESTONE = RegisteredBlockState.of("minecraft:cobblestone");
    public static final RegisteredBlockState CRAFTING_TABLE = RegisteredBlockState.of("minecraft:crafting_table");
    private static final List<RegisteredBlockState> LEAVES = createLeaves();
    private static List<RegisteredBlockState> createLeaves() {
        var result = new ArrayList<RegisteredBlockState>();
        for (int distance=1;distance<=7;distance++) result.add(RegisteredBlockState.of("minecraft:oak_leaves",
                Map.of("distance",Integer.toString(distance),"persistent","false","waterlogged","false")));
        return List.copyOf(result);
    }
    public static RegisteredBlockState oakLeaves(int distance) {
        if (distance<1 || distance>7) throw new IllegalArgumentException("Leaf distance outside 1..7");
        return LEAVES.get(distance-1);
    }
    /** Complete generation palette plus first-loop player-placed states; registry adapters validate every property. */
    public static List<RegisteredBlockState> supportedStates() {
        var values = new ArrayList<>(List.of(AIR,STONE,DIRT,GRASS,GRAVEL,OAK_LOG,OAK_PLANKS,COBBLESTONE,CRAFTING_TABLE));
        values.add(RegisteredBlockState.of("minecraft:oak_log",Map.of("axis","x")));
        values.add(RegisteredBlockState.of("minecraft:oak_log",Map.of("axis","z")));
        values.addAll(LEAVES);
        values.sort(null);
        return List.copyOf(values);
    }
    public static int argb(RegisteredBlockState state) {
        return switch(state.registryId()) {
            case "minecraft:grass_block" -> 0xff72974a;
            case "minecraft:dirt" -> 0xff866043;
            case "minecraft:gravel" -> 0xff8b8581;
            case "minecraft:oak_log" -> 0xff6e5532;
            case "minecraft:oak_leaves" -> 0xff4f7934;
            case "minecraft:air" -> 0;
            default -> 0xff898989;
        };
    }
}
