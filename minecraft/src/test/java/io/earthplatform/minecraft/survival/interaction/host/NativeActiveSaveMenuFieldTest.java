package io.earthplatform.minecraft.survival.interaction.host;

import java.io.InputStream;
import java.util.List;
import net.minecraft.core.NonNullList;
import net.minecraft.world.inventory.AbstractContainerMenu;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;
import static org.junit.jupiter.api.Assertions.*;

/** Native declared-field and exact compiled caller regression; no save authority is minted. */
class NativeActiveSaveMenuFieldTest {
    @Test void nativeMenuVectorsHaveTheExactNonNullListDescriptor() throws Exception {
        for (String name : List.of("lastSlots", "remoteSlots")) {
            var field = AbstractContainerMenu.class.getDeclaredField(name);
            assertSame(NonNullList.class, field.getType());
        }
    }

    @Test void settledSaveMenuUsesTheDeclaredTypeAndKeepsTheStrictReader() throws Exception {
        var host = node(NativeEffectDeliveryHost.class);
        var method = host.methods.stream().filter(m -> m.name.equals("settledSaveMenu")).findFirst().orElseThrow();
        int vectorReads = 0, allReads = 0;
        for (var instruction : method.instructions) {
            if (!(instruction instanceof MethodInsnNode call)
                    || !call.owner.endsWith("/NativeSessionMenuBoundary") || !call.name.equals("read")) continue;
            allReads++;
            var previous = instruction.getPrevious();
            while (previous != null && previous.getOpcode() < 0) previous = previous.getPrevious();
            if (previous instanceof LdcInsnNode constant && constant.cst instanceof Type type) {
                assertEquals("Lnet/minecraft/core/NonNullList;", type.getDescriptor());
                vectorReads++;
            }
        }
        assertEquals(3, allReads);
        assertEquals(1, vectorReads);
        var reader = node(NativeInventoryMenuSnapshot.class).methods.stream()
                .filter(m -> m.name.equals("read")).findFirst().orElseThrow();
        boolean exactTypeRead = false;
        for (var instruction : reader.instructions) {
            if (instruction instanceof MethodInsnNode call) {
                if (call.owner.equals("java/lang/reflect/Field") && call.name.equals("getType")) exactTypeRead = true;
                assertNotEquals("isAssignableFrom", call.name);
            }
        }
        assertTrue(exactTypeRead);
    }

    private static ClassNode node(Class<?> owner) throws Exception {
        try (InputStream input = owner.getResourceAsStream("/" + owner.getName().replace('.', '/') + ".class")) {
            assertNotNull(input);
            var result = new ClassNode();
            new ClassReader(input).accept(result, 0);
            return result;
        }
    }
}
