package io.earthplatform.minecraft.cubic.play.client;

import io.earthplatform.geography.globe.NamedThreads;
import io.earthplatform.geography.globe.WorldCoverSource;
import io.earthplatform.minecraft.cubic.earth.CubicSettings;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.lang.ref.SoftReference;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import javax.imageio.ImageIO;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;

/**
 * What the globe picker shows: NASA's Blue Marble for the whole planet, and close up, Web Mercator tiles drawn from
 * the same elevation and land cover the world is built from, shaded so mountains and valleys stand out. Detail tiles
 * download in the background into the game's shared cache, so a spot looked at here is already cached when the world
 * starts there.
 */
final class GlobeImagery {
    /** Coarsest and finest detail tile zoom; the Blue Marble alone is sharper than anything coarser. */
    static final int MIN_DETAIL = 3, MAX_DETAIL = 13;
    /** Land cover colours the tiles from this zoom on, fully from {@link #COVER_FULL}. */
    private static final int COVER_FROM = 7, COVER_FULL = 10;
    private static final int MAX_TILES = 192, WORKERS = 6;
    private static final ResourceLocation BASE = ResourceLocation.fromNamespaceAndPath("earthplatform", "textures/globe/blue_marble.jpg");
    private static SoftReference<Base> base = new SoftReference<>(null);

    private record Base(int[] argb, int width, int height) {}

    private final Base world;
    private final LinkedHashMap<Long, int[]> ready = new LinkedHashMap<>(256, 0.75f, true);
    private final Set<Long> loading = new HashSet<>(), failed = new HashSet<>();
    private final Deque<Long> queue = new ArrayDeque<>();
    private final ExecutorService workers;
    private volatile boolean changed, closed;
    private volatile int unreachable;

    GlobeImagery() {
        world = loadBase();
        ThreadPoolExecutor pool = new ThreadPoolExecutor(WORKERS, WORKERS, 10, TimeUnit.SECONDS, new LinkedBlockingQueue<>(),
                NamedThreads.daemon("earth-globe-tiles"));
        pool.allowCoreThreadTimeOut(true);
        workers = pool;
    }

    private static synchronized Base loadBase() {
        Base loaded = base.get();
        if (loaded != null) return loaded;
        try (InputStream in = Minecraft.getInstance().getResourceManager().open(BASE)) {
            BufferedImage image = ImageIO.read(in);
            loaded = new Base(image.getRGB(0, 0, image.getWidth(), image.getHeight(), null, 0, image.getWidth()), image.getWidth(), image.getHeight());
        } catch (IOException | RuntimeException failure) {
            // Without the picture the globe is still usable: plain blue, with land drawn as detail loads.
            loaded = new Base(new int[]{0xFF1C3F78}, 1, 1);
        }
        base = new SoftReference<>(loaded);
        return loaded;
    }

    /** The Blue Marble colour at a point, blended between its four nearest pixels. */
    int base(double lat, double lon) {
        Base b = world;
        double u = (lon + 180) / 360 * b.width - 0.5, v = (90 - lat) / 180 * b.height - 0.5;
        int x0 = (int) Math.floor(u), y0 = (int) Math.floor(v);
        double fx = u - x0, fy = v - y0;
        int y1 = Math.min(b.height - 1, Math.max(0, y0 + 1));
        y0 = Math.min(b.height - 1, Math.max(0, y0));
        int x1 = Math.floorMod(x0 + 1, b.width);
        x0 = Math.floorMod(x0, b.width);
        int a = b.argb[y0 * b.width + x0], c = b.argb[y0 * b.width + x1], d = b.argb[y1 * b.width + x0], e = b.argb[y1 * b.width + x1];
        return mix(mix(a, c, fx), mix(d, e, fx), fy);
    }

    /** A loaded detail tile, or null; never waits. */
    int[] tile(int zoom, int x, int y) {
        synchronized (ready) { return ready.get(key(zoom, x, y)); }
    }

    boolean failed(int zoom, int x, int y) {
        synchronized (ready) { return failed.contains(key(zoom, x, y)); }
    }

    /** Asks for exactly these tiles, nearest the centre first; anything asked for earlier and not started is dropped. */
    void want(Iterable<Long> keys) {
        synchronized (ready) {
            queue.clear();
            for (long key : keys) if (!ready.containsKey(key) && !loading.contains(key) && !failed.contains(key)) queue.add(key);
            while (loading.size() < WORKERS && !queue.isEmpty()) start(queue.poll());
        }
    }

    /** Detail tiles still downloading or waiting to. */
    int pending() {
        synchronized (ready) { return loading.size() + queue.size(); }
    }

    /** True once a tile could not be downloaded, until one can. */
    boolean unreachable() { return unreachable > 0; }

    /** True once since the last call if a tile finished loading. */
    boolean takeChanged() {
        boolean was = changed;
        changed = false;
        return was;
    }

    void close() {
        closed = true;
        synchronized (ready) { queue.clear(); ready.clear(); }
        workers.shutdownNow();
    }

    private void start(long key) {
        loading.add(key);
        workers.execute(() -> {
            int[] pixels = null;
            try {
                if (!closed) pixels = build((int) (key >>> 58), (int) (key >>> 29 & 0x1FFFFFFF), (int) (key & 0x1FFFFFFF));
                unreachable = 0;
            } catch (RuntimeException failure) {
                unreachable++;
            }
            synchronized (ready) {
                loading.remove(key);
                if (closed) return;
                if (pixels == null) failed.add(key);
                else {
                    ready.put(key, pixels);
                    var oldest = ready.entrySet().iterator();
                    while (ready.size() > MAX_TILES && oldest.hasNext()) { oldest.next(); oldest.remove(); }
                }
                while (loading.size() < WORKERS && !queue.isEmpty()) start(queue.poll());
            }
            changed = true;
        });
    }

