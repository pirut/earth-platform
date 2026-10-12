package io.earthplatform.minecraft.survival.persistence;

import io.earthplatform.minecraft.survival.interaction.NativeStackSnapshot;
import io.earthplatform.minecraft.survival.interaction.host.PinnedNativeEventAdmission;
import io.earthplatform.minecraft.survival.world.SurvivalRefusal;
import java.io.*;
import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;

/** Native persisted ItemEntity NBT, including health, lifespan, owner/thrower and full stack components. */
public final class NativeItemEntitySnapshot {
    public static final int MAX_BYTES = 131072;
    private final byte[] nbt;
    private final UUID id;
    private final NativeStackSnapshot stack;
    private NativeItemEntitySnapshot(byte[] nbt, UUID id, NativeStackSnapshot stack) {
        this.nbt = nbt.clone(); this.id = id; this.stack = stack;
    }
    public static NativeItemEntitySnapshot capture(ItemEntity item) { return capture(item, MAX_BYTES); }
    /** Bounds native serialization before allocating/cloning its encoded payload. */
    public static NativeItemEntitySnapshot capture(ItemEntity item, int maximumBytes) {
        if (maximumBytes < 1 || maximumBytes > MAX_BYTES) throw new IllegalArgumentException("Bounded remaining native item budget required");
        Objects.requireNonNull(item);
        if (!(item.level() instanceof ServerLevel level) || !level.getServer().isSameThread())
            throw new SurvivalRefusal("Item checkpoint requires its server owner thread");
        if (item.getClass() != ItemEntity.class || !item.isAlive() || item.isPassenger() || item.isVehicle())
            throw new SurvivalRefusal("Only live unmounted native ItemEntity persistence is admitted");
        if (item.hasAttachments()) throw new SurvivalRefusal("Native item attachment callbacks have not been admitted for checkpoint capture");
        NativeStackSnapshot stack = NativeStackSnapshot.capture(item.getItem(), level.registryAccess());
        CompoundTag tag = item.saveWithoutId(new CompoundTag());
        NativeItemEntitySnapshot result = decode(write(tag, maximumBytes), level.registryAccess());
        if (!result.stack.equals(stack)) throw new SurvivalRefusal("Native item save lost stack components");
        if (tag.getShort("Age") != item.getAge())
            throw new SurvivalRefusal("Native item age would be truncated on save");
        // No detached entity construction/load here: those native paths fire mod callbacks.
        // Recovery performs its separately admitted exact roundtrip before adding any entity.
        return result;
    }
    public static NativeItemEntitySnapshot decode(byte[] bytes, HolderLookup.Provider registries) {
        Objects.requireNonNull(bytes); Objects.requireNonNull(registries);
        if (bytes.length == 0 || bytes.length > MAX_BYTES) throw new IllegalArgumentException("Bounded native item NBT required");
        CompoundTag tag = read(bytes);
        if (!tag.hasUUID("UUID") || !tag.contains("Item", Tag.TAG_COMPOUND)
                || tag.contains("Passengers") || tag.contains("id") || tag.contains("neoforge:attachments")
                || !tag.contains("Health", Tag.TAG_SHORT) || !tag.contains("Age", Tag.TAG_SHORT)
                || !tag.contains("PickupDelay", Tag.TAG_SHORT) || !tag.contains("Lifespan", Tag.TAG_INT))
            throw new IllegalArgumentException("Incomplete or unsupported native ItemEntity state");
        requireVector(tag, "Pos", Tag.TAG_DOUBLE, 3);
        requireVector(tag, "Motion", Tag.TAG_DOUBLE, 3);
        requireVector(tag, "Rotation", Tag.TAG_FLOAT, 2);
        ListTag position = tag.getList("Pos", Tag.TAG_DOUBLE), motion = tag.getList("Motion", Tag.TAG_DOUBLE);
        if (Math.abs(position.getDouble(0)) > 30000512 || Math.abs(position.getDouble(1)) > 20000000
                || Math.abs(position.getDouble(2)) > 30000512)
            throw new IllegalArgumentException("Native item position would be clamped on load");
        for (int axis = 0; axis < 3; axis++)
            if (Math.abs(motion.getDouble(axis)) > 10) throw new IllegalArgumentException("Native item velocity would be clamped on load");
        if (tag.getShort("Health") <= 0 || tag.getShort("PickupDelay") < 0)
            throw new IllegalArgumentException("Invalid live item health or pickup delay");
        ItemStack nativeStack = ItemStack.parse(registries, tag.getCompound("Item")).orElseThrow(
                () -> new IllegalArgumentException("Invalid native item stack"));
        NativeStackSnapshot stack = NativeStackSnapshot.capture(nativeStack, registries);
        if (stack.isEmpty() || !tag.getCompound("Item").equals(nativeStack.save(registries)))
            throw new IllegalArgumentException("Lossy native ItemEntity stack encoding");
        return new NativeItemEntitySnapshot(bytes, tag.getUUID("UUID"), stack);
    }
    /** Detached only. The host must prevalidate UUID/capacity/world and add once under its recovery hold. */
    public ItemEntity materialize(ServerLevel level) {
        if (!level.getServer().isSameThread()) throw new SurvivalRefusal("Item recovery requires the server thread");
        var admission = new PinnedNativeEventAdmission(net.neoforged.neoforge.common.NeoForge.EVENT_BUS);
        admission.requireEmpty(net.neoforged.neoforge.event.entity.EntityEvent.Size.class);
        admission.requireEmpty(net.neoforged.neoforge.event.entity.EntityEvent.EntityConstructing.class);
        ItemEntity item = new ItemEntity(EntityType.ITEM, level);
        if (item.hasAttachments()) throw new SurvivalRefusal("Recovery constructor attached unadmitted entity state");
        CompoundTag saved = read(nbt);
        item.load(saved);
        if (!item.isAlive() || !item.getUUID().equals(id) || !stack.matches(item.getItem(), level.registryAccess()))
            throw new SurvivalRefusal("Native item recovery changed persisted identity/components");
        if (!saved.equals(item.saveWithoutId(new CompoundTag())))
            throw new SurvivalRefusal("Native item load changed persisted fields");
        return item;
    }
    public UUID id() { return id; }
    public NativeStackSnapshot stack() { return stack; }
    public byte[] encoded() { return nbt.clone(); }
    public int encodedBytes() { return nbt.length; }
    public CompoundTag tagCopy() { return read(nbt); }
    private static void requireVector(CompoundTag tag, String key, int type, int length) {
        ListTag values = tag.getList(key, type);
        if (values.size() != length) throw new IllegalArgumentException("Incomplete native item " + key);
        for (int i = 0; i < length; i++) {
            double value = type == Tag.TAG_DOUBLE ? values.getDouble(i) : values.getFloat(i);
            if (!Double.isFinite(value)) throw new IllegalArgumentException("Nonfinite native item " + key);
        }
    }
    private static CompoundTag read(byte[] bytes) {
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(bytes))) {
            CompoundTag tag = NbtIo.read(input, new NbtAccounter(8L * MAX_BYTES, 64));
            if (input.available() != 0) throw new IllegalArgumentException("Trailing native item NBT");
            return tag;
        } catch (IOException invalid) { throw new IllegalArgumentException("Invalid native item NBT", invalid); }
    }
    static byte[] write(CompoundTag tag) { return write(tag, MAX_BYTES); }
    static byte[] write(CompoundTag tag, int maximumBytes) {
        if (maximumBytes < 1 || maximumBytes > MAX_BYTES) throw new IllegalArgumentException("Bounded native item serialization budget required");
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            OutputStream bounded = new OutputStream() {
                public void write(int value) throws IOException {
                    if (bytes.size() >= maximumBytes) throw new IOException("Native item NBT exceeds admission");
                    bytes.write(value);
                }
                public void write(byte[] values, int offset, int length) throws IOException {
                    if (length > maximumBytes - bytes.size()) throw new IOException("Native item NBT exceeds admission");
                    bytes.write(values, offset, length);
                }
            };
            try (DataOutputStream output = new DataOutputStream(bounded)) { NbtIo.write(tag, output); }
            return bytes.toByteArray();
        } catch (IOException invalid) { throw new IllegalArgumentException("Unable to encode bounded native item", invalid); }
    }
    @Override public boolean equals(Object other) { return other instanceof NativeItemEntitySnapshot value && Arrays.equals(nbt, value.nbt); }
    @Override public int hashCode() { return Arrays.hashCode(nbt); }
}
