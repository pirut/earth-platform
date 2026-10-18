package io.earthplatform.minecraft.cubic.gen;

import io.earthplatform.minecraft.cubic.far.Far;
import java.io.*;
import java.util.*;
import net.minecraft.core.Holder;
import net.minecraft.core.IdMap;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;

/**
 * Experimental exact vertical runs, before dense sections or a wire palette are built. The base
 * and revisioned sparse block overlay are immutable. This format is NOT a saved/network payload:
 * the first integration deliberately materializes the existing sections at the legacy boundary.
 * Unsupported generators/features continue through their original dense generation path.
 */
public final class FarSurfacePacket {
    private static final int MAGIC = 0x45505231, VERSION = 2;
    public static final int MAX_RUNS = 8192, MAX_EDITS = 16384, MAX_BYTES = 1 << 20;
    public record Run(int end, BlockState state) {
        public Run { Objects.requireNonNull(state); }
    }
    public record Edit(int x, int row, int z, BlockState state) {
        public Edit { Objects.requireNonNull(state); }
    }
    private final int level, bottom, height, runCount;
    private final int[] offsets;
    /** Exclusive row end in the high word, exact registry block-state id in the low word. */
    private final long[] runs;
    private final short[] drops;
    private final Holder<Biome> initialBiome;
    private final List<Holder<Biome>> biomes;
    private final long revision;
    private final Map<Integer, BlockState> edits;

    private record Runs(int[] offsets, long[] words) {}
    public FarSurfacePacket(int level, int bottom, int height, short[] drops, Holder<Biome> initialBiome,
                            List<Holder<Biome>> biomes, List<List<Run>> columns) {
        this(level, bottom, height, drops, initialBiome, biomes, pack(columns));
    }
    private static Runs pack(List<List<Run>> columns) {
        if (columns.size() != 256) throw new IllegalArgumentException("Invalid column count");
        int total = 0;
        for (var column : columns) {
            if (column.isEmpty() || (total += column.size()) > MAX_RUNS) throw new IllegalArgumentException("Run budget exceeded");
        }
        int[] offsets = new int[257]; long[] words = new long[total]; int at = 0;
        for (int cell = 0; cell < 256; cell++) {
            for (Run run : columns.get(cell)) words[at++] = (long) run.end << 32 | (Block.getId(run.state) & 0xffffffffL);
            offsets[cell + 1] = at;
        }
        return new Runs(offsets, words);
    }
    /** Package-private ownership transfer from the generator: the arrays have no surviving mutable aliases. */
    static FarSurfacePacket generated(int level, int bottom, int height, short[] drops, Holder<Biome> initialBiome,
                                      List<Holder<Biome>> biomes, int[] offsets, long[] words) {
        return new FarSurfacePacket(level, bottom, height, drops, initialBiome, biomes, new Runs(offsets, words));
    }
    private FarSurfacePacket(int level, int bottom, int height, short[] drops, Holder<Biome> initialBiome,
                             List<Holder<Biome>> biomes, Runs data) {
        if (level < Far.MIN_LEVEL || level > Far.MAX_LEVEL || height < 16 || height % 16 != 0
                || height > Far.MAX_SECTIONS * 16 || drops.length != 256 || biomes.size() != 16
                || data.offsets.length != 257 || data.offsets[0] != 0 || data.offsets[256] > MAX_RUNS
                || data.offsets[256] > data.words.length) throw new IllegalArgumentException("Unsupported surface packet dimensions");
        this.level = level; this.bottom = bottom; this.height = height;
        this.drops = drops.clone(); this.initialBiome = Objects.requireNonNull(initialBiome); this.biomes = List.copyOf(biomes);
        offsets = data.offsets; runs = data.words; runCount = offsets[256];
        for (int cell = 0; cell < 256; cell++) {
            if (offsets[cell] < 0 || offsets[cell + 1] <= offsets[cell] || offsets[cell + 1] > runCount)
                throw new IllegalArgumentException("Invalid run offsets");
            int previous = 0, last = -1;
            for (int i = offsets[cell]; i < offsets[cell + 1]; i++) {
                int end = (int) (runs[i] >>> 32), state = (int) runs[i];
                if (end <= previous || end > height || state == last || state < 0 || Block.BLOCK_STATE_REGISTRY.byId(state) == null)
                    throw new IllegalArgumentException("Invalid or redundant vertical runs");
                previous = end; last = state;
            }
            if (previous != height) throw new IllegalArgumentException("Incomplete vertical run coverage");
        }
        revision = 0; edits = Map.of();
    }

