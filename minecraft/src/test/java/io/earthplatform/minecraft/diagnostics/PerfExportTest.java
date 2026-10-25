package io.earthplatform.minecraft.diagnostics;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.zip.ZipFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PerfExportTest {
    @Test void packsOnlyTheLogAndKeepsTheNewestFive(@TempDir Path dir) throws Exception {
        assertThrows(NoSuchFileException.class,()->PerfExport.export(dir,Instant.EPOCH));
        Files.writeString(dir.resolve("latest.jsonl"),"{\"type\":\"window\"}\n");
        Files.writeString(dir.resolve("latest.1.jsonl"),"{\"type\":\"build\"}\n");
        Files.writeString(dir.resolve("other.txt"),"not part of the log");
        Path zip=PerfExport.export(dir,Instant.parse("2026-10-25T23:30:00Z"));
        assertEquals("earth-performance-20261025-233000.zip",zip.getFileName().toString());
        try(ZipFile file=new ZipFile(zip.toFile())) {
            var names=new ArrayList<String>();file.stream().forEach(e->names.add(e.getName()));
            assertEquals(java.util.List.of("README.txt","latest.1.jsonl","latest.jsonl"),names);
            assertEquals("{\"type\":\"window\"}\n",new String(file.getInputStream(file.getEntry("latest.jsonl")).readAllBytes()));
        }
        for(int i=1;i<=7;i++)PerfExport.export(dir,Instant.parse("2026-10-26T00:00:0"+i+"Z"));
        try(var paths=Files.list(dir.resolve("exports"))) {
            var left=paths.map(p->p.getFileName().toString()).sorted().toList();
            assertEquals(PerfExport.KEEP,left.size());assertEquals("earth-performance-20261026-000007.zip",left.getLast());
        }
    }
}
