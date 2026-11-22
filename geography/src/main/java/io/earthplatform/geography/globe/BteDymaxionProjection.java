package io.earthplatform.geography.globe;

import io.earthplatform.geography.GeoPoint;

/**
 * BuildTheEarth (BTE) upright conformal Modified Airocean (Dymaxion) projection,
 * projection id {@code bte-dymaxion-v1}.
 *
 * <p>Unfolds the Earth onto Fuller's 20-face icosahedron with the BuildTheEarth
 * Bering/Aleutian rearrangement so inhabited continents stay contiguous without
 * Mercator latitude stretch (~1:1 scale across continents at {@code metresPerBlock = 1.0}).
 * Each face uses the exact Schwarz-Christoffel / Lee conformal icosahedral mapping
 * sampled on a 256-cell triangular grid (computed lazily on first use from Gauss
 * hypergeometric functions, exploiting 6-fold triangular symmetry), giving sub-millimetre
 * coordinate parity with BuildTheEarth / Terra++ ({@code scale = 7318261.522857145}).
 *
 * <p>Points outside the unfolded icosahedron net return {@code null} from
 * {@link #tryGeographic(double, double)}.
 */
public record BteDymaxionProjection(double metresPerBlock) implements GlobeProjection {
    public static final String PROJECTION_ID = "bte-dymaxion-v1";
    /** Standard BuildTheEarth scale factor (projected blocks per Dymaxion unit at 1 m/block). */
    public static final double BTE_SCALE = 7_318_261.522857145;

    private static final double ROOT3 = Math.sqrt(3.0);
    private static final double ARC = 2.0 * Math.asin(Math.sqrt(5.0 - Math.sqrt(5.0)) / Math.sqrt(10.0));
    private static final double Z = Math.sqrt(5.0 + 2.0 * Math.sqrt(5.0)) / Math.sqrt(15.0);
    private static final double EL = Math.sqrt(8.0) / Math.sqrt(5.0 + Math.sqrt(5.0));
    private static final double EL6 = EL / 6.0;
    private static final double DVE = Math.sqrt(3.0 + Math.sqrt(5.0)) / Math.sqrt(5.0 + Math.sqrt(5.0));
    private static final double R = -3.0 * EL6 / DVE;
    private static final int FULLER_NEWTON = 5;

    // BuildTheEarth Modified Airocean Bering/Arctic/Aleutian cut constants.
    private static final double THETA = Math.toRadians(-150.0);
    private static final double SIN_THETA = Math.sin(THETA);
    private static final double COS_THETA = Math.cos(THETA);
    private static final double BERING_X = -0.3420420960118339;
    private static final double BERING_Y = -0.322211064085279;
    private static final double ARCTIC_Y = -0.2;
    private static final double ARCTIC_M = (ARCTIC_Y - ROOT3 * ARC / 4.0) / (BERING_X + 0.5 * ARC);
    private static final double ARCTIC_B = ARCTIC_Y - ARCTIC_M * BERING_X;
    private static final double ALEUTIAN_Y = -0.5000446805492526;
    private static final double ALEUTIAN_XL = -0.5149231279757507;
    private static final double ALEUTIAN_XR = -0.45;
    private static final double ALEUTIAN_M = (BERING_Y - ALEUTIAN_Y) / (BERING_X - ALEUTIAN_XR);
    private static final double ALEUTIAN_B = BERING_Y - ALEUTIAN_M * BERING_X;

    // Fuller icosahedron vertices (longitude, latitude in degrees).
    private static final double[][] VERTICES_DEG = {
            { 10.536199, 64.700000 },
            { -5.245390, 2.300882 },
            { 58.157706, 10.447378 },
            { 122.300000, 39.100000 },
            { -143.478490, 50.103201 },
            { -67.132330, 23.717925 },
            { 36.521510, -50.103200 },
            { 112.867673, -23.717930 },
            { 174.754610, -2.300882 },
            { -121.842290, -10.447350 },
            { -57.700000, -39.100000 },
            { -169.463800, -64.700000 },
    };

    // 20 icosahedron faces + 2 split child faces (20 from 14, 21 from 15).
    private static final int[][] ISO = {
            { 2, 1, 6 }, { 1, 0, 2 }, { 0, 1, 5 }, { 1, 5, 10 }, { 1, 6, 10 },
            { 7, 2, 6 }, { 2, 3, 7 }, { 3, 0, 2 }, { 0, 3, 4 }, { 4, 0, 5 },
            { 5, 4, 9 }, { 9, 5, 10 }, { 10, 9, 11 }, { 11, 6, 10 }, { 6, 7, 11 },
            { 8, 3, 7 }, { 8, 3, 4 }, { 8, 4, 9 }, { 9, 8, 11 }, { 7, 8, 11 },
            { 11, 6, 7 }, { 3, 7, 8 },
    };

