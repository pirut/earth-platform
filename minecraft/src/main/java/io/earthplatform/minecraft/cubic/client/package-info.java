/**
 * Client-side rendering. Near cubes: {@link io.earthplatform.minecraft.cubic.client.ClientCubeWorld},
 * {@link io.earthplatform.minecraft.cubic.client.CubeMesher} and {@link io.earthplatform.minecraft.cubic.client.CubeRenderer},
 * drawn with vanilla's terrain shaders. Far terrain: {@link io.earthplatform.minecraft.cubic.client.FarRenderer}
 * asks for columns and draws them, {@link io.earthplatform.minecraft.cubic.client.FarMesher} turns them into
 * 16-byte faces, {@link io.earthplatform.minecraft.cubic.client.FarGpu} keeps those faces on the GPU and
 * {@link io.earthplatform.minecraft.cubic.client.FarShaders} holds its GLSL.
 *
 * <p>Shader packs: {@link io.earthplatform.minecraft.cubic.client.FarLod} gives far terrain its own depth
 * buffer the way Voxy does, {@link io.earthplatform.minecraft.cubic.client.FarIrisUniforms} and
 * {@link io.earthplatform.minecraft.cubic.client.FarPackPatch} pass the pack's settings through, and
 * {@link io.earthplatform.minecraft.cubic.client.PhotonCompat} lets an unmodified Photon run on drivers it
 * doesn't fit, such as Apple's OpenGL 4.1. Everything that touches OpenGL runs on the render thread; meshing
 * runs on worker threads.
 */
package io.earthplatform.minecraft.cubic.client;
