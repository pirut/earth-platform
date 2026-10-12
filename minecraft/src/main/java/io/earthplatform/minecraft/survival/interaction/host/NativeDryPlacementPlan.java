package io.earthplatform.minecraft.survival.interaction.host;

import io.earthplatform.minecraft.survival.host.NativeDryMutationRouter;
import io.earthplatform.minecraft.survival.host.SurvivalLevelBinding;
import io.earthplatform.minecraft.survival.interaction.NativeEffectCodec;
import io.earthplatform.minecraft.survival.interaction.NativeStackSnapshot;
import io.earthplatform.minecraft.survival.interaction.NativeVanillaPreparation;
import io.earthplatform.minecraft.survival.interaction.NativeWholeAction;
import io.earthplatform.minecraft.survival.persistence.NativeInventorySnapshot;
import io.earthplatform.minecraft.survival.world.CubeCell;
import io.earthplatform.minecraft.survival.world.SurvivalRefusal;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CraftingTableBlock;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.neoforged.neoforge.common.extensions.IBlockExtension;

/** Exact first-table BlockItem.place semantics, prepared against the native dry mutation router. */
public final class NativeDryPlacementPlan {
    public record Prepared(NativeVanillaPreparation.Placement placement,
            NativeEffectCodec.PlacementSound sound, NativeEffectCodec.BlockPlaceEvent event,
            int rejectingPlacedBlockCriteria, net.minecraft.world.InteractionResult result) {}
    private final NativeEffectDeliveryHost host;
    private final NativeWholeAction action;
    private final ServerPlayer player;
    private final int selected,counterBefore;
    private final long commitBefore;
    private final Prepared prepared;
    private java.util.List<io.earthplatform.minecraft.survival.world.NativeActionEffect> encoded;
    private io.earthplatform.minecraft.survival.world.NativeSurvivalWorld.CommitReceipt authorized;
    private boolean verified;
    private NativeDryPlacementPlan(NativeEffectDeliveryHost host,NativeWholeAction action,BlockHitResult hit) {
        this.host=host;this.action=action;this.player=host.player();
        selected=player.getInventory().selected;counterBefore=player.getInventory().getTimesChanged();commitBefore=action.world().commitSequence();
        if (action.world().usage().transactionWrites()!=0 || action.world().usage().transactionEffects()!=1)
            throw refused("Private native table placement must start before other staged writes/effects");
        prepared=prepareExact(host,action,hit);
    }
    Prepared prepared() { return prepared; }
    void requireBeforeCommit(NativeWholeAction exact,java.util.List<NativeEffectCodec.Effect> effects) {
        requireAction(exact,NativeWholeAction.State.PREVALIDATING);
        if (authorized!=null || verified || action.world().usage().transactionWrites()!=1 || effects.size()!=5)
            throw refused("Private native table placement acquired unrelated writes/effects");
        var notification=new NativeEffectCodec.BlockNotification(action.world().world(),prepared.placement().target(),
                action.world().palette().id(Blocks.AIR.defaultBlockState()),action.world().palette().id(Blocks.CRAFTING_TABLE.defaultBlockState()),11,512);
        var inventory=new NativeEffectCodec.InventorySlot(selected,prepared.placement().heldBefore(),prepared.placement().heldAfter());
        if (!(effects.get(0) instanceof NativeEffectCodec.RandomSeed) || !effects.get(1).equals(notification)
                || !effects.get(2).equals(prepared.sound()) || !effects.get(3).equals(prepared.event()) || !effects.get(4).equals(inventory))
            throw refused("Private native table placement lost its exact ordered native outcome");
        requireLiveBefore();var image=effects.stream().map(NativeEffectCodec::encode).toList();
        if (encoded!=null && !encoded.equals(image)) throw refused("Private native placement effect image changed");encoded=image;
    }
    void requireBeforeApply(NativeWholeAction exact,io.earthplatform.minecraft.survival.world.NativeSurvivalWorld.CommitReceipt receipt) {
        requireAction(exact,NativeWholeAction.State.APPLYING);
        if (authorized!=null || verified || encoded==null || !receipt.effects().equals(encoded) || !receipt.action().equals(action.identity())
                || !receipt.world().equals(action.world().world()) || !receipt.ownerEpoch().equals(action.world().ownerEpoch())
                || receipt.commitSequence()!=Math.addExact(commitBefore,1) || receipt.commitSequence()!=action.world().commitSequence()
                || !receipt.changedCubes().keySet().equals(java.util.Set.of(prepared.placement().target().cube()))
                || receipt.changedCubes().get(prepared.placement().target().cube()).blocks().get(prepared.placement().target().localIndex())
                    !=prepared.event().affectedStateId())
            throw refused("Private native table placement lacks its exact accepted receipt");
        requireLiveBefore();authorized=receipt;
    }
    void verifyAfter(io.earthplatform.minecraft.survival.world.NativeSurvivalWorld.CommitReceipt receipt) {
        requireAction(action,NativeWholeAction.State.APPLYING);
        if (receipt!=authorized || verified) throw refused("Private native placement delivery is foreign or repeated");
        verified=true;
        if (player.getInventory().selected!=selected || player.getInventory().getTimesChanged()!=counterBefore
                || !prepared.placement().heldAfter().matches(player.getMainHandItem(),player.registryAccess()))
            throw refused("Native table placement inventory/counter differs from exact native consume semantics");
    }
    private void requireLiveBefore() {
        NativeInventorySnapshot.requireResolvedMenu(player);
        if (host.player()!=player || player.serverLevel()!=host.level() || player.getInventory().selected!=selected
                || player.getInventory().getTimesChanged()!=counterBefore
                || !prepared.placement().heldBefore().matches(player.getMainHandItem(),player.registryAccess()))
            throw refused("Private native placement held stack/counter changed before delivery");
    }
    private void requireAction(NativeWholeAction exact,NativeWholeAction.State state) {
        if (exact!=action || NativeWholeAction.current()!=action || !host.actions().owns(action) || action.state()!=state)
            throw refused("Private native placement action capability is foreign or stale");
    }

