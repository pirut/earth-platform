package io.earthplatform.minecraft.cubic.client;

import java.util.Arrays;

/** Exact terrain maxima for one renderer's cloud projection grid. Render-thread owned. */
final class CloudTerrainGrid {
    @FunctionalInterface interface Top { void accept(int x, int z, int width, int height); }
    @FunctionalInterface interface Source { void visit(Top top); }

    private int grid;
    private long revision, leftBits, nearBits, stepBits;
    private boolean valid;
    private int[] cells = new int[0], corners = new int[0];

    /** Read-only until the next call. Deck height, wind and cloud opacity are deliberately absent. */
    int[] corners(long revision, int grid, double left, double near, double step, Source source) {
        if (grid <= 0) throw new IllegalArgumentException("Cloud grid must be positive");
        boolean finite = Double.isFinite(left) && Double.isFinite(near) && Double.isFinite(step) && step > 0;
        long leftBits = Double.doubleToRawLongBits(left), nearBits = Double.doubleToRawLongBits(near), stepBits = Double.doubleToRawLongBits(step);
        if (finite && valid && this.grid == grid && this.revision == revision
                && this.leftBits == leftBits && this.nearBits == nearBits && this.stepBits == stepBits) return corners;
        valid = false;
        if (this.grid != grid) {
            cells = new int[Math.multiplyExact(grid, grid)];
            corners = new int[Math.multiplyExact(grid + 1, grid + 1)];
            this.grid = grid;
        }
        Arrays.fill(cells, Integer.MIN_VALUE);
        source.visit((x0, z0, width, top) -> {
            // Keep the original integer addition, floating-point stepping and inclusive bounds.
            int i0 = Math.max(0, (int) Math.floor((x0 - left) / step)), i1 = Math.min(grid - 1, (int) Math.floor((x0 + width - left) / step));
            int j0 = Math.max(0, (int) Math.floor((z0 - near) / step)), j1 = Math.min(grid - 1, (int) Math.floor((z0 + width - near) / step));
            for (int i = i0; i <= i1; i++)
                for (int j = j0; j <= j1; j++)
                    if (top > cells[i * grid + j]) cells[i * grid + j] = top;
        });
        for (int i = 0; i <= grid; i++) {
            for (int j = 0; j <= grid; j++) {
                int top = Integer.MIN_VALUE;
                for (int ci = i - 1; ci <= i; ci++)
                    for (int cj = j - 1; cj <= j; cj++)
                        if (ci >= 0 && cj >= 0 && ci < grid && cj < grid) top = Math.max(top, cells[ci * grid + cj]);
                corners[i * (grid + 1) + j] = top;
            }
        }
        this.revision = revision;
        this.leftBits = leftBits;
        this.nearBits = nearBits;
        this.stepBits = stepBits;
        valid = finite;
        return corners;
    }

    void invalidate() { valid = false; }
}
