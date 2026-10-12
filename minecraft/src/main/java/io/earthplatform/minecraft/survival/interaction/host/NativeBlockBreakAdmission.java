package io.earthplatform.minecraft.survival.interaction.host;

import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import io.earthplatform.minecraft.survival.interaction.NativeVanillaPreparation;
import io.earthplatform.minecraft.survival.world.CubeCell;
import io.earthplatform.minecraft.survival.world.SurvivalRefusal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.GrassBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.storage.loot.LootTable;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.NeoForgeEventHandler;
import net.neoforged.neoforge.common.extensions.IBlockExtension;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.level.BlockDropsEvent;
import net.neoforged.neoforge.event.level.BlockEvent;

/** Source-closed bare-hand grass/dirt first proof. It never manufactures loot or tool rules. */
final class NativeBlockBreakAdmission implements NativeVanillaPreparation.CallbackAdmission {
    private final ServerPlayer player;
    private final ServerLevel level;
    private final Object resourceManager, registries, reloadable;
    private final BlockState before;
    private final LootTable loaded, reference;
    private final ResourceLocation lootId;
    private final PinnedNativeEventAdmission events;

    private NativeBlockBreakAdmission(ServerPlayer player, BlockState before) {
        this.player = player; this.level = player.serverLevel(); this.before = before;
        resourceManager = player.server.getResourceManager(); registries = level.registryAccess();
        reloadable = player.server.reloadableRegistries();
        events = new PinnedNativeEventAdmission(NeoForge.EVENT_BUS);
        requirePlayer(); requireTarget(before); requireCallbacks();
        lootId = before.getBlock().getLootTable().location();
        loaded = player.server.reloadableRegistries().getLootTable(before.getBlock().getLootTable());
        byte[] bytes = originalResource(before.is(Blocks.GRASS_BLOCK) ? "grass_block" : "dirt");
        // Only checksum-verified original data is decoded here. The actual loaded graph is never encoded or evaluated for comparison.
        requireNativeDecoders(); requireNativeLootItems();
        reference = LootTable.DIRECT_CODEC.parse(RegistryOps.create(JsonOps.INSTANCE, level.registryAccess()),
                JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8))).getOrThrow();
        NativeBlockLootGraph.requireEquivalent(loaded, reference, lootId);
    }
    static NativeBlockBreakAdmission capture(ServerPlayer player, BlockState before) {
        return new NativeBlockBreakAdmission(player, before);
    }
    void revalidate() {
        requirePlayer(); requireTarget(before); requireCallbacks();
        if (player.server.getResourceManager() != resourceManager || level.registryAccess() != registries
                || player.server.reloadableRegistries() != reloadable
                || player.server.reloadableRegistries().getLootTable(before.getBlock().getLootTable()) != loaded)
            throw refused("Native break resources/reloadable graph changed");
        NativeBlockLootGraph.requireEquivalent(loaded, reference, lootId);
    }
    @Override public void require(NativeVanillaPreparation.Callback callback) {
        revalidate();
        if (callback != NativeVanillaPreparation.Callback.DESTROY_PROGRESS
                && callback != NativeVanillaPreparation.Callback.HARVEST_CHECK
                && callback != NativeVanillaPreparation.Callback.LOOT_TABLE_AND_GLOBAL_MODIFIERS)
            throw refused("Callback is outside the initial empty-hand break proof: " + callback);
    }
    void requirePlayer() {
        requireTrustedAudiences(player); requireEmptyInventory(player); requireStatistics(player);
        if (player.serverLevel() != level || !player.server.isSameThread()
                || player.gameMode.getGameModeForPlayer() != GameType.SURVIVAL || player.hasInfiniteMaterials()
                || player.isSpectator() || !player.getAbilities().mayBuild || player.isRemoved() || !player.isAlive()
                || level.captureBlockSnapshots || level.restoringBlockSnapshots
                || !level.getGameRules().getBoolean(GameRules.RULE_DOBLOCKDROPS))
            throw refused("Native server break proof requires exact ordinary empty-handed survival and native tile drops");
    }
    /** Exact original global/local audience backing, checked before any callback-capable collection method. */
    static void requireTrustedAudiences(ServerPlayer player) {
        if (player == null || player.getClass() != ServerPlayer.class
                || player.server.getClass() != net.minecraft.gametest.framework.GameTestServer.class)
            throw refused("First native break requires exact GameTestServer/player classes");
        NativeBlockBreakPins.require(net.minecraft.gametest.framework.GameTestServer.class);
        NativeBlockBreakPins.require(net.minecraft.server.MinecraftServer.class);
        NativeBlockBreakPins.require(net.minecraft.server.players.PlayerList.class);
        Class<?> exactList;
        try { exactList = Class.forName("net.minecraft.gametest.framework.GameTestServer$1",false,
                    net.minecraft.gametest.framework.GameTestServer.class.getClassLoader()); }
        catch (ClassNotFoundException failure) { throw refused("Pinned native GameTest player-list class unavailable"); }
        NativeBlockBreakPins.require(exactList);
        var players = player.server.getPlayerList();
        if (players == null || players.getClass() != exactList || player.serverLevel().getClass() != ServerLevel.class)
            throw refused("First native break requires exact native registered PlayerList/Level");
        Object global = NativeInventoryMenuSnapshot.read(net.minecraft.server.players.PlayerList.class,players,"players",List.class);
        Object index = NativeInventoryMenuSnapshot.read(net.minecraft.server.players.PlayerList.class,players,"playersByUUID",java.util.Map.class);
        Object local = NativeInventoryMenuSnapshot.read(ServerLevel.class,player.serverLevel(),"players",List.class);
        if (global == null || global.getClass() != java.util.ArrayList.class
                || local == null || local.getClass() != java.util.ArrayList.class
                || index == null || index.getClass() != java.util.HashMap.class)
            throw refused("Foreign native global/local break audience or player index collection");
        List<?> all=(List<?>)global, here=(List<?>)local; java.util.Map<?,?> byId=(java.util.Map<?,?>)index;
        if (all.size()!=1 || all.getFirst()!=player || here.size()!=1 || here.getFirst()!=player
                || byId.size()!=1 || byId.get(player.getUUID())!=player)
            throw refused("Native 2001 break metadata requires exact single registered global/local audience");
    }
    /** Read-only native stat/scoreboard closure before getValue/awardStat may touch their backing maps. */
    static void requireStatistics(ServerPlayer player) {
        NativeBlockBreakPins.require(net.minecraft.stats.Stat.class);
        NativeBlockBreakPins.require(net.minecraft.stats.StatType.class);
        NativeBlockBreakPins.require(net.minecraft.stats.StatsCounter.class);
        NativeBlockBreakPins.require(net.minecraft.stats.ServerStatsCounter.class);
        NativeBlockBreakPins.require(net.minecraft.world.scores.criteria.ObjectiveCriteria.class);
        NativeBlockBreakPins.require(net.minecraft.world.scores.Scoreboard.class);
        NativeBlockBreakPins.require(net.minecraft.server.ServerScoreboard.class);
        var stats=player.getStats();var scoreboard=player.getScoreboard();
        if(stats.getClass()!=net.minecraft.stats.ServerStatsCounter.class||scoreboard.getClass()!=net.minecraft.server.ServerScoreboard.class
                ||net.minecraft.stats.Stats.BLOCK_MINED.getClass()!=net.minecraft.stats.StatType.class)
            throw refused("Native break stat/scoreboard implementation changed");
        usingNativeCodec("mined",net.minecraft.core.registries.BuiltInRegistries.STAT_TYPE,net.minecraft.stats.Stats.BLOCK_MINED);
        Object raw=NativeInventoryMenuSnapshot.read(net.minecraft.stats.StatsCounter.class,stats,"stats",it.unimi.dsi.fastutil.objects.Object2IntMap.class);
        Class<?> synchronizedMap=it.unimi.dsi.fastutil.objects.Object2IntMaps.synchronize(new it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap<>()).getClass();
        if(raw==null||raw.getClass()!=synchronizedMap)throw refused("Native stats synchronized map replaced");
        Object map=NativeInventoryMenuSnapshot.read(synchronizedMap,raw,"map",it.unimi.dsi.fastutil.objects.Object2IntMap.class);
        Class<?> functions=synchronizedMap.getSuperclass();
        Object function=NativeInventoryMenuSnapshot.read(functions,raw,"function",it.unimi.dsi.fastutil.objects.Object2IntFunction.class);
        Object monitor=NativeInventoryMenuSnapshot.read(functions,raw,"sync",Object.class);
        if(map==null||map.getClass()!=it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap.class||function!=map||monitor!=raw)
            throw refused("Native stats wrapper lost its constructor backing/monitor provenance");
        Object dirty=NativeInventoryMenuSnapshot.read(net.minecraft.stats.ServerStatsCounter.class,stats,"dirty",java.util.Set.class);
        Object cache=NativeInventoryMenuSnapshot.read(net.minecraft.stats.StatType.class,net.minecraft.stats.Stats.BLOCK_MINED,"map",java.util.Map.class);
        Object names=NativeInventoryMenuSnapshot.read(net.minecraft.world.scores.Scoreboard.class,scoreboard,"objectivesByName",it.unimi.dsi.fastutil.objects.Object2ObjectMap.class);
        Object criteria=NativeInventoryMenuSnapshot.read(net.minecraft.world.scores.Scoreboard.class,scoreboard,"objectivesByCriteria",it.unimi.dsi.fastutil.objects.Reference2ObjectMap.class);
        if(dirty==null||dirty.getClass()!=java.util.HashSet.class||cache==null||cache.getClass()!=java.util.IdentityHashMap.class
                ||names==null||names.getClass()!=it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap.class
                ||criteria==null||criteria.getClass()!=it.unimi.dsi.fastutil.objects.Reference2ObjectOpenHashMap.class)
            throw refused("Native statistic cache/dirty/scoreboard collection replaced");
        if(((java.util.Set<?>)dirty).size()>8192||((it.unimi.dsi.fastutil.objects.Object2IntMap<?>)map).size()>8192
                ||((java.util.Map<?,?>)cache).size()>8192
                ||!((java.util.Map<?,?>)names).isEmpty()||!((java.util.Map<?,?>)criteria).isEmpty())
            throw refused("Native statistic metadata bound or no-scoreboard-effect profile exceeded");
        // Iterate the already-qualified native containers before any keyed lookup can call Stat.hashCode/equals.
        for(Object key:((it.unimi.dsi.fastutil.objects.Object2IntMap<?>)map).keySet()) requireExactStatKey(key);
        for(Object key:(java.util.Set<?>)dirty) requireExactStatKey(key);
        requireNativeMinedRegistry();
        // IdentityHashMap traversal cannot invoke foreign block keys. Reject every replaced cache value before get(target).
        for(var entry:((java.util.Map<?,?>)cache).entrySet()) {
            var stat=requireExactStatKey(entry.getValue());
            if(stat.getType()!=net.minecraft.stats.Stats.BLOCK_MINED||stat.getValue()!=entry.getKey())
                throw refused("Native mined stat cache changed its exact type/value lineage");
        }
        for(Block target:List.of(Blocks.GRASS_BLOCK,Blocks.DIRT)) {
            Object cached=((java.util.Map<?,?>)cache).get(target);
            if(cached!=null) {
                var stat=requireExactStatKey(cached);
                String expected=net.minecraft.stats.Stat.buildName(net.minecraft.stats.Stats.BLOCK_MINED,target);
                if(stat.getType()!=net.minecraft.stats.Stats.BLOCK_MINED||stat.getValue()!=target||!expected.equals(stat.getName()))
                    throw refused("Native target mined stat identity/name changed");
            }
        }
    }
    static void requireNativeMinedRegistry() {
        NativeBlockBreakPins.require(net.minecraft.stats.StatType.class);
        NativeBlockBreakPins.require(net.minecraft.core.DefaultedMappedRegistry.class);
        if(net.minecraft.stats.Stats.BLOCK_MINED.getClass()!=net.minecraft.stats.StatType.class
                ||net.minecraft.stats.Stats.BLOCK_MINED.getRegistry()!=net.minecraft.core.registries.BuiltInRegistries.BLOCK
                ||net.minecraft.core.registries.BuiltInRegistries.BLOCK.getClass()!=net.minecraft.core.DefaultedMappedRegistry.class)
            throw refused("Native mined stat registry identity/type changed");
    }
    static net.minecraft.stats.Stat<?> requireExactStatKey(Object key) {
        if(key==null||key.getClass()!=net.minecraft.stats.Stat.class)
            throw refused("Foreign statistic key could execute during native lookup or award");
        var stat=(net.minecraft.stats.Stat<?>)key;
        if(stat.getName()==null)throw refused("Native statistic key lost its immutable name");
        return stat;
    }
    static void requireEmptyInventory(ServerPlayer player) {
        if (player.getClass() != ServerPlayer.class || player.getInventory().getClass() != Inventory.class)
            throw refused("Initial break proof requires exact native player/inventory classes");
        NativeSessionMenuBoundary.requireTrustedLiveCollections(player);
        if (player.getInventory().getContainerSize() != 41 || player.getInventory().selected < 0 || player.getInventory().selected > 8)
            throw refused("Initial break proof requires exact native inventory shape");
        for (int i = 0; i < 41; i++) {
            ItemStack stack = player.getInventory().getItem(i);
            if (stack == null || stack.getClass() != ItemStack.class || !stack.isEmpty())
                throw refused("Initial grass/dirt proof requires all 41 inventory slots empty");
        }
    }
    static void requireTarget(BlockState state) {
        if (!(state.is(Blocks.GRASS_BLOCK) && state.getBlock().getClass() == GrassBlock.class
                || state.is(Blocks.DIRT) && state.getBlock().getClass() == Block.class)
                || state.hasBlockEntity() || !state.getFluidState().isEmpty() || state.is(BlockTags.GUARDED_BY_PIGLINS))
            throw refused("Initial native break target must be exact dry grass or dirt without piglin effects");
        NativeBlockBreakPins.requireCallbackRoots();
        Class<?> type = state.getBlock().getClass();
        declared(type, "playerWillDestroy", Block.class, Level.class, BlockPos.class, BlockState.class, Player.class);
        declared(type, "spawnDestroyParticles", Block.class, Level.class, Player.class, BlockPos.class, BlockState.class);
        declared(type, "playerDestroy", Block.class, Level.class, Player.class, BlockPos.class, BlockState.class, BlockEntity.class, ItemStack.class);
        declared(type, "destroy", Block.class, LevelAccessor.class, BlockPos.class, BlockState.class);
        declared(type, "getDestroyProgress", BlockBehaviour.class, BlockState.class, Player.class, BlockGetter.class, BlockPos.class);
        declared(type, "getDrops", BlockBehaviour.class, BlockState.class, net.minecraft.world.level.storage.loot.LootParams.Builder.class);
        declared(type, "spawnAfterBreak", BlockBehaviour.class, BlockState.class, ServerLevel.class, BlockPos.class, ItemStack.class, boolean.class);
        inherited(type, "onDestroyedByPlayer", IBlockExtension.class, BlockState.class, Level.class, BlockPos.class, Player.class, boolean.class, FluidState.class);
        inherited(type, "canHarvestBlock", IBlockExtension.class, BlockState.class, BlockGetter.class, BlockPos.class, Player.class);
        inherited(type, "getExpDrop", IBlockExtension.class, BlockState.class, LevelAccessor.class, BlockPos.class, BlockEntity.class, Entity.class, ItemStack.class);
    }
    static void requireNeighbor(BlockState state) {
        if (!(state.isAir() || state.is(Blocks.GRASS_BLOCK) || state.is(Blocks.DIRT)
                || state.is(Blocks.STONE) || state.is(Blocks.COBBLESTONE)))
            throw refused("Initial native break neighborhood has unsupported shape/tick semantics");
        if (state.hasBlockEntity() || !state.getFluidState().isEmpty()) throw refused("Dry native break neighborhood required");
        // The existing exact NativeDryMutationRouter owns onRemove/onPlace/neighbor callbacks.
        // Only native grass has a different shape callback in this initial neighborhood.
        declared(state.getBlock().getClass(), "updateShape", state.is(Blocks.GRASS_BLOCK)
                ? net.minecraft.world.level.block.SnowyDirtBlock.class : BlockBehaviour.class,
                BlockState.class, net.minecraft.core.Direction.class, BlockState.class,
                LevelAccessor.class, BlockPos.class, BlockPos.class);
    }
    private void requireCallbacks() {
        events.requireDeliveryCallbacks();
        events.requireEmpty(PlayerEvent.BreakSpeed.class); events.requireEmpty(PlayerEvent.HarvestCheck.class);
        events.requireEmpty(BlockEvent.BreakEvent.class); events.requireEmpty(BlockEvent.NeighborNotifyEvent.class);
        events.requireEmpty(BlockDropsEvent.class);
        events.requireEmpty(net.neoforged.neoforge.event.enchanting.GetEnchantmentLevelEvent.class);
        if (net.neoforged.neoforge.server.ServerLifecycleHooks.getCurrentServer() != player.server)
            throw refused("Native enchantment lookup would resolve a foreign server");
        inherited(net.minecraft.world.item.Items.AIR.getClass(), "getAllEnchantments", net.neoforged.neoforge.common.extensions.IItemExtension.class,
                ItemStack.class, net.minecraft.core.HolderLookup.RegistryLookup.class);
        requireNativeDecoders(); requireNativeLootItems();
        try {
            var method = NeoForgeEventHandler.class.getDeclaredMethod("getLootModifierManager");
            if (!java.lang.reflect.Modifier.isStatic(method.getModifiers())
                    || method.getReturnType() != net.neoforged.neoforge.common.loot.LootModifierManager.class
                    || !method.trySetAccessible()) throw refused("Native loot modifier census unavailable");
            var manager = (net.neoforged.neoforge.common.loot.LootModifierManager) method.invoke(null);
            if (manager == null || manager.getClass() != net.neoforged.neoforge.common.loot.LootModifierManager.class)
                throw refused("Native global loot modifier manager implementation changed");
            requireEmptyLootModifierBacking(manager);
        } catch (ReflectiveOperationException | SecurityException failure) { throw refused("Native loot modifier census unavailable"); }
    }
    static void requireEmptyLootModifierBacking(net.neoforged.neoforge.common.loot.LootModifierManager manager) {
        if(manager==null||manager.getClass()!=net.neoforged.neoforge.common.loot.LootModifierManager.class)
            throw refused("Native global loot modifier manager implementation changed");
        NativeBlockBreakPins.require(net.neoforged.neoforge.common.loot.LootModifierManager.class);
        Object modifiers=NativeInventoryMenuSnapshot.read(net.neoforged.neoforge.common.loot.LootModifierManager.class,
                manager,"registeredLootModifiers",java.util.Map.class);
        // Both the native constructor and empty native reload builder use this immutable singleton.
        // Do not call getAllLootMods()/values()/isEmpty() on a caller-replaced map merely to discover emptiness.
        if(modifiers!=com.google.common.collect.ImmutableMap.of())
            throw refused("Native break proof requires the exact empty original loot-modifier backing");
    }
    private static void requireNativeLootItems() {
        for (var pair : List.of(java.util.Map.entry(net.minecraft.world.item.Items.DIRT,Blocks.DIRT),
                java.util.Map.entry(net.minecraft.world.item.Items.GRASS_BLOCK,Blocks.GRASS_BLOCK))) {
            var item=pair.getKey();var block=pair.getValue();
            if (item.getClass()!=net.minecraft.world.item.BlockItem.class
                    || ((net.minecraft.world.item.BlockItem)item).getBlock()!=block
                    || net.minecraft.core.registries.BuiltInRegistries.ITEM.get(
                        net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(block))!=item)
                throw refused("Native break loot item registry singleton/type changed before construction");
            Object prototype=NativeInventoryMenuSnapshot.read(net.minecraft.world.item.Item.class,item,"components",net.minecraft.core.component.DataComponentMap.class);
            Class<?> simple;
            try { simple=Class.forName("net.minecraft.core.component.DataComponentMap$Builder$SimpleMap",false,ItemStack.class.getClassLoader()); }
            catch(ClassNotFoundException failure){throw refused("Native item component prototype class unavailable");}
            if(prototype==null||prototype.getClass()!=simple)throw refused("Native loot item prototype is not the source-qualified native component map");
            NativeBlockBreakPins.require(simple);
            Object raw=NativeInventoryMenuSnapshot.read(simple,prototype,"map",it.unimi.dsi.fastutil.objects.Reference2ObjectMap.class);
            if(raw==null||raw.getClass()!=it.unimi.dsi.fastutil.objects.Reference2ObjectArrayMap.class
                    &&raw.getClass()!=it.unimi.dsi.fastutil.objects.Reference2ObjectOpenHashMap.class)
                throw refused("Native loot item prototype backing was replaced before stack construction");
            var values=(it.unimi.dsi.fastutil.objects.Reference2ObjectMap<?,?>)raw;
            if(values.size()>16)throw refused("Native loot item prototype exceeds the admitted16-entry lookup bound");
            Object max=values.get(net.minecraft.core.component.DataComponents.MAX_STACK_SIZE);
            if(max==null||max.getClass()!=Integer.class||(Integer)max<1||(Integer)max>64)
                throw refused("Native loot item default stack capacity exceeds initial profile");
        }
    }
    private static void requireNativeDecoders() {
        usingNativeCodec("item", net.minecraft.core.registries.BuiltInRegistries.LOOT_POOL_ENTRY_TYPE,
                net.minecraft.world.level.storage.loot.entries.LootPoolEntries.ITEM);
        usingNativeCodec("alternatives", net.minecraft.core.registries.BuiltInRegistries.LOOT_POOL_ENTRY_TYPE,
                net.minecraft.world.level.storage.loot.entries.LootPoolEntries.ALTERNATIVES);
        usingNativeCodec("match_tool", net.minecraft.core.registries.BuiltInRegistries.LOOT_CONDITION_TYPE,
                net.minecraft.world.level.storage.loot.predicates.LootItemConditions.MATCH_TOOL);
        usingNativeCodec("survives_explosion", net.minecraft.core.registries.BuiltInRegistries.LOOT_CONDITION_TYPE,
                net.minecraft.world.level.storage.loot.predicates.LootItemConditions.SURVIVES_EXPLOSION);
        usingNativeCodec("constant", net.minecraft.core.registries.BuiltInRegistries.LOOT_NUMBER_PROVIDER_TYPE,
                net.minecraft.world.level.storage.loot.providers.number.NumberProviders.CONSTANT);
        usingNativeCodec("enchantments", net.minecraft.core.registries.BuiltInRegistries.ITEM_SUB_PREDICATE_TYPE,
                net.minecraft.advancements.critereon.ItemSubPredicates.ENCHANTMENTS);
        if (net.minecraft.world.level.storage.loot.entries.LootPoolEntries.ITEM.codec() != net.minecraft.world.level.storage.loot.entries.LootItem.CODEC
                || net.minecraft.world.level.storage.loot.entries.LootPoolEntries.ALTERNATIVES.codec() != net.minecraft.world.level.storage.loot.entries.AlternativesEntry.CODEC
                || net.minecraft.world.level.storage.loot.predicates.LootItemConditions.MATCH_TOOL.codec() != net.minecraft.world.level.storage.loot.predicates.MatchTool.CODEC
                || net.minecraft.world.level.storage.loot.predicates.LootItemConditions.SURVIVES_EXPLOSION.codec() != net.minecraft.world.level.storage.loot.predicates.ExplosionCondition.CODEC
                || net.minecraft.world.level.storage.loot.providers.number.NumberProviders.CONSTANT.codec() != net.minecraft.world.level.storage.loot.providers.number.ConstantValue.CODEC
                || net.minecraft.advancements.critereon.ItemSubPredicates.ENCHANTMENTS.codec() != net.minecraft.advancements.critereon.ItemEnchantmentsPredicate.Enchantments.CODEC)
            throw refused("Native first-loop decoder implementation was replaced");
        for (Class<?> type : List.of(net.minecraft.world.level.storage.loot.LootTable.class,
                net.minecraft.world.level.storage.loot.LootPool.class,
                net.minecraft.world.level.storage.loot.entries.LootPoolEntries.class,
                net.minecraft.world.level.storage.loot.entries.LootPoolEntryType.class,
                net.minecraft.world.level.storage.loot.predicates.LootItemConditions.class,
                net.minecraft.world.level.storage.loot.predicates.LootItemConditionType.class,
                net.minecraft.world.level.storage.loot.providers.number.NumberProviders.class,
                net.minecraft.world.level.storage.loot.providers.number.LootNumberProviderType.class,
                net.minecraft.advancements.critereon.ItemSubPredicates.class,
                net.minecraft.advancements.critereon.ItemSubPredicate.Type.class)) NativeBlockBreakPins.require(type);
    }
    private static <T> void usingNativeCodec(String name, net.minecraft.core.Registry<T> registry, T expected) {
        // Built-in registries are the pinned native MappedRegistry, not caller-supplied lookup objects.
        if (registry.getClass() != net.minecraft.core.MappedRegistry.class
                || registry.get(ResourceLocation.withDefaultNamespace(name)) != expected)
            throw refused("Native loot decoder registry entry changed: " + name);
    }
    private static byte[] originalResource(String name) {
        String expected = name.equals("grass_block") ? "ef73269d2687815bc3bb1b693caa2aaf61fdd7acd3fc67acaa4a6581c217e582"
                : "9222e5df0ffbb258af7ad3c42a563b432d46bdaf398bf505eccd1df89db24d25";
        try (var input = Block.class.getResourceAsStream("/data/minecraft/loot_table/blocks/" + name + ".json")) {
            if (input == null) throw refused("Pinned original native break resource unavailable");
            byte[] bytes = input.readNBytes(16385);
            if (bytes.length > 16384 || !HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)).equals(expected))
                throw refused("Original native break loot resource differs from pinned first-loop census");
            return bytes;
        } catch (java.io.IOException | java.security.NoSuchAlgorithmException failure) { throw refused("Pinned native break resource census failed"); }
    }
    private static void inherited(Class<?> type, String name, Class<?> expected, Class<?>... args) {
        try { if (type.getMethod(name, args).getDeclaringClass() != expected) throw refused("Unadmitted native break callback: " + name); }
        catch (ReflectiveOperationException failure) { throw refused("Native break callback descriptor unavailable: " + name); }
    }
    private static void declared(Class<?> type, String name, Class<?> expected, Class<?>... args) {
        for (Class<?> c = type; c != null; c = c.getSuperclass()) {
            try {
                if (c.getDeclaredMethod(name, args).getDeclaringClass() != expected) throw refused("Unadmitted native break callback: " + name);
                return;
            } catch (NoSuchMethodException ignored) { }
        }
        throw refused("Native break callback descriptor unavailable: " + name);
    }
    private static SurvivalRefusal refused(String message) { return new SurvivalRefusal(message); }
}
