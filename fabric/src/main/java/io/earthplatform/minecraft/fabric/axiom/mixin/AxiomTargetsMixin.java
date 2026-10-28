package io.earthplatform.minecraft.fabric.axiom.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;

/**
 * Marks the Axiom 6.1 classes that read the world's height or chunk sections directly; the rewrite
 * itself is {@code AxiomMixins#postApply}. A class a later Axiom renames is simply not rewritten.
 */
@Pseudo
@Mixin(targets = {
        "com.moulberry.axiom.editor.EditorMovementControls",
        "com.moulberry.axiom.editor.windows.operations.QuickReplaceWindow",
        "com.moulberry.axiom.RayCaster",
        "com.moulberry.axiom.hooks.WorldRenderHook",
        "com.moulberry.axiom.packets.AxiomServerboundSetBuffer",
        "com.moulberry.axiom.packets.AxiomServerboundRequestChunkData",
        "com.moulberry.axiom.packets.AxiomServerboundTickBlocks$Updater",
        "com.moulberry.axiom.packets.AxiomServerboundSetBlock",
        "com.moulberry.axiom.packets.AxiomServerboundFixArea$Updater",
        "com.moulberry.axiom.clipboard.Placement",
        "com.moulberry.axiom.clipboard.SelectionBuffer$AABB",
        "com.moulberry.axiom.clipboard.SelectionBuffer$Set",
        "com.moulberry.axiom.AsyncChunkProvider",
        "com.moulberry.axiom.capabilities.SpecialPlace",
        "com.moulberry.axiom.render.ChunkRenderOverrider",
        "com.moulberry.axiom.render.regions.ChunkedBlockRegion",
        "com.moulberry.axiom.render.CollisionMeshOverlayRenderer",
        "com.moulberry.axiom.render.BiomeOverlayRenderer",
        "com.moulberry.axiom.render.ChunkRenderOverrider$MappedBlockAndTintGetter",
        "com.moulberry.axiom.world_modification.Dispatcher",
        "com.moulberry.axiom.operations.ReplaceCopyPropertiesOperation",
        "com.moulberry.axiom.operations.FillNearestOperation",
        "com.moulberry.axiom.operations.SetBiomeOperation",
        "com.moulberry.axiom.operations.ReplaceOperation",
        "com.moulberry.axiom.operations.AnalyzeBlocksOperation",
        "com.moulberry.axiom.operations.DrainOperation",
        "com.moulberry.axiom.operations.WaterlogOperation",
        "com.moulberry.axiom.operations.UpdateBlocksOperation",
        "com.moulberry.axiom.operations.TypeReplaceOperation",
        "com.moulberry.axiom.operations.SimulateGravityOperation",
        "com.moulberry.axiom.operations.HollowOperation",
        "com.moulberry.axiom.tools.biome_painter.BiomePainterTool",
        "com.moulberry.axiom.tools.ChamferClosestBlockMap",
        "com.moulberry.axiom.tools.box_select.BoxSelectTool",
        "com.moulberry.axiom.tools.smooth.ClosestBlockMap",
        "com.moulberry.axiom.tools.floodfill.FloodfillTool",
        "com.moulberry.axiom.tools.magic_select.MagicSelectionFast",
        "com.moulberry.axiom.VersionUtils",
        "com.moulberry.axiom.utils.EmptyBlockAndTintGetter",
        "com.moulberry.axiom.utils.RegionHelper",
        "com.moulberry.axiom.collections.ConcurrentLevelPredicateSet",
        "com.moulberry.axiom.mask.LuaHelper$12",
        "com.moulberry.axiom.mask.MaskContext"
}, remap = false)
abstract class AxiomTargetsMixin {}