    private FarSurfacePacket(FarSurfacePacket base, long revision, Map<Integer, BlockState> edits) {
        level = base.level; bottom = base.bottom; height = base.height; runCount = base.runCount;
        offsets = base.offsets; runs = base.runs; drops = base.drops;
        initialBiome = base.initialBiome; biomes = base.biomes;
        this.revision = revision; this.edits = Map.copyOf(edits);
    }

    public int level() { return level; }
    public int bottom() { return bottom; }
    public int height() { return height; }
    public int runCount() { return runCount; }
    public int editCount() { return edits.size(); }
    public long revision() { return revision; }
    public short drop(int cell) { return drops[cell]; }
    public Holder<Biome> biome(int x, int z) { return biomes.get((z >> 2) * 4 + (x >> 2)); }

    /** Exact transient client view; unsupported vertical biome variation/run counts keep dense input. */
    @javax.annotation.Nullable public static FarSurfacePacket fromDense(int level,int bottom,LevelChunkSection[] sections,char[] drops) {
        if(level<3||level>Far.MAX_LEVEL||sections.length==0||sections.length>Far.MAX_SECTIONS||drops.length!=256)return null;
        LevelChunkSection first=null;boolean any=false;
        for(var section:sections)if(section!=null){if(first==null)first=section;if(!section.hasOnlyAir())any=true;}
        if(first==null||!any)return null;
        List<Holder<Biome>> quarts=new ArrayList<>(16);
        for(int z=0;z<4;z++)for(int x=0;x<4;x++)quarts.add(first.getBiomes().get(x,0,z));
        for(var section:sections)if(section!=null)for(int z=0;z<4;z++)for(int x=0;x<4;x++)for(int y=0;y<4;y++)
            if(section.getBiomes().get(x,y,z)!=quarts.get(z*4+x))return null;
        int height=sections.length*16,total=0;int[] offsets=new int[257];long[] words=new long[1024];
        BlockState air=Blocks.AIR.defaultBlockState();
        for(int cell=0;cell<256;cell++) {
            BlockState previous=null;
            for(int row=0;row<=height;row++) {
                LevelChunkSection section=row==height?null:sections[row>>4];
                BlockState state=row==height?null:section==null?air:section.getBlockState(cell&15,row&15,cell>>4);
                if(row!=0&&state!=previous) {
                    if(total==MAX_RUNS)return null;
                    if(total==words.length)words=Arrays.copyOf(words,Math.min(MAX_RUNS,words.length*2));
                    words[total++]=(long)row<<32|(Block.getId(previous)&0xffffffffL);
                }
                previous=state;
            }
            offsets[cell+1]=total;
        }
        short[] lowered=new short[256];for(int i=0;i<256;i++)lowered[i]=(short)drops[i];
        return generated(level,bottom,height,lowered,first.getBiomes().get(2,2,2),quarts,offsets,words);
    }

    public int firstRun(int cell) { return offsets[cell]; }
    public int afterLastRun(int cell) { return offsets[cell + 1]; }
    public int runEnd(int run) { return (int) (runs[run] >>> 32); }
    public BlockState runState(int run) { return Block.BLOCK_STATE_REGISTRY.byId((int) runs[run]); }
    public int top(int cell, java.util.function.Predicate<BlockState> included) {
        for (int i=offsets[cell+1]-1;i>=offsets[cell];i--) if(included.test(runState(i))) return runEnd(i)-1;
        return -1;
    }
    /** Transient client-mesher fallback only; registry wire encoding uses the caller's original id map. */
    public List<LevelChunkSection> materializeForMeshing() {
        net.minecraft.core.IdMapper<Holder<Biome>> ids = new net.minecraft.core.IdMapper<>(32);
        ids.add(initialBiome);
        for (var biome:biomes) if(ids.getId(biome)<0) ids.add(biome);
        return materialize(ids);
    }