    /** Native gameplay callbacks run once here; only typed admitted effects cross the receipt. */
    static NativeDryPlacementPlan prepare(NativeEffectDeliveryHost host, NativeWholeAction action, BlockHitResult hit) {
        try { return new NativeDryPlacementPlan(host,action,hit); }
        catch (RuntimeException | Error failure) {
            if (action!=null && action.state()==NativeWholeAction.State.PREPARING
                    && host!=null && host.actions().owns(action)) action.poisonPreparation();
            throw failure;
        }
    }
    private static Prepared prepareExact(NativeEffectDeliveryHost host,NativeWholeAction action,BlockHitResult hit) {
        if (host==null || action==null || hit==null || NativeWholeAction.current()!=action
                || !host.actions().owns(action) || action.state()!=NativeWholeAction.State.PREPARING)
            throw refused("Native table placement requires its exact preparing host action");
        ServerPlayer player=host.player(); var level=host.level();
        var binding=SurvivalLevelBinding.find(level);
        if (binding==null || binding.actions()!=host.actions() || binding.world()!=action.world()
                || NativeInventoryMenuSnapshot.read(SurvivalLevelBinding.class,binding,"mutations",SurvivalLevelBinding.MutationRouter.class)
                    .getClass()!=NativeDryMutationRouter.class)
            throw refused("Native table placement requires its exact admitted dry mutation router");
        NativeInventorySnapshot.requireResolvedMenu(player);
        ItemStack live=player.getMainHandItem();
        if (live.getClass()!=ItemStack.class || !live.is(Items.CRAFTING_TABLE) || live.getItem().getClass()!=BlockItem.class
                || !live.getComponentsPatch().isEmpty() || Blocks.CRAFTING_TABLE.getClass()!=CraftingTableBlock.class)
            throw refused("First native placement requires the unpatched earned crafting table");
        NativeStackSnapshot before=NativeStackSnapshot.capture(live,level.registryAccess());
        if (!before.matches(action.stack(action.selectedSlot()),level.registryAccess()))
            throw refused("Native placement live and detached held stacks disagree");
        requireCallbacks();
        // Native context asks both the hit block and, if needed, the adjacent target about replacement.
        BlockState clicked=action.read(cell(hit.getBlockPos()));
        BlockPos adjacentPos=checkedAdjacent(hit);
        BlockState adjacent=action.read(cell(adjacentPos));
        requireReplacement(clicked); requireReplacement(adjacent);
        var preparation=new NativeVanillaPreparation(action.world().palette(),callback->{
            if (callback!=NativeVanillaPreparation.Callback.PLACEMENT_STATE
                    && callback!=NativeVanillaPreparation.Callback.SURVIVAL_AND_COLLISION)
                throw refused("Unexpected native table preparation callback: "+callback);
        });
        var prepared=preparation.placement(player,InteractionHand.MAIN_HAND,hit);
        if (!prepared.state().is(Blocks.CRAFTING_TABLE) || !prepared.heldBefore().equals(before))
            throw refused("Native placement preparation changed its admitted state or stack");
        BlockPos pos=NativeVanillaPreparation.nativePos(prepared.target());
        if (!action.read(prepared.target()).is(Blocks.AIR)) throw refused("First private native table placement requires an exact AIR target");
        if (!level.setBlock(pos,prepared.state(),11)) throw refused("Native dry table placement did not replace its target");
        BlockState placed=level.getBlockState(pos);
        if (placed!=prepared.state() || placed.hasBlockEntity() || level.getBlockEntity(pos)!=null)
            throw refused("Native placed table acquired a foreign state or block entity");
        // Empty state/custom-BE patches were proved before preparation. Both native update paths
        // are no-ops; the real inherited setPlacedBy and rejecting criterion execute exactly once.
        placed.getBlock().setPlacedBy(level,pos,placed,player,live);
        var criterion=NativePlacedBlockCriterionAdmission.capture(player,action,prepared.target(),live);
        criterion.invokeNoEffect(live);
        SoundType sound=placed.getSoundType(level,pos,player);
        SoundType placeSound=placed.getSoundType(level,pos,player); // Native BlockItem.getPlaceSound query.
        if (sound.getClass()!=SoundType.class || placeSound.getClass()!=SoundType.class)
            throw refused("Custom native placement sound type");
        var effect=new NativeEffectCodec.PlacementSound(BuiltInRegistries.SOUND_EVENT.getKey(placeSound.getPlaceSound()),
                SoundSource.BLOCKS.getName(),pos.getX()+0.5,pos.getY()+0.5,pos.getZ()+0.5,
                (sound.getVolume()+1.0F)/2.0F,sound.getPitch()*0.8F,action.random().nextLong(),player.getUUID());
        var event=new NativeEffectCodec.BlockPlaceEvent(action.world().world(),prepared.target(),player.getUUID(),
                action.world().palette().id(placed));
        action.stage(effect); action.stage(event);
        action.replaceStack(action.selectedSlot(),prepared.heldAfter().materialize(level.registryAccess()));
        if (!before.matches(live,level.registryAccess())) throw refused("Native placement preparation mutated the live held stack");
        return new Prepared(prepared,effect,event,criterion.criterionCount(),net.minecraft.world.InteractionResult.CONSUME);
    }
    private static void requireReplacement(BlockState state) {
        if (!(state.isAir() || state.is(Blocks.GRASS_BLOCK) || state.is(Blocks.DIRT) || state.is(Blocks.STONE)
                || state.is(Blocks.COBBLESTONE))) throw refused("First table placement hit/adjacent state is outside the dry surface profile");
        declared(state.getBlock().getClass(),"canBeReplaced",BlockBehaviour.class,BlockState.class,BlockPlaceContext.class);
    }
    static void requireCallbacks() {
        Class<?> type=Blocks.CRAFTING_TABLE.getClass();
        declared(type,"getStateForPlacement",Block.class,BlockPlaceContext.class);
        declared(type,"canSurvive",BlockBehaviour.class,BlockState.class,LevelReader.class,BlockPos.class);
        declared(type,"setPlacedBy",Block.class,Level.class,BlockPos.class,BlockState.class,LivingEntity.class,ItemStack.class);
        try {
            if (type.getMethod("getSoundType",BlockState.class,LevelReader.class,BlockPos.class,Entity.class).getDeclaringClass()!=IBlockExtension.class)
                throw refused("Custom native table sound callback");
        } catch (ReflectiveOperationException failure) { throw refused("Native table sound callback descriptor unavailable"); }
    }
    private static void declared(Class<?> type,String name,Class<?> expected,Class<?>...arguments) {
        for (Class<?> current=type;current!=null;current=current.getSuperclass()) {
            try { if (current.getDeclaredMethod(name,arguments).getDeclaringClass()!=expected)
                    throw refused("Custom native table callback: "+name); return; }
            catch (NoSuchMethodException ignored) { }
        }
        throw refused("Native table callback descriptor unavailable: "+name);
    }
    static BlockPos checkedAdjacent(BlockHitResult hit) {
        var pos=hit.getBlockPos();var direction=hit.getDirection();
        try { return new BlockPos(Math.addExact(pos.getX(),direction.getStepX()),Math.addExact(pos.getY(),direction.getStepY()),Math.addExact(pos.getZ(),direction.getStepZ())); }
        catch (ArithmeticException overflow) { throw refused("Native placement adjacent coordinate exceeds exact signed BlockPos domain"); }
    }
    private static CubeCell cell(BlockPos pos) { return CubeCell.of(pos.getX(),pos.getY(),pos.getZ()); }
    private static SurvivalRefusal refused(String message) { return new SurvivalRefusal(message); }
}
