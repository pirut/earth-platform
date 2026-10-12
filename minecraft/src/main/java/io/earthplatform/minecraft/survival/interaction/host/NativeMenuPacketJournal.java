package io.earthplatform.minecraft.survival.interaction.host;

import io.earthplatform.minecraft.survival.interaction.NativeEffectCodec;
import io.earthplatform.minecraft.survival.interaction.NativeStackSnapshot;
import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import net.minecraft.core.HolderLookup;
import net.minecraft.resources.ResourceLocation;

/** Complete bounded evidence only; no construction/decoding grants a live owner or apply capability. */
public record NativeMenuPacketJournal(UUID session, long revision, NativeMenuPacketInput packet,
        NativeSessionMenuPreparation.Result transcript) {
    public NativeMenuPacketJournal {
        Objects.requireNonNull(session); Objects.requireNonNull(packet); Objects.requireNonNull(transcript);
        if (revision < 0 || revision == Long.MAX_VALUE || transcript.notices().size() > 1024
                || transcript.syncPackets() < 0 || transcript.syncPackets() > 47 || transcript.syncBytes() < 0 || transcript.syncBytes() > NativeMenuPacketInput.MAX_PACKET_BYTES)
            throw new IllegalArgumentException("Bounded retained menu lineage required");
        var before = transcript.before(); var clicked = transcript.clicked(); var after = transcript.after();
        packet.requireShape(before.active().table());
        if (packet.containerId() != before.active().containerId()
                || clicked.active().containerId() != packet.containerId() || after.active().containerId() != packet.containerId()
                || !clicked.inventory().equals(after.inventory()) || clicked.selected() != before.selected() || after.selected() != before.selected()
                || clicked.inventoryChanges() != after.inventoryChanges() || before.containerCounter() != after.containerCounter()
                || before.containerCounter() != clicked.containerCounter() || before.active().suppressRemoteUpdates()
                || !clicked.active().suppressRemoteUpdates() || after.active().suppressRemoteUpdates()
                || transcript.staleState() != (packet.stateId() != before.active().stateId())
                || !clicked.active().grid().equals(after.active().grid()) || !clicked.active().result().equals(after.active().result())
                || !clicked.active().carried().equals(after.active().carried())
                || !Objects.equals(clicked.active().recipeUsed(), after.active().recipeUsed()))
            throw new IllegalArgumentException("Native packet synchronization changed authoritative ownership or lineage");
        if (before.tableMenu() != null && (!before.inventoryMenu().equals(clicked.inventoryMenu()) || !before.inventoryMenu().equals(after.inventoryMenu())))
            throw new IllegalArgumentException("Table packet changed the inactive native inventory menu");
        for (var notice : transcript.notices()) {
            Objects.requireNonNull(notice);
            if (notice instanceof NativeInventoryMenuPreparation.Crafted craft && craft.input().size() > (before.active().table() ? 9 : 4)
                    || notice instanceof NativeInventoryMenuPreparation.Unlocked unlock && unlock.recipes().size() != 1
                    || notice instanceof NativeInventoryMenuPreparation.SlotPacket slot && (slot.container() != packet.containerId() || slot.slot() != 0))
                throw new IllegalArgumentException("Native packet notice exceeds its admitted shape");
        }
    }
    public void write(DataOutput out) throws IOException {
        out.writeLong(session.getMostSignificantBits()); out.writeLong(session.getLeastSignificantBits()); out.writeLong(revision);
        out.writeInt(packet.containerId()); out.writeInt(packet.stateId()); out.writeInt(packet.slot()); out.writeInt(packet.button());
        out.writeInt(packet.changedSlots().size());
        for (var change : packet.changedSlots()) { out.writeInt(change.slot()); stack(out, change.stack()); }
        stack(out, packet.carried());
        boundary(out, transcript.before()); boundary(out, transcript.clicked()); boundary(out, transcript.after());
        out.writeBoolean(transcript.staleState()); out.writeInt(transcript.syncPackets()); out.writeInt(transcript.syncBytes()); out.writeInt(transcript.notices().size());
        for (var notice : transcript.notices()) notice(out, notice);
    }
    public static NativeMenuPacketJournal read(DataInput in, HolderLookup.Provider registries) throws IOException {
        UUID session = new UUID(in.readLong(), in.readLong()); long revision = in.readLong();
        int container = in.readInt(), state = in.readInt(), slot = in.readInt(), button = in.readInt();
        int count = count(in, 46); var changes = new ArrayList<NativeMenuPacketInput.Prediction>(count);
        for (int i = 0; i < count; i++) changes.add(new NativeMenuPacketInput.Prediction(in.readInt(), stack(in, registries)));
        var packet = new NativeMenuPacketInput(container, state, slot, button, changes, stack(in, registries));
        var before = boundary(in, registries); var clicked = boundary(in, registries); var after = boundary(in, registries);
        boolean stale = flag(in); int sync = in.readInt(), syncBytes = in.readInt(); count = count(in, 1024);
        var notices = new ArrayList<NativeInventoryMenuPreparation.Notice>(count);
        for (int i = 0; i < count; i++) notices.add(notice(in, registries));
        return new NativeMenuPacketJournal(session, revision, packet, new NativeSessionMenuPreparation.Result(before, clicked, after, notices, stale, sync, syncBytes));
    }
    static void boundary(DataOutput out, NativeSessionMenuBoundary.Data value) throws IOException {
        stacks(out, value.inventory()); out.writeInt(value.selected()); out.writeInt(value.inventoryChanges()); out.writeInt(value.containerCounter());
        menu(out, value.inventoryMenu()); out.writeBoolean(value.tableMenu() != null); if (value.tableMenu() != null) menu(out, value.tableMenu());
    }
    static NativeSessionMenuBoundary.Data boundary(DataInput in, HolderLookup.Provider registries) throws IOException {
        var inventory = stacks(in, registries, 41); int selected = in.readInt(), changes = in.readInt(), counter = in.readInt();
        var inventoryMenu = menu(in, registries); var table = flag(in) ? menu(in, registries) : null;
        return new NativeSessionMenuBoundary.Data(inventory, selected, changes, counter, inventoryMenu, table);
    }
    private static void menu(DataOutput out, NativeSessionMenuBoundary.MenuData value) throws IOException {
        out.writeInt(value.containerId()); stacks(out, value.grid()); stack(out, value.result()); stack(out, value.carried());
        out.writeBoolean(value.recipeUsed() != null); if (value.recipeUsed() != null) out.writeUTF(value.recipeUsed().toString());
        out.writeInt(value.removeCount()); out.writeInt(value.stateId()); stacks(out, value.lastSlots()); stacks(out, value.remoteSlots());
        stack(out, value.remoteCarried()); out.writeInt(value.quickcraftType()); out.writeInt(value.quickcraftStatus());
        out.writeInt(value.quickcraftSlots().size()); out.writeBoolean(value.suppressRemoteUpdates());
    }
    private static NativeSessionMenuBoundary.MenuData menu(DataInput in, HolderLookup.Provider registries) throws IOException {
        int container = in.readInt(); if (container < 0 || container > 100) throw new IllegalArgumentException("Invalid native menu container");
        var grid = stacks(in, registries, container == 0 ? 4 : 9); var result = stack(in, registries); var carried = stack(in, registries);
        ResourceLocation recipe = flag(in) ? id(in) : null;
        int removes = in.readInt(), state = in.readInt(); var last = stacks(in, registries, 46); var remote = stacks(in, registries, 46);
        var remoteCarried = stack(in, registries); int quickType = in.readInt(), quickStatus = in.readInt(); count(in, 0);
        return new NativeSessionMenuBoundary.MenuData(container, grid, result, carried, recipe, removes, state, last, remote, remoteCarried,
                quickType, quickStatus, List.of(), flag(in));
    }
    private static void notice(DataOutput out, NativeInventoryMenuPreparation.Notice value) throws IOException {
        if (value instanceof NativeInventoryMenuPreparation.Statistic statistic) {
            out.writeByte(0); out.writeUTF(statistic.stat().category().toString()); out.writeUTF(statistic.stat().value().toString()); out.writeInt(statistic.stat().amount());
        } else if (value instanceof NativeInventoryMenuPreparation.Crafted crafted) {
            out.writeByte(1); out.writeUTF(crafted.recipe().toString()); out.writeInt(crafted.input().size()); stacks(out, crafted.input());
        } else if (value instanceof NativeInventoryMenuPreparation.Unlocked unlock) {
            out.writeByte(2); out.writeInt(unlock.recipes().size()); for (var recipe : unlock.recipes()) out.writeUTF(recipe.toString());
        } else if (value instanceof NativeInventoryMenuPreparation.SlotPacket packet) {
            out.writeByte(3); out.writeInt(packet.container()); out.writeInt(packet.state()); out.writeInt(packet.slot()); stack(out, packet.stack());
        } else throw new IllegalArgumentException("Unknown native menu notice");
    }
    private static NativeInventoryMenuPreparation.Notice notice(DataInput in, HolderLookup.Provider registries) throws IOException {
        return switch (in.readUnsignedByte()) {
            case 0 -> new NativeInventoryMenuPreparation.Statistic(new NativeEffectCodec.PlayerStat(id(in), id(in), in.readInt()));
            case 1 -> { var recipe = id(in); yield new NativeInventoryMenuPreparation.Crafted(recipe, stacks(in, registries, count(in, 9))); }
            case 2 -> { int count = count(in, 1); var recipes = new ArrayList<ResourceLocation>(count); for (int i = 0; i < count; i++) recipes.add(id(in)); yield new NativeInventoryMenuPreparation.Unlocked(recipes); }
            case 3 -> new NativeInventoryMenuPreparation.SlotPacket(in.readInt(), in.readInt(), in.readInt(), stack(in, registries));
            default -> throw new IllegalArgumentException("Unknown native menu notice tag");
        };
    }
    private static void stacks(DataOutput out, List<NativeStackSnapshot> values) throws IOException { for (var value : values) stack(out, value); }
    private static List<NativeStackSnapshot> stacks(DataInput in, HolderLookup.Provider registries, int count) throws IOException {
        var values = new ArrayList<NativeStackSnapshot>(count); for (int i = 0; i < count; i++) values.add(stack(in, registries)); return List.copyOf(values);
    }
    static void stack(DataOutput out, NativeStackSnapshot value) throws IOException { out.writeInt(value.encodedBytes()); out.write(value.encoded()); }
    static NativeStackSnapshot stack(DataInput in, HolderLookup.Provider registries) throws IOException {
        int length = count(in, NativeStackSnapshot.MAX_BYTES); if (length == 0) throw new IllegalArgumentException("Empty stack encoding");
        // Parent effect input is itself capped64KiB; every allocation is checked before creation.
        var bytes = new byte[length]; in.readFully(bytes); return NativeStackSnapshot.decode(bytes, registries);
    }
    static int count(DataInput in, int max) throws IOException {
        int count = in.readInt(); if (count < 0 || count > max) throw new IllegalArgumentException("Native menu count exceeds bound"); return count;
    }
    private static boolean flag(DataInput in) throws IOException {
        int value = in.readUnsignedByte(); if (value > 1) throw new IllegalArgumentException("Noncanonical native menu boolean"); return value == 1;
    }
    private static ResourceLocation id(DataInput in) throws IOException {
        String value = in.readUTF(); if (value.length() > 1024) throw new IllegalArgumentException("Native menu key exceeds bound"); return ResourceLocation.parse(value);
    }
}
