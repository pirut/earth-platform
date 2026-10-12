package io.earthplatform.minecraft.coregate;

import io.earthplatform.minecraft.enginebridge.EarthNativeEditAdmission;
import java.lang.reflect.Proxy;
import java.util.List;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.client.ChunkRenderTypeSet;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import static org.junit.jupiter.api.Assertions.*;

class EarthPlayableModelPreflightTest {
    private int[] quad(Direction d){int[] v=new int[32];int axis=d.getAxis()==Direction.Axis.X?0:d.getAxis()==Direction.Axis.Y?1:2;for(int i=0;i<4;i++){int bit=0;for(int a=0;a<3;a++){float n=a==axis?(d.getAxisDirection()==Direction.AxisDirection.POSITIVE?1:0):((i>>(bit++))&1);v[i*8+a]=Float.floatToRawIntBits(n);}}return v;}
    private BakedModel model(String fault){
        return (BakedModel)Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{BakedModel.class},(proxy,method,args)->{
            if(method.getName().equals("isCustomRenderer"))return fault.equals("custom");
            if(method.getName().equals("getRenderTypes"))return fault.equals("cutout")?ChunkRenderTypeSet.of(RenderType.cutout()):fault.equals("extra-layer")?ChunkRenderTypeSet.of(RenderType.solid(),RenderType.cutoutMipped()):ChunkRenderTypeSet.of(RenderType.solid());
            if(method.getName().equals("getQuads")){
                assertEquals(5,args.length,"Verify the actual layer-aware native model API");
                Direction face=(Direction)args[1];
                if(face==null)return fault.equals("unculled")?List.of(new BakedQuad(quad(Direction.UP),-1,Direction.UP,null,true)):List.of();
                Direction direction=fault.equals("wrong-direction")?face.getOpposite():face;
                int[] vertices=quad(face);if(fault.equals("fractional"))vertices[0]=Float.floatToRawIntBits(.5f);
                var q=new BakedQuad(vertices,-1,direction,null,true);
                return fault.equals("two-quads")?List.of(q,q):fault.equals("missing-face")?List.of():List.of(q);
            }
            throw new AssertionError("Unexpected model callback: "+method);
        });
    }
    @Test void allFiveOpaqueDefaultEarthModelsFitExactOneQuadCensus(){
        for(var state:EarthNativeEditAdmission.permittedStates())if(!state.isAir())
            assertDoesNotThrow(()->PlayableModelPreflight.requireEarthModel(state,model("valid")));
    }
    @Test void extraGeometryNonSolidLayerAndCustomModelsFailBeforeAdmission(){
        for(var fault:List.of("custom","cutout","extra-layer","unculled","wrong-direction","fractional","two-quads","missing-face"))
            assertThrows(IllegalStateException.class,()->PlayableModelPreflight.requireEarthModel(Blocks.STONE.defaultBlockState(),model(fault)),fault);
    }
    @Test void otherStatesCannotBorrowAnOpaqueCubeModel(){
        for(var block:List.of(Blocks.GRASS_BLOCK,Blocks.PACKED_ICE,Blocks.WATER,Blocks.OAK_LEAVES,Blocks.AIR))
            assertThrows(IllegalStateException.class,()->PlayableModelPreflight.requireEarthModel(block.defaultBlockState(),model("valid")));
    }
    @Test void earthPreflightRetainsDefaultResourceAndInventoryFenceAndSharedStride()throws Exception{
        var node=new ClassNode();try(var input=PlayableModelPreflight.class.getResourceAsStream("PlayableModelPreflight.class")){new ClassReader(input).accept(node,0);}
        var method=node.methods.stream().filter(m->m.name.equals("requireEarth")).findFirst().orElseThrow();
        boolean resources=false,inventory=false,stride=false,models=false;
        var constants=new java.util.HashSet<Object>();
        for(var instruction:method.instructions){
            if(instruction instanceof LdcInsnNode c)constants.add(c.cst);
            if(instruction instanceof MethodInsnNode call){
                resources|=call.name.equals("getSelectedIds");inventory|=call.owner.endsWith("InstalledPlayableInventory")&&call.name.equals("require");
                stride|=call.owner.endsWith("EarthNativeEditAdmission")&&call.name.equals("requireStride");
                models|=call.owner.endsWith("PlayableModelPreflight")&&call.name.equals("requireEarthModel");
            }
        }
        assertTrue(resources&&inventory&&stride&&models);assertTrue(constants.containsAll(List.of("vanilla","mod_resources")));
    }
}
