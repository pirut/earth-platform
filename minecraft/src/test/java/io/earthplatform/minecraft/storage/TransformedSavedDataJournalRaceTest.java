package io.earthplatform.minecraft.storage;

import net.minecraft.core.HolderLookup;
import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.world.level.saveddata.SavedData;
import net.neoforged.neoforge.common.IOUtilities;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

/** Uses real transformed IOUtilities and real SavedData compressed-NBT writes.
 * The faulting filesystem is deterministic; no live world or production queue is used. */
@ResourceLock("earthplatform-test-IOUtilities-saveDataTasks")
class TransformedSavedDataJournalRaceTest {
    @TempDir Path temp;

    @Test void actualFilesFindNegativeControlPoisonsTheRealChainAndSkipsLaterSavedData() throws Exception {
        Path data=Files.createDirectory(temp.resolve("data"));
        Files.writeString(data.resolve("DistantHorizons.sqlite-journal"),"unrelated");
        try(var fs=new VanishingJournalFileSystem(data);var queue=new TestQueue()) {
            fs.failChildAttributes("DistantHorizons.sqlite-journal");
            IOUtilities.withIOWorker(()->{
                try(var stream=Files.find(fs.root(),1,NamesOnlyTempFilesTest.predicate(null))) {
                    stream.toList();
                } catch(IOException failure) {throw new UncheckedIOException(failure);}
            });
            Path target=data.resolve("normal_saveddata.dat");
            MarkerData marker=new MarkerData("must-not-run-negative-control");marker.setDirty();
            marker.save(target.toFile(),RegistryAccess.EMPTY);
            AtomicBoolean tail=new AtomicBoolean();IOUtilities.withIOWorker(()->tail.set(true));
            CompletionException failure=assertThrows(CompletionException.class,IOUtilities::waitUntilIOWorkerComplete);
            assertTrue(hasCause(failure,java.nio.file.NoSuchFileException.class));
            assertTrue(fs.childAttributeReads("DistantHorizons.sqlite-journal")>0);
            assertFalse(Files.exists(target));assertFalse(tail.get());
            assertFalse(marker.isDirty(),"The actual SavedData contract clears dirty before its queued write executes");
            assertTrue(queue.current().isCompletedExceptionally());
        }
    }

    @Test void transformedTempListingAvoidsTheJournalStatAndLaterRealSavedDataPersists() throws Exception {
        Path data=Files.createDirectory(temp.resolve("data"));
        Path journal=Files.writeString(data.resolve("DistantHorizons.sqlite-journal"),"unrelated-journal");
        Path selected=Files.writeString(data.resolve("maps.dat123.neoforge-tmp"),"orphan");
        Path other=Files.writeString(data.resolve("other.dat123.neoforge-tmp"),"other-prefix");
        Path regular=Files.writeString(data.resolve("existing.dat"),"preserve");
        try(var fs=new VanishingJournalFileSystem(data);var queue=new TestQueue()) {
            fs.failChildAttributes("DistantHorizons.sqlite-journal");
            IOUtilities.withIOWorker(()->IOUtilities.tryCleanupTempFiles(fs.root(),"maps.dat"));
            Path target=data.resolve("normal_saveddata.dat");
            MarkerData marker=new MarkerData("after-vanishing-journal");marker.setDirty();
            marker.save(target.toFile(),RegistryAccess.EMPTY);
            AtomicBoolean tail=new AtomicBoolean();IOUtilities.withIOWorker(()->tail.set(true));
            IOUtilities.waitUntilIOWorkerComplete();
            assertEquals(0,fs.childAttributeReads("DistantHorizons.sqlite-journal"),
                    "This assertion fails if the actual private IOUtilities callsite was not transformed");
            assertFalse(Files.exists(selected));assertTrue(Files.exists(other));
            assertEquals("unrelated-journal",Files.readString(journal));assertEquals("preserve",Files.readString(regular));
            assertTrue(tail.get());assertFalse(marker.isDirty());
            CompoundTag saved=NbtIo.readCompressed(target,NbtAccounter.create(1<<20));
            assertEquals("after-vanishing-journal",saved.getCompound("data").getString("marker"));
            assertTrue(saved.contains("DataVersion"));assertFalse(queue.current().isCompletedExceptionally());
        }
    }

