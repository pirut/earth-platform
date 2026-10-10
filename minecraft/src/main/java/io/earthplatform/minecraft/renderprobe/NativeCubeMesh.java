package io.earthplatform.minecraft.renderprobe;

import com.mojang.blaze3d.vertex.*;
import io.earthplatform.minecraft.network.EngineCubeSnapshot;
import io.earthplatform.minecraft.network.EnginePaletteContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.ModelBlockRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.neoforged.neoforge.client.model.data.ModelData;
import java.util.List;
import java.util.zip.CRC32C;

/** Actual native baked-model mesh with bounded fixture content and explicit GPU ownership. */
final class NativeCubeMesh implements AutoCloseable {
    final RenderPublicationGuard.Job job;
    final VertexBuffer buffer;
    final int vertices,bytes;
    final long crc;
    private boolean closed;
    private NativeCubeMesh(RenderPublicationGuard.Job job,VertexBuffer buffer,int vertices,int bytes,long crc) {
        this.job=job;this.buffer=buffer;this.vertices=vertices;this.bytes=bytes;this.crc=crc;
    }
    static NativeCubeMesh build(RenderPublicationGuard guard,EngineCubeSnapshot snapshot,EnginePaletteContext palette) {
        com.mojang.blaze3d.systems.RenderSystem.assertOnRenderThread();
        var job=guard.prepare(snapshot);var neighborhood=new CubeRenderNeighborhood(List.of(snapshot),palette);var cube=snapshot.position();
        int originX=Math.toIntExact(cube.minBlockX()),originY=Math.toIntExact(cube.minBlockY()),originZ=Math.toIntExact(cube.minBlockZ());
        Math.addExact(originX,15);Math.addExact(originY,15);Math.addExact(originZ,15);
        int nonAir=0;
        for(int y=0;y<16;y++)for(int z=0;z<16;z++)for(int x=0;x<16;x++)
            if(!neighborhood.getBlockState(new BlockPos(originX+x,originY+y,originZ+z)).isAir())nonAir++;
        if(nonAir!=1)throw new IllegalArgumentException("Native GPU fixture requires exactly one interior block per cube");
        VertexBuffer gpu=null;
        // Disable the vanilla per-thread packed-BlockPos light/AO cache between native-Y meshes.
        ModelBlockRenderer.clearCache();
        try(var storage=new ByteBufferBuilder(4096)) {
            var builder=new BufferBuilder(storage,VertexFormat.Mode.QUADS,DefaultVertexFormat.BLOCK);
            var pose=new PoseStack();var random=RandomSource.create(0);
            for(int y=0;y<16;y++)for(int z=0;z<16;z++)for(int x=0;x<16;x++) {
                var pos=new BlockPos(originX+x,originY+y,originZ+z);
                var state=neighborhood.getBlockState(pos);if(state.isAir())continue;
                if(x<1||x>14||y<1||y>14||z<1||z>14)throw new IllegalArgumentException("Fixture requires a known interior face/AO neighborhood");
                pose.pushPose();pose.translate(x,y,z);
                Minecraft.getInstance().getBlockRenderer().renderBatched(state,pos,neighborhood,pose,builder,true,random,ModelData.EMPTY,RenderType.solid());
                pose.popPose();
            }
            var mesh=builder.buildOrThrow();boolean[] consumed={false};
            try {
                int vertices=mesh.drawState().vertexCount(),bytes=mesh.vertexBuffer().remaining();
                if(vertices!=24||bytes!=24*DefaultVertexFormat.BLOCK.getVertexSize())throw new IllegalStateException("Expected six actual native baked-model quads");
                CRC32C checksum=new CRC32C();checksum.update(mesh.vertexBuffer().duplicate());
                gpu=new VertexBuffer(VertexBuffer.Usage.STATIC);final var candidate=gpu;
                if(!guard.publish(job,()->{candidate.bind();try{consumed[0]=true;candidate.upload(mesh);}finally{VertexBuffer.unbind();}}))
                    throw new IllegalStateException("Mesh became stale before GPU publication");
                var result=new NativeCubeMesh(job,gpu,vertices,bytes,checksum.getValue());gpu=null;return result;
            } finally { if(!consumed[0])mesh.close(); }
        } finally { ModelBlockRenderer.clearCache();if(gpu!=null)gpu.close(); }
    }
    @Override public void close(){com.mojang.blaze3d.systems.RenderSystem.assertOnRenderThread();if(!closed){closed=true;buffer.close();}}
}
