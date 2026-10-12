package io.earthplatform.minecraft.visual;

/** Explicit031-only allocation manifest. Legacy native and DH policies do not consult these values. */
public final class NativeDistanceLimits {
    public static final String POLICY="earth-native-setting-distance-031-v1";
    public static final long MIB=1L<<20;
    public static final long SOURCE_COLUMNS=3_200_000,SOURCE_BYTES=96*MIB;
    public static final long PLAN_COLUMNS=1_500_000,PLAN_PAGES=1536,PLAN_PIECES=1536,PLAN_QUADS=7_743_000;
    public static final long COVERAGE_BYTES=32*MIB;
    public static final int MAX_LIVE_COVERAGES=2,CPU_MAILBOX_PIECES=2,WORKERS=1;
    public static final long SOURCE_PLAN_STAGING_BYTES=8*MIB,CAPSULE_AND_CAPTURE_BYTES=8*MIB,WORKER_AND_UPLOAD_BYTES=8*MIB;
    public static final long DH_RESULT_BYTES=20*MIB,DH_TOTAL_RESULT_BYTES=40*MIB,DH_GPU_BYTES=32*MIB;
    public static final long ACCOUNTED_ADDITIONAL_CPU_BYTES=384*MIB;
    public static EarthDistanceSurfacePlan.Limits sourceLimits(){return new EarthDistanceSurfacePlan.Limits(SOURCE_COLUMNS,SOURCE_BYTES,PLAN_COLUMNS,PLAN_PAGES,PLAN_PIECES,PLAN_QUADS,NativeVisualBudget.MAX_BYTES,COVERAGE_BYTES);}
    private NativeDistanceLimits(){}
}
