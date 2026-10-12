package io.earthplatform.minecraft.cubic.net;

import io.earthplatform.minecraft.cubic.client.ClientCubeWorld;

/** Client-only bridge; only touched from client payload handlers. */
final class CubicClientReceiver {
    private CubicClientReceiver() {}
    static void cube(CubePayloads.CubeData payload) { ClientCubeWorld world = ClientCubeWorld.current(); if (world != null) world.receive(payload); }
    static void unload(CubePayloads.CubeUnload payload) { ClientCubeWorld world = ClientCubeWorld.current(); if (world != null) world.unload(payload.keys()); }
    static void changes(CubePayloads.BlockChanges payload) { ClientCubeWorld world = ClientCubeWorld.current(); if (world != null) world.apply(payload); }
    static void tops(CubePayloads.ColumnTops payload) { ClientCubeWorld world = ClientCubeWorld.current(); if (world != null) world.tops(payload); }
}