    @Test void transformedCleanupDeletesSelectedSymlinkButNeverItsTarget() throws Exception {
        Path data=Files.createDirectory(temp.resolve("data"));
        Path target=Files.writeString(temp.resolve("valuable.dat"),"untouched");
        Path link=Files.createSymbolicLink(data.resolve("orphan.neoforge-tmp"),target);
        try(var queue=new TestQueue()) {
            IOUtilities.withIOWorker(()->IOUtilities.tryCleanupTempFiles(data,null));
            IOUtilities.waitUntilIOWorkerComplete();
            assertFalse(Files.exists(link,java.nio.file.LinkOption.NOFOLLOW_LINKS));
            assertEquals("untouched",Files.readString(target));
        }
    }

    @Test void realIterationFailureStillPoisonsQueueAndNeverDeletesAPartialSelection() throws Exception {
        Path data=Files.createDirectory(temp.resolve("data"));
        Path first=Files.writeString(data.resolve("a.neoforge-tmp"),"a");
        Path second=Files.writeString(data.resolve("b.neoforge-tmp"),"b");
        IOException injected=new IOException("unrelated directory iteration failure");
        try(var fs=new VanishingJournalFileSystem(data);var queue=new TestQueue()) {
            fs.failDirectoryIterationAfter(1,injected);
            IOUtilities.withIOWorker(()->IOUtilities.tryCleanupTempFiles(fs.root(),null));
            MarkerData marker=new MarkerData("must-not-hide-iteration-error");marker.setDirty();
            Path target=data.resolve("ordinary.dat");marker.save(target.toFile(),RegistryAccess.EMPTY);
            CompletionException failure=assertThrows(CompletionException.class,IOUtilities::waitUntilIOWorkerComplete);
            assertTrue(containsIdentity(failure,injected));
            assertTrue(Files.exists(first));assertTrue(Files.exists(second));assertFalse(Files.exists(target));
            assertEquals(0,fs.count(VanishingJournalFileSystem.Operation.DELETE));
            assertEquals(1,fs.count(VanishingJournalFileSystem.Operation.DIRECTORY_CLOSE));
        }
    }

    @Test void realStreamCloseFailureRemainsVisibleAndPreventsAnySelectedDeletion() throws Exception {
        Path data=Files.createDirectory(temp.resolve("data"));
        Path selected=Files.writeString(data.resolve("a.neoforge-tmp"),"a");
        IOException injected=new IOException("directory close failure");
        try(var fs=new VanishingJournalFileSystem(data);var queue=new TestQueue()) {
            fs.fail(VanishingJournalFileSystem.Operation.DIRECTORY_CLOSE,injected);
            IOUtilities.withIOWorker(()->IOUtilities.tryCleanupTempFiles(fs.root(),null));
            AtomicBoolean tail=new AtomicBoolean();IOUtilities.withIOWorker(()->tail.set(true));
            CompletionException failure=assertThrows(CompletionException.class,IOUtilities::waitUntilIOWorkerComplete);
            assertTrue(containsIdentity(failure,injected));assertFalse(tail.get());
            assertTrue(Files.exists(selected));assertEquals(0,fs.count(VanishingJournalFileSystem.Operation.DELETE));
            assertEquals(1,fs.count(VanishingJournalFileSystem.Operation.DIRECTORY_CLOSE));
        }
    }

    @Test void selectedDeletionFailureKeepsOriginalLoggedCleanupPolicyAndPreservesTheFile() throws Exception {
        Path data=Files.createDirectory(temp.resolve("data"));
        Path selected=Files.writeString(data.resolve("a.neoforge-tmp"),"a");
        IOException injected=new java.nio.file.AccessDeniedException(selected.toString());
        try(var fs=new VanishingJournalFileSystem(data);var queue=new TestQueue()) {
            fs.fail(VanishingJournalFileSystem.Operation.DELETE,injected);
            IOUtilities.withIOWorker(()->IOUtilities.tryCleanupTempFiles(fs.root(),null));
            Path target=data.resolve("ordinary.dat");MarkerData marker=new MarkerData("after-logged-delete-failure");marker.setDirty();
            marker.save(target.toFile(),RegistryAccess.EMPTY);IOUtilities.waitUntilIOWorkerComplete();
            assertEquals(1,fs.count(VanishingJournalFileSystem.Operation.DELETE));assertTrue(Files.exists(selected));
            assertEquals("after-logged-delete-failure",NbtIo.readCompressed(target,NbtAccounter.create(1<<20)).getCompound("data").getString("marker"));
        }
    }

