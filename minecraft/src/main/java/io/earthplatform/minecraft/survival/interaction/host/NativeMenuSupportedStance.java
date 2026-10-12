package io.earthplatform.minecraft.survival.interaction.host;

import io.earthplatform.core.CubePos;
import io.earthplatform.minecraft.survival.interaction.NativeWholeAction;
import io.earthplatform.minecraft.survival.world.CubeCell;
import io.earthplatform.minecraft.survival.world.NativeSurvivalWorld;
import io.earthplatform.minecraft.survival.world.SurvivalRefusal;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

/** Source-closed stationary dry native collision/support proof retained across the menu hold. */
final class NativeMenuSupportedStance {
    private final ServerPlayer player;
    private final NativeSurvivalWorld world;
    private final AABB box;
    private final BlockPos support;
    private final Map<CubeCell, BlockState> cells;
    private final Map<CubePos, NativeSurvivalWorld.CubeStamp> stamps;
    private final int retainedBytes;
    private NativeMenuSupportedStance(ServerPlayer player, NativeSurvivalWorld world, NativeMenuBudget budget) {
        this.player = player; this.world = world;
        NativeSessionMenuBoundary.requireSupportedStance(player);
        if (world.hasActiveTransaction()) throw refused("Native menu support capture requires an idle declared-read boundary");
        box = player.getBoundingBox();
        if (box.getClass() != AABB.class || box.getXsize() <= 0 || box.getXsize() > 1
                || box.getZsize() <= 0 || box.getZsize() > 1 || box.getYsize() <= 0 || box.getYsize() > 2)
            throw refused("Native menu support body exceeds the standing-player profile");
        // Exact BlockCollisions cursor padding, including the thin supporting probe below minY.
        int x1 = Mth.floor(box.minX - 1.0E-7) - 1, x2 = Mth.floor(box.maxX + 1.0E-7) + 1;
        int y1 = Mth.floor(box.minY - 1.0E-6 - 1.0E-7) - 1, y2 = Mth.floor(box.maxY + 1.0E-7) + 1;
        int z1 = Mth.floor(box.minZ - 1.0E-7) - 1, z2 = Mth.floor(box.maxZ + 1.0E-7) + 1;
        long count = ((long)x2-x1+1) * ((long)y2-y1+1) * ((long)z2-z1+1);
        if (count < 1 || count > 256) throw refused("Native menu support read footprint exceeds256 cells");
        retainedBytes = Math.toIntExact(4096 + count * 96); budget.reserve(retainedBytes);
        var captured = new LinkedHashMap<CubeCell, BlockState>();
        var expected = new LinkedHashMap<CubePos, NativeSurvivalWorld.CubeStamp>();
        for (int x=x1; x<=x2; x++) for (int y=y1; y<=y2; y++) for (int z=z1; z<=z2; z++) {
            var cell = CubeCell.of(x,y,z); var state = world.read(cell); NativeDryItemTickAdmission.requireBlock(state);
            captured.put(cell,state); expected.computeIfAbsent(cell.cube(),world::stamp);
        }
        if (expected.size() > 8) throw refused("Native menu support cube footprint exceeds8 cubes");
        cells = Map.copyOf(captured); stamps = Map.copyOf(expected);
        support = findSupport();
        if (!player.mainSupportingBlockPos.orElseThrow().equals(support)) throw refused("Cached native support differs from real cubic collision support");
    }
    static NativeMenuSupportedStance capture(ServerPlayer player, NativeSurvivalWorld world, NativeMenuBudget budget) {
        return new NativeMenuSupportedStance(player,world,budget);
    }
    int retainedBytes() { return retainedBytes; }
    Map<CubePos, NativeSurvivalWorld.CubeStamp> expected(CubeCell table) {
        var result = new LinkedHashMap<>(stamps);
        if (table != null) result.putIfAbsent(table.cube(),world.stamp(table.cube()));
        return Map.copyOf(result);
    }
    void requireIdle() {
        if (world.hasActiveTransaction()) throw refused("Native menu support idle validation overlaps an action");
        requireScalars();
        for (var entry : stamps.entrySet()) if (!entry.getValue().equals(world.stamp(entry.getKey())))
            throw refused("Native menu support cube changed during retained hold");
        for (var entry : cells.entrySet()) if (world.read(entry.getKey()) != entry.getValue())
            throw refused("Native menu support block changed during retained hold");
    }
    void declare(NativeWholeAction action) {
        if (NativeWholeAction.current() != action || action.world() != world || action.state() != NativeWholeAction.State.PREPARING)
            throw refused("Native menu support declaration lacks its exact preparing action");
        requireScalars();
        for (var entry : cells.entrySet()) if (action.read(entry.getKey()) != entry.getValue())
            throw refused("Native menu support declared read differs from retained collision proof");
        if (!findSupport().equals(support)) throw refused("Native collision support changed inside declared menu reads");
    }
    void requireDuring(NativeWholeAction action) {
        if (NativeWholeAction.current() != action || action.world() != world
                || action.state() != NativeWholeAction.State.PREVALIDATING && action.state() != NativeWholeAction.State.APPLYING)
            throw refused("Native support currentness lacks its exact validating/applying action");
        requireScalars();
        // Direct owner-world equality reads only. Generic native Level reads remain fenced while APPLYING.
        for (var entry : cells.entrySet()) if (world.read(entry.getKey()) != entry.getValue())
            throw refused("Accepted native menu support terrain changed");
        if (action.state() == NativeWholeAction.State.APPLYING)
            for (var entry : stamps.entrySet()) if (!world.stamp(entry.getKey()).equals(entry.getValue()))
                throw refused("Committed native menu support stamp changed");
    }
    private BlockPos findSupport() {
        if (!player.level().noBlockCollision(player,box)) throw refused("Native menu player body intersects cubic collision");
        var probe = new AABB(box.minX,box.minY-1.0E-6,box.minZ,box.maxX,box.minY,box.maxZ);
        return player.level().findSupportingBlock(player,probe).orElseThrow(() -> refused("Native menu player has no current cubic collision support")).immutable();
    }
    private void requireScalars() {
        NativeSessionMenuBoundary.requireSupportedStance(player);
        if (!box.equals(player.getBoundingBox()) || !player.mainSupportingBlockPos.orElseThrow().equals(support))
            throw refused("Native held menu body/support identity changed");
    }
    private static SurvivalRefusal refused(String message) { return new SurvivalRefusal(message); }
}
