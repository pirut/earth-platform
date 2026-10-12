package io.earthplatform.minecraft.survival.interaction.host;

import io.earthplatform.minecraft.survival.world.SurvivalRefusal;
import java.lang.reflect.Method;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.AirBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CraftingTableBlock;
import net.minecraft.world.level.block.GrassBlock;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.neoforged.neoforge.common.extensions.IBlockExtension;
import net.neoforged.neoforge.common.extensions.IItemExtension;

/** Source-closed native dry movement/landing profile. Not general entity physics/mod admission. */
final class NativeDryItemTickAdmission {
    static final int MAX_CELLS = 4096;
    record ReadBounds(int x1, int y1, int z1, int x2, int y2, int z2) {
        ReadBounds {
            long nx = (long) x2 - x1 + 1, ny = (long) y2 - y1 + 1, nz = (long) z2 - z1 + 1;
            if (nx < 1 || ny < 1 || nz < 1 || nx > MAX_CELLS || ny > MAX_CELLS / nx || nz > MAX_CELLS / (nx * ny))
                throw new SurvivalRefusal("Native sealed tick read budget exceeded");
        }
        int cells() { return Math.toIntExact(((long) x2 - x1 + 1) * ((long) y2 - y1 + 1) * ((long) z2 - z1 + 1)); }
        boolean contains(int x, int y, int z) { return x >= x1 && x <= x2 && y >= y1 && y <= y2 && z >= z1 && z <= z2; }
    }
    private static final Map<Block, Class<?>> BLOCK_TYPES = Map.of(
            Blocks.AIR, AirBlock.class, Blocks.GRASS_BLOCK, GrassBlock.class,
            Blocks.DIRT, Block.class, Blocks.STONE, Block.class, Blocks.COBBLESTONE, Block.class,
            Blocks.OAK_LOG, RotatedPillarBlock.class, Blocks.OAK_LEAVES, LeavesBlock.class,
            Blocks.OAK_PLANKS, Block.class, Blocks.CRAFTING_TABLE, CraftingTableBlock.class);