    private int index(int x, int row, int z) {
        if (x < 0 || x > 15 || z < 0 || z > 15 || row < 0 || row >= height)
            throw new IndexOutOfBoundsException("Voxel outside surface packet");
        return row * 256 + z * 16 + x;
    }
    private BlockState base(int cell, int row) {
        int low = offsets[cell], high = offsets[cell + 1];
        while (low < high) {
            int mid = (low + high) >>> 1;
            if ((runs[mid] >>> 32) <= row) low = mid + 1; else high = mid;
        }
        return Block.BLOCK_STATE_REGISTRY.byId((int) runs[low]);
    }
    public BlockState block(int x, int row, int z) {
        int key = index(x, row, z);
        BlockState edit = edits.get(key);
        return edit == null ? base(z * 16 + x, row) : edit;
    }

    /** A stale revision cannot overwrite a newer snapshot. Edits never mutate the shared base. */
    public FarSurfacePacket withEdits(long expectedRevision, long nextRevision, List<Edit> changes) {
        if (expectedRevision != revision || nextRevision <= revision)
            throw new IllegalArgumentException("Stale or non-increasing surface edit revision");
        Map<Integer, BlockState> changed = new HashMap<>(edits);
        for (Edit e : changes) {
            int key = index(e.x, e.row, e.z);
            if (e.state == base(e.z * 16 + e.x, e.row)) changed.remove(key);
            else changed.put(key, e.state);
            if (changed.size() > MAX_EDITS) throw new IllegalArgumentException("Use dense fallback for a large edit overlay");
        }
        return new FarSurfacePacket(this, nextRevision, changed);
    }

    /** Existing section/palette and traversal order, including biome defaults and all block states. */
    public List<LevelChunkSection> materialize(IdMap<Holder<Biome>> biomeIds) {
        List<LevelChunkSection> result = new ArrayList<>(height / 16);
        BlockState air = Blocks.AIR.defaultBlockState();
        for (int si = 0; si < height / 16; si++) {
            var palette = new PalettedContainer<>(biomeIds, initialBiome, PalettedContainer.Strategy.SECTION_BIOMES);
            for (int z = 0; z < 4; z++) for (int x = 0; x < 4; x++) for (int y = 0; y < 4; y++)
                palette.set(x, y, z, biomes.get(z * 4 + x));
            var section = new LevelChunkSection(new PalettedContainer<>(Block.BLOCK_STATE_REGISTRY, air,
                    PalettedContainer.Strategy.SECTION_STATES), palette);
            for (int y = 0; y < 16; y++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
                BlockState state = block(x, si * 16 + y, z);
                if (state != air) section.setBlockState(x, y, z, state, false);
            }
            section.recalcBlockCounts(); result.add(section.hasOnlyAir() ? null : section);
        }
        return result;
    }

