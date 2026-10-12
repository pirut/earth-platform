package io.earthplatform.minecraft.coregate;

import io.earthplatform.core.CubePos;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Executes the actual prepareCandidateWork bytecode and real legacy queue. The037 distance
 * branch dispatches only the data authority (its internals are independently tested). Legacy
 * mesh adoption/classification and downstream sinks are substituted as in036. No GL claim.
 */
class RollingAdaptiveFrameHostIndependentTest {
    private static final String BASE = "io/earthplatform/minecraft/coregate/";
    private static final String ROOT = BASE + "RollingGateClient";
    private static final String SESSION = ROOT + "$Session";
    private static final String SELF = BASE + "RollingAdaptiveFrameHostIndependentTest";
    private static final String GENERATED = BASE + "AdaptiveFrameExecutableIndependentFixture";
    private static Class<?> fixtureClass;
    private static final ThreadLocal<Inputs> INPUTS = new ThreadLocal<>();
    private static final class Inputs {
        boolean distance = true, valid = true;
        int classified, validated, farPreparations, bodySlices;
        boolean bodyFails;
        RollingAdaptiveSliceIndependentTest.Fixture owner;
    }

    @Test void distanceDispatchRunsOnlyDataAuthorityAndNeverBeginsAnUndrawnMesh() throws Throwable {
        var in=new Inputs();in.owner=new RollingAdaptiveSliceIndependentTest.Fixture(1,10,100,128);
        var fixture=fixture(in);
        try {
            in.owner.start(1);call(fixture);
            assertEquals(1,in.bodySlices);assertTrue(in.owner.events.isEmpty());
            assertEquals(0,in.owner.adopted.size());assertEquals(0,in.classified);
            assertEquals(0,in.validated);assertEquals(0,in.farPreparations);
        } finally {in.owner.queue.close();INPUTS.remove();}
    }
    @Test void repeatedMainAndShadowCannotGrantSecondDataSliceEvenAfterNewRequest() throws Throwable {
        var in=new Inputs();in.owner=new RollingAdaptiveSliceIndependentTest.Fixture(1,10,100,128);
        var fixture=fixture(in);
        try {
            call(fixture);for(int callback=0;callback<8;callback++)call(fixture);
            assertEquals(1,in.bodySlices);assertTrue(in.owner.events.isEmpty());
            put(null,"actualRenderFrame",12L);call(fixture);assertEquals(2,in.bodySlices);
            assertEquals(0,in.owner.queue.metrics().ownerSlice().timing().calls());
        } finally {in.owner.queue.close();INPUTS.remove();}
    }
    @Test void legacyClassificationCannotStealDistanceAuthorityDispatch() throws Throwable {
        var in=new Inputs();in.owner=new RollingAdaptiveSliceIndependentTest.Fixture(1,10,100,128);
        var fixture=fixture(in);
        try {
            put(fixture,"nativeClassificationComplete",false);call(fixture);
            assertEquals(1,in.bodySlices);assertEquals(0,in.classified);assertTrue(in.owner.events.isEmpty());
        } finally {in.owner.queue.close();INPUTS.remove();}
    }
    @Test void failedDataSliceStillConsumesFrameAndNeverFallsThroughToMeshes() throws Throwable {
        var in=new Inputs();in.owner=new RollingAdaptiveSliceIndependentTest.Fixture(1,10,100,128);in.bodyFails=true;
        var fixture=fixture(in);
        try {
            assertThrows(IllegalStateException.class,()->call(fixture));
            call(fixture);assertEquals(1,in.bodySlices);assertTrue(in.owner.events.isEmpty());
            put(null,"actualRenderFrame",12L);assertThrows(IllegalStateException.class,()->call(fixture));
            assertEquals(2,in.bodySlices);assertTrue(in.owner.events.isEmpty());
        } finally {in.owner.queue.close();INPUTS.remove();}
    }
    @Test void legacyDrainStillRequiresCandidateSourceAndNonDeclinedAuthority() throws Throwable {
        for(String block:List.of("candidate","source","declined")) {
            var in=new Inputs();in.distance=false;in.owner=new RollingAdaptiveSliceIndependentTest.Fixture(1,10,100,128);
            var fixture=fixture(in);
            try {
                if(block.equals("candidate"))in.valid=false;
                if(block.equals("source"))put(fixture,"sourceVerified",false);
                if(block.equals("declined"))put(fixture,"replacementDeclineSent",true);
                in.owner.start(1);call(fixture);call(fixture);call(fixture);
                assertTrue(in.owner.queue.isDrained());assertEquals(1,in.validated);assertEquals(0,in.farPreparations);assertEquals(0,in.bodySlices);
            } finally {in.owner.queue.close();INPUTS.remove();}
        }
    }

    @Test void nonDistanceHostKeepsOneLegacyPhasePerInvocation() throws Throwable {
        var in = new Inputs(); in.distance = false;
        in.owner = new RollingAdaptiveSliceIndependentTest.Fixture(1, 10, 100, 128);
        var fixture = fixture(in);
        try {
            in.owner.start(1); call(fixture);
            assertEquals(List.of(RollingAdaptiveSliceIndependentTest.Op.BEGIN), in.owner.events);
            call(fixture); assertEquals(0, in.owner.adopted.size());
            call(fixture); assertEquals(1, in.owner.adopted.size());
            assertEquals(0, in.owner.queue.metrics().ownerSlice().timing().calls());
        } finally { in.owner.queue.close(); INPUTS.remove(); }
    }