    static ReadBounds bounds(AABB box, Vec3 velocity) {
        if (!finite(velocity.x) || !finite(velocity.y) || !finite(velocity.z)
                || Math.abs(velocity.x) > 1 || Math.abs(velocity.y) > 1 || Math.abs(velocity.z) > 1
                || box.getXsize() <= 0 || box.getXsize() > 1 || box.getYsize() <= 0 || box.getYsize() > 1
                || box.getZsize() <= 0 || box.getZsize() > 1)
            throw new SurvivalRefusal("Native isolated tick velocity/dimensions exceed the dry profile");
        // Gravity is native 0.04 for exact ItemEntity. Padding includes eye/fluid, inside-block,
        // legacy underfoot, supporting-block, collision and possible clip probes, never a column.
        AABB reads = box.expandTowards(velocity.x, velocity.y - 0.04, velocity.z).inflate(2.0);
        ReadBounds result = new ReadBounds(NativeItemTickReadScope.floor(reads.minX), NativeItemTickReadScope.floor(reads.minY), NativeItemTickReadScope.floor(reads.minZ),
                NativeItemTickReadScope.ceil(reads.maxX), NativeItemTickReadScope.ceil(reads.maxY), NativeItemTickReadScope.ceil(reads.maxZ));
        NativeEffectDeliveryHost.exactNativePosition(io.earthplatform.minecraft.survival.world.CubeCell.of(result.x1(), result.y1(), result.z1()));
        NativeEffectDeliveryHost.exactNativePosition(io.earthplatform.minecraft.survival.world.CubeCell.of(result.x2(), result.y2(), result.z2()));
        if (Math.abs((long) result.x1()) > 30000512 || Math.abs((long) result.x2()) > 30000512
                || Math.abs((long) result.z1()) > 30000512 || Math.abs((long) result.z2()) > 30000512)
            throw new SurvivalRefusal("Native tick swept result could exceed durable item position bounds");
        return result;
    }
    static void requireBlock(BlockState state) {
        Block block = state.getBlock(); Class<?> type = BLOCK_TYPES.get(block);
        if (type == null || block.getClass() != type || !state.getFluidState().isEmpty() || state.hasBlockEntity())
            throw new SurvivalRefusal("Native item tick reached a non-dry/custom block profile");
        inherited(type, Block.class, "fallOn", Level.class, BlockState.class, BlockPos.class, Entity.class, float.class);
        inherited(type, Block.class, "stepOn", Level.class, BlockPos.class, BlockState.class, Entity.class);
        inherited(type, Block.class, "updateEntityAfterFallOn", BlockGetter.class, Entity.class);
        inherited(type, BlockBehaviour.class, "entityInside", BlockState.class, Level.class, BlockPos.class, Entity.class);
        inherited(type, BlockBehaviour.class, "getCollisionShape", BlockState.class, BlockGetter.class, BlockPos.class, CollisionContext.class);
        inherited(type, type == AirBlock.class ? AirBlock.class : BlockBehaviour.class,
                "getShape", BlockState.class, BlockGetter.class, BlockPos.class, CollisionContext.class);
        inherited(type, type == LeavesBlock.class ? LeavesBlock.class : BlockBehaviour.class,
                "getBlockSupportShape", BlockState.class, BlockGetter.class, BlockPos.class);
        inherited(type, Block.class, "getFriction"); inherited(type, Block.class, "getSpeedFactor");
        publicInherited(type, IBlockExtension.class, "getFriction", BlockState.class, LevelReader.class, BlockPos.class, Entity.class);
        if (block.getFriction() != 0.6F || block.getSpeedFactor() != 1.0F)
            throw new SurvivalRefusal("Native dry block movement properties changed");
    }
    static void requireItem(ItemEntity item) {
        PinnedNativeEventAdmission.requireOrdinaryItem(item.getItem());
        requireItemCallbacks(item.getItem().getItem().getClass());
    }
    /** Method-declaration guard only; never invokes an item callback or grants tick authority. */
    static void requireItemCallbacks(Class<? extends Item> type) {
        publicInherited(type, IItemExtension.class, "onEntityItemUpdate", ItemStack.class, ItemEntity.class);
        inherited(ItemEntity.class, Entity.class, "causeFallDamage", float.class, float.class, DamageSource.class);
    }
    static void requirePinnedBinaries() {
        PinnedNativeEventAdmission.requireClassBytes(Entity.class, "6a5a5b7db211b144fe88f3079a0e1d93a0c31db069acab4ac593dce8d144e8cb");
        PinnedNativeEventAdmission.requireClassBytes(ItemEntity.class, "a6d193f782bae02c8387147a2e00606441a31fd8379141ed71563f941f5a324a");
        PinnedNativeEventAdmission.requireClassBytes(ServerLevel.class, "e4e249f6a71830ab4eda69b25ea10cfe3a3e0af5d84a6926016cab18775c4c32");
        PinnedNativeEventAdmission.requireClassBytes(Block.class, "083bda462c9b9d8a23ea93edca56c137d95d3069824a6a8574c0958673d76741");
        PinnedNativeEventAdmission.requireClassBytes(BlockBehaviour.class, "c8d49cd77efa5ef69a6f09b6e8a0b62bad29797cedfa40b199493cb09b2797e5");
        PinnedNativeEventAdmission.requireClassBytes(AirBlock.class, "f639cbf623d0fe60d71998eb55e8a960c21af6f154ae07786c9658b270015b61");
        PinnedNativeEventAdmission.requireClassBytes(LeavesBlock.class, "e7667a98295a225c4e97a81262b83be6d8dd1f50f6e564303a4d81cd66c23f02");
        PinnedNativeEventAdmission.requireClassBytes(IBlockExtension.class, "4018a3041dd156b81eb158af084bfea53353fb593ffcb51dc9b87ed0cd9d0615");
    }
    private static boolean finite(double number) { return Double.isFinite(number); }
    private static void inherited(Class<?> type, Class<?> expected, String name, Class<?>... parameters) {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            try {
                Method method = current.getDeclaredMethod(name, parameters);
                if (method.getDeclaringClass() != expected) throw new SurvivalRefusal("Unadmitted native dry callback override: " + name);
                return;
            } catch (NoSuchMethodException absent) { /* Continue exact class hierarchy. */ }
        }
        throw new SurvivalRefusal("Native dry callback is unavailable: " + name);
    }
    private static void publicInherited(Class<?> type, Class<?> expected, String name, Class<?>... parameters) {
        try {
            if (type.getMethod(name, parameters).getDeclaringClass() != expected)
                throw new SurvivalRefusal("Unadmitted native dry default callback: " + name);
        } catch (NoSuchMethodException failure) { throw new SurvivalRefusal("Native dry default callback is unavailable: " + name); }
    }
}