    /** Deterministic bounded experimental encoding; no existing packet id or world data changes. */
    public byte[] encode(IdMap<Holder<Biome>> biomeIds) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeInt(MAGIC); out.writeByte(VERSION); out.writeByte(level); out.writeInt(bottom); out.writeShort(height);
            for (short drop : drops) out.writeShort(drop);
            writeVarInt(out, id(biomeIds, initialBiome));
            for (var b : biomes) writeVarInt(out, id(biomeIds, b));
            for (int cell = 0; cell < 256; cell++) {
                writeVarInt(out, offsets[cell + 1] - offsets[cell]);
                for (int i = offsets[cell]; i < offsets[cell + 1]; i++) {
                    writeVarInt(out, (int) (runs[i] >>> 32)); writeVarInt(out, (int) runs[i]);
                }
            }
            out.writeLong(revision); writeVarInt(out, edits.size());
            for (var e : new TreeMap<>(edits).entrySet()) { writeVarInt(out, e.getKey()); writeVarInt(out, Block.getId(e.getValue())); }
        }
        if (bytes.size() > MAX_BYTES) throw new IOException("Surface packet byte budget exceeded");
        return bytes.toByteArray();
    }
    private static void writeVarInt(DataOutput out, int value) throws IOException {
        if (value < 0) throw new IOException("Negative surface packet integer");
        while ((value & ~127) != 0) { out.writeByte((value & 127) | 128); value >>>= 7; }
        out.writeByte(value);
    }
    private static int readVarInt(DataInput in) throws IOException {
        int value = 0;
        for (int shift = 0; shift <= 28; shift += 7) {
            int next = in.readUnsignedByte();
            if (shift == 28 && (next & 248) != 0) throw new IOException("Surface packet integer overflow");
            value |= (next & 127) << shift;
            if ((next & 128) == 0) return value;
        }
        throw new IOException("Unterminated surface packet integer");
    }
    private static int id(IdMap<Holder<Biome>> ids, Holder<Biome> biome) throws IOException {
        int id = ids.getId(biome);
        if (id < 0) throw new IOException("Unknown biome");
        return id;
    }
    private static BlockState state(int id) throws IOException {
        BlockState state = id < 0 ? null : Block.BLOCK_STATE_REGISTRY.byId(id);
        if (state == null) throw new IOException("Unknown block state");
        return state;
    }
    private static Holder<Biome> biome(IdMap<Holder<Biome>> ids, int id) throws IOException {
        Holder<Biome> biome = id < 0 ? null : ids.byId(id);
        if (biome == null) throw new IOException("Unknown biome");
        return biome;
    }
    public static FarSurfacePacket decode(byte[] bytes, IdMap<Holder<Biome>> ids) throws IOException {
        if (bytes.length > MAX_BYTES) throw new IOException("Surface packet byte budget exceeded");
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes))) {
            if (in.readInt() != MAGIC || in.readUnsignedByte() != VERSION) throw new IOException("Unknown surface packet format");
            int level = in.readUnsignedByte(), bottom = in.readInt(), height = in.readUnsignedShort();
            if (height < 16 || height % 16 != 0 || height > Far.MAX_SECTIONS * 16) throw new IOException("Invalid height");
            short[] drops = new short[256]; for (int i = 0; i < 256; i++) drops[i] = in.readShort();
            Holder<Biome> initial = biome(ids, readVarInt(in));
            List<Holder<Biome>> biomes = new ArrayList<>(16);
            for (int i = 0; i < 16; i++) biomes.add(biome(ids, readVarInt(in)));
            List<List<Run>> columns = new ArrayList<>(256); int total = 0;
            for (int cell = 0; cell < 256; cell++) {
                int count = readVarInt(in);
                if (count < 1 || count > height || (total += count) > MAX_RUNS) throw new IOException("Invalid run count");
                List<Run> runs = new ArrayList<>(count);
                for (int i = 0; i < count; i++) runs.add(new Run(readVarInt(in), state(readVarInt(in))));
                columns.add(runs);
            }
            FarSurfacePacket base = new FarSurfacePacket(level, bottom, height, drops, initial, biomes, columns);
            long revision = in.readLong(); int count = readVarInt(in);
            if (revision < 0 || count < 0 || count > MAX_EDITS || revision == 0 && count != 0) throw new IOException("Invalid edit overlay");
            Map<Integer, BlockState> edits = new HashMap<>();
            for (int i = 0; i < count; i++) {
                int key = readVarInt(in); BlockState state = state(readVarInt(in));
                if (key < 0 || key >= height * 256 || edits.putIfAbsent(key, state) != null) throw new IOException("Invalid edit index");
                if (state == base.base(key & 255, key >> 8)) throw new IOException("Redundant edit");
            }
            if (in.read() != -1) throw new IOException("Trailing surface packet data");
            return revision == 0 ? base : new FarSurfacePacket(base, revision, edits);
        } catch (IllegalArgumentException failure) { throw new IOException("Invalid surface packet", failure); }
    }
}