    private static final double[][] CENTER_MAP = {
            { -3, 7 }, { -2, 5 }, { -1, 7 }, { 2, 5 }, { 4, 5 },
            { -4, 1 }, { -3, -1 }, { -2, 1 }, { -1, -1 }, { 0, 1 }, { 1, -1 }, { 2, 1 }, { 3, -1 }, { 4, 1 }, { 5, -1 },
            { -3, -5 }, { -1, -5 }, { 1, -5 }, { 2, -7 }, { -4, -7 },
            { -5, -5 }, { -2, -7 },
    };

    private static final boolean[] FLIP_TRIANGLE = {
            true, false, true, false, false,
            true, false, true, false, true, false, true, false, true, false,
            true, true, true, false, false,
            true, false,
    };

    private static final int[] FACE_ON_GRID = {
            -1, -1, 0, 1, 2, -1, -1, 3, -1, 4, -1,
            -1, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14,
            20, 19, 15, 21, 16, -1, 17, 18, -1, -1, -1,
    };

    private static final double[][] CENTROIDS = new double[22][3];
    private static final double[][][] ROT = new double[22][3][3];
    private static final double[][][] INV_ROT = new double[22][3][3];

    static {
        for (int i = 0; i < 22; i++) {
            CENTER_MAP[i][0] *= 0.5 * ARC;
            CENTER_MAP[i][1] *= ARC * ROOT3 / 12.0;
        }
        double[][] vCart = new double[VERTICES_DEG.length][3];
        double[][] vSph = new double[VERTICES_DEG.length][2];
        for (int i = 0; i < VERTICES_DEG.length; i++) {
            double lam = Math.toRadians(VERTICES_DEG[i][0]);
            double phi = Math.toRadians(90.0 - VERTICES_DEG[i][1]);
            vSph[i][0] = lam;
            vSph[i][1] = phi;
            double sinPhi = Math.sin(phi);
            vCart[i][0] = sinPhi * Math.cos(lam);
            vCart[i][1] = sinPhi * Math.sin(lam);
            vCart[i][2] = Math.cos(phi);
        }
        for (int i = 0; i < 22; i++) {
            double[] a = vCart[ISO[i][0]], b = vCart[ISO[i][1]], c = vCart[ISO[i][2]];
            double sx = a[0] + b[0] + c[0], sy = a[1] + b[1] + c[1], sz = a[2] + b[2] + c[2];
            double mag = Math.sqrt(sx * sx + sy * sy + sz * sz);
            CENTROIDS[i][0] = sx / mag;
            CENTROIDS[i][1] = sy / mag;
            CENTROIDS[i][2] = sz / mag;

            double cLam = Math.atan2(CENTROIDS[i][1], CENTROIDS[i][0]);
            double cPhi = Math.atan2(Math.hypot(CENTROIDS[i][0], CENTROIDS[i][1]), CENTROIDS[i][2]);
            double vLam = vSph[ISO[i][0]][0] - cLam;
            double vPhi = vSph[ISO[i][0]][1];
            double sinPhi = Math.sin(vPhi);
            double vx = sinPhi * Math.cos(vLam), vy = sinPhi * Math.sin(vLam), vz = Math.cos(vPhi);
            double sinR = Math.sin(-cPhi), cosR = Math.cos(-cPhi);
            double rx = vz * sinR + vx * cosR;
            double rotAngle = Math.atan2(vy, rx);

            ROT[i] = zyz(-cLam, -cPhi, Math.PI / 2.0 - rotAngle);
            INV_ROT[i] = zyz(rotAngle - Math.PI / 2.0, cPhi, cLam);
        }
    }

    /** Standard 1:1 BuildTheEarth Dymaxion projection. */
    public BteDymaxionProjection() { this(1.0); }

    public BteDymaxionProjection {
        if (!Double.isFinite(metresPerBlock) || metresPerBlock <= 0)
            throw new IllegalArgumentException("Invalid meters per block");
    }

    @Override public String id() { return PROJECTION_ID; }

    @Override public boolean wrapsEastWest() { return false; }

