package io.earthplatform.minecraft.enginebridge;

import io.earthplatform.core.CubePos;
import java.util.List;
import java.util.Objects;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Common-side census for the separately admitted opaque alpine model policy. The client proves
 * one unit quad per exposed face at the pinned 52-byte Iris stride before entry. The actor owns
 * all source reads and validates its 27 exact dependency revisions before calling this helper.
 * No cube array, source callback or proposed edit survives a call; nothing here writes a save.
 */
public final class EarthNativeEditAdmission {
    public static final String MODEL_POLICY = "earth-alpine-opaque-cube-model-v1";
    public static final int IRIS_STRIDE = 52, VERTICES_PER_FACE = 4;
    public static final int BYTES_PER_FACE = VERTICES_PER_FACE * IRIS_STRIDE;
    public static final int MAX_VERTEX_BYTES = 1_048_576, MAX_VERTICES = 32_768;
    public static final int MAX_FACES = Math.min(MAX_VERTEX_BYTES / BYTES_PER_FACE, MAX_VERTICES / VERTICES_PER_FACE);
    private EarthNativeEditAdmission() {}

    @FunctionalInterface public interface StateReader { int stateAt(long x, long y, long z); }
    public record Receipt(boolean admitted, int faces, long bytes, String reason) {}

    /** Exact default states only. The source and player placement share this narrow model contract. */
    public static List<BlockState> permittedStates() {
        return List.of(Blocks.AIR.defaultBlockState(), Blocks.STONE.defaultBlockState(),
                Blocks.ANDESITE.defaultBlockState(), Blocks.TUFF.defaultBlockState(),
                Blocks.SNOW_BLOCK.defaultBlockState(), Blocks.DIORITE.defaultBlockState());
    }
    public static boolean supported(BlockState state) {
        return state == Blocks.AIR.defaultBlockState() || state == Blocks.STONE.defaultBlockState()
                || state == Blocks.ANDESITE.defaultBlockState() || state == Blocks.TUFF.defaultBlockState()
                || state == Blocks.SNOW_BLOCK.defaultBlockState() || state == Blocks.DIORITE.defaultBlockState();
    }
    public static long bytesForFaces(int faces) {
        if (faces < 0) throw new IllegalArgumentException("Negative native face census");
        return Math.multiplyExact((long) faces, BYTES_PER_FACE);
    }
    public static void requireStride(int stride) {
        if (stride != IRIS_STRIDE) throw new IllegalStateException("Earth native census requires the exact 52-byte Iris terrain format");
    }
    public static Receipt assessCurrent(CubePos target, StateReader source) {
        return assess(target, source, false, 0, 0, 0, 0);
    }
    public static Receipt assessEdit(CubePos target, long x, long y, long z, int state, StateReader source) {
        if (!RollingEditPolicy.INTERIOR_V1.permits(target, x, y, z))
            throw new IllegalArgumentException("Earth native admission covers only the pinned cube interior");
        return assess(target, source, true, x, y, z, state);
    }
    private static Receipt assess(CubePos target, StateReader source, boolean edit,
            long editX, long editY, long editZ, int editState) {
        Objects.requireNonNull(target); Objects.requireNonNull(source);
        if (edit) classify(editState);
        int faces = 0;
        for (int y = 0; y < 16; y++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
            long ax = Math.addExact(target.minBlockX(), x), ay = Math.addExact(target.minBlockY(), y), az = Math.addExact(target.minBlockZ(), z);
            if (read(source, edit, editX, editY, editZ, editState, ax, ay, az) == 0) continue;
            // All six neighbors are authoritative scalar reads, including the immutable shell's
            // outside neighbors. Different opaque materials still occlude each other completely.
            if (read(source, edit, editX, editY, editZ, editState, ax - 1, ay, az) == 0) faces++;
            if (read(source, edit, editX, editY, editZ, editState, ax + 1, ay, az) == 0) faces++;
            if (read(source, edit, editX, editY, editZ, editState, ax, ay - 1, az) == 0) faces++;
            if (read(source, edit, editX, editY, editZ, editState, ax, ay + 1, az) == 0) faces++;
            if (read(source, edit, editX, editY, editZ, editState, ax, ay, az - 1) == 0) faces++;
            if (read(source, edit, editX, editY, editZ, editState, ax, ay, az + 1) == 0) faces++;
        }
        long bytes = bytesForFaces(faces);
        boolean admitted = faces <= MAX_FACES;
        return new Receipt(admitted, faces, bytes, admitted ? "Exact opaque Earth native census admitted"
                : "Earth edit exceeds the native 1 MiB / 52-byte terrain census: " + faces + " faces, " + bytes + " bytes");
    }
    private static int read(StateReader source, boolean edit, long ex, long ey, long ez, int es, long x, long y, long z) {
        return classify(edit && x == ex && y == ey && z == ez ? es : source.stateAt(x, y, z));
    }
    private static int classify(int id) {
        BlockState state = NativeCubeCodec.registeredState(id);
        if (!supported(state)) throw new IllegalArgumentException("Unsupported Earth native material: " + state);
        return state == Blocks.AIR.defaultBlockState() ? 0 : 1;
    }
}
