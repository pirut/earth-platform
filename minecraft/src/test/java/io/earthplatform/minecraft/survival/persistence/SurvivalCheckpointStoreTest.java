package io.earthplatform.minecraft.survival.persistence;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.io.IOException;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class SurvivalCheckpointStoreTest {
    @TempDir Path temp;
    private static final SurvivalCheckpointStore.Limits LIMITS = new SurvivalCheckpointStore.Limits(8, 1024, 4096, 4096);
    private static SurvivalCheckpointStore.Image image(long generation, int value) {
        return new SurvivalCheckpointStore.Image(LIMITS, generation, Map.of("cube_n1_457_2", new byte[]{(byte)value}, "inventory", new byte[]{3,(byte)value}, "drops", new byte[]{(byte)value,4}, "ticks", new byte[]{(byte)value,5}));
    }
    @Test void completeGenerationAndAllGameplayPayloadsRestartTogether() throws Exception {
        try (var store = new SurvivalCheckpointStore(temp,"world/source/palette",LIMITS)) { assertTrue(store.load().isEmpty()); store.commit(0,image(1,7)); }
        try (var store = new SurvivalCheckpointStore(temp,"world/source/palette",LIMITS)) { var got=store.load().orElseThrow(); assertEquals(1,got.generation()); for(String key:got.names()) assertArrayEquals(image(1,7).payload(key),got.payload(key)); }
    }
    @Test void everyInjectedInterruptionKeepsOneCompleteGenerationAndIdenticalRetryWorks() throws Exception {
        for(var phase:SurvivalCheckpointStore.Phase.values()) {
            Path dir=temp.resolve(phase.name());
            try(var store=new SurvivalCheckpointStore(dir,"world",LIMITS)) { store.commit(0,image(1,1)); }
            try(var store=new SurvivalCheckpointStore(dir,"world",LIMITS, reached->{if(reached==phase)throw new IOException("injected "+phase);})) { assertThrows(IOException.class,()->store.commit(1,image(2,2))); }
            try(var store=new SurvivalCheckpointStore(dir,"world",LIMITS)) {
                var got=store.load().orElseThrow(); long expected=phase.ordinal()<2?1:2;assertEquals(expected,got.generation());
                for(String key:got.names())assertArrayEquals(image(expected,(int)expected).payload(key),got.payload(key));
                store.commit(1,image(2,2)); assertEquals(2,store.load().orElseThrow().generation());
            }
        }
    }
    @Test void missingAuthorityNeverBecomesNewWorld() throws Exception {
        try(var store=new SurvivalCheckpointStore(temp,"world",LIMITS)) {store.commit(0,image(1,1));}
        Files.delete(temp.resolve("CURRENT")); assertThrows(IOException.class,()->new SurvivalCheckpointStore(temp,"world",LIMITS));
    }
    @Test void firstGenerationInterruptedBeforePublicationCanRetryAgainstExplicitZeroAuthority() throws Exception {
        try(var store=new SurvivalCheckpointStore(temp,"world",LIMITS,p->{if(p==SurvivalCheckpointStore.Phase.MANIFEST_SYNCED)throw new IOException("stop");})) {assertThrows(IOException.class,()->store.commit(0,image(1,1)));assertTrue(store.load().isEmpty());}
        try(var store=new SurvivalCheckpointStore(temp,"world",LIMITS)) {store.commit(0,image(1,1));assertEquals(1,store.load().orElseThrow().generation());}
    }
    @Test void foreignWorldAndCorruptReferencedPayloadRefuse() throws Exception {
        try(var store=new SurvivalCheckpointStore(temp,"world",LIMITS)) {store.commit(0,image(1,1));}
        assertThrows(IOException.class,()->new SurvivalCheckpointStore(temp,"other",LIMITS));
        Path generation;try(var files=Files.list(temp)){generation=files.filter(p->p.getFileName().toString().startsWith("generation-")).findFirst().orElseThrow();}
        Files.write(generation.resolve("drops.bin"),new byte[]{9});
        try(var store=new SurvivalCheckpointStore(temp,"world",LIMITS)){assertThrows(IOException.class,store::load);assertThrows(IOException.class,()->store.commit(1,image(2,2)));}
    }
    @Test void staleDifferentRetryAndSkippedGenerationRefuse() throws Exception {
        try(var store=new SurvivalCheckpointStore(temp,"world",LIMITS)){store.commit(0,image(1,1));assertThrows(IOException.class,()->store.commit(0,image(1,2)));assertThrows(IllegalArgumentException.class,()->store.commit(1,image(3,3)));store.commit(0,image(1,1));}
    }
    @Test void inputAndOutputArraysAreDefensivelyOwned() throws Exception {
        byte[] bytes={1};var image=new SurvivalCheckpointStore.Image(LIMITS,1,Map.of("inventory",bytes));bytes[0]=9;image.payload("inventory")[0]=8;
        try(var store=new SurvivalCheckpointStore(temp,"world",LIMITS)){store.commit(0,image);assertArrayEquals(new byte[]{1},store.load().orElseThrow().payload("inventory"));}
    }
    @Test void limitsAndNamesAreCheckedBeforePayloadCopies() {
        assertThrows(IllegalArgumentException.class,()->new SurvivalCheckpointStore.Image(LIMITS,1,Map.of("inventory",new byte[1025])));
        assertThrows(IllegalArgumentException.class,()->new SurvivalCheckpointStore.Image(LIMITS,1,Map.of("../escape",new byte[1])));
        assertThrows(IllegalArgumentException.class,()->new SurvivalCheckpointStore.Image(LIMITS,1,Map.of()));
    }
    @Test void immutableGenerationOrphansAreRetainedButNeverChosenByScanning() throws Exception {
        try(var store=new SurvivalCheckpointStore(temp,"world",LIMITS)){store.commit(0,image(1,1));}
        Files.createDirectory(temp.resolve("generation-999-00000000-0000-0000-0000-000000000000"));
        try(var store=new SurvivalCheckpointStore(temp,"world",LIMITS)){assertEquals(1,store.load().orElseThrow().generation());store.commit(0,image(1,1));}
        assertTrue(Files.exists(temp.resolve("generation-999-00000000-0000-0000-0000-000000000000")));
    }
    @Test void singleWriterAndClosedOwnerRefuse() throws Exception {
        var store=new SurvivalCheckpointStore(temp,"world",LIMITS);try{assertThrows(Exception.class,()->new SurvivalCheckpointStore(temp,"world",LIMITS));}finally{store.close();}assertThrows(IOException.class,store::load);
    }
    @Test void growingOrInfiniteInputConsumesAtMostTheBoundPlusOne() {
        var count = new java.util.concurrent.atomic.AtomicInteger();
        var growing = new java.io.InputStream() { @Override public int read() { count.incrementAndGet(); return 7; } };
        assertThrows(IOException.class, () -> SurvivalCheckpointStore.readAtMost(growing, 123));
        assertEquals(124, count.get());
    }
    @Test void unreferencedSymlinkDoesNotBecomeARetrySyncTarget() throws Exception {
        try(var store=new SurvivalCheckpointStore(temp,"world",LIMITS)){store.commit(0,image(1,1));}
        Path generation;try(var files=Files.list(temp)){generation=files.filter(p->p.getFileName().toString().startsWith("generation-")).findFirst().orElseThrow();}
        Files.createSymbolicLink(generation.resolve("unreferenced"),temp.resolve("does-not-exist"));
        try(var store=new SurvivalCheckpointStore(temp,"world",LIMITS)){store.commit(0,image(1,1));assertEquals(1,store.load().orElseThrow().generation());}
    }
    @Test void writerLockSymlinkAndBrokenAuthoritySymlinkRefuse() throws Exception {
        Path target=temp.resolve("target");Files.write(target,new byte[]{1});
        Path dir=temp.resolve("lock-case");Files.createDirectory(dir);Files.createSymbolicLink(dir.resolve("writer.lock"),target);
        assertThrows(IOException.class,()->new SurvivalCheckpointStore(dir,"world",LIMITS));assertArrayEquals(new byte[]{1},Files.readAllBytes(target));
        Path another=temp.resolve("authority-case");try(var store=new SurvivalCheckpointStore(another,"world",LIMITS)){};
        Files.delete(another.resolve("CURRENT"));Files.createSymbolicLink(another.resolve("CURRENT"),temp.resolve("missing"));
        assertThrows(IOException.class,()->new SurvivalCheckpointStore(another,"world",LIMITS));
    }
}
