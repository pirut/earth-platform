package io.earthplatform.minecraft.survival.interaction.host;

import io.earthplatform.minecraft.survival.interaction.NativeStackSnapshot;
import io.earthplatform.minecraft.survival.world.CubeCell;
import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import net.minecraft.core.HolderLookup;

/** Immutable bounded native evidence only. No record or decoded value carries execution authority. */
public record NativeMiningControlTranscript(UUID session, long revision, Input input,
        NativeMiningControlState.Mode before, NativeMiningControlState.Mode after,
        NativeMiningControlState.Prefix prefixBefore, NativeMiningControlState.Prefix prefixAfter,
        int selected, NativeStackSnapshot tool, List<Target> targets,
        List<NativeMiningControlJournal.Notice> notices, int wireBytes, int destroyIndex, CubeCell destroyed) {
    // Worst case: header24 + input41 + two modes132 + prefixes24 + selection4 + empty stack8
    // + target vector136 + eight crack notices268 + terminal33 = 670 logical serialized bytes.
    static final int MAX_TRANSCRIPT_BYTES=670;
    static final int MAX_EFFECT_BYTES=1024;
    /** kind: START=0, STOP=1, ABORT=2, genuine scheduler pulse=3. */
    public record Input(int kind, CubeCell target, int face, int sequence, int rawServerTick) {
        public Input {
            if (kind < 0 || kind > 3 || (kind == 3 ? target != null || face != -1 || sequence != -1
                    : target == null || face < 0 || face > 5 || sequence < 0 || rawServerTick != 0))
                throw new IllegalArgumentException("Exact bounded native mining input required");
            if (target != null) NativeMiningControlJournal.requireWireCell(target);
        }
    }
    /** Full XYZ plus exact owner-local cube incarnation/revision and native state identity number. */
    public record Target(CubeCell cell, long incarnation, long simulationRevision, int stateId) {
        public Target { Objects.requireNonNull(cell); NativeMiningControlJournal.requireWireCell(cell);
            if (incarnation <= 0 || simulationRevision < 0 || stateId < 0) throw new IllegalArgumentException("Invalid mining target stamp"); }
    }
    public NativeMiningControlTranscript {
        Objects.requireNonNull(session); Objects.requireNonNull(input); Objects.requireNonNull(before); Objects.requireNonNull(after);
        Objects.requireNonNull(prefixBefore); Objects.requireNonNull(prefixAfter); Objects.requireNonNull(tool);
        targets = List.copyOf(targets); notices = List.copyOf(notices);
        if (revision < 0 || revision == Long.MAX_VALUE || selected < 0 || selected > 8 || !tool.isEmpty()
                || targets.size() > 3 || notices.size() > NativeMiningControlJournal.MAX_NOTICES
                || wireBytes < 0 || wireBytes > NativeMiningControlJournal.MAX_WIRE_BYTES
                || (destroyed == null ? destroyIndex != -1 : destroyIndex < 0 || destroyIndex > notices.size()))
            throw new IllegalArgumentException("Bounded native mining transcript required");
        if (input.kind() == 3 ? after.ticks() != before.ticks() + 1 : after.ticks() != before.ticks())
            throw new IllegalArgumentException("Native mining tick progression changed");
        for (var mode : List.of(before, after)) { NativeMiningControlJournal.requireWireCell(mode.target()); NativeMiningControlJournal.requireWireCell(mode.delayedTarget()); }
        if (destroyed != null) NativeMiningControlJournal.requireWireCell(destroyed);
        var unique = new java.util.HashSet<CubeCell>();
        for (var target : targets) if (!unique.add(target.cell())) throw new IllegalArgumentException("Duplicate native target evidence");
        for (var notice : notices) {
            Objects.requireNonNull(notice);
            if (notice instanceof NativeMiningControlJournal.Crack crack) NativeMiningControlJournal.requireWireCell(crack.target());
            else if (notice instanceof NativeMiningControlJournal.BlockResync block) { NativeMiningControlJournal.requireWireCell(block.target()); if (block.stateId() < 0) throw new IllegalArgumentException("Negative state id"); }
            else if (notice instanceof NativeMiningControlJournal.Ack ack && ack.sequence() < 0) throw new IllegalArgumentException("Negative ack");
        }
    }
    public void write(DataOutput out) throws IOException {
        out.writeLong(session.getMostSignificantBits()); out.writeLong(session.getLeastSignificantBits()); out.writeLong(revision);
        out.writeInt(input.kind()); out.writeBoolean(input.target() != null); if (input.target() != null) cell(out,input.target());
        out.writeInt(input.face()); out.writeInt(input.sequence()); out.writeInt(input.rawServerTick());
        mode(out,before); mode(out,after); prefix(out,prefixBefore); prefix(out,prefixAfter);
        out.writeInt(selected); NativeMenuPacketJournal.stack(out,tool); out.writeInt(targets.size());
        for (var target : targets) { cell(out,target.cell()); out.writeLong(target.incarnation()); out.writeLong(target.simulationRevision()); out.writeInt(target.stateId()); }
        out.writeInt(notices.size());
        for (var notice : notices) {
            if (notice instanceof NativeMiningControlJournal.Crack value) { out.writeByte(0); out.writeInt(value.breakerId()); cell(out,value.target()); out.writeInt(value.stage()); }
            else if (notice instanceof NativeMiningControlJournal.BlockResync value) { out.writeByte(1); cell(out,value.target()); out.writeInt(value.stateId()); }
            else if (notice instanceof NativeMiningControlJournal.Ack value) { out.writeByte(2); out.writeInt(value.sequence()); }
            else throw new IOException("Unknown native mining notice");
        }
        out.writeInt(wireBytes); out.writeInt(destroyIndex); out.writeBoolean(destroyed != null); if (destroyed != null) cell(out,destroyed);
    }
    public static NativeMiningControlTranscript read(DataInput in, HolderLookup.Provider registries) throws IOException {
        var session = new UUID(in.readLong(),in.readLong()); long revision=in.readLong(); int kind=in.readInt();
        CubeCell target=flag(in)?cell(in):null;
        var input=new Input(kind,target,in.readInt(),in.readInt(),in.readInt());
        var before=mode(in); var after=mode(in); var prefixBefore=prefix(in); var prefixAfter=prefix(in);
        int selected=in.readInt(); var tool=NativeMenuPacketJournal.stack(in,registries);
        int count=NativeMenuPacketJournal.count(in,3); var targets=new ArrayList<Target>(count);
        for(int i=0;i<count;i++)targets.add(new Target(cell(in),in.readLong(),in.readLong(),in.readInt()));
        count=NativeMenuPacketJournal.count(in,NativeMiningControlJournal.MAX_NOTICES); var notices=new ArrayList<NativeMiningControlJournal.Notice>(count);
        for(int i=0;i<count;i++)notices.add(switch(in.readUnsignedByte()) {
            case 0 -> new NativeMiningControlJournal.Crack(in.readInt(),cell(in),in.readInt());
            case 1 -> new NativeMiningControlJournal.BlockResync(cell(in),in.readInt());
            case 2 -> new NativeMiningControlJournal.Ack(in.readInt());
            default -> throw new IOException("Unknown native mining notice tag");
        });
        int wire=in.readInt(),destroyIndex=in.readInt(); CubeCell destroyed=flag(in)?cell(in):null;
        return new NativeMiningControlTranscript(session,revision,input,before,after,prefixBefore,prefixAfter,selected,tool,targets,notices,wire,destroyIndex,destroyed);
    }
    private static boolean flag(DataInput in)throws IOException {int value=in.readUnsignedByte();if(value>1)throw new IOException("Noncanonical mining boolean");return value!=0;}
    private static void cell(DataOutput out,CubeCell value)throws IOException {out.writeLong(value.x());out.writeLong(value.y());out.writeLong(value.z());}
    private static CubeCell cell(DataInput in)throws IOException {return CubeCell.of(in.readLong(),in.readLong(),in.readLong());}
    private static void prefix(DataOutput out,NativeMiningControlState.Prefix value)throws IOException {out.writeInt(value.acknowledgement());out.writeLong(value.lastActionTime());}
    private static NativeMiningControlState.Prefix prefix(DataInput in)throws IOException {return new NativeMiningControlState.Prefix(in.readInt(),in.readLong());}
    private static void mode(DataOutput out,NativeMiningControlState.Mode value)throws IOException {
        out.writeBoolean(value.destroying());out.writeInt(value.started());cell(out,value.target());out.writeInt(value.ticks());
        out.writeBoolean(value.delayed());cell(out,value.delayedTarget());out.writeInt(value.delayedStarted());out.writeInt(value.lastSentStage());
    }
    private static NativeMiningControlState.Mode mode(DataInput in)throws IOException {
        return new NativeMiningControlState.Mode(flag(in),in.readInt(),cell(in),in.readInt(),flag(in),cell(in),in.readInt(),in.readInt());
    }
}