    @Override public double worldWidthBlocks() { return 2.0 * Math.PI * MercatorGrid.RADIUS / metresPerBlock; }

    /** Block units per unit Dymaxion radian. */
    public double scale() { return BTE_SCALE / metresPerBlock; }

    @Override public double[] toBlock(double latitude, double longitude) {
        if (!Double.isFinite(latitude) || !Double.isFinite(longitude))
            throw new IllegalArgumentException("Non-finite geographic coordinate");
        if (latitude < -90.0 || latitude > 90.0)
            throw new IllegalArgumentException("Latitude out of range: " + latitude);

        double lam = Math.toRadians(longitude);
        double phi = Math.toRadians(90.0 - latitude);
        double sinPhi = Math.sin(phi);
        double vx = sinPhi * Math.cos(lam), vy = sinPhi * Math.sin(lam), vz = Math.cos(phi);

        int face = findFace(vx, vy, vz);
        double[][] m = ROT[face];
        double rx = m[0][0] * vx + m[0][1] * vy + m[0][2] * vz;
        double ry = m[1][0] * vx + m[1][1] * vy + m[1][2] * vz;
        double rz = m[2][0] * vx + m[2][1] * vy + m[2][2] * vz;

        double[] tri = ConformalTable.forward(rx, ry, rz);
        double px = tri[0], py = tri[1];
        if (FLIP_TRIANGLE[face]) {
            px = -px;
            py = -py;
        }
        if (((face == 15 && px > py * ROOT3) || face == 14) && px > 0.0) {
            double ox = px;
            px = 0.5 * ox - 0.5 * ROOT3 * py;
            py = 0.5 * ROOT3 * ox + 0.5 * py;
            face += 6;
        }
        px += CENTER_MAP[face][0];
        py += CENTER_MAP[face][1];

        boolean eurasia = isEurasianPart(px, py);
        py -= 0.75 * ARC * ROOT3;
        if (eurasia) {
            px += ARC;
            double t = px;
            px = COS_THETA * px - SIN_THETA * py;
            py = SIN_THETA * t + COS_THETA * py;
        } else {
            px -= ARC;
        }

        // Upright BTE orientation: (x_bte, y_bte) = (py, -px), then vertical flip -> (py, px), then scale.
        double s = scale();
        return new double[]{py * s, px * s};
    }

    @Override public GeoPoint tryGeographic(double x, double z) {
        if (!Double.isFinite(x) || !Double.isFinite(z))
            throw new IllegalArgumentException("Non-finite block position");
        double s = scale();
        double bx = x / s;
        double by = -z / s;

        boolean eurasia;
        if (by < 0.0) eurasia = bx > 0.0;
        else if (by > ARC / 2.0) eurasia = bx > -ROOT3 * ARC / 2.0;
        else eurasia = by * -ROOT3 < bx;

        double px = -by;
        double py = bx;
        if (eurasia) {
            double t = px;
            px = COS_THETA * px + SIN_THETA * py;
            py = COS_THETA * py - SIN_THETA * t;
            px -= ARC;
        } else {
            px += ARC;
        }
        py += 0.75 * ARC * ROOT3;
        if (eurasia != isEurasianPart(px, py)) return null;

        int face = findTriangleGrid(px, py);
        if (face == -1) return null;

        px -= CENTER_MAP[face][0];
        py -= CENTER_MAP[face][1];
        switch (face) {
            case 14 -> { if (px > 0.0) return null; }
            case 20 -> { if (-py * ROOT3 > px) return null; }
            case 15 -> { if (px > 0.0 && px > py * ROOT3) return null; }
            case 21 -> { if (px < 0.0 || -py * ROOT3 > px) return null; }
            default -> {}
        }
        if (FLIP_TRIANGLE[face]) {
            px = -px;
            py = -py;
        }

        double[] vec = ConformalTable.inverse(px, py);
        double[][] m = INV_ROT[face];
        double gx = m[0][0] * vec[0] + m[0][1] * vec[1] + m[0][2] * vec[2];
        double gy = m[1][0] * vec[0] + m[1][1] * vec[1] + m[1][2] * vec[2];
        double gz = m[2][0] * vec[0] + m[2][1] * vec[1] + m[2][2] * vec[2];

        double lon = Math.toDegrees(Math.atan2(gy, gx));
        double lat = 90.0 - Math.toDegrees(Math.atan2(Math.hypot(gx, gy), gz));
        return new GeoPoint(Math.clamp(lat, -90.0, 90.0), lon);
    }

