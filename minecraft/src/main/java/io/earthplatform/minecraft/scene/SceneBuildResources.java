package io.earthplatform.minecraft.scene;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Owner-local cleanup journal: attempt every close, retain only failed ownership for an explicit retry. */
final class SceneBuildResources {
    @FunctionalInterface interface Resource { void close(); }
    private final Thread owner = Thread.currentThread();
    private final List<Resource> owned = new ArrayList<>();
    private boolean retiring;

    void retain(Resource resource) {
        checkOwner();
        if (retiring) throw new IllegalStateException("Cannot add resources after cleanup starts");
        owned.add(Objects.requireNonNull(resource));
    }
    void releaseToPublishedOwner() {
        checkOwner();
        if (retiring) throw new IllegalStateException("Failed cleanup ownership cannot be transferred");
        owned.clear();
    }
    int size() { checkOwner(); return owned.size(); }
    void close() {
        checkOwner();
        retiring = true;
        Throwable failure = null;
        for (int i = 0; i < owned.size();) {
            try { owned.get(i).close(); owned.remove(i); }
            catch (RuntimeException | Error error) { failure = SceneBuildStepper.append(failure, error); i++; }
        }
        SceneBuildStepper.rethrow(failure);
    }
    private void checkOwner() {
        if (Thread.currentThread() != owner) throw new IllegalStateException("Scene cleanup requires its owner thread");
    }
}
