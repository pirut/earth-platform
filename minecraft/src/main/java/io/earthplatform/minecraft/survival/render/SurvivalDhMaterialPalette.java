package io.earthplatform.minecraft.survival.render;

import io.earthplatform.geography.survival.RegisteredBlockState;
import io.earthplatform.geography.survival.SurvivalMaterials;
import io.earthplatform.minecraft.survival.world.SurvivalRefusal;
import io.earthplatform.minecraft.visual.NativeDistanceCpuBudget;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

/** Owner-captured registry mapping; workers never consult mutable native registries. */
public final class SurvivalDhMaterialPalette implements AutoCloseable {
    public static final String ENUM_SHA256="a07b4e06d79b53497152f18929db1332fe1654a4b9cd1978c855a312a8710051";
    public static final String CLASSIFIER_SHA256="8d7ee20fd9696b1be6feae71d0c3a70bad4303b2a1396e81afcf425ca255647a";
    public static final String POLICY = "survival-dh-materials-039-v1;official-3.3.3;"
            + "leaves1,stone2,wood3,dirt5,grass13,air14;gravel=dirt;opaque-lod-colors;no-unknown";
    public record Material(int argb, int category) {
        public Material {
            if (category == 14 ? argb != 0 : (argb >>> 24) != 255
                    || category != 1 && category != 2 && category != 3 && category != 5 && category != 13)
                throw new IllegalArgumentException("Pinned survival DH material required");
        }
        public boolean air() { return category == 14; }
    }
    private static final class Shared {
        Map<Integer,Material> states;
        final String sourcePaletteIdentity,identity;
        final NativeDistanceCpuBudget budget;
        final NativeDistanceCpuBudget.Reservation charge;
        int references=1;
        Shared(Map<Integer,Material> states,String sourcePaletteIdentity,NativeDistanceCpuBudget budget,
                NativeDistanceCpuBudget.Reservation charge) {
            this.states=states;this.sourcePaletteIdentity=sourcePaletteIdentity;this.budget=budget;this.charge=charge;
            identity=sha(POLICY+"\n"+ENUM_SHA256+"\n"+CLASSIFIER_SHA256+"\n"+sourcePaletteIdentity+"\n"+new TreeMap<>(states));
        }
    }
    private final Shared shared;
    private final NativeDistanceCpuBudget.Reservation handle;
    private boolean closed;
    public SurvivalDhMaterialPalette(SurvivalRenderPalette palette,NativeDistanceCpuBudget budget) {
        if(palette==null||budget==null)throw new NullPointerException("palette/budget");
        var charge=budget.reserve(NativeDistanceCpuBudget.Pool.OWNER_METADATA,65536);
        NativeDistanceCpuBudget.Reservation handle=null;
        try {
            handle=budget.reserve(NativeDistanceCpuBudget.Pool.OWNER_METADATA,256);
            String sourcePaletteIdentity=palette.identity();var values=new TreeMap<Integer,Material>();
            for(Block block:new Block[]{Blocks.AIR,Blocks.STONE,Blocks.DIRT,Blocks.GRASS_BLOCK,
                    Blocks.GRAVEL,Blocks.COBBLESTONE,Blocks.OAK_LOG,Blocks.OAK_LEAVES,
                    Blocks.OAK_PLANKS,Blocks.CRAFTING_TABLE}) {
                String name=BuiltInRegistries.BLOCK.getKey(block).toString();Material material=material(RegisteredBlockState.of(name));
                for(var state:block.getStateDefinition().getPossibleStates()) {
                    if(!state.getFluidState().isEmpty())continue;
                    if(values.size()>=256)throw new SurvivalRefusal("DH admitted material index exceeds reservation");
                    values.put(palette.id(state),material);
                }
            }
            shared=new Shared(Map.copyOf(values),sourcePaletteIdentity,budget,charge);this.handle=handle;
        }catch(RuntimeException|Error failure){if(handle!=null)handle.releaseAfterPhysicalDrain();charge.releaseAfterPhysicalDrain();throw failure;}
    }
    private SurvivalDhMaterialPalette(Shared shared,NativeDistanceCpuBudget.Reservation handle){this.shared=shared;this.handle=handle;}
    private Shared open(){if(closed||shared.references<1)throw new SurvivalRefusal("Survival DH material handle is closed");return shared;}
    public SurvivalDhMaterialPalette retain(){synchronized(shared){
        Shared value=open();var charge=value.budget.reserve(NativeDistanceCpuBudget.Pool.OWNER_METADATA,256);
        try{int next=Math.incrementExact(value.references);var alias=new SurvivalDhMaterialPalette(value,charge);value.references=next;return alias;}
        catch(RuntimeException|Error failure){charge.releaseAfterPhysicalDrain();throw failure;}
    }}
    public Material state(int id){synchronized(shared){Material value=open().states.get(id);
        if(value==null)throw new SurvivalRefusal("Unmapped native survival DH state "+id);return value;}}
    public String paletteIdentity(){synchronized(shared){return open().sourcePaletteIdentity;}}
    public String identity(){synchronized(shared){return open().identity;}}
    @Override public void close(){synchronized(shared){if(closed)return;closed=true;
        if(--shared.references==0){shared.states=null;shared.charge.releaseAfterPhysicalDrain();}handle.releaseAfterPhysicalDrain();}}
    public static Material material(RegisteredBlockState state) {
        int category = switch (state.registryId()) {
            case "minecraft:air" -> 14;
            case "minecraft:oak_leaves" -> 1;
            case "minecraft:stone", "minecraft:cobblestone" -> 2;
            case "minecraft:oak_log", "minecraft:oak_planks", "minecraft:crafting_table" -> 3;
            case "minecraft:dirt", "minecraft:gravel" -> 5;
            case "minecraft:grass_block" -> 13;
            default -> throw new SurvivalRefusal("Unsupported source survival DH material " + state.registryId());
        };
        return new Material(SurvivalMaterials.argb(state),category);
    }
    private static String sha(String text) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
}