    @Override public double groundMetresPerBlock(double x, double z) {
        double[] j = localJacobian(x, z);
        if (j == null) return metresPerBlock;
        double sx = Math.hypot(j[0], j[2]);
        double sz = Math.hypot(j[1], j[3]);
        double avg = 0.5 * (sx + sz);
        return avg > 1e-6 && Double.isFinite(avg) ? avg : metresPerBlock;
    }

    @Override public double[] eastUnit(double x, double z) {
        double[] j = localJacobian(x, z);
        if (j == null) return new double[]{1.0, 0.0};
        // j = {dEast/dx, dEast/dz, dNorth/dx, dNorth/dz}
        // Solve J * [dx, dz]^T = [1, 0]^T => dx = dNorth/dz / det, dz = -dNorth/dx / det.
        double det = j[0] * j[3] - j[1] * j[2];
        if (Math.abs(det) < 1e-12) return new double[]{1.0, 0.0};
        double ex = j[3] / det;
        double ez = -j[2] / det;
        double norm = Math.hypot(ex, ez);
        if (norm < 1e-12 || !Double.isFinite(norm)) return new double[]{1.0, 0.0};
        return new double[]{ex / norm, ez / norm};
    }

    /**
     * Local Jacobian {@code {dEast/dx, dEast/dz, dNorth/dx, dNorth/dz}} in ground metres per block,
     * or {@code null} outside the net or right at a pole.
     */
    private double[] localJacobian(double x, double z) {
        GeoPoint p0 = tryGeographic(x, z);
        if (p0 == null || Math.abs(p0.latitude()) > 89.95) return null;
        double step = 1.0;
        double sx = 1.0;
        GeoPoint px = tryGeographic(x + step, z);
        if (px == null) {
            px = tryGeographic(x - step, z);
            sx = -1.0;
        }
        double sz = 1.0;
        GeoPoint pz = tryGeographic(x, z + step);
        if (pz == null) {
            pz = tryGeographic(x, z - step);
            sz = -1.0;
        }
        if (px == null || pz == null) return null;

        double cosLat = Math.cos(Math.toRadians(p0.latitude()));
        double dEastDx = Math.toRadians(Math.IEEEremainder(px.longitude() - p0.longitude(), 360.0)) * MercatorGrid.RADIUS * cosLat * (sx / step);
        double dNorthDx = Math.toRadians(px.latitude() - p0.latitude()) * MercatorGrid.RADIUS * (sx / step);
        double dEastDz = Math.toRadians(Math.IEEEremainder(pz.longitude() - p0.longitude(), 360.0)) * MercatorGrid.RADIUS * cosLat * (sz / step);
        double dNorthDz = Math.toRadians(pz.latitude() - p0.latitude()) * MercatorGrid.RADIUS * (sz / step);
        return new double[]{dEastDx, dEastDz, dNorthDx, dNorthDz};
    }

    private static int findFace(double vx, double vy, double vz) {
        double min = Double.MAX_VALUE;
        int face = 0;
        for (int i = 0; i < 20; i++) {
            double dx = CENTROIDS[i][0] - vx;
            double dy = CENTROIDS[i][1] - vy;
            double dz = CENTROIDS[i][2] - vz;
            double d2 = dx * dx + dy * dy + dz * dz;
            if (d2 < min) {
                if (d2 < 0.1) return i;
                min = d2;
                face = i;
            }
        }
        return face;
    }

    private static int findTriangleGrid(double x, double y) {
        double xp = x / ARC;
        double yp = y / (ARC * ROOT3);
        int row;
        if (yp > -0.25) {
            if (yp < 0.25) row = 1;
            else if (yp <= 0.75) { row = 0; yp = 0.5 - yp; }
            else return -1;
        } else if (yp >= -0.75) {
            row = 2;
            yp = -yp - 0.5;
        } else {
            return -1;
        }
        yp += 0.25;
        int gx = (int) Math.floor(xp - yp);
        int gy = (int) Math.floor(xp + yp);
        int col = 2 * gx + (gy != gx ? 1 : 0) + 6;
        if (col < 0 || col >= 11) return -1;
        return FACE_ON_GRID[row * 11 + col];
    }

