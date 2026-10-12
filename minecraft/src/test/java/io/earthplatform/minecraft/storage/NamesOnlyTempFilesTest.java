package io.earthplatform.minecraft.storage;

import io.earthplatform.compat.neoforge.NamesOnlyTempFiles;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.FileVisitOption;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.List;
import java.util.Set;
import java.util.function.BiPredicate;

import static org.junit.jupiter.api.Assertions.*;

class NamesOnlyTempFilesTest {
    @TempDir Path temp;
    static BiPredicate<Path, BasicFileAttributes> predicate(String prefix) {
        return (path, unused) -> path.getFileName().toString().endsWith(".neoforge-tmp")
                && (prefix == null || path.getFileName().toString().startsWith(prefix));
    }
    static List<Path> names(Path path, String prefix) throws IOException {
        try (var stream = NamesOnlyTempFiles.namesOnlyDepthOne(path, 1, predicate(prefix))) {
            return stream.toList();
        }
    }
    static List<Path> original(Path path, String prefix) throws IOException {
        try (var stream = Files.find(path, 1, predicate(prefix))) { return stream.toList(); }
    }
    @Test void exactPrefixSuffixAndDepthSelectionMatchThePinnedWalk() throws Exception {
        Path dir=Files.createDirectory(temp.resolve("data"));
        Path a=Files.writeString(dir.resolve("map.dat1.neoforge-tmp"),"temporary");
        Path b=Files.writeString(dir.resolve("other.neoforge-tmp"),"temporary");
        Path nested=Files.createDirectory(dir.resolve("nested"));
        Files.writeString(nested.resolve("map.deep.neoforge-tmp"),"nested");
        Path journal=Files.writeString(dir.resolve("DistantHorizons.sqlite-journal"),"journal");
        Path saved=Files.writeString(dir.resolve("map.dat"),"saved");
        assertEquals(Set.of(a,b),Set.copyOf(names(dir,null)));
        assertEquals(List.of(a),names(dir,"map.dat"));
        assertEquals(Set.copyOf(original(dir,null)),Set.copyOf(names(dir,null)));
        assertEquals("journal",Files.readString(journal)); assertEquals("saved",Files.readString(saved));
    }
    @Test void rootItselfAndMatchingDirectoriesRemainIncluded() throws Exception {
        Path root=Files.createDirectory(temp.resolve("root.neoforge-tmp"));
        Path child=Files.createDirectory(root.resolve("child.neoforge-tmp"));
        Files.writeString(child.resolve("deeper.neoforge-tmp"),"ignored");
        assertEquals(Set.of(root,child),Set.copyOf(names(root,null)));
        assertEquals(Set.copyOf(original(root,null)),Set.copyOf(names(root,null)));
    }
    @Test void regularFileRootIsNotOpenedAsDirectory() throws Exception {
        Path root=Files.writeString(temp.resolve("one.neoforge-tmp"),"x");
        assertEquals(List.of(root),names(root,null));
        assertEquals(List.of(),names(root,"different"));
        assertEquals(original(root,null),names(root,null));
    }
    @Test void symbolicRootIsNotFollowedAndSymbolicChildMatchesByName() throws Exception {
        Path target=Files.createDirectory(temp.resolve("target"));
        Files.writeString(target.resolve("deep.neoforge-tmp"),"x");
        Path root=Files.createSymbolicLink(temp.resolve("link.neoforge-tmp"),target);
        assertEquals(List.of(root),names(root,null)); assertEquals(original(root,null),names(root,null));
        Path parent=Files.createDirectory(temp.resolve("parent"));
        Path link=Files.createSymbolicLink(parent.resolve("child.neoforge-tmp"),target);
        assertEquals(List.of(link),names(parent,null)); assertEquals(original(parent,null),names(parent,null));
    }
    @Test void missingRootAndPredicateFailureRemainFailures() throws Exception {
        assertThrows(java.nio.file.NoSuchFileException.class,()->names(temp.resolve("missing"),null));
        RuntimeException expected=new IllegalStateException("predicate");
        try(var stream=NamesOnlyTempFiles.namesOnlyDepthOne(temp,1,(path,attrs)->{throw expected;})) {
            assertSame(expected,assertThrows(IllegalStateException.class,stream::toList));
        }
    }
    @Test void unsupportedDepthAndLinkOptionsRejectBeforeFilesystemAccess() {
        Path absent=temp.resolve("not-present");
        assertThrows(IllegalArgumentException.class,()->NamesOnlyTempFiles.namesOnlyDepthOne(absent,0,predicate(null)));
        assertThrows(IllegalArgumentException.class,()->NamesOnlyTempFiles.namesOnlyDepthOne(absent,2,predicate(null)));
        assertThrows(IllegalArgumentException.class,()->NamesOnlyTempFiles.namesOnlyDepthOne(absent,1,predicate(null),FileVisitOption.FOLLOW_LINKS));
    }
    @Test void rootAttributesAreRealAndChildrenHaveNoInventedAttributes() throws Exception {
        Path child=Files.writeString(temp.resolve("selected.neoforge-tmp"),"x");
        java.util.ArrayList<Path> called=new java.util.ArrayList<>();
        try(var stream=NamesOnlyTempFiles.namesOnlyDepthOne(temp,1,(path,attrs)->{
            called.add(path); if(path.equals(temp)){assertNotNull(attrs);assertTrue(attrs.isDirectory());}
            else {assertEquals(child,path);assertNull(attrs);}
            return true;
        })) {assertEquals(List.of(temp,child),stream.toList());}
        assertEquals(List.of(temp,child),called);
    }
}
