package io.earthplatform.minecraft.survival.interaction.host;

import io.earthplatform.minecraft.survival.world.CubeCell;
import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.util.Objects;
import java.util.UUID;
import net.minecraft.core.HolderLookup;

/** Canonical open evidence; construction and persistent ownership stay private to the host. */
public record NativeMenuOpenJournal(UUID session, long revision, CubeCell table, int tableStateId,
        NativeSessionMenuBoundary.Data before, NativeSessionMenuBoundary.Data after) {
    public NativeMenuOpenJournal {
        Objects.requireNonNull(session); Objects.requireNonNull(table); Objects.requireNonNull(before); Objects.requireNonNull(after);
        if (revision < 0 || revision == Long.MAX_VALUE || tableStateId < 0 || !before.durableResolved()
                || after.tableMenu() == null || !after.tableMenu().resolved() || !before.inventory().equals(after.inventory())
                || before.selected() != after.selected() || before.inventoryChanges() != after.inventoryChanges()
                || !before.inventoryMenu().equals(after.inventoryMenu()) || after.containerCounter() != before.containerCounter() % 100 + 1)
            throw new IllegalArgumentException("Exact empty native table-open lineage required");
    }
    public void write(DataOutput out) throws IOException {
        out.writeLong(session.getMostSignificantBits()); out.writeLong(session.getLeastSignificantBits()); out.writeLong(revision);
        out.writeLong(table.x()); out.writeLong(table.y()); out.writeLong(table.z()); out.writeInt(tableStateId);
        NativeMenuPacketJournal.boundary(out, before); NativeMenuPacketJournal.boundary(out, after);
    }
    public static NativeMenuOpenJournal read(DataInput in, HolderLookup.Provider registries) throws IOException {
        var session = new UUID(in.readLong(), in.readLong()); long revision = in.readLong();
        var table = CubeCell.of(in.readLong(), in.readLong(), in.readLong()); int state = in.readInt();
        return new NativeMenuOpenJournal(session, revision, table, state,
                NativeMenuPacketJournal.boundary(in, registries), NativeMenuPacketJournal.boundary(in, registries));
    }
}
