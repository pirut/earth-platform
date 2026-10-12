# Native action boundary, first 039

Source audit: Minecraft 1.21.1, NeoForge 21.1.256, generated pinned sources in the build's moddev artifacts. This is an implementation contract, not gameplay acceptance evidence.

## Implemented preparation

- `NativeWholeAction` holds one server-owner-thread action, a complete before-image of all 41 ordinary native inventory slots, mutable detached stack copies, and the cubic transaction. Nested actions refuse globally on that thread. Components, removed defaults, counts and damage use the native named-registry `ItemStack.CODEC`, not custom item tables.
- Native callbacks run during preparation once. World reads must route to the active cubic transaction, including neighbor reads. Each read/write cube requires its expected stamp. No native callback runs again as a validation pass.
- Typed data-only effects are fully decoded and host-prevalidated before cube commit. Changed live inventory or selected slot refuses. Effect limits refuse without truncation.
- Every admitted scope creates one detached native random stream from a versioned SHA-256 derivation of all WorldIdentity fields, both halves of owner epoch and actor UUIDs, and action sequence. A typed immutable seed effect is always included in the receipt. Repeated access returns the existing stream; replayed/aborted identities cannot acquire a replacement admitted stream. There is no Level/global RNG or clock input. The host must persist the seed effect with the eventual coherent checkpoint; the codec alone is not persistence wiring.
- The private-constructor core receipt is passed once to a prevalidated host batch. Publication may follow only successful application. A postcommit delivery exception retains the exact receipt and prepared batch and fences the owner. This is a recovery-required fault, not rollback. No retry/unfence API exists here.
- Mining progress implements the native START/STOP/ABORT timing, including native STOP's 0.7 threshold and delayed completion at 1.0. The host supplies `BlockState.getDestroyProgress` every server tick and must cancel on changed session, tool, target revision, or reach. This object alone does not authorize breaking a block.
- Mining preparation uses `BlockState.canHarvestBlock`, the registered block loot table and `LootTable.getRandomItems(LootParams, RandomSource)`. The detached source prevents live level/random-sequence consumption. That native overload includes global loot modifiers.
- Unenchanted admitted vanilla tools use their native `DataComponents.TOOL.damagePerBlock` and native `ItemStack.hurtAndBreak` on a detached stack with a null living owner. Player durability criteria, equipment-break effects and statistics therefore remain separate host effects. No durability or drop table is recreated.
- Ordinary exact `BlockItem` placement uses its native update context, `Block.getStateForPlacement`, survival and collision checks, then native consumption on a detached stack. Custom BlockItem subclasses, block entities, and nonempty BLOCK_STATE patches refuse. The latter would require two staged native neighbor passes, rather than collapsing those passes into one final state.
- Crafting-table lookup uses `BlockState.getMenuProvider`; a crafting table is not a block entity. Detached crafting evaluation uses the native recipe manager, assemble and remainder methods. No custom recipes are registered.

## Required host interception/admission before calling preparation

`CallbackAdmission` must refuse until the host has audited or captured all reachable callbacks for each capability. It is not a sandbox and an unconditional allow lambda is not evidence of safety. In particular NeoForge harvest/progress events, global loot modifiers and recipe extensions can execute arbitrary mod code. A read/write Level facade does not capture their player, inventory, advancement, saved-data or RNG writes.

The Level facade needs full signed coordinate routing for reads and staged writes. Never use `BlockPos.asLong`, native packed block-position packets, `Long2ObjectMap` position keys, or Y-rebased aliases. Native `BlockPos` objects retain signed integer coordinates; conversion refuses wider core coordinates.

Mutating callbacks to capture or explicitly refuse include:

1. `CommonHooks.onLeftClickBlock`, `fireBlockBreak`, enchantment on-hit effects, `BlockState.attack`, `playerWillDestroy`, `onDestroyedByPlayer`, `Block.destroy`, `playerDestroy`, `CommonHooks.handleBlockDrops` and `BlockDropsEvent`, `spawnAfterBreak`, tool-break events.
2. Native block place hooks and cancellation, `Level.setBlock` flags 11, block `setPlacedBy`, neighbor shape/notification passes, item-block state patches, placement criteria, sounds, game events and held stack consumption.
3. Player `awardStat`, food exhaustion, durability/placed-block/crafting criteria, equipment break broadcasts, recipe awards, item pickup statistics/events and persistent player data changes.
4. `addFreshEntity` for exact admitted item entities/XP only. Preserve UUID, full native stack, position, velocity, age and pickup delay; bound entity count before commit. Generic entities, block entities, fluids, arbitrary saved-data and unsupported scheduled/random ticks refuse.

Oak leaves need bounded native neighbor/tick handling even when generated `persistent=true`: `LeavesBlock.updateShape` schedules a one-tick distance update; the native tick recomputes six-neighbor distance and calls setBlock with flags 3. Waterlogged leaves schedule fluid ticks and are outside the palette. The signed `ScheduledBlockTick` payload is available, but existence of its codec does not establish a working tick queue. Refuse a dependent action until the host admits and captures that queue.

## Still-open runtime gates

Native preparation is not runtime wiring. A native CraftingMenu click calls ResultSlot achievements/events, recipe awards, grid and inventory mutation, and possibly player.drop for remainders. A native ItemEntity pickup changes inventory/entity lifetime and fires pickup events. Both need a bounded host action scope and inclusion in the same authoritative save/checkpoint boundary; ordinary menu/pickup callbacks must not bypass it.

Full GUI progression, actual Marin Y134, native item entities/crafting, publication, checkpoint/restart conservation, callback cancellation, and postcommit-fault recovery remain unqualified until separately executed and verified.
