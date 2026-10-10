package io.earthplatform.geography.variety;

import io.earthplatform.geography.GeoPoint;
import java.util.Objects;

/** Stateless bounded cosmetic noise on a sphere, continuous at the antimeridian and independent of tile/cube boundaries. */
public final class SurfaceMicrodetail {
    private static final double RADIUS = 6_378_137;
    public static final double WATER_PROTECTION_HEIGHT = 32;
    public static final double FULL_DETAIL_HEIGHT = 96;
    private SurfaceMicrodetail() {}
    public static double displacement(GeoPoint point,double baseMetres,double resolutionArcSeconds,MicrodetailProfile profile) {
        Objects.requireNonNull(point); Objects.requireNonNull(profile);
        if (!Double.isFinite(baseMetres) || !Double.isFinite(resolutionArcSeconds) || resolutionArcSeconds <= 0)
            throw new IllegalArgumentException("Finite elevation and positive source resolution required");
        if (!profile.enabled() || baseMetres <= WATER_PROTECTION_HEIGHT) return 0;
        double regionWeight = 0;
        for (var region : profile.regions()) {
            double d = distanceMetres(point,region.centre());
            regionWeight = Math.max(regionWeight,smooth((region.radiusMetres()-d)/region.fadeMetres()));
        }
        if (regionWeight == 0) return 0;
        for (var preserve : profile.preserveZones()) {
            double d = distanceMetres(point,preserve.centre());
            regionWeight = Math.min(regionWeight,smooth((d-preserve.radiusMetres())/preserve.fadeMetres()));
        }
        if (regionWeight == 0) return 0;
        double heightWeight = smooth((baseMetres-WATER_PROTECTION_HEIGHT)/(FULL_DETAIL_HEIGHT-WATER_PROTECTION_HEIGHT));
        double nativeMetres = RADIUS * StrictMath.toRadians(resolutionArcSeconds/3600);
        // Approximate north/south native spacing determines scale. Coarse grids never receive larger invented hills.
        double spacing = Math.max(4,Math.min(12,nativeMetres/4));
        double amplitude = profile.maximumAmplitudeMetres() * Math.min(1,nativeMetres/8) * regionWeight * heightWeight;
        double lat = StrictMath.toRadians(point.latitude()), lon = StrictMath.toRadians(point.longitude());
        double cosLat = Math.abs(point.latitude()) == 90 ? 0 : StrictMath.cos(lat);
        double x = RADIUS*cosLat*StrictMath.cos(lon), y = RADIUS*cosLat*StrictMath.sin(lon), z = RADIUS*StrictMath.sin(lat);
        double noise = (4*noise(x/spacing,y/spacing,z/spacing,profile.seed())
            +2*noise(x/(spacing*2),y/(spacing*2),z/(spacing*2),profile.seed()+1)
            +noise(x/(spacing*4),y/(spacing*4),z/(spacing*4),profile.seed()+2))/7;
        return amplitude * Math.max(-1,Math.min(1,noise));
    }
    /** Cosmetic mask distance on the same sphere as the v1 projection, not a geodetic survey distance. */
    public static double distanceMetres(GeoPoint a,GeoPoint b) {
        double p=StrictMath.toRadians(a.latitude()),q=StrictMath.toRadians(b.latitude());
        double dp=(q-p)/2, dl=StrictMath.toRadians(GeoPoint.wrapLongitude(b.longitude()-a.longitude()))/2;
        double s=StrictMath.sin(dp), t=StrictMath.sin(dl);
        double h=Math.max(0,Math.min(1,s*s+StrictMath.cos(p)*StrictMath.cos(q)*t*t));
        return 2*RADIUS*StrictMath.atan2(StrictMath.sqrt(h),StrictMath.sqrt(1-h));
    }
    private static double smooth(double t) { t=Math.max(0,Math.min(1,t)); return t*t*t*(t*(t*6-15)+10); }
    private static double noise(double x,double y,double z,long seed) {
        long ix=(long)StrictMath.floor(x),iy=(long)StrictMath.floor(y),iz=(long)StrictMath.floor(z);
        double fx=smooth(x-ix),fy=smooth(y-iy),fz=smooth(z-iz);
        double a=lerp(lattice(ix,iy,iz,seed),lattice(ix+1,iy,iz,seed),fx);
        double b=lerp(lattice(ix,iy+1,iz,seed),lattice(ix+1,iy+1,iz,seed),fx);
        double c=lerp(lattice(ix,iy,iz+1,seed),lattice(ix+1,iy,iz+1,seed),fx);
        double d=lerp(lattice(ix,iy+1,iz+1,seed),lattice(ix+1,iy+1,iz+1,seed),fx);
        return lerp(lerp(a,b,fy),lerp(c,d,fy),fz);
    }
    private static double lerp(double a,double b,double t) { return a+(b-a)*t; }
    private static double lattice(long x,long y,long z,long seed) {
        long h=seed ^ x*0x9E3779B97F4A7C15L ^ y*0xC2B2AE3D27D4EB4FL ^ z*0x165667B19E3779F9L;
        h=(h^(h>>>30))*0xBF58476D1CE4E5B9L; h=(h^(h>>>27))*0x94D049BB133111EBL; h^=h>>>31;
        return (h>>>11)*0x1.0p-52-1;
    }
}