    @Test void actualAtomicWriteStillPropagatesWriteForceAndMoveFailuresWithoutReplacingOldData() throws Exception {
        for(var operation:java.util.List.of(VanishingJournalFileSystem.Operation.WRITE,
                VanishingJournalFileSystem.Operation.FORCE,VanishingJournalFileSystem.Operation.MOVE)) {
            Path data=Files.createDirectory(temp.resolve(operation.name()));
            Path target=Files.writeString(data.resolve("ordinary.dat"),"old-data");
            IOException injected=new IOException("actual "+operation+" failure");
            try(var fs=new VanishingJournalFileSystem(data)) {
                fs.fail(operation,injected);
                IOException failure=assertThrows(IOException.class,()->IOUtilities.atomicWrite(fs.wrap(target),
                        output->output.write("replacement".getBytes(java.nio.charset.StandardCharsets.UTF_8))));
                assertSame(injected,failure);assertEquals("old-data",Files.readString(target));
                assertTrue(fs.count(operation)>0);
                try(var files=Files.list(data)){assertEquals(java.util.List.of(target),files.toList());}
            }
        }
    }

    @Test void actualSavedDataCheckedWriteFailureIsStillLoggedWhileItsQueueCompletes() throws Exception {
        Path regularParent=Files.writeString(temp.resolve("not-a-directory"),"preserve-parent");
        Path target=regularParent.resolve("ordinary.dat");
        var events=new java.util.concurrent.CopyOnWriteArrayList<org.apache.logging.log4j.core.LogEvent>();
        var logger=(org.apache.logging.log4j.core.Logger)org.apache.logging.log4j.LogManager.getLogger(SavedData.class);
        var appender=new org.apache.logging.log4j.core.appender.AbstractAppender("savedata026-checked-failure",null,
                org.apache.logging.log4j.core.layout.PatternLayout.createDefaultLayout(),false,
                org.apache.logging.log4j.core.config.Property.EMPTY_ARRAY) {
            @Override public void append(org.apache.logging.log4j.core.LogEvent event){events.add(event.toImmutable());}
        };
        appender.start();logger.addAppender(appender);
        try(var queue=new TestQueue()) {
            MarkerData marker=new MarkerData("cannot-write-below-regular-file");marker.setDirty();
            marker.save(target.toFile(),RegistryAccess.EMPTY);
            AtomicBoolean tail=new AtomicBoolean();IOUtilities.withIOWorker(()->tail.set(true));
            assertDoesNotThrow(IOUtilities::waitUntilIOWorkerComplete,
                    "Upstream SavedData catches checked IOException in its queued lambda; the shim must not change that policy");
            assertTrue(tail.get());assertFalse(marker.isDirty());assertFalse(Files.exists(target));
            assertEquals("preserve-parent",Files.readString(regularParent));
            var failures=events.stream().filter(event->event.getMessage().getFormattedMessage().startsWith("Could not save data ")).toList();
            assertEquals(1,failures.size());assertEquals(org.apache.logging.log4j.Level.ERROR,failures.getFirst().getLevel());
            assertInstanceOf(IOException.class,failures.getFirst().getThrown());
        } finally {logger.removeAppender(appender);appender.stop();}
    }

    static boolean containsIdentity(Throwable value,Throwable wanted) {
        if(value==wanted)return true;
        if(value.getCause()!=null&&containsIdentity(value.getCause(),wanted))return true;
        for(Throwable suppressed:value.getSuppressed())if(containsIdentity(suppressed,wanted))return true;
        return false;
    }

    static boolean hasCause(Throwable problem,Class<? extends Throwable> type) {
        for(Throwable at=problem;at!=null;at=at.getCause())if(type.isInstance(at))return true;
        return false;
    }
    static final class MarkerData extends SavedData {
        private final String marker;
        MarkerData(String marker){this.marker=marker;}
        @Override public CompoundTag save(CompoundTag tag,HolderLookup.Provider registries){tag.putString("marker",marker);return tag;}
    }
    /** Only isolates a completed test-owned queue; never conceals an earlier failing chain.
     * Pending tasks must settle before restoring the exact previous future identity. */
    static final class TestQueue implements AutoCloseable {
        private final Field field;
        private final CompletableFuture<?> previous;
        TestQueue() throws Exception {
            field=IOUtilities.class.getDeclaredField("saveDataTasks");field.setAccessible(true);
            previous=(CompletableFuture<?>)field.get(null);
            previous.get(30,TimeUnit.SECONDS);
            assertSame(previous,field.get(null),"Concurrent queue owner during isolated test admission");
            field.set(null,CompletableFuture.completedFuture(null));
        }
        CompletableFuture<?> current() throws IllegalAccessException {return (CompletableFuture<?>)field.get(null);}
        @Override public void close() throws Exception {
            CompletableFuture<?> testOwned=current();
            testOwned.handle((ignored,failure)->null).get(30,TimeUnit.SECONDS);
            assertSame(testOwned,field.get(null),"Concurrent queue mutation during test cleanup");
            field.set(null,previous);
        }
    }
}
