package io.earthplatform.minecraft.diagnostics;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DiagnosticsTest {
    @Test void frameSummaryUsesObservedDurationsAndBoundedQuantiles() {
        FrameWindow w=new FrameWindow();for(int i=0;i<95;i++)w.add(10_100_000);for(int i=0;i<4;i++)w.add(40_000_001);w.add(3_000_000_000L);w.add(-1);
        var s=w.take();assertEquals(100,s.frames());assertEquals(1,s.invalid());assertEquals(11,s.p95UpperMs());assertEquals(41,s.p99UpperMs());
        assertEquals(3000,s.maxMs());assertEquals(5,s.over40());assertEquals(1,s.over100());assertEquals(1000/s.meanMs(),s.averageFps());
        assertEquals(0,w.take().frames());
        w.add(Long.MAX_VALUE);var capped=w.take();assertTrue(capped.p95Capped());assertTrue(capped.p99Capped());
    }
    @Test void identitiesAndErrorsNeverUseArbitraryText() {
        assertEquals("unknown",BuildInfo.commit("user@example.com token=private"));
        assertEquals("a".repeat(40),BuildInfo.commit("a".repeat(40)));
        assertEquals("java.lang.IllegalStateException",PerfFailures.safeType(new IllegalStateException("username secret server.example token=private")));
    }
    @Test void logRotationAndOversizedEventsAreBounded(@TempDir Path dir) throws Exception {
        LocalPerfLog log=new LocalPerfLog(dir,128,3);
        assertFalse(log.offer("x".repeat(200)));
        for(int i=0;i<30;i++)assertTrue(log.offer("{\"frame\":"+i+"}"));
        log.close();assertFalse(log.failed());assertEquals(1,log.dropped());
        try(var paths=Files.list(dir)) {var files=paths.toList();assertTrue(files.size()<=3);for(var p:files)assertTrue(Files.size(p)<=128);}
        assertFalse(log.offer("{}"));
    }
}
