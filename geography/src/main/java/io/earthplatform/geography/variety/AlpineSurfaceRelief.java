package io.earthplatform.geography.variety;

import io.earthplatform.geography.GeoPoint;
import io.earthplatform.geography.WorldManifest;
import io.earthplatform.geography.landcover.*;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;

/** Regional high-alpine cosmetic surface relief. Source DEM, cover and generated detail remain distinct. */
public final class AlpineSurfaceRelief {
    public static final String VERSION = "source-aligned-alpine-relief-v2";
    public static final String RIDGE_PREVIEW = "copernicus-alpine-ridge-v2";
    public static final double MAXIMUM_DISPLACEMENT_METRES = 2.0;
    private static final double RADIUS = 6_378_137;
    private static final long SEED = 0x4541525448524944L;
    private final MicrodetailProfile mask;
    private final String identity;
    public AlpineSurfaceRelief(MicrodetailProfile mask) {
        this.mask = Objects.requireNonNull(mask);
        identity = WorldManifest.sha256((VERSION + "\n" + mask.fingerprint()
                + "\nregion=high-himalayan-alpine;snow70-or-bare60;height=4800:6000;slope=0.15:0.65"
                + "\nexposure=snow-slope0.55:1.35,bare1;shelter-attenuation0.3"
                + "\nrock=13-taps-1.5m-triangular-downslope;noise=2.5m,5m;mix=0.8,0.2;tanh4"
                + "\nsnow=8m-value-noise-0.175;source-gradient-east-south-physical;quintic;seed=" + SEED)
                .getBytes(StandardCharsets.UTF_8));
    }
    public static AlpineSurfaceRelief ridgePreview() {
        return new AlpineSurfaceRelief(new MicrodetailProfile(SEED, MAXIMUM_DISPLACEMENT_METRES,
                List.of(new MicrodetailProfile.Region(new GeoPoint(27.959879063586857,86.91136144313555),512,128)),
                List.of(new MicrodetailProfile.PreserveZone(new GeoPoint(27.9881,86.925),256,128))));
    }
    public String identity() { return identity; }
    public MicrodetailProfile profile() { return mask; }
    /** East/south derivatives are metres rise per physical metre, estimated only from the original DEM. */
    public Sample sample(GeoPoint point, double sourceMetres, double sourceResolutionArcSeconds,
            double eastGradient, double southGradient, LandCoverSample cover, double shelter) {
        Objects.requireNonNull(point); Objects.requireNonNull(cover);
        if (!Double.isFinite(sourceMetres) || !Double.isFinite(sourceResolutionArcSeconds) || sourceResolutionArcSeconds <= 0
                || !Double.isFinite(eastGradient) || !Double.isFinite(southGradient)
                || !Double.isFinite(shelter) || shelter < 0 || shelter > 1)
            throw new IllegalArgumentException("Finite source height, resolution, gradient and bounded shelter required");
        double slope = StrictMath.hypot(eastGradient,southGradient);
        if (!Double.isFinite(slope)) throw new IllegalArgumentException("Unrepresentable slope");
        boolean eligible = cover.status() == LandCoverSample.Status.MAPPED
                && (cover.classification() == LandCoverClass.SNOW_AND_ICE || cover.classification() == LandCoverClass.BARE_SPARSE_VEGETATION);
        double weight = eligible ? maskWeight(point) * smooth((sourceMetres-4800)/1200) * smooth((slope-0.15)/0.5) : 0;
        if (weight == 0 || !mask.enabled()) return new Sample(sourceMetres,0,sourceMetres,0,identity);
        double lat = StrictMath.toRadians(point.latitude()), lon = StrictMath.toRadians(point.longitude());
        double cp = StrictMath.cos(lat), sp = StrictMath.sin(lat), cl = StrictMath.cos(lon), sl = StrictMath.sin(lon);
        double x = RADIUS*cp*cl, y = RADIUS*cp*sl, z = RADIUS*sp;
        double de = -eastGradient/slope, ds = -southGradient/slope;
        // Tangent-space downhill direction on the sphere. No tile-local phase or per-column orientation reseed.
        double dx = -sl*de + sp*cl*ds, dy = cl*de + sp*sl*ds, dz = -cp*ds;
        double fine = filtered(x,y,z,dx,dy,dz,2.5,mask.seed());
        double coarse = filtered(x,y,z,dx,dy,dz,5,mask.seed()+1);
        double rock = StrictMath.tanh(4*(0.8*fine+0.2*coarse));
        double snow = 0.175*noise(x/8,y/8,z/8,mask.seed()+2);
        double exposure = cover.classification() == LandCoverClass.BARE_SPARSE_VEGETATION ? 1 : smooth((slope-0.55)/0.8);
        exposure *= 1-0.3*shelter;
        double nativeMetres = RADIUS*StrictMath.toRadians(sourceResolutionArcSeconds/3600);
        double delta = mask.maximumAmplitudeMetres()*Math.min(1,nativeMetres/8)*weight*(snow+(rock-snow)*exposure);
        return new Sample(sourceMetres,delta,sourceMetres+delta,exposure,identity);
    }
    private double maskWeight(GeoPoint point) {
        double weight = 0;
        for (var region : mask.regions()) weight = Math.max(weight,smooth((region.radiusMetres()-SurfaceMicrodetail.distanceMetres(point,region.centre()))/region.fadeMetres()));
        for (var zone : mask.preserveZones()) weight = Math.min(weight,smooth((SurfaceMicrodetail.distanceMetres(point,zone.centre())-zone.radiusMetres())/zone.fadeMetres()));
        return weight;
    }
    /** Directional averaging creates long coherent ribs with narrow cross-slope variation, not speckle. */
    private static double filtered(double x,double y,double z,double dx,double dy,double dz,double spacing,long seed) {
        double sum = 0;
        for (int i = -6; i <= 6; i++) {
            double offset = i*1.5, weight = 7-Math.abs(i);
            sum += weight*noise((x+dx*offset)/spacing,(y+dy*offset)/spacing,(z+dz*offset)/spacing,seed);
        }
        return sum/49;
    }
    private static double noise(double x,double y,double z,long seed) {
        long ix=(long)StrictMath.floor(x),iy=(long)StrictMath.floor(y),iz=(long)StrictMath.floor(z);
        double fx=smooth(x-ix),fy=smooth(y-iy),fz=smooth(z-iz);
        double a=lerp(hash(ix,iy,iz,seed),hash(ix+1,iy,iz,seed),fx), b=lerp(hash(ix,iy+1,iz,seed),hash(ix+1,iy+1,iz,seed),fx);
        double c=lerp(hash(ix,iy,iz+1,seed),hash(ix+1,iy,iz+1,seed),fx), d=lerp(hash(ix,iy+1,iz+1,seed),hash(ix+1,iy+1,iz+1,seed),fx);
        return lerp(lerp(a,b,fy),lerp(c,d,fy),fz);
    }
    private static double hash(long x,long y,long z,long seed) {
        long h=seed ^ x*0x9E3779B97F4A7C15L ^ y*0xC2B2AE3D27D4EB4FL ^ z*0x165667B19E3779F9L;
        h=(h^(h>>>30))*0xBF58476D1CE4E5B9L; h=(h^(h>>>27))*0x94D049BB133111EBL; h^=h>>>31;
        return (h>>>11)*0x1.0p-52-1;
    }
    private static double lerp(double a,double b,double t) { return a+(b-a)*t; }
    private static double smooth(double t) { t=Math.max(0,Math.min(1,t)); return t*t*t*(t*(t*6-15)+10); }
    public record Sample(double sourceMetres,double proceduralDeltaMetres,double generatedMetres,double exposedRockWeight,String policyIdentity) {}
}