    private static boolean isEurasianPart(double x, double y) {
        if (x > 0.0) return false;
        if (x < -0.5 * ARC) return true;
        if (y > ROOT3 * ARC / 4.0) return x < 0.0;
        if (y < ALEUTIAN_Y) return y < (ALEUTIAN_Y + ALEUTIAN_XL) - x;
        if (y > BERING_Y) {
            if (y < ARCTIC_Y) return x < BERING_X;
            return y < ARCTIC_M * x + ARCTIC_B;
        }
        return y > ALEUTIAN_M * x + ALEUTIAN_B;
    }

    private static double[][] zyz(double a, double b, double c) {
        double sa = Math.sin(a), ca = Math.cos(a);
        double sb = Math.sin(b), cb = Math.cos(b);
        double sc = Math.sin(c), cc = Math.cos(c);
        return new double[][]{
                { ca * cb * cc - sc * sa, -sa * cb * cc - sc * ca, cc * sb },
                { sc * cb * ca + cc * sa, cc * ca - sc * cb * sa, sc * sb },
                { -sb * ca, sb * sa, cb },
        };
    }

    private static double[] fullerForward(double vx, double vy, double vz) {
        double s = Z / vz;
        double xp = s * vx, yp = s * vy;
        double a = Math.atan((2.0 * yp / ROOT3 - EL6) / DVE);
        double b = Math.atan((xp - yp / ROOT3 - EL6) / DVE);
        double c = Math.atan((-xp - yp / ROOT3 - EL6) / DVE);
        return new double[]{0.5 * (b - c), (2.0 * a - b - c) / (2.0 * ROOT3)};
    }

    private static double[] fullerInverse(double xpp, double ypp) {
        double tanAOff = Math.tan(ROOT3 * ypp + xpp);
        double tanBOff = Math.tan(2.0 * xpp);
        double aNumer = tanAOff * tanAOff + 1.0;
        double bNumer = tanBOff * tanBOff + 1.0;
        double tanA = tanAOff, tanB = tanBOff, tanC = 0.0;
        double aDenom = 1.0, bDenom = 1.0;
        for (int i = 0; i < FULLER_NEWTON; i++) {
            double f = tanA + tanB + tanC - R;
            double fp = aNumer * aDenom * aDenom + bNumer * bDenom * bDenom + 1.0;
            tanC -= f / fp;
            aDenom = 1.0 / (1.0 - tanC * tanAOff);
            bDenom = 1.0 / (1.0 - tanC * tanBOff);
            tanA = (tanC + tanAOff) * aDenom;
            tanB = (tanC + tanBOff) * bDenom;
        }
        double yp = ROOT3 * (DVE * tanA + EL6) / 2.0;
        double xp = DVE * tanB + yp / ROOT3 + EL6;
        double xpoZ = xp / Z, ypoZ = yp / Z;
        double z = 1.0 / Math.sqrt(1.0 + xpoZ * xpoZ + ypoZ * ypoZ);
        return new double[]{z * xpoZ, z * ypoZ, z};
    }

    /**
     * Lazily computed 256-cell conformal lookup table for the equilateral template triangle.
     *
     * <p>Each vertex of the grid is computed from the exact analytical Schwarz-Christoffel / Lee
     * conformal mapping between the unit disk, the flat equilateral triangle
     * ({@code z * 2F1(1/3, 2/3; 4/3; z^3)}), and the stereographic spherical icosahedron face
     * ({@code z * 2F1(3/10, 19/30; 4/3; z^3) / 2F1(-1/30, 3/10; 2/3; z^3)}), then converted into
     * Fuller's triangle coordinates. Six-fold D3 symmetry cuts initialization to 5,590 points (~10 ms).
     */
    private static final class ConformalTable {
        private static final int SIDE = 256;
        // Lanczos approximation (g = 7, 9 terms) accurate to ~1e-15 across all real non-integer arguments.
        private static final double[] LANCZOS = {
                0.99999999999980993,
                676.5203681218851,
                -1259.1392167224028,
                771.32342877765313,
                -176.61502916214059,
                12.507343278686905,
                -0.13857109526572012,
                9.9843695780195716e-6,
                1.5056327351493116e-7,
        };

        private static final double R_V = Math.sqrt((1.0 - Z) / (1.0 + Z));
        private static final double K_SPH = R_V / (hypAtOne(0.3, 19.0 / 30.0, 4.0 / 3.0) / hypAtOne(-1.0 / 30.0, 0.3, 2.0 / 3.0));
        private static final double K_TRI = (ARC / ROOT3) / hypAtOne(1.0 / 3.0, 2.0 / 3.0, 4.0 / 3.0);
        private static final double COS120 = -0.5;
        private static final double SIN120 = 0.5 * ROOT3;

