package io.earthplatform.minecraft.survival.persistence;

import io.earthplatform.minecraft.survival.world.SurvivalRefusal;
import java.io.*;
import java.nio.file.*;
import java.util.zip.GZIPOutputStream;
import net.minecraft.nbt.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class BoundedOrdinarySaveNbtTest {
    @TempDir Path root;
    @Test void freezeCopiesActualNativeTagWithoutAliases() throws Exception {
        CompoundTag tag=new CompoundTag();CompoundTag child=new CompoundTag();child.putInt("value",1);tag.put("child",child);
        CompoundTag frozen=BoundedOrdinarySaveNbt.freeze(tag);child.putInt("value",2);
        assertEquals(1,frozen.getCompound("child").getInt("value"));
    }
    @Test void tooLargeArraysFailBeforeCopyOrEncoding(){
        CompoundTag tag=new CompoundTag();tag.putByteArray("payload",new byte[BoundedOrdinarySaveNbt.MAX_BYTES]);
        assertThrows(SurvivalRefusal.class,()->BoundedOrdinarySaveNbt.freeze(tag));
    }
    @Test void excessiveDepthAndCyclesFailBeforeNativeRecursion(){
        CompoundTag tag=new CompoundTag(),cursor=tag;
        for(int i=0;i<BoundedOrdinarySaveNbt.MAX_DEPTH;i++){CompoundTag child=new CompoundTag();cursor.put("next",child);cursor=child;}
        assertThrows(SurvivalRefusal.class,()->BoundedOrdinarySaveNbt.freeze(tag));
        CompoundTag cycle=new CompoundTag();cycle.put("self",cycle);
        assertThrows(SurvivalRefusal.class,()->BoundedOrdinarySaveNbt.freeze(cycle));
    }
    @Test void nodeBudgetAndLossyModifiedUtfFallbackAreRefused(){
        CompoundTag tag=new CompoundTag();ListTag nodes=new ListTag();
        for(int i=0;i<BoundedOrdinarySaveNbt.MAX_NODES;i++)nodes.add(ByteTag.valueOf((byte)1));tag.put("nodes",nodes);
        assertThrows(SurvivalRefusal.class,()->BoundedOrdinarySaveNbt.freeze(tag));
        CompoundTag text=new CompoundTag();text.putString("value","\u0800".repeat(21846));
        assertThrows(SurvivalRefusal.class,()->BoundedOrdinarySaveNbt.freeze(text));
    }
    @Test void trailingDecompressedDataIsNotIgnored() throws Exception {
        Path path=root.resolve("trailing.dat");
        try(var out=new DataOutputStream(new GZIPOutputStream(Files.newOutputStream(path)))){NbtIo.write(new CompoundTag(),out);out.writeByte(1);}
        assertThrows(SurvivalRefusal.class,()->BoundedOrdinarySaveNbt.readTarget(path));
    }
    @Test void nativeCompressedReadbackIsBoundedAndSymlinksRefused() throws Exception {
        Path target=root.resolve("ordinary.dat");CompoundTag tag=new CompoundTag();tag.putLong("saved",77);NbtIo.writeCompressed(tag,target);
        assertEquals(tag,BoundedOrdinarySaveNbt.readTarget(target));
        Path link=root.resolve("linked.dat");Files.createSymbolicLink(link,target);
        assertThrows(SurvivalRefusal.class,()->BoundedOrdinarySaveNbt.readTarget(link));
        Files.write(target,new byte[BoundedOrdinarySaveNbt.MAX_COMPRESSED_BYTES+1]);
        assertThrows(SurvivalRefusal.class,()->BoundedOrdinarySaveNbt.readTarget(target));
    }
    @Test void compressedPayloadBombIsRejected() throws Exception {
        Path path=root.resolve("large.dat");CompoundTag tag=new CompoundTag();tag.putByteArray("large",new byte[BoundedOrdinarySaveNbt.MAX_BYTES+1]);
        NbtIo.writeCompressed(tag,path);
        assertThrows(RuntimeException.class,()->BoundedOrdinarySaveNbt.readTarget(path));
    }
}
