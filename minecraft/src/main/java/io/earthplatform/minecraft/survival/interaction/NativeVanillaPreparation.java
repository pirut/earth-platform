package io.earthplatform.minecraft.survival.interaction;

import io.earthplatform.minecraft.survival.world.CubeCell;
import io.earthplatform.minecraft.survival.world.SurvivalPalette;
import io.earthplatform.minecraft.survival.world.SurvivalRefusal;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.BlockItemStateProperties;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;

/**
 * Narrow preparation using the pinned 1.21.1/21.1.256 native implementations. This is deliberately
 * not a replacement ServerPlayerGameMode. It never commits a cube, changes a live stack, invokes a
 * live menu click, or runs the broad playerDestroy/BlockItem.place mutators. The host must capture
 * and admit the remaining callback/effect boundary described in NATIVE_CALLBACK_CONTRACT.md.
 *
 * <p>All Level reads must already route to the active declared cubic transaction. A host guard is
 * mandatory because NeoForge query, loot and recipe callbacks may otherwise escape that boundary.
 */
public final class NativeVanillaPreparation {
    public enum Callback {
        DESTROY_PROGRESS, HARVEST_CHECK, LOOT_TABLE_AND_GLOBAL_MODIFIERS,
        TOOL_DURABILITY, PLACEMENT_STATE, SURVIVAL_AND_COLLISION, RECIPE_MATCH_AND_ASSEMBLE,
        RECIPE_REMAINDERS, CRAFTING_MENU_PROVIDER
    }
    @FunctionalInterface public interface CallbackAdmission {
        /** Throw before invocation unless every possible side effect has been audited/captured. */
        void require(Callback callback);
    }

    public record ToolUse(NativeStackSnapshot before, NativeStackSnapshot after,
                          boolean awardsItemUsed, boolean durabilityChanged,
                          int durabilityCriterionValue, boolean broken) {}
    public record Mining(BlockState state, boolean harvestable, List<NativeStackSnapshot> loot, ToolUse tool) {
        public Mining { loot = List.copyOf(loot); }
    }
    public record Placement(CubeCell target, BlockState state, NativeStackSnapshot heldBefore,
                            NativeStackSnapshot heldAfter) {}
    public record Recipe(net.minecraft.resources.ResourceLocation id, int left, int top, int width, int height,
                         NativeStackSnapshot output, List<NativeStackSnapshot> remainders) {
        public Recipe { remainders = List.copyOf(remainders); }
    }

    private final SurvivalPalette palette;
    private final CallbackAdmission admission;
    public NativeVanillaPreparation(SurvivalPalette palette, CallbackAdmission admission) {
        this.palette = Objects.requireNonNull(palette); this.admission = Objects.requireNonNull(admission);
    }

    public float destroyProgress(ServerPlayer player, BlockGetter view, CubeCell target) {
        BlockPos pos = nativePos(target);
        BlockState state = view.getBlockState(pos); palette.id(state);
        requireSurvival(player); admission.require(Callback.DESTROY_PROGRESS);
        float progress = state.getDestroyProgress(player, view, pos);
        if (!Float.isFinite(progress) || progress < 0) throw new SurvivalRefusal("invalid native mining progress");
        return progress;
    }

    /** Uses native reach, including the same one-block server tolerance as handleBlockBreakAction. */
    public boolean canReach(ServerPlayer player, CubeCell target) {
        requireSurvival(player);
        return player.canInteractWithBlock(nativePos(target), 1.0);
    }

