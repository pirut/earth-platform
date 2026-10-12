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

    @Test void compiledValidityRetainsTheExactEntrySequenceAndComparesCurrentReload()throws Exception {
        var node=compiledSession();
        var field=node.fields.stream().filter(f->f.name.equals("entryResourceSequence")).findFirst().orElseThrow();
        assertEquals("J",field.desc);assertNotEquals(0,field.access&Opcodes.ACC_FINAL);
        boolean retained=false,current=false,read=false,compared=false;
        for(var instruction:method(node,"valid").instructions) {
            if(instruction instanceof FieldInsnNode f) {
                retained|=f.getOpcode()==Opcodes.GETFIELD&&f.name.equals("entryResourceSequence");
                current|=f.getOpcode()==Opcodes.GETSTATIC&&f.name.equals("SCENARIO_RELOAD_SEQUENCE");
            }
            if(instruction instanceof MethodInsnNode call)read|=call.owner.equals("java/util/concurrent/atomic/AtomicLong")&&call.name.equals("get");
            compared|=instruction.getOpcode()==Opcodes.LCMP;
        }
        assertTrue(retained&&current&&read&&compared,"Every validity check must compare its immutable preflight generation against the current reload generation");
        boolean capturesApproved=false,storesImmutable=false;
        for(var instruction:method(node,"<init>").instructions)if(instruction instanceof FieldInsnNode f) {
            capturesApproved|=f.getOpcode()==Opcodes.GETSTATIC&&f.name.equals("playableApprovedReload");
            storesImmutable|=f.getOpcode()==Opcodes.PUTFIELD&&f.name.equals("entryResourceSequence");
        }
        assertTrue(capturesApproved&&storesImmutable,"A playable constructor must retain the exact approved resource generation");
    }

    @Test void compiledInitializationChecksValidityBeforeAnyShaderOrDhOwner()throws Exception {
        var node=compiledSession();int index=0,valid=-1,owner=Integer.MAX_VALUE;
        for(var instruction:method(node,"initialize").instructions) {
            if(instruction instanceof MethodInsnNode call) {
                if(call.owner.equals(node.name)&&call.name.equals("valid"))valid=index;
                if(call.name.equals("initializeShader")||call.owner.endsWith("DhCarrierRenderGate")
                        ||call.owner.endsWith("InstalledDhEvents"))owner=Math.min(owner,index);
            }
            index++;
        }
        assertTrue(valid>=0&&valid<owner&&owner<Integer.MAX_VALUE,"No render/source owner may be acquired before the retained entry generation is rechecked");
    }
}
