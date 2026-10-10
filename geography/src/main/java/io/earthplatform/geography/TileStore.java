package io.earthplatform.geography;

import java.util.concurrent.CompletableFuture;

public interface TileStore { CompletableFuture<RasterTile> load(TileSpec spec); }
