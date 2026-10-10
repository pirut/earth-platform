package io.earthplatform.geography.variety;

import io.earthplatform.geography.GeoPoint;
import io.earthplatform.geography.WorldManifest;
import java.io.*;
import java.util.*;

/** Explicit opt-in cosmetic displacement. Neither this profile nor its output represents measured geology. */
public record MicrodetailProfile(long seed, double maximumAmplitudeMetres,
                                 List<Region> regions, List<PreserveZone> preserveZones) {
    public static final String VERSION = "spherical-surface-microdetail-v1";
    public static final int MAX_REGIONS = 16;
    public MicrodetailProfile {
        if (!Double.isFinite(maximumAmplitudeMetres) || maximumAmplitudeMetres < 0 || maximumAmplitudeMetres > 2)
            throw new IllegalArgumentException("Cosmetic displacement amplitude must be in [0,2] metres");
        regions = boundedCopy(regions);
        preserveZones = boundedCopy(preserveZones);
        regions = regions.stream().sorted(Comparator.comparingDouble((Region r) -> r.centre().latitude())
            .thenComparingDouble(r -> r.centre().longitude()).thenComparingDouble(Region::radiusMetres)
            .thenComparingDouble(Region::fadeMetres)).distinct().toList();
        preserveZones = preserveZones.stream().sorted(Comparator.comparingDouble((PreserveZone r) -> r.centre().latitude())
            .thenComparingDouble(r -> r.centre().longitude()).thenComparingDouble(PreserveZone::radiusMetres)
            .thenComparingDouble(PreserveZone::fadeMetres)).distinct().toList();
        if (maximumAmplitudeMetres > 0 && regions.isEmpty()) throw new IllegalArgumentException("Enabled detail requires an explicit region");
        if (maximumAmplitudeMetres == 0) maximumAmplitudeMetres = 0; // canonical positive zero
    }
    private static <T> List<T> boundedCopy(List<T> source) {
        Objects.requireNonNull(source);
        if (source.size() > MAX_REGIONS) throw new IllegalArgumentException("Too many detail regions");
        ArrayList<T> copy = new ArrayList<>();
        for (T entry : source) {
            if (copy.size() >= MAX_REGIONS) throw new IllegalArgumentException("Too many detail regions");
            copy.add(Objects.requireNonNull(entry));
        }
        return List.copyOf(copy);
    }
    public boolean enabled() { return maximumAmplitudeMetres > 0; }
    public static MicrodetailProfile disabled() { return new MicrodetailProfile(0,0,List.of(),List.of()); }
    /** Example only: limited flank patch, bounded to 0.75 m, with a protected summit neighbourhood. */
    public static MicrodetailProfile everestExample(long seed) {
        return new MicrodetailProfile(seed,0.75,
            List.of(new Region(new GeoPoint(27.976,86.935),1000,256)),
            List.of(new PreserveZone(new GeoPoint(27.9881,86.925),256,128)));
    }
    public String fingerprint() {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream out = new DataOutputStream(bytes)) {
                out.writeUTF(VERSION); out.writeLong(seed); out.writeDouble(maximumAmplitudeMetres);
                out.writeInt(regions.size());
                for (Region r : regions) write(out,r.centre(),r.radiusMetres(),r.fadeMetres());
                out.writeInt(preserveZones.size());
                for (PreserveZone r : preserveZones) write(out,r.centre(),r.radiusMetres(),r.fadeMetres());
            }
            return WorldManifest.sha256(bytes.toByteArray());
        } catch (IOException impossible) { throw new AssertionError(impossible); }
    }
    private static void write(DataOutputStream out,GeoPoint centre,double radius,double fade) throws IOException {
        out.writeDouble(centre.latitude()); out.writeDouble(centre.longitude()); out.writeDouble(radius); out.writeDouble(fade);
    }
    public record Region(GeoPoint centre,double radiusMetres,double fadeMetres) {
        public Region { validate(centre,radiusMetres,fadeMetres); if (fadeMetres > radiusMetres) throw new IllegalArgumentException("Fade wider than region"); }
    }
    public record PreserveZone(GeoPoint centre,double radiusMetres,double fadeMetres) {
        public PreserveZone { validate(centre,radiusMetres,fadeMetres); }
    }
    private static void validate(GeoPoint centre,double radius,double fade) {
        Objects.requireNonNull(centre);
        if (!Double.isFinite(radius) || radius <= 0 || radius > 1_000_000 || !Double.isFinite(fade) || fade <= 0 || fade > 1_000_000)
            throw new IllegalArgumentException("Region radius/fade must be positive and at most 1000 km");
    }
}
