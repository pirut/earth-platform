package io.earthplatform.minecraft.diagnostics;

import java.util.concurrent.atomic.AtomicLongArray;
import java.util.concurrent.atomic.AtomicReferenceArray;

/** Only fixed stages and exception TYPES: never messages, stacks, paths, names or addresses. */
public final class PerfFailures {
    public enum Stage { FAR_MESH, FAR_GENERATION, FAR_SHADER, FAR_UPLOAD, PACKET_DECODE, RUN_CONVERSION, LIGHTMAP_TEXTURE, DIAGNOSTICS }
    private static final AtomicLongArray COUNTS=new AtomicLongArray(Stage.values().length);
    private static final AtomicReferenceArray<String> TYPES=new AtomicReferenceArray<>(Stage.values().length);
    private PerfFailures() {}
    public static void record(Stage stage,Throwable failure) {
        if(!BuildInfo.diagnostics())return;
        COUNTS.incrementAndGet(stage.ordinal());TYPES.set(stage.ordinal(),safeType(failure));
    }
    public static long count(Stage stage){return COUNTS.get(stage.ordinal());}
    public static String type(Stage stage){return TYPES.get(stage.ordinal());}
    static String safeType(Throwable failure) {
        String name=failure.getClass().getName();
        return name.length()<=160&&name.matches("[A-Za-z0-9_.$]+")?name:"Throwable";
    }
}
