package io.earthplatform.minecraft.lod;

import io.earthplatform.core.CubePos;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Pure, bounded geometry for the native-height terrain proof. Readiness is a full
 * 3D volume, supplied by the caller from current, successfully published native
 * meshes. A published empty mesh owns its cube too; missing data owns nothing.
 * This class neither samples missing terrain nor invents faces around it.
 */
public final class DhTerrainGeometry {
    public static final int PAGE_HEIGHT = 2048;
    public static final int TILE_SIZE = 4096;
    public static final int MIN_Y = -11008;
    public static final int MAX_Y_EXCLUSIVE = 8864;
    public static final int MAX_READY_CUBES = 256;
    public static final int MAX_PIECES = 4096;

    public enum Direction { UP, DOWN, NORTH, SOUTH, WEST, EAST }

    /** Pinned stratified Earth face colour. The same function serves base and exact replacement. */
    public static int stratifiedFaceArgb(Direction direction,int argb) {
        Objects.requireNonNull(direction);
        if((argb>>>24)!=255)throw new IllegalArgumentException("Opaque stratified face colour required");
        float factor=switch(direction){case WEST,EAST->0.72f;case NORTH,SOUTH->0.85f;case UP,DOWN->1.0f;};
        return 0xff000000 | Math.round(((argb>>>16)&255)*factor)<<16
                |Math.round(((argb>>>8)&255)*factor)<<8|Math.round((argb&255)*factor);
    }


    /**
     * Native lower in-plane corner and face plane. Width/height are X/Z for
     * UP/DOWN, X/Y for NORTH/SOUTH, and Z/Y for WEST/EAST. The normal is outward
     * from the solid, so an UP face at y belongs to the solid just below y.
     */
    public record Face(Direction direction, long x, long y, long z,
                       int width, int height, int argb, int material) {
        public Face(Direction direction,long x,long y,long z,int width,int height,int argb) {
            this(direction,x,y,z,width,height,argb,0);
        }
        /** Preserve the frozen legacy diagnostic encoding; explicit categories remain visible. */
        @Override public String toString(){return "Face[direction="+direction+", x="+x+", y="+y+", z="+z+", width="+width+", height="+height+", argb="+argb+(material==0?"":", material="+material)+"]";}
        public Face {
            requireMaterial(material);
            Objects.requireNonNull(direction, "direction");
            if (width <= 0 || height <= 0) {
                throw new IllegalArgumentException("Face dimensions must be positive");
            }
            switch (direction) {
                case UP, DOWN -> {
                    add(x, width); add(z, height);
                    boolean inside = direction == Direction.UP
                            ? MIN_Y < y && y <= MAX_Y_EXCLUSIVE
                            : MIN_Y <= y && y < MAX_Y_EXCLUSIVE;
                    if (!inside) throw new IllegalArgumentException("Face solid is outside the native Y envelope");
                }
                case NORTH, SOUTH, WEST, EAST -> {
                    add(direction == Direction.NORTH || direction == Direction.SOUTH ? x : z, width);
                    if (y < MIN_Y || add(y, height) > MAX_Y_EXCLUSIVE) {
                        throw new IllegalArgumentException("Face span is outside the native Y envelope");
                    }
                }
            }
        }
    }

    /** All coordinates and dimensions are unsigned-short-safe; only pageY2048 is signed. */
    public record PageFace(Direction direction, int pageY2048, int x, int y, int z,
                           int width, int height, int argb, int material) {
        public PageFace(Direction direction,int pageY2048,int x,int y,int z,int width,int height,int argb) {
            this(direction,pageY2048,x,y,z,width,height,argb,0);
        }
        /** Material zero has exactly the pre-strata record text used by frozen geometry receipts. */
        @Override public String toString(){return "PageFace[direction="+direction+", pageY2048="+pageY2048+", x="+x+", y="+y+", z="+z+", width="+width+", height="+height+", argb="+argb+(material==0?"":", material="+material)+"]";}
        public PageFace {
            requireMaterial(material);
            Objects.requireNonNull(direction, "direction");
            requireTileCoordinate(x); requireTileCoordinate(z);
            if (width <= 0 || height <= 0 || width > TILE_SIZE || height > TILE_SIZE) {
                throw new IllegalArgumentException("Page face dimensions are outside the bounded tile");
            }
            switch (direction) {
                case UP, DOWN -> {
                    if (x + width > TILE_SIZE || z + height > TILE_SIZE
                            || (direction == Direction.UP ? y <= 0 || y > PAGE_HEIGHT : y < 0 || y >= PAGE_HEIGHT)) {
                        throw new IllegalArgumentException("Invalid horizontal page face");
                    }
                }
                case NORTH, SOUTH, WEST, EAST -> {
                    int u = direction == Direction.NORTH || direction == Direction.SOUTH ? x : z;
                    if (u + width > TILE_SIZE || y < 0 || y >= PAGE_HEIGHT || y + height > PAGE_HEIGHT) {
                        throw new IllegalArgumentException("Invalid vertical page face");
                    }
                }
            }
            // Also validates the native envelope, including solid-side ownership at its ends.
            new Face(direction, x, (long) pageY2048 * PAGE_HEIGHT + y, z, width, height, argb,material);
        }
    }

