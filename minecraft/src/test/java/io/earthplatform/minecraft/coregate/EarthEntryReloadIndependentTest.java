package io.earthplatform.minecraft.coregate;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;
import static org.junit.jupiter.api.Assertions.*;

/** Deterministic construction/publication interleavings and compiled production-wiring checks. */
class EarthEntryReloadIndependentTest {
    private static final class EntryModel {
        final long approvedSequence;
        boolean fenced, initialized;
        EntryModel(long approvedSequence,AtomicLong sequence) {
            this.approvedSequence=approvedSequence;
            if(sequence.get()!=approvedSequence)throw new IllegalStateException("Preflight changed before construction");
        }
        boolean valid(AtomicLong sequence){return !fenced&&sequence.get()==approvedSequence;}
        void initialize(AtomicLong sequence){if(!valid(sequence))throw new IllegalStateException("Entry expired");initialized=true;}
    }
    private static void reload(AtomicLong sequence,AtomicReference<EntryModel> current) {
        sequence.incrementAndGet();var owner=current.get();if(owner!=null)owner.fenced=true;
    }

    @Test void reloadBetweenConstructorCheckAndPublicationCannotInitializeTheMissedOwner() {
        var sequence=new AtomicLong(7);var current=new AtomicReference<EntryModel>();
        long approved=sequence.get();var candidate=new EntryModel(approved,sequence);
        reload(sequence,current); // The listener cannot fence the as-yet unpublished candidate.
        assertFalse(candidate.fenced);current.set(candidate);
        assertFalse(candidate.valid(sequence));
        assertThrows(IllegalStateException.class,()->candidate.initialize(sequence));
        assertFalse(candidate.initialized);
    }

    @Test void bothSidesOfPublicationAndSubsequentReloadRemainFenced() {
        var before=new AtomicLong(1);long old=before.get();reload(before,new AtomicReference<>());
        assertThrows(IllegalStateException.class,()->new EntryModel(old,before));
        var sequence=new AtomicLong(1);var owner=new EntryModel(1,sequence);var current=new AtomicReference<>(owner);
        owner.initialize(sequence);assertTrue(owner.valid(sequence));
        reload(sequence,current);assertFalse(owner.valid(sequence));assertTrue(owner.fenced);
    }

    private static ClassNode compiledSession()throws IOException {
        var result=new ClassNode();
        try(var input=EarthEntryReloadIndependentTest.class.getResourceAsStream("RollingGateClient$Session.class")) {
            assertNotNull(input);new ClassReader(input).accept(result,0);
        }
        return result;
    }
    private static MethodNode method(ClassNode node,String name) {
        return node.methods.stream().filter(m->m.name.equals(name)).findFirst().orElseThrow();
    }


}
