package io.earthplatform.minecraft.survival.interaction.host;
import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import net.minecraft.core.HolderLookup;
/** Bounded data-only clean-close evidence, never a recovery or live callback capability. */
public record NativeMenuCloseJournal(UUID session,long revision,NativeSessionMenuBoundary.Data before,
        NativeSessionMenuBoundary.Data after,List<NativeInventoryMenuPreparation.SlotPacket> packets) {
 public NativeMenuCloseJournal {
  Objects.requireNonNull(session);Objects.requireNonNull(before);Objects.requireNonNull(after);Objects.requireNonNull(packets);
  if(revision<0||revision==Long.MAX_VALUE||!after.durableResolved()||packets.size()>400
      ||before.selected()!=after.selected()||before.containerCounter()!=after.containerCounter())throw new IllegalArgumentException("Bounded native clean-close lineage required");
  for(var packet:packets)if(packet.container()!=-2||packet.state()!=0||packet.slot()<0||packet.slot()>=41)throw new IllegalArgumentException("Unexpected native close packet");
  packets=List.copyOf(packets);
 }
 public void write(DataOutput out)throws IOException {
  out.writeLong(session.getMostSignificantBits());out.writeLong(session.getLeastSignificantBits());out.writeLong(revision);
  NativeMenuPacketJournal.boundary(out,before);NativeMenuPacketJournal.boundary(out,after);out.writeInt(packets.size());
  for(var packet:packets){out.writeInt(packet.slot());NativeMenuPacketJournal.stack(out,packet.stack());}
 }
 public static NativeMenuCloseJournal read(DataInput in,HolderLookup.Provider registries)throws IOException {
  var session=new UUID(in.readLong(),in.readLong());long revision=in.readLong();
  var before=NativeMenuPacketJournal.boundary(in,registries);var after=NativeMenuPacketJournal.boundary(in,registries);
  int count=NativeMenuPacketJournal.count(in,400);var packets=new ArrayList<NativeInventoryMenuPreparation.SlotPacket>(count);
  for(int i=0;i<count;i++)packets.add(new NativeInventoryMenuPreparation.SlotPacket(-2,0,in.readInt(),NativeMenuPacketJournal.stack(in,registries)));
  return new NativeMenuCloseJournal(session,revision,before,after,packets);
 }
}
