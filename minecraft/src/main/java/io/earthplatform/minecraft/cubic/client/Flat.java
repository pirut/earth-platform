package io.earthplatform.minecraft.cubic.client;

import static io.earthplatform.minecraft.cubic.client.FarMesher.DIRECTIONS;
import static io.earthplatform.minecraft.cubic.client.FarMesher.UNIT;

import com.mojang.blaze3d.vertex.BufferBuilder;
import io.earthplatform.minecraft.cubic.client.FarMesher.Rectangle;
import io.earthplatform.minecraft.cubic.client.FarMesher.WaterLook;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.core.Direction;

/**
 * Collects a column's up and down faces per voxel row and merges each row's matching faces
 * into rectangles, so flat ground and lakes cost a few faces instead of one per voxel.
 * {@link Builder} keeps two: one for opaque faces, packed for {@link FarGpu}, and one for
 * flat-colored water.
 */
final class Flat {
    /** Per direction (down, up) and voxel row, a 16 x 16 grid of merge ids, 0 for none. */
    private final Map<Integer, int[]>[] rows = newRows();
    private final List<Object> looks = new ArrayList<>();
    private final List<Integer> heights = new ArrayList<>();
    private final Map<Object, Integer> ids = new HashMap<>();

    @SuppressWarnings("unchecked") private static Map<Integer, int[]>[] newRows() { return new Map[] {new HashMap<>(), new HashMap<>()}; }

    void add(Direction direction, int x, int vy, int z, int height, Object look) {
        int[] grid = rows[direction.get3DDataValue()].computeIfAbsent(vy, unused -> new int[256]);
        List<Object> key = List.of(look, height);
        Integer id = ids.get(key);
        if (id == null) { id = looks.size() + 1; looks.add(look); heights.add(height); ids.put(key, id); }
        grid[z * 16 + x] = id;
    }

    /** Greedy rectangles: each run along x grows along z while the next rows match. */
    private void merge(Rectangle out) {
        for (int d = 0; d < 2; d++) {
            for (int[] grid : rows[d].values()) {
                for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
                    int id = grid[z * 16 + x];
                    if (id == 0) continue;
                    int width = 1;
                    while (x + width < 16 && grid[z * 16 + x + width] == id) width++;
                    int depth = 1;
                    rows:
                    while (z + depth < 16) {
                        for (int k = 0; k < width; k++) if (grid[(z + depth) * 16 + x + k] != id) break rows;
                        depth++;
                    }
                    for (int t = 0; t < depth; t++) for (int k = 0; k < width; k++) grid[(z + t) * 16 + x + k] = 0;
                    out.accept(DIRECTIONS[d], x, z, width, depth, heights.get(id - 1), looks.get(id - 1));
                }
            }
        }
    }

    void emitQuads(Builder builder, IntArrayList[] groups) {
        merge((direction, x, z, width, depth, height, look) ->
                builder.pack(groups[direction.get3DDataValue()], x, z, direction, width, height, depth - 1, (Long) look));
    }

    void emitWater(Builder builder) {
        float unit = 1.0F / UNIT;
        int v = builder.v;
        merge((direction, x, z, width, depth, height, look) -> {
            WaterLook water = (WaterLook) look;
            BufferBuilder out = builder.water();
            ShaderBlocks.begin(out, builder.job.shaderIds(), water.state(), true, x, 0, z);
            // Only flat-coloured water merges; textured levels draw each water face on its own.
            builder.face(out, water.state(), water.argb(), direction, x * v, height * unit, z * v, width * v, 0, depth * v,
                    LightTexture.FULL_SKY, false);
            ShaderBlocks.end(out, builder.job.shaderIds());
        });
    }
}
