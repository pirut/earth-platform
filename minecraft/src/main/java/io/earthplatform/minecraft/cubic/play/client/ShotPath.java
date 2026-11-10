package io.earthplatform.minecraft.cubic.play.client;

/**
 * The camera paths of {@code /earth orbit} and {@code /earth flyover} (see {@link EarthCinema}):
 * plain arithmetic on a shot's own clock, so the same second of a shot always gives the same view
 * however long its frames took to draw.
 */
final class ShotPath {
    private ShotPath() {}

    /** Where the camera is and which way it looks, in Minecraft's degrees: yaw 0 looks south, pitch 90 straight down. */
    record View(double x, double y, double z, double yaw, double pitch) {
        /** Part of the way from this view to another, turning the short way round. */
        View toward(View other, double part) {
            double turn = wrap(other.yaw - yaw);
            return new View(x + (other.x - x) * part, y + (other.y - y) * part, z + (other.z - z) * part,
                    yaw + turn * part, pitch + (other.pitch - pitch) * part);
        }
    }

    /** An angle in degrees brought into -180 to 180. */
    static double wrap(double degrees) {
        degrees %= 360;
        return degrees >= 180 ? degrees - 360 : degrees < -180 ? degrees + 360 : degrees;
    }

    static double smooth(double t) { return FlightPath.smooth(t); }

    /**
     * A circle round a point, always looking at it. It starts behind the way the player looked, so
     * the first frame looks the way they did, and goes round once in {@code seconds}.
     */
    record Orbit(double x, double y, double z, double radius, double height, double startYaw, double seconds) {
        View at(double time) {
            double yaw = startYaw + 360 * time / seconds, angle = Math.toRadians(yaw);
            // A camera looking along yaw stands behind the point: the look is (-sin, cos).
            return new View(x + Math.sin(angle) * radius, y + height, z - Math.cos(angle) * radius,
                    wrap(yaw), Math.toDegrees(Math.atan2(height, radius)));
        }
    }

    /**
     * How far along a straight run of {@code length} the camera is at {@code time}: it gathers speed
     * evenly over {@code ramp} seconds, holds it, and slows evenly to a stop at {@code seconds}.
     */
    static double travelled(double time, double seconds, double length, double ramp) {
        if (time <= 0) return 0;
        if (time >= seconds) return length;
        ramp = Math.min(ramp, seconds / 2);
        double speed = length / (seconds - ramp);
        if (time < ramp) return speed * time * time / (2 * ramp);
        if (time > seconds - ramp) return length - speed * (seconds - time) * (seconds - time) / (2 * ramp);
        return speed * (time - ramp / 2);
    }

    /**
     * The height a flyover keeps clear of, from ground heights sampled evenly along its line: at each
     * sample the highest ground within {@code reach} samples either way, then smoothed twice over
     * {@code reach / 2} samples so the camera rises ahead of a ridge and eases over it. Never under
     * the ground at any sample. Unknown heights ({@code NaN}) take the nearest known one.
     */
    static double[] clearance(double[] ground, int reach) {
        int n = ground.length;
        double[] known = fill(ground);
        double[] high = new double[n];
        for (int i = 0; i < n; i++) {
            double top = known[i];
            for (int j = Math.max(0, i - reach), end = Math.min(n - 1, i + reach); j <= end; j++) top = Math.max(top, known[j]);
            high[i] = top;
        }
        // Two passes over reach / 4 samples each way reach no farther than reach / 2: every height averaged is at least the ground here.
        int blur = reach / 4;
        return blur == 0 ? high : blur(blur(high, blur), blur);
    }

    private static double[] fill(double[] ground) {
        int n = ground.length;
        double[] filled = ground.clone();
        double last = Double.NaN;
        for (int i = 0; i < n; i++) { if (Double.isNaN(filled[i])) filled[i] = last; else last = filled[i]; }
        last = Double.NaN;
        for (int i = n - 1; i >= 0; i--) { if (Double.isNaN(filled[i])) filled[i] = last; else last = filled[i]; }
        for (int i = 0; i < n; i++) if (Double.isNaN(filled[i])) filled[i] = 0;
        return filled;
    }

    private static double[] blur(double[] values, int reach) {
        int n = values.length;
        double[] out = new double[n];
        for (int i = 0; i < n; i++) {
            double sum = 0;
            // Past the ends the end value repeats, so the ends keep their height.
            for (int j = i - reach; j <= i + reach; j++) sum += values[Math.max(0, Math.min(n - 1, j))];
            out[i] = sum / (2 * reach + 1);
        }
        return out;
    }

    /** A value read between evenly spaced samples, {@code at} counted in samples. */
    static double sample(double[] values, double at) {
        if (at <= 0) return values[0];
        if (at >= values.length - 1) return values[values.length - 1];
        int i = (int) at;
        return values[i] + (values[i + 1] - values[i]) * (at - i);
    }

    /**
     * A value that follows a moving target without overshooting or jerking, like a camera on a
     * damped arm: {@link #step} moves it on by a stretch of the shot's clock.
     */
    static final class Follower {
        private final double quickness;
        private double value, speed;

        /** {@code quickness} is how eagerly it follows: it closes most of a gap in about 3 / quickness seconds. */
        Follower(double start, double quickness) {
            value = start;
            this.quickness = quickness;
        }

        double value() { return value; }

        double step(double target, double seconds) {
            // Small steps, so a long frame lands where many short ones would.
            for (double left = seconds; left > 1e-9; ) {
                double dt = Math.min(left, 1 / 120.0);
                speed += (quickness * quickness * (target - value) - 2 * quickness * speed) * dt;
                value += speed * dt;
                left -= dt;
            }
            return value;
        }

        /** Never below {@code floor}: a camera pushed up by the ground keeps no downward speed. */
        void atLeast(double floor) {
            if (value < floor) { value = floor; speed = Math.max(speed, 0); }
        }
    }
}
