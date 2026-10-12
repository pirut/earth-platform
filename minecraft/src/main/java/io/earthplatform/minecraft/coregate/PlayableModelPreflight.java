package io.earthplatform.minecraft.coregate;

import java.util.Set;
import io.earthplatform.minecraft.enginebridge.EarthNativeEditAdmission;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.EmptyBlockGetter;
import net.neoforged.neoforge.client.model.data.ModelData;

/** The exact full-cube models that justify the pre-save 4*52-byte face census. */
final class PlayableModelPreflight {
    private PlayableModelPreflight(){}
    /** Separate alpine contract; the finite arena's existing admission remains unchanged. */
    static void requireEarth(Minecraft game){
        if(!game.isSameThread())throw new IllegalStateException("Earth resource admission requires client thread");
        if(!Set.copyOf(game.getResourcePackRepository().getSelectedIds()).equals(Set.of("vanilla","mod_resources")))
            throw new IllegalStateException("Earth requires default block resources; disable extra resource packs and reload");
        io.earthplatform.compat.dh.InstalledPlayableInventory.require();
        EarthNativeEditAdmission.requireStride(net.irisshaders.iris.vertices.IrisVertexFormats.TERRAIN.getVertexSize());
        for(var state:EarthNativeEditAdmission.permittedStates()){
            if(state.isAir())continue;
            requireEarthModel(state,game.getBlockRenderer().getBlockModel(state));
        }
    }
    static void requireEarthModel(BlockState state,BakedModel model){
            if(!EarthNativeEditAdmission.supported(state)||state.isAir()||!state.getFluidState().isEmpty()||state.hasBlockEntity()
                    ||state.getLightEmission()!=0||state.getRenderShape()!=RenderShape.MODEL
                    ||!state.isSolidRender(EmptyBlockGetter.INSTANCE,BlockPos.ZERO)
                    ||!state.isCollisionShapeFullBlock(EmptyBlockGetter.INSTANCE,BlockPos.ZERO))
                throw new IllegalStateException("Unsupported opaque Earth block semantics: "+state);
            if(model==null||model.isCustomRenderer())throw new IllegalStateException("Earth requires an ordinary baked full-cube block model");
            // Vanilla selected resources plus the exact installed inventory constrain model origins.
            // Every admitted default model must actually expose only the solid terrain pass.
            var layers=model.getRenderTypes(state,RandomSource.create(0),ModelData.EMPTY);
            int layerCount=0;for(var layer:layers){layerCount++;if(layer!=RenderType.solid())throw new IllegalStateException("Earth model requires an unsupported terrain layer");}
            if(layerCount!=1)throw new IllegalStateException("Earth model must expose exactly one solid terrain layer");
            if(!model.getQuads(state,null,RandomSource.create(0),ModelData.EMPTY,RenderType.solid()).isEmpty())
                throw new IllegalStateException("Unsupported unculled Earth block geometry");
            for(var direction:Direction.values()){
                var quads=model.getQuads(state,direction,RandomSource.create(0),ModelData.EMPTY,RenderType.solid());
                if(quads.size()!=1||quads.getFirst().getDirection()!=direction)
                    throw new IllegalStateException("Earth requires exactly one opaque full-cube quad per face");
                requireUnitQuad(quads.getFirst().getVertices(),direction);
            }
    }
    static void require(Minecraft game){
        if(!game.isSameThread())throw new IllegalStateException("Playable resource admission requires client thread");
        if(!Set.copyOf(game.getResourcePackRepository().getSelectedIds()).equals(Set.of("vanilla","mod_resources")))
            throw new IllegalStateException("Finite arena requires default block resources; disable extra resource packs and reload");
        io.earthplatform.compat.dh.InstalledPlayableInventory.require();
        for(var state:new net.minecraft.world.level.block.state.BlockState[]{Blocks.STONE.defaultBlockState(),Blocks.DIORITE.defaultBlockState()}){
            var model=game.getBlockRenderer().getBlockModel(state);
            if(!model.getQuads(state,null,RandomSource.create(0)).isEmpty())throw new IllegalStateException("Unsupported unculled arena block geometry");
            for(var direction:Direction.values()){
                var quads=model.getQuads(state,direction,RandomSource.create(0));
                if(quads.size()!=1||quads.getFirst().getDirection()!=direction)throw new IllegalStateException("Arena requires exactly one full-cube quad per face");
                requireUnitQuad(quads.getFirst().getVertices(),direction);
            }
        }
    }
    static void requireUnitQuad(int[] vertices,Direction face){
        if(vertices==null||vertices.length!=32)throw new IllegalStateException("Unsupported block-model vertex layout");
        int axis=face.getAxis()==Direction.Axis.X?0:face.getAxis()==Direction.Axis.Y?1:2;
        float plane=face.getAxisDirection()==Direction.AxisDirection.POSITIVE?1:0;
        int corners=0;
        for(int v=0;v<4;v++){
            int corner=0,bit=0;
            for(int a=0;a<3;a++){
                float value=Float.intBitsToFloat(vertices[v*8+a]);
                if(value!=0f&&value!=1f)throw new IllegalStateException("Arena block model is not an exact unit cube");
                if(a==axis){if(value!=plane)throw new IllegalStateException("Arena face lies on wrong cube plane");}
                else {corner|=((int)value)<<bit;bit++;}
            }
            if((corners&(1<<corner))!=0)throw new IllegalStateException("Duplicate arena face corner");corners|=1<<corner;
        }
        if(corners!=15)throw new IllegalStateException("Incomplete arena unit face");
    }
}