        private static final double[][] VX = new double[SIDE + 1][];
        private static final double[][] VY = new double[SIDE + 1][];

        static {
            for (int u = 0; u <= SIDE; u++) {
                VX[u] = new double[SIDE + 1 - u];
                VY[u] = new double[SIDE + 1 - u];
            }
            double[] zWarm = new double[2];
            double[] tmp = new double[2];
            for (int a = 0; a <= SIDE; a++) {
                boolean hasWarm = false;
                int bMax = Math.min(a, SIDE - a);
                for (int b = 0; b <= bMax; b++) {
                    int c = SIDE - a - b;
                    if (c > b) continue;
                    double gx = (c + 0.5 * a) / SIDE;
                    double gy = (0.5 * ROOT3 * a) / SIDE;
                    double tx = (gx - 0.5) * ARC;
                    double ty = (gy - ROOT3 / 6.0) * ARC;
                    double uRe = ty, uIm = -tx;
                    double fx, fy;
                    if (uRe * uRe + uIm * uIm < 1e-30) {
                        fx = 0.0;
                        fy = 0.0;
                        zWarm[0] = 0.0;
                        zWarm[1] = 0.0;
                        hasWarm = true;
                    } else if (a == SIDE) {
                        double r2 = R_V * R_V, inv = 1.0 / (1.0 + r2);
                        double[] f = fullerForward(0.0, 2.0 * R_V * inv, (1.0 - r2) * inv);
                        fx = f[0];
                        fy = f[1];
                    } else {
                        if (!hasWarm) {
                            zWarm[0] = uRe / K_TRI;
                            zWarm[1] = uIm / K_TRI;
                            double r = Math.hypot(zWarm[0], zWarm[1]);
                            if (r > 0.98) { zWarm[0] *= 0.98 / r; zWarm[1] *= 0.98 / r; }
                        }
                        triToDisk(uRe, uIm, zWarm);
                        hasWarm = true;
                        diskToSph(zWarm[0], zWarm[1], tmp);
                        double xs = -tmp[1], ys = tmp[0];
                        double r2 = xs * xs + ys * ys, inv = 1.0 / (1.0 + r2);
                        double[] f = fullerForward(2.0 * xs * inv, 2.0 * ys * inv, (1.0 - r2) * inv);
                        fx = f[0];
                        fy = f[1];
                    }
                    putSym(a, b, c, fx, fy);
                }
            }
        }

        private static void putSym(int a, int b, int c, double fx, double fy) {
            VX[c][a] = fx; VY[c][a] = fy;
            VX[b][a] = -fx; VY[b][a] = fy;
            double rx1 = COS120 * fx - SIN120 * fy, ry1 = SIN120 * fx + COS120 * fy;
            VX[b][c] = rx1; VY[b][c] = ry1;
            double rx2 = COS120 * (-fx) - SIN120 * fy, ry2 = SIN120 * (-fx) + COS120 * fy;
            VX[c][b] = rx2; VY[c][b] = ry2;
            double rx3 = COS120 * fx + SIN120 * fy, ry3 = -SIN120 * fx + COS120 * fy;
            VX[a][b] = rx3; VY[a][b] = ry3;
            double rx4 = COS120 * (-fx) + SIN120 * fy, ry4 = -SIN120 * (-fx) + COS120 * fy;
            VX[a][c] = rx4; VY[a][c] = ry4;
        }

        static double[] forward(double rx, double ry, double rz) {
            double[] f = fullerForward(rx, ry, rz);
            double targetX = f[0], targetY = f[1];
            double xe = targetX / ARC + 0.5;
            double ye = targetY / ARC + ROOT3 / 6.0;
            double[] sample = new double[6];
            for (int i = 0; i < 5; i++) {
                interpolate(xe, ye, sample);
                double fx = sample[0] - targetX;
                double fy = sample[1] - targetY;
                double det = 1.0 / (sample[2] * sample[5] - sample[3] * sample[4]);
                xe -= det * (sample[5] * fx - sample[3] * fy);
                ye -= det * (-sample[4] * fx + sample[2] * fy);
            }
            return new double[]{(xe - 0.5) * ARC, (ye - ROOT3 / 6.0) * ARC};
        }

        static double[] inverse(double tx, double ty) {
            double[] sample = new double[6];
            interpolate(tx / ARC + 0.5, ty / ARC + ROOT3 / 6.0, sample);
            return fullerInverse(sample[0], sample[1]);
        }