    /** One 256-pixel Web Mercator tile: Blue Marble, then land cover up close, lit by the ground's slope. */
    private int[] build(int zoom, int x, int y) {
        float[] metres = CubicSettings.tiles().tile(zoom, x, y);
        double n = 1 << zoom;
        double cover = Math.min(1, Math.max(0, (zoom - COVER_FROM + 1) / (double) (COVER_FULL - COVER_FROM + 1)));
        // Land cover read at about the tile's own pixel size.
        double degreesPerPixel = 360 / (256 * n);
        int overview = Math.max(0, Math.min(WorldCoverSource.MAX_OVERVIEW, (int) Math.round(Math.log(degreesPerPixel * WorldCoverSource.PIXELS_PER_DEGREE) / Math.log(2))));
        WorldCoverSource land = cover > 0 ? CubicSettings.cover() : null;
        // Mountains are exaggerated when far away, or a whole range would look flat.
        double exaggeration = zoom < 6 ? 3 : zoom < 9 ? 2 : 1.4;
        int[] out = new int[256 * 256];
        for (int row = 0; row < 256; row++) {
            double lat = Math.toDegrees(Math.atan(Math.sinh(Math.PI * (1 - 2 * (y + (row + 0.5) / 256) / n))));
            double metresPerPixel = 40_075_016.686 * Math.cos(Math.toRadians(lat)) / (256 * n);
            for (int col = 0; col < 256; col++) {
                double lon = (x + (col + 0.5) / 256) / n * 360 - 180;
                int i = row * 256 + col;
                float h = metres[i];
                int colour = base(lat, lon);
                int code = WorldCoverSource.NO_DATA;
                if (land != null && h > -50) {
                    try {
                        code = land.code(lat, lon, overview);
                    } catch (RuntimeException missing) {
                        land = null;
                    }
                }
                boolean water = code == WorldCoverSource.WATER || (code == WorldCoverSource.NO_DATA && h <= 0);
                if (code != WorldCoverSource.NO_DATA) colour = mix(colour, coverColour(code), cover * 0.85);
                if (!water) {
                    double east = (metres[row * 256 + Math.min(255, col + 1)] - metres[row * 256 + Math.max(0, col - 1)]) / (2 * metresPerPixel);
                    double north = (metres[Math.max(0, row - 1) * 256 + col] - metres[Math.min(255, row + 1) * 256 + col]) / (2 * metresPerPixel);
                    colour = shade(colour, east * exaggeration, north * exaggeration);
                }
                out[i] = colour;
            }
        }
        return out;
    }

    /** Lights a colour by the ground's slope, from the north-west; flat ground keeps its colour. */
    private static int shade(int colour, double east, double north) {
        double nx = -east, ny = -north, nz = 1, length = Math.sqrt(nx * nx + ny * ny + 1);
        double lx = -0.6, ly = 0.6, lz = 0.53;
        double light = (nx * lx + ny * ly + nz * lz) / length / lz;
        return scale(colour, Math.max(0.35, Math.min(1.45, light)));
    }

    /** Natural colours for ESA WorldCover classes, near what they look like from orbit. */
    private static int coverColour(int code) {
        return switch (code) {
            case WorldCoverSource.TREE_COVER -> 0xFF2F4A23;
            case WorldCoverSource.SHRUBLAND -> 0xFF6E6A3E;
            case WorldCoverSource.GRASSLAND -> 0xFF7F8F4C;
            case WorldCoverSource.CROPLAND -> 0xFF8E9A5A;
            // Earth worlds have no cities: built-up land is drawn as the grass it becomes.
            case WorldCoverSource.BUILT_UP -> 0xFF7F8F4C;
            case WorldCoverSource.BARE -> 0xFFC2AE88;
            case WorldCoverSource.SNOW_AND_ICE -> 0xFFF0F3F7;
            case WorldCoverSource.WATER -> 0xFF1E3D66;
            case WorldCoverSource.WETLAND -> 0xFF4C6A48;
            case WorldCoverSource.MANGROVES -> 0xFF2A5236;
            case WorldCoverSource.MOSS_AND_LICHEN -> 0xFF8C8A68;
            default -> 0xFF808080;
        };
    }

    static long key(int zoom, int x, int y) { return (long) zoom << 58 | (long) x << 29 | y; }

    static int mix(int a, int b, double t) {
        int r = (int) Math.round(((a >> 16) & 0xFF) * (1 - t) + ((b >> 16) & 0xFF) * t);
        int g = (int) Math.round(((a >> 8) & 0xFF) * (1 - t) + ((b >> 8) & 0xFF) * t);
        int bl = (int) Math.round((a & 0xFF) * (1 - t) + (b & 0xFF) * t);
        return 0xFF000000 | r << 16 | g << 8 | bl;
    }

    static int scale(int colour, double factor) {
        int r = Math.min(255, (int) (((colour >> 16) & 0xFF) * factor));
        int g = Math.min(255, (int) (((colour >> 8) & 0xFF) * factor));
        int b = Math.min(255, (int) ((colour & 0xFF) * factor));
        return 0xFF000000 | r << 16 | g << 8 | b;
    }
}