    /** Exact half-open XYZ ownership; no authority or native-readiness is implied. */
    public record Volume(long minX, long minY, long minZ,
                         long maxXExclusive, long maxYExclusive, long maxZExclusive) {
        public Volume {
            try {
                if (Math.subtractExact(maxXExclusive, minX) <= 0
                        || Math.subtractExact(maxYExclusive, minY) <= 0
                        || Math.subtractExact(maxZExclusive, minZ) <= 0) {
                    throw new IllegalArgumentException("Ownership volume must be nonempty");
                }
            } catch (ArithmeticException overflow) {
                throw new IllegalArgumentException("Ownership volume extent overflows", overflow);
            }
            if (minY < MIN_Y || maxYExclusive > MAX_Y_EXCLUSIVE) {
                throw new IllegalArgumentException("Ownership volume is outside the native Y envelope");
            }
        }

        public boolean contains(long x, long y, long z) {
            return minX <= x && x < maxXExclusive && minY <= y && y < maxYExclusive
                    && minZ <= z && z < maxZExclusive;
        }
    }

    /** Pinned opaque DH categories: legacy UNKNOWN, STONE and SNOW only. */
    private static void requireMaterial(int material) {
        if(material!=0&&material!=2&&material!=8)throw new IllegalArgumentException("Unsupported opaque DH material category");
    }

    private record Rect(long u0, long v0, long u1, long v1) { }

    private static final Comparator<Rect> VERTICAL_ORDER = Comparator.comparingLong(Rect::u0)
            .thenComparingLong(Rect::u1).thenComparingLong(Rect::v0).thenComparingLong(Rect::v1);
    private static final Comparator<Rect> HORIZONTAL_ORDER = Comparator.comparingLong(Rect::v0)
            .thenComparingLong(Rect::v1).thenComparingLong(Rect::u0).thenComparingLong(Rect::u1);

    private DhTerrainGeometry() { }

    /**
     * Subtracts only actual ready cube volumes. At a positive-normal plane p,
     * min &lt; p &lt;= max owns the face; negative normals use min &lt;= p &lt; max.
     * All inputs are validated even if an earlier cube completely covers the
     * face. The result is immutable and exact, or throws on a budget violation;
     * it is never truncated. An empty ready list leaves the face unchanged.
     */
    public static List<Face> clip(Face face, List<CubePos> actualReadyCubes, int maxPieces) {
        return clip(face, actualReadyCubes, List.of(), maxPieces);
    }

    /**
     * Subtracts native cubes and separately supplied exact XYZ replacement volumes.
     * Combined inputs share the existing 256-volume bound. A volume owns only the
     * solid side of a face, just like a ready cube; empty or unknown space is never
     * capped. Overlapping volumes form an exact union, not their bounding box.
     * Supplying volumes does not declare their source cubes native-ready.
     */
    public static List<Face> clip(Face face, List<CubePos> actualReadyCubes,
                                  List<Volume> replacementVolumes, int maxPieces) {
        Objects.requireNonNull(face, "face");
        Objects.requireNonNull(actualReadyCubes, "actualReadyCubes");
        Objects.requireNonNull(replacementVolumes, "replacementVolumes");
        if (maxPieces < 1 || maxPieces > MAX_PIECES) {
            throw new IllegalArgumentException("Face-piece budget must be in 1.." + MAX_PIECES);
        }
        Rect source = rectangle(face);
        var coverage = new ArrayList<Rect>();
        int count = 0;
        for (CubePos cube : actualReadyCubes) {
            if (++count > MAX_READY_CUBES) throw new IllegalArgumentException("Native readiness exceeds 256 cubes");
            Objects.requireNonNull(cube, "ready cube");
            if (cube.minBlockY() < MIN_Y || cube.minBlockY() + CubePos.SIZE > MAX_Y_EXCLUSIVE) {
                throw new IllegalArgumentException("Ready cube is outside the native Y envelope");
            }
            Rect projected = projectIfOwned(face, cube.minBlockX(), cube.minBlockY(), cube.minBlockZ(),
                    cube.minBlockX() + CubePos.SIZE, cube.minBlockY() + CubePos.SIZE, cube.minBlockZ() + CubePos.SIZE);
            if (projected != null) {
                Rect intersection = intersection(source, projected);
                if (intersection != null) coverage.add(intersection);
            }
        }
        for (Volume volume : replacementVolumes) {
            if (++count > MAX_READY_CUBES) throw new IllegalArgumentException("Combined ownership exceeds 256 volumes");
            Rect projected = projectIfOwned(face, Objects.requireNonNull(volume, "replacement volume"));
            if (projected != null) {
                Rect intersection = intersection(source, projected);
                if (intersection != null) coverage.add(intersection);
            }
        }
        // Exact rectangular unions remove repeated, adjacent or overlapping
        // coverage without replacing an L-shaped union by its bounding box.
        coverage = coalesce(coverage);
        var remaining = new ArrayList<Rect>();
        remaining.add(source);
        for (Rect cover : coverage) {
            var next = new ArrayList<Rect>();
            for (Rect piece : remaining) subtract(piece, cover, next);
            remaining = coalesce(next);
            if (remaining.isEmpty()) break;
        }
        if (remaining.size() > maxPieces) throw new IllegalArgumentException("Clipped face-piece budget exceeded");
        var result = new ArrayList<Face>(remaining.size());
        for (Rect piece : remaining) result.add(face(face, piece));
        return List.copyOf(result);
    }

