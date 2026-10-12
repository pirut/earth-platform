package io.earthplatform.compat.neoforge;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;
import java.util.zip.ZipFile;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

import static org.junit.jupiter.api.Assertions.*;

/** Independent mutations of actual external class bytes; no upstream code is shipped. */
class PinnedTempScanIndependentMutationTest {
    @Test void originalPinnedMethodsAreAdmittedButDifferentClassIdentityIsNot() throws Exception {
        ClassNode original = original();
        assertDoesNotThrow(() -> PinnedTempScanContract.verify(original));
        original.name += "Modified";
        assertThrows(IllegalStateException.class, () -> PinnedTempScanContract.verify(original));
    }

    @Test void predicateStartingToReadAttributesMustBeRejectedBeforeNamesOnlyUse() throws Exception {
        reject(node -> {
            MethodNode predicate = method(node, "lambda$createPredicate$0");
            assertEquals("(Ljava/lang/String;Ljava/nio/file/Path;Ljava/nio/file/attribute/BasicFileAttributes;)Z", predicate.desc);
            InsnList injected = new InsnList();
            injected.add(new VarInsnNode(Opcodes.ALOAD, 2));
            injected.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, "java/nio/file/attribute/BasicFileAttributes",
                    "isRegularFile", "()Z", true));
            injected.add(new InsnNode(Opcodes.POP));
            predicate.instructions.insert(injected);
        });
    }

    @Test void suffixAndPrefixMeaningCannotDriftWithoutRefusingActivation() throws Exception {
        reject(node -> {
            LdcInsnNode suffix = instructions(method(node, "lambda$createPredicate$0"), LdcInsnNode.class)
                    .stream().filter(instruction -> ".neoforge-tmp".equals(instruction.cst)).findFirst().orElseThrow();
            suffix.cst = ".tmp";
        });
        reject(node -> {
            MethodInsnNode prefix = instructions(method(node, "lambda$createPredicate$0"), MethodInsnNode.class)
                    .stream().filter(instruction -> instruction.name.equals("startsWith")).findFirst().orElseThrow();
            prefix.name = "endsWith";
        });
    }

    @Test void predicateFactoryCannotSilentlyPointAtAnUnvalidatedLambda() throws Exception {
        reject(node -> {
            InvokeDynamicInsnNode factory = instructions(method(node, "createPredicate"), InvokeDynamicInsnNode.class)
                    .getFirst();
            boolean replaced = false;
            for (int index = 0; index < factory.bsmArgs.length; index++) {
                if (factory.bsmArgs[index] instanceof Handle handle && handle.getName().equals("lambda$createPredicate$0")) {
                    factory.bsmArgs[index] = new Handle(handle.getTag(), handle.getOwner(), "anotherPredicate",
                            handle.getDesc(), handle.isInterface());
                    replaced = true;
                }
            }
            assertTrue(replaced, "Mutation must reach the actual predicate implementation handle");
        });
    }

    @Test void depthAndEnumerationCallsiteCannotBroaden() throws Exception {
        reject(node -> {
            MethodNode listing = method(node, "tryListTempFiles");
            AbstractInsnNode depth = instructions(listing, InsnNode.class).stream()
                    .filter(instruction -> instruction.getOpcode() == Opcodes.ICONST_1).findFirst().orElseThrow();
            listing.instructions.set(depth, new InsnNode(Opcodes.ICONST_2));
        });
        reject(node -> {
            MethodInsnNode find = instructions(method(node, "tryListTempFiles"), MethodInsnNode.class).stream()
                    .filter(instruction -> instruction.owner.equals("java/nio/file/Files") && instruction.name.equals("find"))
                    .findFirst().orElseThrow();
            find.owner = "example/OtherTraversal";
        });
    }

    @Test void errorCatchesAndDeletionPolicyCannotBeRelaxed() throws Exception {
        reject(node -> {
            TryCatchBlockNode checked = method(node, "tryListTempFiles").tryCatchBlocks.stream()
                    .filter(block -> "java/io/IOException".equals(block.type)).findFirst().orElseThrow();
            checked.type = "java/lang/Exception";
        });
        reject(node -> {
            MethodInsnNode delete = instructions(method(node, "tryCleanupTempFiles"), MethodInsnNode.class).stream()
                    .filter(instruction -> instruction.owner.equals("java/nio/file/Files") && instruction.name.equals("deleteIfExists"))
                    .findFirst().orElseThrow();
            delete.name = "isReadable";
        });
    }

    @Test void missingAndOverloadedPinnedMethodsAreRefusedRatherThanPartiallyValidated() throws Exception {
        reject(node -> assertTrue(node.methods.remove(method(node, "createPredicate"))));
        reject(node -> node.methods.add(new MethodNode(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC,
                "createPredicate", "()V", null, null)));
    }

    @Test void debugLineChangesDoNotPretendToBeASemanticMismatch() throws Exception {
        ClassNode node = original();
        for (MethodNode method : node.methods) {
            for (LineNumberNode line : instructions(method, LineNumberNode.class)) line.line += 1000;
            method.localVariables = null;
        }
        assertDoesNotThrow(() -> PinnedTempScanContract.verify(node));
    }

    private static void reject(Consumer<ClassNode> mutation) throws Exception {
        ClassNode node = original();
        assertDoesNotThrow(() -> PinnedTempScanContract.verify(node), "Negative control requires an admitted original");
        mutation.accept(node);
        assertThrows(IllegalStateException.class, () -> PinnedTempScanContract.verify(node));
    }

    private static ClassNode original() throws Exception {
        String configured = System.getProperty("earthplatform.test.minecraftJar");
        assertNotNull(configured, "Use the normal FML test partition's exact external merged artifact");
        Path artifact = Path.of(configured);
        assertTrue(Files.isRegularFile(artifact), artifact.toString());
        try (ZipFile jar = new ZipFile(artifact.toFile())) {
            var entry = jar.getEntry("net/neoforged/neoforge/common/IOUtilities.class");
            assertNotNull(entry);
            try (InputStream bytes = jar.getInputStream(entry)) {
                ClassNode node = new ClassNode();
                new ClassReader(bytes).accept(node, 0);
                return node;
            }
        }
    }

    private static MethodNode method(ClassNode node, String name) {
        List<MethodNode> matching = node.methods.stream().filter(method -> method.name.equals(name)).toList();
        assertEquals(1, matching.size(), name);
        return matching.getFirst();
    }

    private static <T extends AbstractInsnNode> List<T> instructions(MethodNode method, Class<T> kind) {
        return java.util.Arrays.stream(method.instructions.toArray()).filter(kind::isInstance).map(kind::cast).toList();
    }
}