    public Mining mining(ServerPlayer player, CubeCell target, RandomSource detachedRandom) {
        requireSurvival(player); Objects.requireNonNull(detachedRandom);
        ServerLevel level = player.serverLevel(); BlockPos pos = nativePos(target);
        BlockState state = level.getBlockState(pos); palette.id(state);
        if (state.isAir() || !player.canInteractWithBlock(pos, 1.0))
            throw new SurvivalRefusal("mining target is air or outside native reach");
        ItemStack tool = player.getMainHandItem().copy(); requireSupportedTool(tool, palette);
        admission.require(Callback.HARVEST_CHECK);
        boolean harvestable = state.canHarvestBlock(level, pos, player);
        List<NativeStackSnapshot> loot = new ArrayList<>();
        if (harvestable) {
            admission.require(Callback.LOOT_TABLE_AND_GLOBAL_MODIFIERS);
            LootParams params = new LootParams.Builder(level)
                    .withParameter(LootContextParams.ORIGIN, Vec3.atCenterOf(pos))
                    .withParameter(LootContextParams.BLOCK_STATE, state)
                    .withParameter(LootContextParams.TOOL, tool.copy())
                    .withParameter(LootContextParams.THIS_ENTITY, player)
                    .create(LootContextParamSets.BLOCK);
            var table = level.getServer().reloadableRegistries().getLootTable(state.getBlock().getLootTable());
            // This overload exists in the pinned NeoForge source, applies global modifiers, and
            // supplies the detached source even when the loot table declares a random sequence.
            var drops = table.getRandomItems(params, detachedRandom);
            if (drops.size() > 128) throw new SurvivalRefusal("native loot exceeds the action drop bound");
            for (ItemStack drop : drops) {
                if (!drop.isEmpty()) loot.add(NativeStackSnapshot.capture(drop, level.registryAccess()));
            }
        }
        return new Mining(state, harvestable, loot, toolUse(player, state, pos, tool));
    }

    private ToolUse toolUse(ServerPlayer player, BlockState state, BlockPos pos, ItemStack detached) {
        ServerLevel level = player.serverLevel();
        NativeStackSnapshot before = NativeStackSnapshot.capture(detached, level.registryAccess());
        var properties = detached.get(DataComponents.TOOL);
        boolean changed = properties != null && state.getDestroySpeed(level, pos) != 0.0F
                && properties.damagePerBlock() > 0 && detached.isDamageableItem();
        boolean[] broken = {false};
        int criterionDamage = detached.getDamageValue();
        if (changed) {
            admission.require(Callback.TOOL_DURABILITY);
            criterionDamage = Math.addExact(criterionDamage, properties.damagePerBlock());
            // Null owner prevents live advancement/equipment/stat mutation. With the explicitly
            // admitted unenchanted vanilla items, native damageItem and enchant iteration are pure.
            detached.hurtAndBreak(properties.damagePerBlock(), level, (LivingEntity) null, item -> broken[0] = true);
        }
        return new ToolUse(before, NativeStackSnapshot.capture(detached, level.registryAccess()),
                properties != null, changed, criterionDamage, broken[0]);
    }

    /** Exact BlockItem placement-state path for the admitted ordinary BlockItem class. */
    public Placement placement(ServerPlayer player, InteractionHand hand, BlockHitResult hit) {
        requireSurvival(player); Objects.requireNonNull(hand); Objects.requireNonNull(hit);
        ServerLevel level = player.serverLevel(); ItemStack detached = player.getItemInHand(hand).copy();
        if (detached.isEmpty() || !(detached.getItem() instanceof BlockItem item) || item.getClass() != BlockItem.class)
            throw new SurvivalRefusal("held item is not an admitted native BlockItem");
        palette.id(item.getBlock().defaultBlockState());
        if (!item.getBlock().isEnabled(level.enabledFeatures()) || !player.canInteractWithBlock(hit.getBlockPos(), 1.0))
            throw new SurvivalRefusal("placement block is disabled or outside native reach");
        if (detached.has(DataComponents.BLOCK_ENTITY_DATA))
            throw new SurvivalRefusal("block-entity item components are unsupported");
        // Native BlockItem applies this patch with a second write after the first neighbor pass.
        // Refuse it until the host can stage both callback passes, rather than collapse semantics.
        if (!detached.getOrDefault(DataComponents.BLOCK_STATE, BlockItemStateProperties.EMPTY).isEmpty())
            throw new SurvivalRefusal("custom placed-state component requires two captured neighbor passes");
        NativeStackSnapshot before = NativeStackSnapshot.capture(detached, level.registryAccess());
        admission.require(Callback.PLACEMENT_STATE);
        BlockPlaceContext original = new BlockPlaceContext(new UseOnContext(level, player, hand, detached, hit));
        if (!original.canPlace()) throw new SurvivalRefusal("native placement context cannot replace target");
        BlockPlaceContext context = item.updatePlacementContext(original);
        if (context == null) throw new SurvivalRefusal("native item rejected placement context");
        BlockState state = item.getBlock().getStateForPlacement(context);
        if (state == null) throw new SurvivalRefusal("native block rejected placement state");
        palette.id(state); admission.require(Callback.SURVIVAL_AND_COLLISION);
        if (!state.canSurvive(level, context.getClickedPos())
                || !level.isUnobstructed(state, context.getClickedPos(), CollisionContext.of(player)))
            throw new SurvivalRefusal("native survival or collision check rejected placement");
        detached.consume(1, player);
        BlockPos pos = context.getClickedPos();
        return new Placement(CubeCell.of(pos.getX(), pos.getY(), pos.getZ()), state, before,
                NativeStackSnapshot.capture(detached, level.registryAccess()));
    }

