package io.earthplatform.geography;

import io.earthplatform.core.*;
import java.util.*;
import java.util.concurrent.*;

/** Independent 16^3 cubes. Surface heights are measured/modelled; rock, soil and negative-height water fill are procedural. */
public final class GeographyCubeProvider implements CubeProvider {
    public static final String GENERATOR_VERSION = "surface-fill-v1";
    private final EquirectangularProjection projection;
    private final ElevationProvider elevation;
    private final MaterialPalette palette;
    private final int maxColumns;
    private final LinkedHashMap<Column,long[]> columns = new LinkedHashMap<>(16,0.75f,true);
    private final Map<Column,CompletableFuture<long[]>> pendingColumns = new HashMap<>();
    private long elevationBatches;
    private record Column(int x,int z) {}
    public GeographyCubeProvider(EquirectangularProjection projection,ElevationProvider elevation,MaterialPalette palette) {
        this(projection,elevation,palette,256);
    }
    public GeographyCubeProvider(EquirectangularProjection projection,ElevationProvider elevation,MaterialPalette palette,int maxColumns) {
        this.projection = Objects.requireNonNull(projection); this.elevation = Objects.requireNonNull(elevation); this.palette = Objects.requireNonNull(palette);
        if (maxColumns < 0 || maxColumns > 65536) throw new IllegalArgumentException("Invalid column cache budget");
        this.maxColumns = maxColumns;
    }
    @Override public CompletableFuture<ImmutableCube> load(CubePos position,CancellationToken cancellation) {
        try {
            cancellation.throwIfCancelled();
            return heights(position).thenApply(heights -> fromHeights(position,heights,palette,cancellation));
        } catch (RuntimeException e) { return CompletableFuture.failedFuture(e); }
    }
    private CompletableFuture<long[]> heights(CubePos position) {
        Column key = new Column(position.x(),position.z());
        CompletableFuture<long[]> result;
        synchronized (this) {
            long[] hit = columns.get(key);
            if (hit != null) return CompletableFuture.completedFuture(hit);
            var pending = pendingColumns.get(key);
            if (pending != null) return pending;
            if (pendingColumns.size() >= 128) return CompletableFuture.failedFuture(new RejectedExecutionException("Geography column in-flight limit reached"));
            result = new CompletableFuture<>(); pendingColumns.put(key,result); elevationBatches++;
        }
        try {
            var points = new ArrayList<GeoPoint>(256);
            for (int z=0;z<16;z++) for (int x=0;x<16;x++) points.add(projection.blockCentre(position.minBlockX()+x,position.minBlockZ()+z));
            // Shared column work uses its own lifetime; one departing cube must not poison other vertical cubes.
            elevation.sample(points,new CancellationToken()).whenComplete((samples,failure) -> {
                if (failure != null) { synchronized(this) { pendingColumns.remove(key); } result.completeExceptionally(failure); return; }
                try {
                    if (samples.size()!=256) throw new IllegalStateException("Elevation provider returned wrong sample count");
                    long[] heights = new long[256];
                    for (int i=0;i<256;i++) heights[i] = surfaceY(samples.get(i).metres());
                    synchronized(this) {
                        if (maxColumns>0) {
                            columns.put(key,heights);
                            while (columns.size()>maxColumns) { var it=columns.entrySet().iterator();it.next();it.remove(); }
                        }
                        pendingColumns.remove(key);
                    }
                    result.complete(heights);
                } catch (RuntimeException e) { synchronized(this) { pendingColumns.remove(key); } result.completeExceptionally(e); }
            });
        } catch (RuntimeException e) { synchronized(this) { pendingColumns.remove(key); } result.completeExceptionally(e); }
        return result;
    }
    public synchronized ColumnStats columnStats() { return new ColumnStats(columns.size(),(long)columns.size()*256*Long.BYTES,pendingColumns.size(),elevationBatches); }
    public record ColumnStats(int columns,long payloadBytes,int inFlight,long elevationBatches) {}
    static long surfaceY(double metres) {
        if (!Double.isFinite(metres) || metres < Long.MIN_VALUE + 4096.0 || metres > Long.MAX_VALUE - 4096.0)
            throw new IllegalArgumentException("Elevation outside supported block coordinates");
        return (long) StrictMath.floor(metres);
    }
    static ImmutableCube fromHeights(CubePos position,long[] heights,MaterialPalette palette,CancellationToken cancellation) {
        cancellation.throwIfCancelled();
        long min = Arrays.stream(heights).min().orElseThrow(), max = Arrays.stream(heights).max().orElseThrow();
        long bottom = position.minBlockY(), top = bottom + 15;
        if (bottom > Math.max(max,-1)) return ImmutableCube.uniform(palette.air());
        if (top < min-3) return ImmutableCube.uniform(palette.stone());
        if (bottom > max && top < 0) return ImmutableCube.uniform(palette.water());
        int[] cells = new int[CubePos.VOLUME];
        for (int y=0;y<16;y++) {
            cancellation.throwIfCancelled();
            long worldY = bottom+y;
            for (int z=0;z<16;z++) for (int x=0;x<16;x++) {
                long ground = heights[z*16+x];
                int state;
                if (worldY > ground) state = worldY < 0 ? palette.water() : palette.air();
                else if (worldY < ground-3) state = palette.stone();
                else if (ground < 0) state = palette.gravel();
                else state = worldY==ground ? palette.grass() : palette.dirt();
                cells[CubePos.index(x,y,z)] = state;
            }
        }
        return ImmutableCube.copyOf(cells);
    }
}
