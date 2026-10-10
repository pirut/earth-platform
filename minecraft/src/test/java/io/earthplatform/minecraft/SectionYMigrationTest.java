package io.earthplatform.minecraft;

import io.earthplatform.minecraft.storage.SectionYCodec;
import io.earthplatform.minecraft.storage.SectionYIntegrityError;
import io.earthplatform.minecraft.storage.SectionYReadGuard;
import io.earthplatform.minecraft.transform.SectionYTransformer;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.nio.file.Path;
import java.nio.file.Files;
import java.util.zip.ZipFile;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;
import net.minecraft.nbt.ListTag;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.BasicVerifier;
import static org.junit.jupiter.api.Assertions.*;

class SectionYMigrationTest {
    private String previous;
    @BeforeEach void rememberFlag() { previous = System.getProperty(SectionYCodec.OPT_IN); System.clearProperty(SectionYCodec.OPT_IN); }
    @AfterEach void restoreFlag() {
        if (previous == null) System.clearProperty(SectionYCodec.OPT_IN); else System.setProperty(SectionYCodec.OPT_IN, previous);
    }

    @Test void legacyByteTagsRemainExactlyCompatibleAtEveryValue() {
        for (int y = -128; y <= 127; y++) {
            var section = new CompoundTag(); section.putByte("Y", (byte) y);
            assertEquals(y, SectionYCodec.read(section, "Y"));
            var output = new CompoundTag(); SectionYCodec.write(output, "Y", y);
            assertEquals(section, output);
            assertFalse(output.contains(SectionYCodec.FORMAT_TAG));
        }
    }

    @Test void wideSectionTagsSurviveActualNbtBinaryRoundTrip() throws Exception {
        System.setProperty(SectionYCodec.OPT_IN, "true");
        for (int y : new int[]{Integer.MIN_VALUE, -689, -688, -129, -128, -1, 0, 127, 128, 553, 554, Integer.MAX_VALUE}) {
            var section = new CompoundTag(); SectionYCodec.write(section, "Y", y);
            assertTrue(section.contains("Y", Tag.TAG_INT));
            assertEquals(1, section.getInt(SectionYCodec.FORMAT_TAG));
            var bytes = new ByteArrayOutputStream();
            NbtIo.write(section, new DataOutputStream(bytes));
            var restored = NbtIo.read(new DataInputStream(new ByteArrayInputStream(bytes.toByteArray())), NbtAccounter.create(4096));
            assertEquals(y, SectionYCodec.read(restored, "Y"));
        }
    }

    @Test void disabledWideFormatCannotSilentlyWriteOrLoadTruncatedSections() {
        assertThrows(SectionYIntegrityError.class, () -> SectionYCodec.write(new CompoundTag(), "Y", -688));
        assertThrows(SectionYIntegrityError.class, () -> SectionYCodec.write(new CompoundTag(), "Y", 553));
        var section = new CompoundTag(); section.putInt("Y", 553); section.putInt(SectionYCodec.FORMAT_TAG, 1);
        assertThrows(SectionYIntegrityError.class, () -> SectionYCodec.read(section, "Y"));
    }

    @Test void malformedUnknownAndUnversionedIntFormatsFailClosed() {
        System.setProperty(SectionYCodec.OPT_IN, "true");
        var section = new CompoundTag();
        assertThrows(SectionYIntegrityError.class, () -> SectionYCodec.read(section, "Y"));
        section.putInt("Y", 553);
        assertThrows(SectionYIntegrityError.class, () -> SectionYCodec.read(section, "Y"));
        section.putInt(SectionYCodec.FORMAT_TAG, 2);
        assertThrows(SectionYIntegrityError.class, () -> SectionYCodec.read(section, "Y"));
        section.putInt(SectionYCodec.FORMAT_TAG, 1); section.putByte("Y", (byte) 41);
        assertThrows(SectionYIntegrityError.class, () -> SectionYCodec.read(section, "Y"));
        assertThrows(IllegalArgumentException.class, () -> SectionYCodec.write(new CompoundTag(), "other", 1));
    }