    /**
     * Splits native Y at signed 2048-block pages. X/Z must fit the chosen
     * 4096-block tile, including all endpoints; values never wrap into a short.
     * UP owns floorDiv(y-1,2048), DOWN owns floorDiv(y,2048), and vertical spans
     * are half-open. Thus an UP face on a page boundary has local Y = 2048.
     */
    public static List<PageFace> splitPages(Face face, long tileOriginX, long tileOriginZ) {
        Objects.requireNonNull(face, "face");
        int x = tileLocal(face.x, tileOriginX);
        int z = tileLocal(face.z, tileOriginZ);
        switch (face.direction) {
            case UP, DOWN -> {
                if ((long) x + face.width > TILE_SIZE || (long) z + face.height > TILE_SIZE) {
                    throw new IllegalArgumentException("Face extends outside the selected X/Z tile");
                }
                int page = Math.toIntExact(Math.floorDiv(face.y - (face.direction == Direction.UP ? 1 : 0), PAGE_HEIGHT));
                return List.of(new PageFace(face.direction, page, x,
                        Math.toIntExact(face.y - (long) page * PAGE_HEIGHT), z, face.width, face.height, face.argb, face.material));
            }
            case NORTH, SOUTH, WEST, EAST -> {
                int u = face.direction == Direction.NORTH || face.direction == Direction.SOUTH ? x : z;
                if ((long) u + face.width > TILE_SIZE) {
                    throw new IllegalArgumentException("Face extends outside the selected X/Z tile");
                }
                var result = new ArrayList<PageFace>();
                long end = face.y + face.height;
                for (long y = face.y; y < end;) {
                    int page = Math.toIntExact(Math.floorDiv(y, PAGE_HEIGHT));
                    long next = Math.min(end, ((long) page + 1) * PAGE_HEIGHT);
                    result.add(new PageFace(face.direction, page, x,
                            Math.toIntExact(y - (long) page * PAGE_HEIGHT), z,
                            face.width, Math.toIntExact(next - y), face.argb, face.material));
                    y = next;
                }
                return List.copyOf(result);
            }
        }
        throw new AssertionError("Unknown face direction");
    }

    private static Rect projectIfOwned(Face face, Volume volume) {
        return projectIfOwned(face, volume.minX, volume.minY, volume.minZ,
                volume.maxXExclusive, volume.maxYExclusive, volume.maxZExclusive);
    }

    private static Rect projectIfOwned(Face face, long x, long y, long z, long maxX, long maxY, long maxZ) {
        return switch (face.direction) {
            case UP -> ownsPositive(face.y, y, maxY) ? new Rect(x, z, maxX, maxZ) : null;
            case DOWN -> ownsNegative(face.y, y, maxY) ? new Rect(x, z, maxX, maxZ) : null;
            case NORTH -> ownsNegative(face.z, z, maxZ) ? new Rect(x, y, maxX, maxY) : null;
            case SOUTH -> ownsPositive(face.z, z, maxZ) ? new Rect(x, y, maxX, maxY) : null;
            case WEST -> ownsNegative(face.x, x, maxX) ? new Rect(z, y, maxZ, maxY) : null;
            case EAST -> ownsPositive(face.x, x, maxX) ? new Rect(z, y, maxZ, maxY) : null;
        };
    }

