package io.earthplatform.minecraft.enginebridge;

import io.earthplatform.core.CubePos;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class IndexedReplacementLedgerTest {
    private static final CubePos A = new CubePos(0, 8, 0), B = A.offset(1, 0, 0);
    private static final ReplacementWorkspaces WORKSPACES = ReplacementWorkspaces.adjacent(A);
    private static final String SCOPE = "a".repeat(64);
    @TempDir Path directory;
    private List<NativeReplacementCapture> clean() {
        return WORKSPACES.positions().stream().map(p -> new NativeReplacementCapture(p, new byte[8000])).toList();
    }
    private ReplacementRevisionLedger open() throws IOException { return ReplacementRevisionLedger.openIndexed(directory, WORKSPACES, SCOPE); }

    @Test void summarySealAndNoOpEditKeepIndexIdentitySeparateFromGenerationAndRestart() throws Exception {
        var ledger = open(); assertNull(ledger.attestation());
        ledger.sealCaptures(clean()); var before = ledger.attestation(); assertNotNull(before);
        assertTrue(before.completeCatalogue()); assertEquals(2, before.resultCount());
        ledger.begin(A, 0, clean()); assertNull(ledger.attestation()); assertFalse(ledger.queryCurrent(before));
        assertThrows(IOException.class, () -> ledger.verifyObserved(A, 0));
        ledger.confirm(A, 1); var after = ledger.attestation(); assertNotNull(after);
        assertEquals(before.indexIdentity(), after.indexIdentity()); assertEquals(before.storeIdentity(), after.storeIdentity());
        assertEquals(before.snapshotGeneration() + 1, after.snapshotGeneration());
        assertNotEquals(before.snapshotRootIdentity(), after.snapshotRootIdentity()); assertNotEquals(before.resultDigest(), after.resultDigest());
        assertEquals(1, ledger.expectedRevision(A)); assertEquals(0, ledger.expectedRevision(B));
        var reopened = open(); assertEquals(after, reopened.attestation());
        reopened.sealCaptures(clean()); assertEquals(after, reopened.attestation());
    }

    @Test void reconstructMustMatchPersistedActualCaptureIdentity() throws Exception {
        var ledger = open(); ledger.sealCaptures(clean()); var before = ledger.attestation();
        var changed = clean().stream().map(c -> c.withReadMaterial(15, 129, 0, NativeReplacementCapture.DIORITE)).toList();
        assertThrows(IOException.class, () -> open().sealCaptures(changed));
        assertEquals(before, open().attestation());
    }

    @Test void unresolvedIntentCannotReopenOrAuthorizeAnySet() throws Exception {
        var ledger = open(); ledger.sealCaptures(clean()); var before = ledger.attestation();
        ledger.begin(A, 0, clean());
        assertTrue(Files.exists(directory.resolve(PagedReplacementIndex.INTENT_FILE)));
        assertNull(ledger.attestation()); assertFalse(ledger.queryCurrent(before)); assertThrows(IOException.class, this::open);
    }

    @Test void indexedAndLegacyModesNeverAdoptEachOtherEvenWithoutCubeWrites() throws Exception {
        var ledger = open(); ledger.sealCaptures(clean());
        assertThrows(IOException.class, () -> ReplacementRevisionLedger.open(directory, WORKSPACES));
        assertThrows(IOException.class, () -> ReplacementStoreIdentity.open(directory, A));
        assertFalse(Files.exists(directory.resolve(ReplacementStoreIdentity.ADJACENT_FILE)));
        Path old = Files.createDirectory(directory.resolve("old")); ReplacementRevisionLedger.open(old, WORKSPACES);
        assertThrows(IOException.class, () -> ReplacementRevisionLedger.openIndexed(old, WORKSPACES, SCOPE));
        assertFalse(Files.exists(old.resolve(PagedReplacementIndex.ROOT_FILE)));
    }

    @Test void unknownThirdPositionAndIncompleteProspectiveCapturesNeverCreateIntent() throws Exception {
        var ledger = open(); ledger.sealCaptures(clean()); var before = ledger.attestation();
        assertThrows(IllegalArgumentException.class, () -> ledger.begin(B.offset(1, 0, 0), 0, clean()));
        assertThrows(IOException.class, () -> ledger.begin(A, 0, clean().subList(0, 1)));
        assertFalse(Files.exists(directory.resolve(PagedReplacementIndex.INTENT_FILE)));
        assertEquals(before, ledger.attestation());
    }
}
