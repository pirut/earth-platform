package io.earthplatform.minecraft.renderprobe;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.VertexBuffer;
import io.earthplatform.minecraft.fixture.NativeProbeChecks;
import io.earthplatform.minecraft.fixture.NativeSparseBinding;
import io.earthplatform.core.CubePos;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.network.chat.Component;
import org.joml.Matrix4f;
import org.lwjgl.glfw.GLFW;

/** A labeled diagnostic scene. It never installs the sparse level into vanilla LevelRenderer. */
final class NativeRenderScreen extends Screen {
    private final NativeRenderSession session;
    NativeRenderScreen(NativeRenderSession session){super(Component.literal("Native cubic GPU diagnostic"));this.session=session;}
    @Override public boolean isPauseScreen(){return false;}
    @Override public void removed(){session.close();}
    @Override public void onClose(){minecraft.setScreen(null);}
    @Override public void tick(){if(!session.openForDraw()||session.guard.invalidationRequested())onClose();}
    @Override public boolean keyPressed(int key,int scan,int modifiers) {
        if(key==GLFW.GLFW_KEY_R){session.retireAndReaddLower();return true;}
        if(key==GLFW.GLFW_KEY_G){session.invalidateAndRebuildResources();return true;}
        if(key==GLFW.GLFW_KEY_L&&session.coexistence()){session.swapNearMesh();return true;}
        if(key==GLFW.GLFW_KEY_T){session.reloadResourcePacks();return true;}
        return super.keyPressed(key,scan,modifiers);
    }
    @Override public void render(GuiGraphics gui,int mouseX,int mouseY,float partialTick) {
        gui.fill(0,0,width,height,0xFF101720);
        gui.fill(12,47,width/2-6,height-73,0xFF1C2835);
        gui.fill(width/2+6,47,width-12,height-73,0xFF1C2835);
        gui.flush();
        if(!session.openForDraw()||session.guard.invalidationRequested())return;
        // The diagnostic owns this modal screen's depth buffer. GUI backgrounds must not
        // occlude half a cube when their depth uses a different projection.
        RenderSystem.clear(256,net.minecraft.client.Minecraft.ON_OSX);
        float size=Math.max(12,Math.min(width/7f,(height-147)/2.4f));
        float centerY=(47+height-73)/2f;
        if(!drawSlot(NativeSparseBinding.LOWER,width/4f,centerY,size,NativeProbeChecks.LOWER_BLOCK.getY()))return;
        if(!drawSlot(NativeSparseBinding.UPPER,width*3/4f,centerY,size,NativeProbeChecks.UPPER_BLOCK.getY()))return;
        if(session.guard.invalidationRequested())return;
        RenderSystem.clear(256,net.minecraft.client.Minecraft.ON_OSX);
        gui.drawCenteredString(font,session.coexistence()?"NATIVE + DISTANT HORIZONS GPU DIAGNOSTIC":"NATIVE CUBIC GPU DIAGNOSTIC",width/2,12,0xFFFFFF);
        gui.drawCenteredString(font,session.coexistence()?"Minecraft near mesh + page-aware official DH shaders":"Minecraft baked models > MeshData > VBO > solid shader",width/2,27,0xAFC5D9);
        gui.drawCenteredString(font,"Y -11000 | "+source(NativeSparseBinding.LOWER),width/4,55,0xF2CA58);
        gui.drawCenteredString(font,"Y +8849 | "+source(NativeSparseBinding.UPPER),width*3/4,55,0x64DED4);
        gui.drawCenteredString(font,session.coexistence()?"2 cube owners | 1 near VBO + 2 cached DH pages | 0 tall arrays":"2 cube owners | 2 VBOs | 48 vertices | 0 tall section arrays",width/2,height-62,0xFFFFFF);
        gui.drawCenteredString(font,"Fixed light, separate cameras. No playable full-height world.",width/2,height-48,0xB1C1CF);
        gui.drawCenteredString(font,session.coexistence()?"L: swap near/DH   R: renew   G: rebuild   T: reload   Esc: close":"R: retire/re-add   G: rebuild GPU   T: pack reload   Esc: close",width/2,height-32,0xCBD4DD);
        gui.drawCenteredString(font,session.statusLine(),width/2,height-17,0x8AE0A1);
        gui.flush();session.didDraw(width,height,centerY,size);
    }
    private String source(CubePos cube){return session.meshes.containsKey(cube)?"Minecraft near":"DH far page";}
    private boolean drawSlot(CubePos position,float centerX,float centerY,float size,int blockY) {
        if(session.guard.invalidationRequested())return false;
        var mesh=session.meshes.get(position);
        if(mesh!=null&&!drawMesh(mesh,centerX,centerY,size,blockY))return false;
        if(session.lod!=null) {
            var projection=new Matrix4f().setOrtho(0,width,height,0,-1000,1000);
            var view=new Matrix4f().translation(centerX,centerY,0).scale(size,-size,size)
                    .rotateX((float)Math.toRadians(25)).rotateY((float)Math.toRadians(-35));
            boolean drew=session.lod.draw(position,session.readyNearCoverage(),projection.mul(view),8.5,blockY+0.5,8.5);
            if(session.guard.invalidationRequested())return false;
            if(drew==(mesh!=null))throw new IllegalStateException("Exactly one current near or far owner must draw for each cube");
        } else if(mesh==null)throw new IllegalStateException("Missing native diagnostic mesh");
        return true;
    }
    private boolean drawMesh(NativeCubeMesh mesh,float centerX,float centerY,float size,int blockY) {
        if(mesh==null||!session.guard.isCurrent(mesh.job)) {
            if(session.guard.invalidationRequested())return false;
            throw new IllegalStateException("Stale GPU draw rejected");
        }
        var origin=CameraRelativeOrigin.of(mesh.job.position(),8.5,blockY+0.5,8.5);
        var model=new Matrix4f().translation(centerX,centerY,0).scale(size,-size,size)
                .rotateX((float)Math.toRadians(25)).rotateY((float)Math.toRadians(-35)).translate(origin.x(),origin.y(),origin.z());
        var projection=new Matrix4f().setOrtho(0,width,height,0,-1000,1000);
        float fogStart=RenderSystem.getShaderFogStart(),fogEnd=RenderSystem.getShaderFogEnd();
        float[] color=RenderSystem.getShaderColor().clone();int oldAtlas=RenderSystem.getShaderTexture(0),oldLight=RenderSystem.getShaderTexture(2);
        var layer=RenderType.solid();layer.setupRenderState();
        try {
            RenderSystem.setShaderColor(1,1,1,1);RenderSystem.setShaderFogStart(1_000_000);RenderSystem.setShaderFogEnd(2_000_000);
            RenderSystem.setShaderTexture(2,session.whiteLight.getId());RenderSystem.disableCull();
            mesh.buffer.bind();
            // Validate again at the immediate draw boundary, independently of upload validation.
            if(!session.guard.isCurrent(mesh.job)) {
                if(session.guard.invalidationRequested())return false;
                throw new IllegalStateException("GPU draw lease retired");
            }
            mesh.buffer.drawWithShader(model,projection,GameRenderer.getRendertypeSolidShader());
        } finally {
            VertexBuffer.unbind();layer.clearRenderState();RenderSystem.enableCull();
            RenderSystem.setShaderTexture(0,oldAtlas);RenderSystem.setShaderTexture(2,oldLight);
            RenderSystem.setShaderFogStart(fogStart);RenderSystem.setShaderFogEnd(fogEnd);
            RenderSystem.setShaderColor(color[0],color[1],color[2],color[3]);
        }
        return true;
    }
}