        private static void interpolate(double x, double y, double[] out) {
            double sx = x * SIDE;
            double sy = y * SIDE;
            double v = 2.0 * sy / ROOT3;
            double u = sx - 0.5 * v;
            int u1 = Math.clamp((int) u, 0, SIDE - 1);
            int v1 = Math.clamp((int) v, 0, SIDE - u1 - 1);

            double x1, y1, x2, y2, x3, y3, cx3, cy3, flip;
            if (sy < -ROOT3 * (sx - u1 - v1 - 1) || v1 == SIDE - u1 - 1) {
                x1 = VX[u1][v1]; y1 = VY[u1][v1];
                x2 = VX[u1][v1 + 1]; y2 = VY[u1][v1 + 1];
                x3 = VX[u1 + 1][v1]; y3 = VY[u1 + 1][v1];
                cy3 = 0.5 * ROOT3 * v1;
                cx3 = (u1 + 1) + 0.5 * v1;
                flip = 1.0;
            } else {
                x1 = VX[u1][v1 + 1]; y1 = VY[u1][v1 + 1];
                x2 = VX[u1 + 1][v1]; y2 = VY[u1 + 1][v1];
                x3 = VX[u1 + 1][v1 + 1]; y3 = VY[u1 + 1][v1 + 1];
                sy = -sy;
                cy3 = -0.5 * ROOT3 * (v1 + 1);
                cx3 = (u1 + 1) + 0.5 * (v1 + 1);
                flip = -1.0;
            }
            double w1 = -(sy - cy3) / ROOT3 - (sx - cx3);
            double w2 = 2.0 * (sy - cy3) / ROOT3;
            double w3 = 1.0 - w1 - w2;
            out[0] = x1 * w1 + x2 * w2 + x3 * w3;
            out[1] = y1 * w1 + y2 * w2 + y3 * w3;
            out[2] = (x3 - x1) * SIDE;
            out[3] = SIDE * flip * (2.0 * x2 - x1 - x3) / ROOT3;
            out[4] = (y3 - y1) * SIDE;
            out[5] = SIDE * flip * (2.0 * y2 - y1 - y3) / ROOT3;
        }

        private static void triToDisk(double uRe, double uIm, double[] z) {
            double[] f = new double[2];
            double[] df = new double[2];
            for (int i = 0; i < 30; i++) {
                diskToTri(z[0], z[1], f);
                double eRe = f[0] - uRe, eIm = f[1] - uIm;
                if (eRe * eRe + eIm * eIm < 1e-28) break;
                // d/dz (K_TRI * z * 2F1(1/3, 2/3; 4/3; z^3)) = K_TRI * (1 - z^3)^(-2/3)
                double z2Re = z[0] * z[0] - z[1] * z[1], z2Im = 2.0 * z[0] * z[1];
                double omRe = 1.0 - (z2Re * z[0] - z2Im * z[1]);
                double omIm = -(z2Re * z[1] + z2Im * z[0]);
                cpow(omRe, omIm, -2.0 / 3.0, df);
                df[0] *= K_TRI;
                df[1] *= K_TRI;
                double d2 = df[0] * df[0] + df[1] * df[1];
                double sRe = (eRe * df[0] + eIm * df[1]) / d2;
                double sIm = (eIm * df[0] - eRe * df[1]) / d2;
                while (Math.hypot(z[0] - sRe, z[1] - sIm) > 1.0000001) {
                    sRe *= 0.5;
                    sIm *= 0.5;
                }
                z[0] -= sRe;
                z[1] -= sIm;
            }
        }

        private static void diskToTri(double zRe, double zIm, double[] out) {
            double z2Re = zRe * zRe - zIm * zIm, z2Im = 2.0 * zRe * zIm;
            double z3Re = z2Re * zRe - z2Im * zIm, z3Im = z2Re * zIm + z2Im * zRe;
            double[] h = new double[2];
            hyp2f1(1.0 / 3.0, 2.0 / 3.0, 4.0 / 3.0, z3Re, z3Im, h);
            out[0] = K_TRI * (zRe * h[0] - zIm * h[1]);
            out[1] = K_TRI * (zRe * h[1] + zIm * h[0]);
        }

