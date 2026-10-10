package io.earthplatform.core;

import java.io.IOException;

/** Blocking durability boundary: invoke on a dedicated I/O executor, never the Minecraft tick thread. */
public interface EditStore extends AutoCloseable {
    LoadedEdits load(CubePos position) throws IOException;
    void save(EditSnapshot snapshot) throws IOException;
    @Override void close() throws IOException;
    enum Status { EMPTY, PRIMARY, RECOVERED_BACKUP }
    record LoadedEdits(EditSnapshot snapshot, Status status, String warning) {}
}