    private static boolean ownsPositive(long plane, long min, long max) { return min < plane && plane <= max; }
    private static boolean ownsNegative(long plane, long min, long max) { return min <= plane && plane < max; }

    private static Rect rectangle(Face face) {
        return switch (face.direction) {
            case UP, DOWN -> new Rect(face.x, face.z, face.x + face.width, face.z + face.height);
            case NORTH, SOUTH -> new Rect(face.x, face.y, face.x + face.width, face.y + face.height);
            case WEST, EAST -> new Rect(face.z, face.y, face.z + face.width, face.y + face.height);
        };
    }

    private static Face face(Face source, Rect r) {
        int width = Math.toIntExact(r.u1 - r.u0), height = Math.toIntExact(r.v1 - r.v0);
        return switch (source.direction) {
            case UP, DOWN -> new Face(source.direction, r.u0, source.y, r.v0, width, height, source.argb, source.material);
            case NORTH, SOUTH -> new Face(source.direction, r.u0, r.v0, source.z, width, height, source.argb, source.material);
            case WEST, EAST -> new Face(source.direction, source.x, r.v0, r.u0, width, height, source.argb, source.material);
        };
    }

    private static Rect intersection(Rect a, Rect b) {
        long u0 = Math.max(a.u0, b.u0), v0 = Math.max(a.v0, b.v0);
        long u1 = Math.min(a.u1, b.u1), v1 = Math.min(a.v1, b.v1);
        return u0 < u1 && v0 < v1 ? new Rect(u0, v0, u1, v1) : null;
    }

    /** Disjoint left/right strips and bottom/top strips, at most four pieces. */
    private static void subtract(Rect source, Rect cover, ArrayList<Rect> result) {
        Rect cut = intersection(source, cover);
        if (cut == null) {
            append(result, source);
            return;
        }
        if (source.u0 < cut.u0) append(result, new Rect(source.u0, source.v0, cut.u0, source.v1));
        if (cut.u1 < source.u1) append(result, new Rect(cut.u1, source.v0, source.u1, source.v1));
        if (source.v0 < cut.v0) append(result, new Rect(cut.u0, source.v0, cut.u1, cut.v0));
        if (cut.v1 < source.v1) append(result, new Rect(cut.u0, cut.v1, cut.u1, source.v1));
    }

    private static void append(ArrayList<Rect> result, Rect rectangle) {
        if (result.size() == MAX_PIECES) throw new IllegalArgumentException("Geometry working-piece budget exceeded");
        result.add(rectangle);
    }

    /** Exact unions only: equal opposite edges and touching/overlapping intervals. */
    private static ArrayList<Rect> coalesce(ArrayList<Rect> rectangles) {
        int previousSize;
        do {
            previousSize = rectangles.size();
            rectangles = merge(rectangles, true);
            rectangles = merge(rectangles, false);
        } while (rectangles.size() < previousSize);
        return rectangles;
    }

    private static ArrayList<Rect> merge(ArrayList<Rect> rectangles, boolean vertical) {
        rectangles.sort(vertical ? VERTICAL_ORDER : HORIZONTAL_ORDER);
        var result = new ArrayList<Rect>(rectangles.size());
        for (Rect r : rectangles) {
            if (!result.isEmpty()) {
                int last = result.size() - 1;
                Rect p = result.get(last);
                if (vertical && p.u0 == r.u0 && p.u1 == r.u1 && r.v0 <= p.v1) {
                    result.set(last, new Rect(p.u0, p.v0, p.u1, Math.max(p.v1, r.v1)));
                    continue;
                }
                if (!vertical && p.v0 == r.v0 && p.v1 == r.v1 && r.u0 <= p.u1) {
                    result.set(last, new Rect(p.u0, p.v0, Math.max(p.u1, r.u1), p.v1));
                    continue;
                }
            }
            result.add(r);
        }
        return result;
    }

    private static long add(long coordinate, int length) {
        try { return Math.addExact(coordinate, length); }
        catch (ArithmeticException e) { throw new IllegalArgumentException("Face endpoint overflows native coordinates", e); }
    }

    private static int tileLocal(long coordinate, long origin) {
        final long local;
        try { local = Math.subtractExact(coordinate, origin); }
        catch (ArithmeticException e) { throw new IllegalArgumentException("Tile-relative coordinate overflows", e); }
        if (local < 0 || local > TILE_SIZE) throw new IllegalArgumentException("Coordinate is outside the selected X/Z tile");
        return (int) local;
    }

    private static void requireTileCoordinate(int coordinate) {
        if (coordinate < 0 || coordinate > TILE_SIZE) throw new IllegalArgumentException("Invalid tile coordinate");
    }
}
