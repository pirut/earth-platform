/**
 * The server side of far terrain: a voxel pyramid ({@link io.earthplatform.minecraft.cubic.far.Far}) whose
 * columns {@link io.earthplatform.minecraft.cubic.far.FarServer} makes on low-priority
 * {@link io.earthplatform.minecraft.cubic.far.FarWorkers} and sends to players, with what players built
 * shown through {@link io.earthplatform.minecraft.cubic.far.FarBuilds}. The client meshes and draws them in
 * {@code cubic.client} ({@code FarRenderer}, {@code FarMesher}, {@code FarGpu}).
 */
package io.earthplatform.minecraft.cubic.far;