    private static Object fixture(Inputs in) throws Exception {
        INPUTS.set(in);
        var fixture = fixtureType().getConstructor().newInstance();
        put(null, "actualRenderFrame", 11L); put(fixture, "nativeWorkPumpedFrame", -1L);
        put(fixture, "nativeClassificationComplete", true); put(fixture, "meshWork", in.owner.queue);
        put(fixture, "sourceVerified", true); put(fixture, "replacementDeclineSent", false);
        return fixture;
    }
    private static synchronized Class<?> fixtureType() throws Exception {
        if (fixtureClass != null) return fixtureClass;
        ClassNode source;
        try (var bytes = RollingAdaptiveFrameHostIndependentTest.class.getClassLoader().getResourceAsStream(SESSION + ".class")) {
            assertNotNull(bytes); source = new ClassNode(); new ClassReader(bytes).accept(source, 0);
        }
        var original = source.methods.stream().filter(m -> m.name.equals("prepareCandidateWork")).findFirst().orElseThrow();
        var method = new MethodNode(Opcodes.ACC_PUBLIC, original.name, original.desc, null, null);
        original.accept(method); method.access = Opcodes.ACC_PUBLIC; method.localVariables = null;
        var fields = new LinkedHashMap<String, FieldNode>();
        for (var instruction : method.instructions.toArray()) {
            if (instruction instanceof FrameNode) method.instructions.remove(instruction);
            else if (instruction instanceof FieldInsnNode field && (field.owner.equals(SESSION) || field.owner.equals(ROOT))) {
                if (field.name.equals("preparedFar")) field.desc = "Ljava/lang/Object;";
                fields.putIfAbsent(field.name, new FieldNode(Opcodes.ACC_PUBLIC | (field.getOpcode() == Opcodes.GETSTATIC ? Opcodes.ACC_STATIC : 0), field.name, field.desc, null, null));
                field.owner = GENERATED;
            } else if (instruction instanceof MethodInsnNode call && call.owner.equals(SESSION)) {
                String name = switch (call.name) {
                    case "prepareOneNativeReuse" -> "classify";
                    case "prepareDistanceBody" -> "body";
                    case "validateCandidate" -> "validate";
                    case "prepareFarCoverage" -> "far";
                    case "preparationStatus" -> "status";
                    case "distanceMode" -> "distance";
                    default -> throw new AssertionError("Unreviewed host call: " + call.name);
                };
                call.setOpcode(Opcodes.INVOKESTATIC); call.owner = SELF; call.name = name;
                call.desc = "(Ljava/lang/Object;" + call.desc.substring(1); call.itf = false;
            } else if (instruction instanceof InvokeDynamicInsnNode dynamic && dynamic.desc.endsWith("Ljava/util/function/BiConsumer;")) {
                assertEquals("(L" + SESSION + ";)Ljava/util/function/BiConsumer;", dynamic.desc);
                method.instructions.set(dynamic, new MethodInsnNode(Opcodes.INVOKESTATIC, SELF, "consumer", "(Ljava/lang/Object;)Ljava/util/function/BiConsumer;", false));
            }
        }
        var generated = new ClassNode(); generated.version = source.version; generated.access = Opcodes.ACC_PUBLIC;
        generated.name = GENERATED; generated.superName = "java/lang/Object";
        generated.fields.addAll(fields.values()); generated.methods.add(method);
        var ctor = new MethodNode(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        ctor.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        ctor.instructions.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false));
        ctor.instructions.add(new InsnNode(Opcodes.RETURN)); generated.methods.add(ctor);
        var writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS); generated.accept(writer);
        fixtureClass = MethodHandles.lookup().defineClass(writer.toByteArray()); return fixtureClass;
    }
    private static void put(Object fixture, String name, Object value) throws Exception { fixtureClass.getField(name).set(fixture, value); }
    private static void call(Object fixture) throws Throwable {
        try { fixtureClass.getMethod("prepareCandidateWork").invoke(fixture); }
        catch (InvocationTargetException error) { throw error.getCause(); }
    }
    public static BiConsumer<CubePos, RollingAdaptiveSliceIndependentTest.Mesh> consumer(Object ignored) { return INPUTS.get().owner::adopt; }
    public static void body(Object ignored) {var in=INPUTS.get();in.bodySlices++;if(in.bodyFails)throw new IllegalStateException("Revoked body proof");}
    public static void classify(Object ignored) { INPUTS.get().classified++; }
    public static boolean validate(Object ignored) { INPUTS.get().validated++; return INPUTS.get().valid; }
    public static void far(Object ignored) { INPUTS.get().farPreparations++; }
    public static String status(Object ignored, String value) { return value; }
    public static boolean distance(Object ignored) { return INPUTS.get().distance; }
}