        private static void diskToSph(double zRe, double zIm, double[] out) {
            double z2Re = zRe * zRe - zIm * zIm, z2Im = 2.0 * zRe * zIm;
            double z3Re = z2Re * zRe - z2Im * zIm, z3Im = z2Re * zIm + z2Im * zRe;
            double[] num = new double[2], den = new double[2];
            hyp2f1(0.3, 19.0 / 30.0, 4.0 / 3.0, z3Re, z3Im, num);
            hyp2f1(-1.0 / 30.0, 0.3, 2.0 / 3.0, z3Re, z3Im, den);
            double d2 = den[0] * den[0] + den[1] * den[1];
            double qRe = (num[0] * den[0] + num[1] * den[1]) / d2;
            double qIm = (num[1] * den[0] - num[0] * den[1]) / d2;
            out[0] = K_SPH * (zRe * qRe - zIm * qIm);
            out[1] = K_SPH * (zRe * qIm + zIm * qRe);
        }

        private static void hyp2f1(double a, double b, double c, double re, double im, double[] out) {
            double r2 = re * re + im * im;
            if (r2 <= 0.36) {
                hyp2f1Series(a, b, c, re, im, 300, out);
                return;
            }
            double omRe = 1.0 - re, omIm = -im;
            if (omRe * omRe + omIm * omIm <= 0.36) {
                double c1 = gamma(c) * gamma(c - a - b) / (gamma(c - a) * gamma(c - b));
                double c2 = gamma(c) * gamma(a + b - c) / (gamma(a) * gamma(b));
                double[] s1 = new double[2], s2 = new double[2], p = new double[2];
                hyp2f1Series(a, b, a + b - c + 1.0, omRe, omIm, 300, s1);
                hyp2f1Series(c - a, c - b, c - a - b + 1.0, omRe, omIm, 300, s2);
                cpow(omRe, omIm, c - a - b, p);
                out[0] = c1 * s1[0] + c2 * (p[0] * s2[0] - p[1] * s2[1]);
                out[1] = c1 * s1[1] + c2 * (p[0] * s2[1] + p[1] * s2[0]);
                return;
            }
            // Pfaff transformation: 2F1(a, b; c; z) = (1 - z)^(-a) * 2F1(a, c - b; c; z / (z - 1)).
            double dRe = re - 1.0, dIm = im;
            double d2 = dRe * dRe + dIm * dIm;
            double z2Re = (re * dRe + im * dIm) / d2;
            double z2Im = (im * dRe - re * dIm) / d2;
            if (z2Re * z2Re + z2Im * z2Im <= 0.7225) {
                double[] s = new double[2], p = new double[2];
                hyp2f1Series(a, c - b, c, z2Re, z2Im, 300, s);
                cpow(omRe, omIm, -a, p);
                out[0] = p[0] * s[0] - p[1] * s[1];
                out[1] = p[0] * s[1] + p[1] * s[0];
                return;
            }
            hyp2f1Series(a, b, c, re, im, 1000, out);
        }

        private static void hyp2f1Series(double a, double b, double c, double re, double im, int limit, double[] out) {
            double sRe = 1.0, sIm = 0.0, tRe = 1.0, tIm = 0.0;
            for (int n = 0; n < limit; n++) {
                double k = (a + n) * (b + n) / ((c + n) * (n + 1));
                double nRe = (tRe * re - tIm * im) * k;
                double nIm = (tRe * im + tIm * re) * k;
                tRe = nRe;
                tIm = nIm;
                sRe += tRe;
                sIm += tIm;
                if (tRe * tRe + tIm * tIm < 1e-32 * (sRe * sRe + sIm * sIm)) break;
            }
            out[0] = sRe;
            out[1] = sIm;
        }

        private static void cpow(double re, double im, double p, double[] out) {
            double r = Math.hypot(re, im);
            if (r == 0.0) { out[0] = 0.0; out[1] = 0.0; return; }
            double theta = Math.atan2(im, re) * p;
            double rp = Math.pow(r, p);
            out[0] = rp * Math.cos(theta);
            out[1] = rp * Math.sin(theta);
        }

        private static double hypAtOne(double a, double b, double c) {
            return gamma(c) * gamma(c - a - b) / (gamma(c - a) * gamma(c - b));
        }

        private static double gamma(double z) {
            if (z < 0.5) return Math.PI / (Math.sin(Math.PI * z) * gamma(1.0 - z));
            z -= 1.0;
            double x = LANCZOS[0];
            for (int i = 1; i < LANCZOS.length; i++) x += LANCZOS[i] / (z + i);
            double t = z + 7.5;
            return Math.sqrt(2.0 * Math.PI) * Math.pow(t, z + 0.5) * Math.exp(-t) * x;
        }
    }
}
