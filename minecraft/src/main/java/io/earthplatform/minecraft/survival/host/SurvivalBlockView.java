package io.earthplatform.minecraft.survival.host;

import io.earthplatform.core.CubePos;
import io.earthplatform.minecraft.survival.world.CubeCell;
import io.earthplatform.minecraft.survival.world.NativeSurvivalWorld;
import io.earthplatform.minecraft.survival.world.SurvivalRefusal;
import java.util.Objects;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;

/** Exact server-owner view; no terrain column, air fallback, Y packing or height rebasing. */
public final class SurvivalBlockView implements BlockGetter {
    interface Access {
        BlockState state(BlockPos position);
        boolean resident(BlockPos position);
        boolean box(int x1,int y1,int z1,int x2,int y2,int z2);
        void geometry();
        void owner();
    }
    private final NativeSurvivalWorld world;
    private final Access access;
    private final int minimumY, height;
    public SurvivalBlockView(NativeSurvivalWorld world) { this(world, null); }
    SurvivalBlockView(NativeSurvivalWorld world, Access access) {
        this.access = access;
        this.world = Objects.requireNonNull(world);
        world.checkOwner();
        minimumY = Math.toIntExact((long)world.bounds().minimum().y() * CubePos.SIZE);
        long exclusiveMaximum = Math.multiplyExact((long)world.bounds().maximum().y() + 1, CubePos.SIZE);
        height = Math.toIntExact(Math.subtractExact(exclusiveMaximum, minimumY));
        if (height <= 0) throw new IllegalArgumentException("Native BlockGetter height envelope is not representable");
    }
    public NativeSurvivalWorld world() { world.checkOwner(); if (access != null) access.owner(); return world; }
    @Override public BlockState getBlockState(BlockPos position) {
        Objects.requireNonNull(position);
        if (access != null) return access.state(position);
        return world.read(CubeCell.of(position.getX(), position.getY(), position.getZ()));
    }
    @Override public FluidState getFluidState(BlockPos position) { return getBlockState(position).getFluidState(); }
    @Override public BlockEntity getBlockEntity(BlockPos position) {
        BlockState state = getBlockState(position);
        if (state.hasBlockEntity()) throw new SurvivalRefusal("Block entity simulation has not been admitted");
        return null; // Audited native states genuinely have no BlockEntity; unknown cells already refused.
    }
    public boolean isResident(BlockPos position) {
        world.checkOwner(); Objects.requireNonNull(position);
        return access != null ? access.resident(position) : residentUnchecked(position);
    }
    boolean residentUnchecked(BlockPos position) {
        world.checkOwner();
        CubePos cube = CubePos.fromBlock(position.getX(), position.getY(), position.getZ());
        return world.bounds().contains(cube) && world.presence(cube) == NativeSurvivalWorld.Presence.RESIDENT;
    }
    /** Inclusive block box; no projection to an X/Z column and no unbounded search. */
    public boolean hasResidentBox(int x1, int y1, int z1, int x2, int y2, int z2) {
        world.checkOwner();
        return access != null ? access.box(x1,y1,z1,x2,y2,z2) : residentBoxUnchecked(x1,y1,z1,x2,y2,z2);
    }
    boolean residentBoxUnchecked(int x1, int y1, int z1, int x2, int y2, int z2) {
        world.checkOwner();
        if (x1 > x2 || y1 > y2 || z1 > z2) return false;
        CubePos minimum = CubePos.fromBlock(x1, y1, z1), maximum = CubePos.fromBlock(x2, y2, z2);
        if (!world.bounds().contains(minimum) || !world.bounds().contains(maximum)) return false;
        long nx = (long)maximum.x() - minimum.x() + 1;
        long ny = (long)maximum.y() - minimum.y() + 1;
        long nz = (long)maximum.z() - minimum.z() + 1;
        // Division first prevents overflow and bounds every loop by actual resident ownership.
        long budget = world.residentCount();
        if (nx > budget || ny > budget / nx || nz > budget / (nx * ny)) return false;
        for (int x = minimum.x(); x <= maximum.x(); x++)
            for (int y = minimum.y(); y <= maximum.y(); y++)
                for (int z = minimum.z(); z <= maximum.z(); z++)
                    if (world.presence(new CubePos(x, y, z)) != NativeSurvivalWorld.Presence.RESIDENT) return false;
        return true;
    }
    @Override public int getMinBuildHeight() { world.checkOwner(); if (access != null) access.geometry(); return minimumY; }
    @Override public int getHeight() { world.checkOwner(); if (access != null) access.geometry(); return height; }
}