    /** A crafting table is a MenuProvider. No BlockEntity is invented or required. */
    public MenuProvider craftingMenu(ServerPlayer player, CubeCell target) {
        requireSurvival(player); BlockPos pos = nativePos(target); ServerLevel level = player.serverLevel();
        BlockState state = level.getBlockState(pos);
        if (!state.is(Blocks.CRAFTING_TABLE) || !player.canInteractWithBlock(pos, 1.0))
            throw new SurvivalRefusal("crafting table is missing or outside native reach");
        admission.require(Callback.CRAFTING_MENU_PROVIDER);
        MenuProvider provider = state.getMenuProvider(level, pos);
        if (provider == null) throw new SurvivalRefusal("native crafting table has no menu provider");
        return provider;
    }

    /** Native recipe match/assemble/remainders over detached inputs; no custom recipe table. */
    public Recipe recipe(ServerLevel level, int width, int height, List<ItemStack> input) {
        if (width < 1 || height < 1 || width > 3 || height > 3 || input.size() != width * height)
            throw new SurvivalRefusal("crafting input exceeds an ordinary native crafting grid");
        List<ItemStack> copy = input.stream().map(ItemStack::copy).toList();
        CraftingInput.Positioned positioned = CraftingInput.ofPositioned(width, height, copy);
        admission.require(Callback.RECIPE_MATCH_AND_ASSEMBLE);
        var holder = level.getRecipeManager().getRecipeFor(RecipeType.CRAFTING, positioned.input(), level)
                .orElseThrow(() -> new SurvivalRefusal("no native crafting recipe matches"));
        ItemStack output = holder.value().assemble(positioned.input(), level.registryAccess());
        if (output.isEmpty() || !output.isItemEnabled(level.enabledFeatures()))
            throw new SurvivalRefusal("native recipe produced an unavailable output");
        admission.require(Callback.RECIPE_REMAINDERS);
        var remainder = holder.value().getRemainingItems(positioned.input());
        if (remainder.size() != positioned.input().size()) throw new SurvivalRefusal("invalid native recipe remainder shape");
        return new Recipe(holder.id(), positioned.left(), positioned.top(), positioned.input().width(),
                positioned.input().height(), NativeStackSnapshot.capture(output, level.registryAccess()),
                remainder.stream().map(stack -> NativeStackSnapshot.capture(stack, level.registryAccess())).toList());
    }

    public static BlockPos nativePos(CubeCell cell) {
        Objects.requireNonNull(cell);
        return new BlockPos(Math.toIntExact(cell.x()), Math.toIntExact(cell.y()), Math.toIntExact(cell.z()));
    }
    private static void requireSurvival(ServerPlayer player) {
        Objects.requireNonNull(player);
        if (!player.server.isSameThread() || player.gameMode.getGameModeForPlayer() != GameType.SURVIVAL || player.hasInfiniteMaterials())
            throw new SurvivalRefusal("native preparation requires the server thread and genuine survival mode");
    }
    static void requireSupportedTool(ItemStack tool, SurvivalPalette palette) {
        if (!tool.isEmpty() && !tool.is(Items.WOODEN_PICKAXE) && !tool.is(Items.STONE_PICKAXE)
                && !(tool.getItem() instanceof BlockItem) && !tool.is(Items.STICK))
            throw new SurvivalRefusal("held item mining callback is outside the admitted vanilla progression");
        if (!tool.isEmpty() && tool.getItem() instanceof BlockItem item) requireOrdinaryMiningBlockItem(item, palette);
        if (!tool.getOrDefault(DataComponents.ENCHANTMENTS, ItemEnchantments.EMPTY).isEmpty())
            throw new SurvivalRefusal("enchantment callbacks require a separate captured action capability");
    }
    static void requireOrdinaryMiningBlockItem(BlockItem item, SurvivalPalette palette) {
        // The class check must precede even getBlock: a subclass can override that query too.
        if (item.getClass() != BlockItem.class)
            throw new SurvivalRefusal("custom BlockItem mining callbacks are outside the admitted capability");
        palette.id(item.getBlock().defaultBlockState());
    }
}