    @Test void exactPinnedSerializerBytecodeIsRepairedAndStackVerified() throws Exception {
        // Read the original compile artifact, not a bytecode facsimile or a source-level mock.
        Path jar = Path.of(System.getProperty("earthplatform.test.minecraftJar"));
        ClassNode node = new ClassNode();
        try (var zip = new ZipFile(jar.toFile()); var input = zip.getInputStream(zip.getEntry("net/minecraft/world/level/chunk/storage/ChunkSerializer.class"))) {
            new ClassReader(input).accept(node, 0);
        }
        SectionYTransformer.apply(node);
        int reads = 0, writes = 0;
        for (var method : node.methods) {
            if (method.name.equals("read") || method.name.equals("write")) new Analyzer<>(new BasicVerifier()).analyze(node.name, method);
            for (var instruction : method.instructions) if (instruction instanceof MethodInsnNode call
                    && call.owner.equals("io/earthplatform/minecraft/storage/SectionYCodec")) {
                assertEquals(Opcodes.INVOKESTATIC, call.getOpcode());
                if (call.name.equals("read")) { reads++; assertEquals("(Lnet/minecraft/nbt/CompoundTag;Ljava/lang/String;)I", call.desc); }
                if (call.name.equals("write")) { writes++; assertEquals("(Lnet/minecraft/nbt/CompoundTag;Ljava/lang/String;I)V", call.desc); }
            }
        }
        assertEquals(1, reads); assertEquals(1, writes);
        var output = new ClassWriter(0); node.accept(output);
        assertTrue(output.toByteArray().length > 0);
        assertThrows(IllegalStateException.class, () -> SectionYTransformer.apply(node), "Double application must not silently patch another site");
    }

    @Test void actualGameSerializerLoadsWithTheInstalledHook() throws Exception {
        // Triggers ModLauncher's registered mixin; malformed target shapes would make this fail.
        assertNotNull(Class.forName("net.minecraft.world.level.chunk.storage.ChunkSerializer"));
    }

    @Test void rejectedOwnedFormatLeavesBinarySaveUntouchedAndBlocksLaterOverwrite() throws Exception {
        Path directory = Files.createTempDirectory("earth-section-y-regression-");
        Path saved = directory.resolve("chunk.nbt");
        var section = new CompoundTag(); section.putInt("Y", 553); section.putInt(SectionYCodec.FORMAT_TAG, 99);
        var list = new ListTag(); list.add(section);
        var chunk = new CompoundTag(); chunk.put("sections", list);
        var encoded = new ByteArrayOutputStream(); NbtIo.write(chunk, new DataOutputStream(encoded));
        byte[] original = encoded.toByteArray(); Files.write(saved, original);
        String world = directory.toAbsolutePath().toString();
        var failure = assertThrows(SectionYIntegrityError.class, () -> SectionYReadGuard.beforeRead(world, 7, -3, chunk));
        assertInstanceOf(Error.class, failure);
        assertTrue(failure.getMessage().contains("protect its saved data"));
        assertThrows(SectionYIntegrityError.class, () -> {
            SectionYReadGuard.beforeWrite(world, 7, -3);
            Files.write(saved, new byte[]{0});
        });
        assertArrayEquals(original, Files.readAllBytes(saved));
        // An unrelated chunk or world is not poisoned by this failure.
        assertDoesNotThrow(() -> SectionYReadGuard.beforeWrite(world, 8, -3));
        assertDoesNotThrow(() -> SectionYReadGuard.beforeWrite(world + "-other", 7, -3));
        // Changing the in-memory metadata is not an authorization to recover a rejected save.
        section.putInt(SectionYCodec.FORMAT_TAG, 1); System.setProperty(SectionYCodec.OPT_IN, "true");
        assertThrows(SectionYIntegrityError.class, () -> SectionYReadGuard.beforeRead(world, 7, -3, chunk));
        assertArrayEquals(original, Files.readAllBytes(saved));
        Files.delete(saved); Files.delete(directory);
    }

    @Test void unmarkedVanillaReadBehaviorIsUnchangedWithoutOptIn() {
        var section = new CompoundTag();
        assertEquals(section.getByte("Y"), SectionYCodec.read(section, "Y"));
        section.putInt("Y", 42);
        assertEquals(section.getByte("Y"), SectionYCodec.read(section, "Y"));
    }
}
