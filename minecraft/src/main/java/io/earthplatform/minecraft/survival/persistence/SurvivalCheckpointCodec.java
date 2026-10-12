package io.earthplatform.minecraft.survival.persistence;

import com.google.gson.*;
import com.mojang.serialization.JsonOps;
import io.earthplatform.core.*;
import io.earthplatform.minecraft.survival.interaction.NativeEffectCodec;
import io.earthplatform.minecraft.survival.interaction.NativeStackSnapshot;
import io.earthplatform.minecraft.survival.world.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.state.BlockState;
import static io.earthplatform.minecraft.survival.persistence.NativeSurvivalCheckpoint.*;

/** Bounded versioned native codecs; persisted cube palettes contain canonical registered state names/properties. */
public final class SurvivalCheckpointCodec {
    private static final int MAGIC = 0x4553434E, VERSION = 3;
    public static final String PAYLOAD = "survival";
    private final Binding binding;
    private final Limits limits;
    private final HolderLookup.Provider registries;
    private final SurvivalPalette palette;
    private final NativeSurvivalWorld.Bounds bounds;
    public SurvivalCheckpointCodec(Binding binding, Limits limits, HolderLookup.Provider registries,
            SurvivalPalette palette, NativeSurvivalWorld.Bounds bounds) {
        this.binding = Objects.requireNonNull(binding); this.limits = Objects.requireNonNull(limits);
        this.registries = Objects.requireNonNull(registries); this.palette = Objects.requireNonNull(palette);
        this.bounds = Objects.requireNonNull(bounds);
        if (!binding.world().blockPaletteVersion().equals(palette.identity()))
            throw new IllegalArgumentException("Exact current native palette identity required");
    }
    public Binding binding() { return binding; }
    public Limits limits() { return limits; }
    /** Bind SurvivalCheckpointStore to this value, not only a seed or owner-local epoch. */
    public String storageIdentity() {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            writeBinding(new DataOutputStream(bytes), binding);
            return "native-survival-v3:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()));
        } catch (IOException impossible) { throw new AssertionError(impossible); }
        catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
    public SurvivalCheckpointStore.Image image(SurvivalCheckpointStore.Limits storeLimits, NativeSurvivalCheckpoint value) {
        return new SurvivalCheckpointStore.Image(storeLimits, value.generation(), Map.of(PAYLOAD, encode(value)));
    }
    /** Private owner supplies a precharged byte-array peak, never a relaxed format limit. */
    SurvivalCheckpointStore.Image image(SurvivalCheckpointStore.Limits storeLimits, NativeSurvivalCheckpoint value, int maximumBytes) {
        return new SurvivalCheckpointStore.Image(storeLimits,value.generation(),Map.of(PAYLOAD,encode(value,maximumBytes)));
    }
    public NativeSurvivalCheckpoint decode(SurvivalCheckpointStore.Image image) {
        if (!image.names().equals(Set.of(PAYLOAD))) throw new IllegalArgumentException("Unexpected native checkpoint payloads");
        NativeSurvivalCheckpoint result = decode(image.payload(PAYLOAD));
        if (result.generation() != image.generation()) throw new IllegalArgumentException("Manifest/native generation mismatch");
        return result;
    }
    public byte[] encode(NativeSurvivalCheckpoint value) { return encode(value,limits.payloadBytes()); }
    private byte[] encode(NativeSurvivalCheckpoint value,int maximumBytes) {
        if(maximumBytes<1||maximumBytes>limits.payloadBytes())throw new IllegalArgumentException("Invalid prepaid encoder bound");
        if (!binding.equals(value.binding())) throw new IllegalArgumentException("Foreign checkpoint identity");
        validateCounts(value);
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(new BoundedOutput(bytes, maximumBytes));
            out.writeInt(MAGIC); out.writeInt(VERSION); writeBinding(out, binding);
            uuid(out, value.captureOwner()); out.writeLong(value.generation()); out.writeLong(value.commitSequence());
            out.writeLong(value.boundary().gameTime()); uuid(out, value.session().id()); out.writeUTF(value.session().phase().name());
            inventory(out, value.session().ordinaryEscrow()); food(out, value.session().ordinaryFood()); value.session().ordinaryPose().write(out);
            inventory(out, value.boundary().inventory()); food(out, value.boundary().food()); value.boundary().pose().write(out);
            out.writeInt(value.actorSequences().size());
            for (var actor : new TreeMap<>(value.actorSequences()).entrySet()) { uuid(out, actor.getKey()); out.writeLong(actor.getValue()); }
            out.writeInt(value.cubes().size());
            Map<Integer, String> stateCache = new HashMap<>();
            for (var cube : value.cubes().entrySet()) {
                if (!bounds.contains(cube.getKey())) throw new IllegalArgumentException("Checkpoint cube outside admitted bounds");
                out.writeInt(cube.getKey().x()); out.writeInt(cube.getKey().y()); out.writeInt(cube.getKey().z());
                out.writeLong(cube.getValue().simulationRevision()); out.writeLong(cube.getValue().durableRevision());
                cube(out, cube.getValue().blocks(), stateCache);
            }
            List<NativeItemEntitySnapshot> items = new ArrayList<>(value.boundary().items());
            items.sort(Comparator.comparing(NativeItemEntitySnapshot::id)); out.writeInt(items.size());
            for (var item : items) { validateItemBounds(item); blob(out, item.encoded()); }
            List<NativeEffectCodec.ScheduledBlockTick> ticks = new ArrayList<>(value.boundary().scheduledTicks());
            ticks.sort(TICK_ORDER); out.writeInt(ticks.size());
            for (var tick : ticks) {
                validateTick(tick);
                out.writeLong(tick.cell().x()); out.writeLong(tick.cell().y()); out.writeLong(tick.cell().z());
                out.writeUTF(tick.block().toString()); out.writeLong(tick.triggerTick());
                out.writeInt(tick.priority()); out.writeLong(tick.subTickOrder());
            }
            out.flush(); return bytes.toByteArray();
        } catch (IOException invalid) { throw new IllegalArgumentException("Native checkpoint exceeds encoding admission", invalid); }
    }
    public NativeSurvivalCheckpoint decode(byte[] bytes) {
        Objects.requireNonNull(bytes);
        if (bytes.length == 0 || bytes.length > limits.payloadBytes()) throw new IllegalArgumentException("Bounded checkpoint bytes required");
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes))) {
            if (in.readInt() != MAGIC || in.readInt() != VERSION || !binding.equals(readBinding(in)))
                throw new IllegalArgumentException("Native checkpoint identity/version mismatch");
            UUID captureOwner = uuid(in); long generation = in.readLong(), commit = in.readLong(), time = in.readLong();
            Session session = new Session(uuid(in), SessionPhase.valueOf(in.readUTF()), inventory(in), food(in), NativePlayerPoseSnapshot.read(in));
            NativeInventorySnapshot inventory = inventory(in);
            NativeFoodSnapshot food = food(in);
            NativePlayerPoseSnapshot pose = NativePlayerPoseSnapshot.read(in);
            int actorCount = count(in, limits.actors(), 24); Map<UUID, Long> actors = new TreeMap<>();
            for (int i = 0; i < actorCount; i++) if (actors.put(uuid(in), in.readLong()) != null)
                throw new IllegalArgumentException("Duplicate actor high-water metadata");
            int cubeCount = count(in, limits.cubes(), 30); Map<CubePos, Cube> cubes = new TreeMap<>();
            for (int i = 0; i < cubeCount; i++) {
                CubePos position = new CubePos(in.readInt(), in.readInt(), in.readInt());
                if (!bounds.contains(position)) throw new IllegalArgumentException("Saved cube outside admitted bounds");
                Cube value = new Cube(in.readLong(), in.readLong(), cube(in));
                if (cubes.put(position, value) != null) throw new IllegalArgumentException("Duplicate checkpoint cube");
            }
            int itemCount = count(in, limits.items(), 4); List<NativeItemEntitySnapshot> items = new ArrayList<>(itemCount);
            for (int i = 0; i < itemCount; i++) {
                var item = NativeItemEntitySnapshot.decode(blob(in, NativeItemEntitySnapshot.MAX_BYTES), registries);
                validateItemBounds(item); items.add(item);
            }
            int tickCount = count(in, limits.scheduledTicks(), 46); List<NativeEffectCodec.ScheduledBlockTick> ticks = new ArrayList<>(tickCount);
            for (int i = 0; i < tickCount; i++) {
                var tick = new NativeEffectCodec.ScheduledBlockTick(CubeCell.of(in.readLong(), in.readLong(), in.readLong()),
                        ResourceLocation.parse(in.readUTF()), in.readLong(), in.readInt(), in.readLong());
                validateTick(tick); ticks.add(tick);
            }
            if (in.available() != 0) throw new IllegalArgumentException("Trailing native checkpoint bytes");
            NativeSurvivalCheckpoint result = new NativeSurvivalCheckpoint(limits, binding, captureOwner, generation, commit,
                    actors, cubes, new Boundary(time, inventory, food, pose, items, ticks), session);
            if (!Arrays.equals(bytes, encode(result,bytes.length))) throw new IllegalArgumentException("Noncanonical native checkpoint encoding");
            return result;
        } catch (IOException invalid) { throw new IllegalArgumentException("Truncated native checkpoint", invalid); }
    }
    private void validateCounts(NativeSurvivalCheckpoint value) {
        if (value.cubes().size() > limits.cubes() || value.actorSequences().size() > limits.actors()
                || value.boundary().items().size() > limits.items() || value.boundary().scheduledTicks().size() > limits.scheduledTicks())
            throw new IllegalArgumentException("Native checkpoint count exceeds admission");
    }
    private void validateItemBounds(NativeItemEntitySnapshot item) {
        var position = item.tagCopy().getList("Pos", net.minecraft.nbt.Tag.TAG_DOUBLE);
        CubePos cube = CubePos.fromBlock((long) Math.floor(position.getDouble(0)), (long) Math.floor(position.getDouble(1)),
                (long) Math.floor(position.getDouble(2)));
        if (!bounds.contains(cube)) throw new IllegalArgumentException("Saved item outside admitted cube bounds");
    }
    private void validateTick(NativeEffectCodec.ScheduledBlockTick tick) {
        if (!bounds.contains(tick.cell().cube()) || !BuiltInRegistries.BLOCK.containsKey(tick.block()))
            throw new IllegalArgumentException("Unadmitted scheduled block tick position/type");
        palette.id(BuiltInRegistries.BLOCK.get(tick.block()).defaultBlockState());
    }
    private void cube(DataOutputStream out, ImmutableCube blocks, Map<Integer, String> cache) throws IOException {
        TreeSet<String> states = new TreeSet<>();
        for (int i = 0; i < CubePos.VOLUME; i++) {
            int id=blocks.get(i);String encoded=cache.get(id);
            if(encoded==null){
                // Explicit bounded codec scratch profile, charged by the persistence owner.
                if(cache.size()>=64)throw new IllegalArgumentException("Checkpoint codec exceeds64 admitted named states");
                encoded=state(palette.state(id));cache.put(id,encoded);
            }
            states.add(encoded);
        }
        out.writeInt(states.size()); Map<String, Integer> indices = new HashMap<>(); int next = 0;
        for (String state : states) { indices.put(state, next++); out.writeUTF(state); }
        out.writeBoolean(blocks.isUniform());
        int count = blocks.isUniform() ? 1 : CubePos.VOLUME;
        for (int i = 0; i < count; i++) out.writeShort(indices.get(cache.get(blocks.get(i))));
    }
    private ImmutableCube cube(DataInputStream in) throws IOException {
        int count = count(in, CubePos.VOLUME, 2);
        if (count == 0) throw new IllegalArgumentException("Empty saved state palette");
        int[] ids = new int[count]; String previous = "";
        for (int i = 0; i < count; i++) {
            String encoded = in.readUTF();
            if (encoded.length() > 4096 || encoded.compareTo(previous) <= 0) throw new IllegalArgumentException("Invalid named-state palette order/size");
            previous = encoded;
            // Named BlockState.CODEC is the native registry/property codec; re-encode rejects ignored unknown fields/properties.
            checkStateDepth(encoded);
            JsonElement json = JsonParser.parseString(encoded);
            canonical(json, 0);
            BlockState state = BlockState.CODEC.parse(JsonOps.INSTANCE, json).getOrThrow();
            if (!encoded.equals(state(state))) throw new IllegalArgumentException("Lossy or noncanonical native state");
            ids[i] = palette.id(state);
        }
        boolean uniform = in.readBoolean(); int slots = uniform ? 1 : CubePos.VOLUME;
        if (in.available() < slots * 2) throw new IllegalArgumentException("Truncated saved cube indices");
        int[] blocks = uniform ? null : new int[CubePos.VOLUME]; int uniformId = -1;
        for (int i = 0; i < slots; i++) {
            int index = in.readUnsignedShort(); if (index >= count) throw new IllegalArgumentException("Invalid saved cube palette index");
            if (uniform) uniformId = ids[index]; else blocks[i] = ids[index];
        }
        return uniform ? ImmutableCube.uniform(uniformId) : ImmutableCube.copyOf(blocks);
    }
    private static String state(BlockState state) {
        String encoded = canonical(BlockState.CODEC.encodeStart(JsonOps.INSTANCE, state).getOrThrow(), 0).toString();
        if (encoded.length() > 4096) throw new IllegalArgumentException("Native block state encoding exceeds admission");
        return encoded;
    }
    private static void checkStateDepth(String text) {
        int depth = 0; boolean quoted = false, escaped = false;
        for (int i = 0; i < text.length(); i++) {
            char current = text.charAt(i);
            if (quoted) {
                if (escaped) escaped = false;
                else if (current == '\\') escaped = true;
                else if (current == '"') quoted = false;
            } else if (current == '"') quoted = true;
            else if (current == '{' || current == '[') {
                if (++depth > 16) throw new IllegalArgumentException("Native state nesting exceeds admission");
            } else if (current == '}' || current == ']') depth--;
        }
    }
    private static JsonElement canonical(JsonElement value, int depth) {
        if (depth > 16) throw new IllegalArgumentException("Native state nesting exceeds admission");
        if (value.isJsonObject()) {
            JsonObject result = new JsonObject();
            for (String key : new TreeSet<>(value.getAsJsonObject().keySet())) result.add(key, canonical(value.getAsJsonObject().get(key), depth + 1));
            return result;
        }
        if (value.isJsonArray()) { JsonArray result = new JsonArray(); for (var child : value.getAsJsonArray()) result.add(canonical(child, depth + 1)); return result; }
        return value.deepCopy();
    }
    private static final Comparator<NativeEffectCodec.ScheduledBlockTick> TICK_ORDER = Comparator
            .comparingLong(NativeEffectCodec.ScheduledBlockTick::triggerTick).thenComparingInt(NativeEffectCodec.ScheduledBlockTick::priority)
            .thenComparingLong(NativeEffectCodec.ScheduledBlockTick::subTickOrder).thenComparing(NativeEffectCodec.ScheduledBlockTick::cell)
            .thenComparing(tick -> tick.block().toString());
    private static void food(DataOutputStream out, NativeFoodSnapshot food) throws IOException {
        out.writeInt(food.foodLevel()); out.writeInt(food.tickTimer());
        out.writeFloat(food.saturation()); out.writeFloat(food.exhaustion());
    }
    private static NativeFoodSnapshot food(DataInputStream in) throws IOException {
        return new NativeFoodSnapshot(in.readInt(), in.readInt(), in.readFloat(), in.readFloat());
    }
    private static void inventory(DataOutputStream out, NativeInventorySnapshot value) throws IOException {
        out.writeInt(value.selected()); for (var stack : value.slots()) blob(out, stack.encoded());
    }
    private NativeInventorySnapshot inventory(DataInputStream in) throws IOException {
        int selected = in.readInt(); List<NativeStackSnapshot> slots = new ArrayList<>(NativeInventorySnapshot.SLOTS);
        for (int i = 0; i < NativeInventorySnapshot.SLOTS; i++) slots.add(NativeStackSnapshot.decode(blob(in, NativeStackSnapshot.MAX_BYTES), registries));
        return new NativeInventorySnapshot(slots, selected);
    }
    private static void writeBinding(DataOutputStream out, Binding value) throws IOException {
        uuid(out, value.saveId()); out.writeUTF(value.profileId()); WorldIdentity world = value.world();
        out.writeLong(world.seed()); out.writeUTF(world.generatorId()); out.writeUTF(world.generatorVersion());
        out.writeUTF(world.datasetVersion()); out.writeUTF(world.blockPaletteVersion());
        out.writeUTF(value.sourceIdentity()); out.writeUTF(value.registryIdentity()); out.writeUTF(value.dataPackIdentity());
        uuid(out, value.player()); out.writeUTF(value.dimension().toString());
    }
    private static Binding readBinding(DataInputStream in) throws IOException {
        UUID save = uuid(in); String profile = in.readUTF();
        WorldIdentity world = new WorldIdentity(in.readLong(), in.readUTF(), in.readUTF(), in.readUTF(), in.readUTF());
        return new Binding(save, profile, world, in.readUTF(), in.readUTF(), in.readUTF(), uuid(in), ResourceLocation.parse(in.readUTF()));
    }
    private static void uuid(DataOutputStream out, UUID id) throws IOException { out.writeLong(id.getMostSignificantBits()); out.writeLong(id.getLeastSignificantBits()); }
    private static UUID uuid(DataInputStream in) throws IOException { return new UUID(in.readLong(), in.readLong()); }
    private static void blob(DataOutputStream out, byte[] value) throws IOException { out.writeInt(value.length); out.write(value); }
    private static byte[] blob(DataInputStream in, int maximum) throws IOException {
        int size = in.readInt(); if (size <= 0 || size > maximum || size > in.available()) throw new IllegalArgumentException("Invalid bounded checkpoint blob");
        return in.readNBytes(size);
    }
    private static int count(DataInputStream in, int maximum, int minimumBytes) throws IOException {
        int count = in.readInt();
        if (count < 0 || count > maximum || (long) count * minimumBytes > in.available())
            throw new IllegalArgumentException("Invalid bounded checkpoint count");
        return count;
    }
    private static final class BoundedOutput extends OutputStream {
        private final ByteArrayOutputStream target; private final int limit;
        BoundedOutput(ByteArrayOutputStream target, int limit) { this.target = target; this.limit = limit; }
        public void write(int value) throws IOException { if (target.size() == limit) throw new IOException("Checkpoint byte budget exceeded"); target.write(value); }
        public void write(byte[] value, int offset, int length) throws IOException {
            if (length > limit - target.size()) throw new IOException("Checkpoint byte budget exceeded"); target.write(value, offset, length);
        }
    }
}
