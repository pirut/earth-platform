package io.earthplatform.minecraft.cubic.earth;

import io.earthplatform.geography.GeoPoint;

/**
 * The boxes of latitude and longitude that worlds made before build 70 placed red deserts, lava
 * fields, cacti, bamboo, cherries and giant sequoias by. They are kept only so those worlds go on
 * generating new ground that matches what they have saved: new worlds read the same things from
 * data ({@link EarthMaps}, ecoregion realms, climate and canopy height), and nothing new belongs
 * here.
 */
final class OldRegions {
    private OldRegions() {}

    /** Australia's arid interior, the red centre. */
    static boolean outback(GeoPoint p) {
        return p.latitude() > -32 && p.latitude() < -14 && p.longitude() > 114 && p.longitude() < 150;
    }

    /** The Colorado Plateau, whose bare ground is red rock and red sand. */
    static boolean redRock(GeoPoint p) {
        // The Grand Canyon and its rims keep the canyon strata's own colours.
        if (p.latitude() > 35.8 && p.latitude() < 36.7 && p.longitude() < -111.5) return false;
        return p.latitude() > 35 && p.latitude() < 38.5 && p.longitude() > -113 && p.longitude() < -108;
    }

    /** About how much of a hot desert's sand is orange: most in Australia, much in the Sahara and Arabia, little in the Americas. */
    static double redShare(GeoPoint p) {
        double lat = p.latitude(), lon = p.longitude();
        if (lon > 110 && lon < 155 && lat < -10) return 0.7;
        // Orange ergs: Morocco's and the western Sahara's, the Namib's and Arabia's. Algeria's and Libya's are pale.
        if (lon > -20 && lon < 0 && lat > 10) return 0.5;
        if (lon > 10 && lon < 20 && lat < -15) return 0.5;
        if (lon > 35 && lon < 60 && lat < 32) return 0.5;
        return 0.05;
    }

    /** Where bamboo forests grow, in South and East Asia. */
    static boolean bamboo(GeoPoint p) {
        return p.longitude() > 68 && p.longitude() < 150 && p.latitude() > -10 && p.latitude() < 40;
    }

    /** Where cherries grow wild: Japan, Korea, China and the Himalaya. */
    static boolean cherries(GeoPoint p) {
        double lat = p.latitude(), lon = p.longitude();
        return lon > 100 && lon < 146 && lat > 24 && lat < 46 || lon > 74 && lon <= 100 && lat > 25 && lat < 36;
    }

    /** The Americas, the only place cacti grow wild. */
    static boolean americas(GeoPoint p) {
        return p.longitude() < -30 && p.longitude() > -170;
    }

    /**
     * The world's great young lava fields, which are broad and low and so not told by their shape: Hawaii, Iceland,
     * the Canaries, the Galápagos, Etna and Réunion.
     */
    private static final double[][] LAVA = {
            {18.8, 22.3, -160.5, -154.7}, {63.2, 66.6, -24.6, -13.4}, {27.6, 29.5, -18.2, -13.3},
            {-1.5, 0.7, -92.0, -89.0}, {37.6, 37.9, 14.8, 15.2}, {-21.4, -20.8, 55.2, 55.9}};

    static boolean lava(GeoPoint p) {
        for (double[] box : LAVA) if (p.latitude() > box[0] && p.latitude() < box[1] && p.longitude() > box[2] && p.longitude() < box[3]) return true;
        return false;
    }

    /**
     * Giant sequoia groves: the larger real groves on the Sierra Nevada's western slope, as
     * latitude, longitude and radius in metres (centres and sizes approximate).
     */
    private static final double[][] GROVES = {
            {38.2777, -120.3080, 500},   // Calaveras North Grove
            {38.2460, -120.2480, 1200},  // Calaveras South Grove
            {37.7590, -119.8060, 400},   // Tuolumne Grove
            {37.7480, -119.8410, 400},   // Merced Grove
            {37.5110, -119.6000, 1100},  // Mariposa Grove
            {37.4300, -119.5850, 900},   // Nelder Grove
            {37.0230, -119.1080, 400},   // McKinley Grove
            {36.8150, -118.9550, 1500},  // Converse Basin
            {36.7470, -118.9720, 700},   // Grant Grove
            {36.6950, -118.9200, 1900},  // Redwood Mountain Grove
            {36.6380, -118.8170, 500},   // Muir Grove
            {36.5700, -118.7600, 2000},  // Giant Forest
            {36.4650, -118.6750, 1300},  // Atwell Grove
            {36.2400, -118.6750, 2000},  // Mountain Home Grove
            {36.1350, -118.5150, 1500},  // Freeman Creek Grove
    };

    /** The Sierra Nevada's sequoia belt, round all the groves. */
    static boolean sequoiaBelt(GeoPoint p) {
        return p.latitude() > 36.05 && p.latitude() < 38.33 && p.longitude() > -120.36 && p.longitude() < -118.45;
    }

    /** Whether a place between 1,200 and 2,600 m up is inside one of the groves. */
    static boolean grove(GeoPoint p, int metres) {
        if (metres < 1200 || metres > 2600 || !sequoiaBelt(p)) return false;
        double east = 111320 * Math.cos(Math.toRadians(p.latitude()));
        for (double[] grove : GROVES) {
            double dx = (p.longitude() - grove[1]) * east, dz = (p.latitude() - grove[0]) * 110540;
            if (dx * dx + dz * dz < grove[2] * grove[2]) return true;
        }
        return false;
    }
}
