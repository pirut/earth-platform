package io.earthplatform.minecraft.survival.persistence;

import io.earthplatform.minecraft.survival.world.SurvivalRefusal;
import java.io.*;
import java.nio.file.*;
import java.util.zip.GZIPInputStream;
import net.minecraft.nbt.*;

/** Fixed, deliberately small ordinary-player profile. Checks occur before recursive copy/write. */
final class BoundedOrdinarySaveNbt {
    static final int MAX_BYTES = 4 * 1024 * 1024;
    static final int MAX_COMPRESSED_BYTES = MAX_BYTES + 64 * 1024;
    static final int MAX_HEAP_BYTES = 16 * 1024 * 1024;
    static final int MAX_DEPTH = 64;
    static final int MAX_NODES = 65_536;
    private BoundedOrdinarySaveNbt() {}

    static CompoundTag freeze(CompoundTag tag) throws IOException {
        validate(tag);
        LimitedOutput bytes = new LimitedOutput(MAX_BYTES);
        try (DataOutputStream output = new DataOutputStream(bytes)) { NbtIo.write(tag, output); }
        CompoundTag copy;
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
            copy = NbtIo.read(input, new NbtAccounter(MAX_HEAP_BYTES, MAX_DEPTH));
            if (input.read() != -1) throw refused("Trailing bytes in native serialized payload");
        }
        validate(copy);
        if (!tag.equals(copy)) throw refused("Native emitted NBT is not losslessly encodable");
        return copy;
    }

    static CompoundTag readTarget(Path target) throws IOException {
        if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)
                || Files.size(target) > MAX_COMPRESSED_BYTES)
            throw refused("Ordinary save target is missing, linked, or outside its byte allowance");
        try (InputStream file = Files.newInputStream(target, LinkOption.NOFOLLOW_LINKS);
             InputStream compressed = new LimitedInput(file, MAX_COMPRESSED_BYTES);
             InputStream gzip = new GZIPInputStream(compressed);
             DataInputStream input = new DataInputStream(new LimitedInput(gzip, MAX_BYTES))) {
            CompoundTag result = NbtIo.read(input, new NbtAccounter(MAX_HEAP_BYTES, MAX_DEPTH));
            if (input.read() != -1) throw refused("Trailing data in ordinary save target");
            validate(result);
            return result;
        }
    }

    static void validate(CompoundTag tag) { new Budget().visit(tag, 0); }
    private static final class Budget {
        long bytes = 3; // Root tag type and empty root name.
        int nodes;
        void add(long count) {
            bytes += count;
            if (count < 0 || bytes > MAX_BYTES) throw refused("Native save payload exceeds byte allowance");
        }
        void utf(String text) {
            if (text.length() > 65_535) throw refused("Native save string exceeds modified-UTF allowance");
            long length = 0;
            for (int i = 0; i < text.length(); i++) {
                int c = text.charAt(i); length += c >= 1 && c <= 127 ? 1 : c > 2047 ? 3 : 2;
            }
            if (length > 65_535) throw refused("Native save string would trigger lossy NBT fallback");
            add(2 + length);
        }
        void visit(Tag tag, int depth) {
            if (tag == null || depth >= MAX_DEPTH || ++nodes > MAX_NODES)
                throw refused("Native save payload exceeds depth/node allowance");
            Class<?> type = tag.getClass();
            if (type == CompoundTag.class) {
                CompoundTag compound = (CompoundTag) tag;
                if (compound.size() > MAX_NODES - nodes) throw refused("Native save compound exceeds node allowance");
                add(1);
                for (String key : compound.getAllKeys()) { add(1); utf(key); visit(compound.get(key), depth + 1); }
            } else if (type == ListTag.class) {
                ListTag list = (ListTag) tag;
                if (list.size() > MAX_NODES - nodes) throw refused("Native save list exceeds node allowance");
                add(5); for (int i = 0; i < list.size(); i++) visit(list.get(i), depth + 1);
            } else if (type == ByteArrayTag.class) add(4L + ((ByteArrayTag) tag).size());
            else if (type == IntArrayTag.class) add(4L + 4L * ((IntArrayTag) tag).size());
            else if (type == LongArrayTag.class) add(4L + 8L * ((LongArrayTag) tag).size());
            else if (type == StringTag.class) utf(tag.getAsString());
            else if (type == ByteTag.class) add(1);
            else if (type == ShortTag.class) add(2);
            else if (type == IntTag.class || type == FloatTag.class) add(4);
            else if (type == LongTag.class || type == DoubleTag.class) add(8);
            else throw refused("Non-native or unexpected NBT tag class in ordinary save");
        }
    }
    private static final class LimitedOutput extends ByteArrayOutputStream {
        private final int limit;
        LimitedOutput(int limit) { super(4096); this.limit = limit; }
        private void admit(int length) {
            if (length < 0 || (long) count + length > limit) throw refused("Native save encoding exceeds allowance");
        }
        @Override public synchronized void write(int value) { admit(1); super.write(value); }
        @Override public synchronized void write(byte[] value, int start, int length) { admit(length); super.write(value, start, length); }
    }
    private static final class LimitedInput extends FilterInputStream {
        private long remaining;
        LimitedInput(InputStream input, long maximum) { super(input); remaining = maximum; }
        @Override public int read() throws IOException {
            int result = in.read();
            if (result >= 0 && remaining-- <= 0) throw refused("Native save readback exceeds allowance");
            return result;
        }
        @Override public int read(byte[] value, int start, int length) throws IOException {
            if (length == 0) return 0;
            int result = in.read(value, start, (int) Math.min(length, remaining + 1));
            if (result > 0 && (remaining -= result) < 0) throw refused("Native save readback exceeds allowance");
            return result;
        }
        @Override public long skip(long count) throws IOException {
            long skipped = 0;
            while (skipped < count && read() >= 0) skipped++;
            return skipped;
        }
    }
    private static SurvivalRefusal refused(String reason) { return new SurvivalRefusal(reason); }
}
