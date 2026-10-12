package io.earthplatform.minecraft.coregate;

import java.io.IOException;
import java.util.*;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import static org.junit.jupiter.api.Assertions.*;

/** Controller bytecode contracts: actual owner wiring, without constructing or mocking a Level. */
class RollingEarthServerBootstrapTest {
    private static final String COMMANDS="io/earthplatform/minecraft/coregate/RollingGateCommands";
    private static final String PREPARATION="io/earthplatform/minecraft/enginebridge/BoundedProviderPreparation";
    private static final String DESCRIPTOR="io/earthplatform/minecraft/coregate/RollingSessionDescriptor";
    private static final String PALETTE="Lio/earthplatform/minecraft/network/EnginePaletteContext;";

    @Test void earthCommandIsOptedInAndOnlySubmitsImmutableSelectorAndPaletteWork() throws IOException {
        var c=read(COMMANDS);var enter=method(c,"enterEarth");var begin=method(c,"beginEarthPreparation");
        assertTrue(constants(method(c,"commands")).contains("earth"));
        assertTrue(constants(enter).contains("earthplatform.experimental.earthPlayerGate"));
        int legacy=index(enter,call(COMMANDS,"beginEarthPreparation"));
        assertRequiredBoolean(enter,call("java/lang/Boolean","getBoolean"),legacy,true);
        var optIn=previousOpcode(enter.instructions.get(index(enter,call("java/lang/Boolean","getBoolean"))));
        assertEquals("earthplatform.experimental.earthPlayerGate",assertInstanceOf(LdcInsnNode.class,optIn).cst);
        assertRequiredBoolean(enter,call(COMMANDS,"mayEnter"),legacy,true);
        assertRequiredBoolean(enter,call("io/earthplatform/compat/PlayableProfile$Selection","earth"),legacy,false);
        assertEquals(Opcodes.ICONST_0,previousOpcode(enter.instructions.get(legacy)).getOpcode());
        int playable=index(enter,call(COMMANDS,"requestPlayable"));
        assertRequiredBoolean(enter,call("io/earthplatform/compat/PlayableProfile$Selection","earth"),playable,true);
        var request=method(c,"requestPlayable");int requestSent=index(request,call("net/neoforged/neoforge/network/PacketDistributor","sendToPlayer"));
        assertRequiredBoolean(request,call("io/earthplatform/compat/dh/EarlyPlayableAdmission","enabled"),requestSent,true);
        assertRequiredBoolean(request,call(COMMANDS,"mayEnter"),requestSent,true);
        var reply=method(c,"playableEntryReply");int alpine=index(reply,call(COMMANDS,"beginEarthPreparation"));
        assertRequiredBoolean(reply,call(COMMANDS,"mayEnter"),alpine,true);
        assertRequiredBoolean(reply,call("io/earthplatform/compat/PlayableProfile$Selection","earth"),alpine,true);
        assertEquals(Opcodes.ICONST_1,previousOpcode(reply.instructions.get(alpine)).getOpcode());
        assertEquals(Set.of("enterEarth","playableEntryReply"),c.methods.stream()
                .filter(m->has(m,call(COMMANDS,"beginEarthPreparation"))).map(m->m.name).collect(java.util.stream.Collectors.toSet()),
                "No unguarded third entry may submit provider work");
        assertEquals(Set.of("beginEarthPreparation","playableEntryReply"),c.methods.stream()
                .filter(m->has(m,call(PREPARATION,"submit"))).map(m->m.name).collect(java.util.stream.Collectors.toSet()));
        var closures=instructions(begin).stream().filter(InvokeDynamicInsnNode.class::isInstance)
                .map(InvokeDynamicInsnNode.class::cast).filter(n->n.desc.endsWith("L"+PREPARATION+"$Source;")).toList();
        assertEquals(1,closures.size());
        var closure=closures.getFirst();
        assertEquals("(Z"+PALETTE+")L"+PREPARATION+"$Source;",closure.desc,
                "Provider source captures exactly a primitive selector and immutable palette, never player/server/Level");
        var handles=Arrays.stream(closure.bsmArgs).filter(Handle.class::isInstance).map(Handle.class::cast).toList();
        assertEquals(1,handles.size());var implementation=handles.getFirst();
        assertEquals(COMMANDS,implementation.getOwner());assertEquals(Opcodes.H_INVOKESTATIC,implementation.getTag());
        var source=method(c,implementation.getName());
        assertEquals("(Z"+PALETTE+"L"+PREPARATION+"$Cancellation;)Lio/earthplatform/minecraft/coregate/RollingEarthPreparation$Prepared;",source.desc);
        assertEquals(source.desc,implementation.getDesc());
        assertTrue((source.access&Opcodes.ACC_STATIC)!=0);assertTrue((source.access&Opcodes.ACC_SYNTHETIC)!=0);
        assertFalse(has(source,FieldInsnNode.class::isInstance),"Worker cannot recover mutable owners through fields");
        var first=source.instructions.getFirst();while(first.getOpcode()<0)first=first.getNext();
        assertEquals(Opcodes.ILOAD,first.getOpcode());assertEquals(0,assertInstanceOf(VarInsnNode.class,first).var);
        for(boolean selected:new boolean[]{false,true}) {
            String owner="io/earthplatform/minecraft/coregate/"+(selected?"RollingAlpinePreparation":"RollingEarthPreparation");
            int preparation=index(source,call(owner,"prepare"));
            assertBooleanBranch(source,assertInstanceOf(JumpInsnNode.class,nextOpcode(first)),preparation,selected);
            assertEquals(1,instructions(source).stream().filter(call(owner,"prepare")).count());
        }
        for(var m:List.of(enter,begin,source))
            assertFalse(has(m,n->n instanceof MethodInsnNode call&&(call.name.equals("teleportTo")||call.name.equals("setGameMode")||call.name.equals("open"))));
    }

    @Test void continuationSourceSubmissionIsOwnerBoundAndCapturesOnlyImmutablePalette()throws IOException{
        var c=read(COMMANDS);var reply=method(c,"playableEntryReply");int submit=index(reply,call(PREPARATION,"submit"));
        assertRequiredBoolean(reply,call(COMMANDS,"distanceOwnerCurrent"),submit,true);
        assertRequiredBoolean(reply,call("io/earthplatform/minecraft/coregate/NativeDistanceRestartGate","ready"),submit,true);
        assertBefore(reply,call(COMMANDS+"$PendingPlayable","serial"),call(PREPARATION,"submit"));
        assertBefore(reply,call("io/earthplatform/minecraft/coregate/NativeDistanceChangePayload","serial"),call(PREPARATION,"submit"));
        var closures=instructions(reply).stream().filter(InvokeDynamicInsnNode.class::isInstance).map(InvokeDynamicInsnNode.class::cast)
                .filter(n->n.desc.endsWith("L"+PREPARATION+"$Source;")).toList();
        assertEquals(1,closures.size());var closure=closures.getFirst();
        assertEquals("("+PALETTE+")L"+PREPARATION+"$Source;",closure.desc);
        var handles=Arrays.stream(closure.bsmArgs).filter(Handle.class::isInstance).map(Handle.class::cast).toList();
        assertEquals(1,handles.size());var impl=handles.getFirst();assertEquals(COMMANDS,impl.getOwner());assertEquals(Opcodes.H_INVOKESTATIC,impl.getTag());
        var source=method(c,impl.getName());
        assertEquals("("+PALETTE+"L"+PREPARATION+"$Cancellation;)Lio/earthplatform/minecraft/coregate/RollingEarthPreparation$Prepared;",source.desc);
        assertFalse(has(source,FieldInsnNode.class::isInstance),"Continuation worker must not recover mutable owner fields");
        assertEquals(1,instructions(source).stream().filter(call("io/earthplatform/minecraft/coregate/RollingAlpinePreparation","prepare")).count());
        assertFalse(has(reply,n->n instanceof MethodInsnNode m&&(m.name.equals("teleportTo")||m.name.equals("setGameMode"))));
    }

    @Test void ordinaryOpenDelegatesNullAndContinuationCannotTeleportOrChangeMode()throws IOException{
        var c=read(COMMANDS);var wrapper=method(c,"open");var owner=ownerOpen(c);
        assertNotSame(wrapper,owner);var delegation=instructions(wrapper).stream().filter(n->n instanceof MethodInsnNode m&&m.owner.equals(COMMANDS)&&m.name.equals("open")).map(MethodInsnNode.class::cast).toList();
        assertEquals(1,delegation.size());assertEquals(owner.desc,delegation.getFirst().desc);
        assertEquals(Opcodes.ACONST_NULL,previousOpcode(delegation.getFirst()).getOpcode());
        assertFalse(has(wrapper,n->n instanceof MethodInsnNode m&&(m.name.equals("teleportTo")||m.name.equals("setGameMode"))));
        int mode=index(owner,call("net/minecraft/server/level/ServerPlayer","setGameMode"));
        int teleport=index(owner,call("net/minecraft/server/level/ServerPlayer","teleportTo"));
        var args=Type.getArgumentTypes(owner.desc);int localSlots=0;for(int i=0;i<args.length-1;i++)localSlots+=args[i].getSize();final int continuationLocal=localSlots;
        var branches=instructions(owner).stream().filter(n->n instanceof VarInsnNode v&&v.getOpcode()==Opcodes.ALOAD&&v.var==continuationLocal)
                .map(RollingEarthServerBootstrapTest::nextOpcode).filter(n->n instanceof JumpInsnNode j&&(j.getOpcode()==Opcodes.IFNULL||j.getOpcode()==Opcodes.IFNONNULL)).map(JumpInsnNode.class::cast).toList();
        assertTrue(branches.stream().anyMatch(branch->{int jump=owner.instructions.indexOf(branch.label),fall=owner.instructions.indexOf(branch)+1;
            int whenNull=branch.getOpcode()==Opcodes.IFNULL?jump:fall,whenPresent=branch.getOpcode()==Opcodes.IFNULL?fall:jump;
            return reachable(owner,whenNull,teleport,Set.of())&&!reachable(owner,whenPresent,teleport,Set.of())&&!reachable(owner,whenPresent,mode,Set.of());}),"Continuation must return before ordinary entry mode/teleport");
    }

    @Test void occupiedBootstrapAllowanceRequiresValidatedSpectatorContinuationAndKnownCoverage()throws IOException{
        for(boolean bootstrap:new boolean[]{false,true})for(boolean continuation:new boolean[]{false,true})
            for(boolean spectator:new boolean[]{false,true})for(boolean noPhysics:new boolean[]{false,true})
                assertEquals((!bootstrap||continuation)&&spectator&&noPhysics,RollingGateCommands.permitOccupiedBodyAtBootstrap(bootstrap,continuation,spectator,noPhysics));
        assertFalse(RollingGateCommands.permitOccupiedBodyAtBootstrap(true,false,true,true),"Ordinary first entry must still be clear for walking");
        var c=read(COMMANDS);var prepared=method(c,"preparedBodyKnown");
        assertBefore(prepared,call(COMMANDS,"windowSnapshots"),call(COMMANDS,"permitOccupiedBodyAtBootstrap"));
        assertBefore(prepared,call(COMMANDS,"permitOccupiedBodyAtBootstrap"),call("io/earthplatform/minecraft/coregate/CoreCollisionBinding","preparedBodyKnown"));
        assertTrue(has(prepared,n->n instanceof FieldInsnNode f&&f.owner.equals(COMMANDS+"$Session")&&f.name.equals("continuation")&&f.getOpcode()==Opcodes.GETFIELD));
        for(String name:List.of("bootstrap","spectator","noPhysics"))assertTrue(has(prepared,n->n instanceof MethodInsnNode m&&m.name.equals(name)));
        var returns=instructions(prepared).stream().filter(n->n.getOpcode()==Opcodes.IRETURN).toList();assertEquals(1,returns.size());
        assertTrue(call("io/earthplatform/minecraft/coregate/CoreCollisionBinding","preparedBodyKnown").test(previousOpcode(returns.getFirst())),"Known-cell proof must directly determine admission");
        var owner=ownerOpen(c);int continuationStore=index(owner,n->n instanceof FieldInsnNode f&&f.owner.equals(COMMANDS+"$Session")&&f.name.equals("continuation")&&f.getOpcode()==Opcodes.PUTFIELD);
        int check=index(owner,call(COMMANDS,"distanceOwnerCurrent"));assertBooleanBranch(owner,assertInstanceOf(JumpInsnNode.class,nextOpcode(owner.instructions.get(check))),continuationStore,true);
    }

    @Test void onePendingOwnerRetainsExactPlayerConnectionSourceAndTicket() throws IOException {
        var c=read(COMMANDS);var pending=read(COMMANDS+"$PendingOpen");
        assertEquals(1,c.fields.stream().filter(f->f.desc.equals("L"+COMMANDS+"$PendingOpen;")).count());
        var fields=new HashMap<String,String>();pending.fields.forEach(f->fields.put(f.name,f.desc));
        assertEquals("Lnet/minecraft/server/level/ServerPlayer;",fields.get("requestedPlayer"));
        assertEquals("Lnet/minecraft/server/network/ServerGamePacketListenerImpl;",fields.get("connection"));
        assertEquals("Ljava/util/UUID;",fields.get("player"));
        assertEquals("Lnet/minecraft/resources/ResourceKey;",fields.get("sourceDimension"));
        assertEquals("Lnet/minecraft/world/level/GameType;",fields.get("sourceMode"));
        assertEquals("J",fields.get("epoch"));assertEquals(PALETTE,fields.get("palette"));
        assertEquals("L"+PREPARATION+"$Ticket;",fields.get("ticket"));
        assertTrue(read(COMMANDS+"$Session").fields.stream().anyMatch(f->f.name.equals("descriptor")&&f.desc.equals("L"+DESCRIPTOR+";")));
        assertFalse(c.fields.stream().anyMatch(f->f.desc.contains("Future")||f.desc.contains("Executor")));
    }

    @Test void ownerTickValidatesBeforeClaimAndClaimBeforeAnyAdmission() throws IOException {
        var c=read(COMMANDS);var tick=method(c,"tick");var pump=method(c,"pumpOpen");
        assertBefore(tick,call(COMMANDS,"pumpOpen"),n->n instanceof FieldInsnNode f&&f.owner.equals(COMMANDS)&&f.name.equals("session"));
        assertBefore(pump,call(COMMANDS,"pendingCurrent"),call(PREPARATION+"$Ticket","takeReady"));
        assertBefore(pump,call(PREPARATION+"$Ticket","takeReady"),call(COMMANDS,"open"));
        assertBefore(pump,call(DESCRIPTOR,"requirePreparedWorld"),call(COMMANDS,"open"));
        assertFalse(reachable(pump,0,index(pump,call(COMMANDS,"open")),Set.of(index(pump,call(COMMANDS,"pendingCurrent")))));
        assertFalse(reachable(pump,0,index(pump,call(COMMANDS,"open")),Set.of(index(pump,call(PREPARATION+"$Ticket","takeReady")))));
        assertTrue(has(pump,n->n instanceof FieldInsnNode f&&f.owner.equals(COMMANDS)&&f.name.equals("EPOCH")&&f.getOpcode()==Opcodes.GETSTATIC));
        assertFalse(has(pump,call(PREPARATION+"$Ticket","stamp")),"Claim must use the current owner epoch, never trust the result's stamp");
    }

    @Test void replacementEpochConnectionDeathWorldAndModeInvalidateAdmission() throws IOException {
        var current=method(read(COMMANDS),"pendingCurrent");
        for(String name:List.of("epoch","requestedPlayer","server","player","connection","sourceDimension","sourceMode"))
            assertTrue(has(current,call(COMMANDS+"$PendingOpen",name)),"Missing pending owner check: "+name);
        for(String name:List.of("isAlive","isRemoved","getPlayer","dimension","getGameModeForPlayer","remembered","current","allowed"))
            assertTrue(has(current,n->n instanceof MethodInsnNode m&&m.name.equals(name)),"Missing current owner check: "+name);
        assertTrue(has(current,n->n.getOpcode()==Opcodes.LCMP),"Epoch must be compared, not merely read");
        assertTrue(instructions(current).stream().filter(n->n.getOpcode()==Opcodes.IF_ACMPNE).count()>=5,
                "Player, server, exact connection, connection.player and source mode must reject reference replacement");
        assertTrue(has(current,n->n instanceof FieldInsnNode f&&f.owner.equals(COMMANDS)&&f.name.equals("pendingOpen")));
        assertTrue(has(current,n->n instanceof FieldInsnNode f&&f.owner.equals(COMMANDS)&&f.name.equals("session")));
    }

    @Test void failedCancelledOrUnclaimablePreparationCannotReachOpenOrTeleport() throws IOException {
        var pump=method(read(COMMANDS),"pumpOpen");int opening=index(pump,call(COMMANDS,"open"));
        var code=instructions(pump);int cancellations=0;
        for(int i=0;i<code.size();i++)if(call(COMMANDS,"cancelPending").test(code.get(i))){
            cancellations++;assertFalse(reachable(pump,i+1,opening,Set.of()),"Invalid preparation must terminate before host admission");
        }
        assertEquals(3,cancellations,"Stale owner, failed source and failed current-epoch claim must cancel");
        assertFalse(reachable(pump,index(pump,call(PREPARATION+"$Ticket","failure")),opening,Set.of()));
        assertFalse(has(pump,n->n instanceof MethodInsnNode m&&(m.name.equals("teleportTo")||m.name.equals("setGameMode"))));
        assertTrue(has(pump,call(PREPARATION+"$Ticket","close")),"Claimed admission retains no outstanding result");
    }

    @Test void everyCancellationLifecycleReachesTicketCloseAndNeverPublishes() throws IOException {
        var c=read(COMMANDS);
        for(String name:List.of("logout","login","death","clonePlayer"))
            assertTrue(has(method(c,name),call(COMMANDS,"cancelPendingPlayer")),name+" must cancel an old owner");
        for(String name:List.of("leave","stopping","stopped"))
            assertTrue(has(method(c,name),call(COMMANDS,"cancelPending")),name+" must cancel before draining");
        assertTrue(has(method(c,"cancelPendingPlayer"),call(COMMANDS,"cancelPending")));
        var cancel=method(c,"cancelPending");
        assertBefore(cancel,n->n instanceof FieldInsnNode f&&f.owner.equals(COMMANDS)&&f.name.equals("pendingOpen")&&f.getOpcode()==Opcodes.PUTSTATIC,
                call(PREPARATION+"$Ticket","close"));
        assertFalse(has(cancel,call(COMMANDS,"open")));
        assertFalse(has(cancel,n->n instanceof MethodInsnNode m&&(m.name.startsWith("then")||m.name.equals("join")||m.name.equals("get"))));
    }

    @Test void registrationFailureClosesTheAlreadyAdmittedTicketBeforeRethrow() throws IOException {
        assertTicketRegistrationCleanup(method(read(COMMANDS),"beginEarthPreparation"));
    }
    @Test void continuationRegistrationFailureClosesExactTicketBeforeRethrow() throws IOException {
        assertTicketRegistrationCleanup(method(read(COMMANDS),"playableEntryReply"));
    }
    private static void assertTicketRegistrationCleanup(MethodNode enter){
        assertBefore(enter,call(PREPARATION,"submit"),call(COMMANDS+"$PendingOpen","<init>"));
        int submitted=index(enter,call(PREPARATION,"submit")),registration=index(enter,call(COMMANDS+"$PendingOpen","<init>"));
        int publication=index(enter,n->n instanceof FieldInsnNode f&&f.owner.equals(COMMANDS)&&f.name.equals("pendingOpen")&&f.getOpcode()==Opcodes.PUTSTATIC);
        var ticket=assertInstanceOf(VarInsnNode.class,nextOpcode(enter.instructions.get(submitted)));
        assertEquals(Opcodes.ASTORE,ticket.getOpcode());
        for(String exception:List.of("java/lang/RuntimeException","java/lang/Error")) {
            var handler=enter.tryCatchBlocks.stream().filter(t->exception.equals(t.type)
                    &&submitted<enter.instructions.indexOf(t.start)&&enter.instructions.indexOf(t.start)<registration
                    &&publication<enter.instructions.indexOf(t.end)).findFirst().orElseThrow();
            int protectedStart=enter.instructions.indexOf(handler.start),protectedEnd=enter.instructions.indexOf(handler.end);
            assertTrue(submitted<protectedStart&&protectedStart<registration&&publication<protectedEnd,
                    "Registration constructor and publication must both be inside post-submission cleanup scope");
            int start=enter.instructions.indexOf(handler.handler);int close=indexAfter(enter,start,call(PREPARATION+"$Ticket","close"));
            int rethrow=indexAfter(enter,close,n->n.getOpcode()==Opcodes.ATHROW);
            assertTrue(close>=start&&rethrow>close,"Failed registration must close before rethrowing "+exception);
            assertFalse(reachable(enter,start,rethrow,Set.of(close)),"Cleanup cannot be bypassed");
            var receiver=assertInstanceOf(VarInsnNode.class,previousOpcode(enter.instructions.get(close)));
            assertEquals(Opcodes.ALOAD,receiver.getOpcode());assertEquals(ticket.var,receiver.var,"Close the exact submitted ticket");
            var caught=assertInstanceOf(VarInsnNode.class,nextOpcode(handler.handler));
            var thrown=assertInstanceOf(VarInsnNode.class,previousOpcode(enter.instructions.get(rethrow)));
            assertEquals(Opcodes.ASTORE,caught.getOpcode());assertEquals(Opcodes.ALOAD,thrown.getOpcode());
            assertEquals(caught.var,thrown.var,"Rethrow the original registration failure");
            assertFalse(reachable(enter,start,publication,Set.of()),"Failed registration cannot publish");
        }
        var rejected=enter.tryCatchBlocks.stream().filter(t->"java/util/concurrent/RejectedExecutionException".equals(t.type)).findFirst().orElseThrow();
        assertTrue(enter.instructions.indexOf(rejected.start)<=submitted&&submitted<enter.instructions.indexOf(rejected.end));
        assertFalse(reachable(enter,enter.instructions.indexOf(rejected.handler),publication,Set.of()),"Rejected admission cannot publish a pending owner");
    }

    @Test void measuredEntryPitchSupportsTheBodyClearGroundedEditTarget() throws IOException {
        var open=ownerOpen(read(COMMANDS));
        var code=instructions(open).stream().filter(n->n.getOpcode()>=0).toList();
        var teleports=code.stream().filter(n->n instanceof MethodInsnNode m&&m.name.equals("teleportTo")).toList();
        assertEquals(1,teleports.size(),"Entry has exactly one actual teleport");
        var teleport=assertInstanceOf(MethodInsnNode.class,teleports.getFirst());
        assertEquals(Opcodes.INVOKEVIRTUAL,teleport.getOpcode());
        assertEquals("net/minecraft/server/level/ServerPlayer",teleport.owner);
        assertEquals("(Lnet/minecraft/server/level/ServerLevel;DDDLjava/util/Set;FF)Z",teleport.desc);
        int target=code.indexOf(teleport);
        var pitch=assertInstanceOf(MethodInsnNode.class,code.get(target-1));
        var yaw=assertInstanceOf(MethodInsnNode.class,code.get(target-3));
        assertEquals(COMMANDS,pitch.owner);assertEquals("entryPitch",pitch.name);
        assertEquals(COMMANDS,yaw.owner);assertEquals("entryYaw",yaw.name);
        for(var helper:List.of(yaw,pitch)) {
            assertEquals(Opcodes.INVOKESTATIC,helper.getOpcode());
            assertEquals("(L"+DESCRIPTOR+";)F",helper.desc);
        }
        var pitchArgument=assertInstanceOf(VarInsnNode.class,code.get(target-2));
        var yawArgument=assertInstanceOf(VarInsnNode.class,code.get(target-4));
        assertEquals(Opcodes.ALOAD,pitchArgument.getOpcode());
        assertEquals(Opcodes.ALOAD,yawArgument.getOpcode());
        assertEquals(pitchArgument.var,yawArgument.var,"Both actual camera operands use the same descriptor");
        int entryCalls=0;
        for(int i=0;i<target;i++)if(call(DESCRIPTOR,"entry").test(code.get(i))) {
            entryCalls++;
            var receiver=assertInstanceOf(VarInsnNode.class,code.get(i-1));
            assertEquals(Opcodes.ALOAD,receiver.getOpcode());
            assertEquals(pitchArgument.var,receiver.var,"XYZ spawn and camera use the same immutable descriptor");
            var coordinate=assertInstanceOf(FieldInsnNode.class,code.get(i+1));
            assertEquals(Opcodes.GETFIELD,coordinate.getOpcode());
            assertEquals("net/minecraft/world/phys/Vec3",coordinate.owner);
            assertEquals(List.of("x","y","z").get(entryCalls-1),coordinate.name);
        }
        assertEquals(3,entryCalls,"All three spawn coordinates remain descriptor-owned");

        var biomes=new net.minecraft.core.MappedRegistry<net.minecraft.world.level.biome.Biome>(
                net.minecraft.core.registries.Registries.BIOME,com.mojang.serialization.Lifecycle.stable());
        biomes.register(net.minecraft.world.level.biome.Biomes.PLAINS,
                net.minecraft.data.registries.VanillaRegistries.createLookup()
                        .lookupOrThrow(net.minecraft.core.registries.Registries.BIOME)
                        .getOrThrow(net.minecraft.world.level.biome.Biomes.PLAINS).value(),
                net.minecraft.core.RegistrationInfo.BUILT_IN);
        var palette=io.earthplatform.minecraft.network.EnginePaletteContext.create(biomes.freeze());
        var world=new io.earthplatform.core.WorldIdentity(173,
                io.earthplatform.minecraft.enginebridge.NativeEarthCubeProvider.GENERATOR_ID,
                io.earthplatform.minecraft.enginebridge.NativeEarthCubeProvider.GENERATOR_VERSION+"/"+RollingGateFixture.VERSION,
                "a".repeat(64),io.earthplatform.minecraft.enginebridge.NativeCubeCodec.paletteIdentity(palette));
        var earth=RollingSessionDescriptor.earth(world,palette,9674936,7506,-3112472);
        assertEquals(0f,RollingGateCommands.entryYaw(earth));
        assertEquals(31f,RollingGateCommands.entryPitch(earth),"Measured Earth retains the grounded-edit aim");
        assertEquals(new net.minecraft.world.phys.Vec3(9674936.5,7509,-3112471.5),earth.entry());
        for(var profile:CoreGateProfile.values()) {
            var analytic=RollingSessionDescriptor.analytic(profile,palette);
            assertEquals(0f,RollingGateCommands.entryYaw(analytic));
            assertEquals(31f,RollingGateCommands.entryPitch(analytic),"Every analytic preset retains its original aim");
            assertEquals(profile.entry(),analytic.entry());
        }
        var arena=RollingSessionDescriptor.arena(palette);
        assertEquals(-150f,RollingGateCommands.entryYaw(arena));
        assertEquals(-8f,RollingGateCommands.entryPitch(arena));
        assertEquals(new net.minecraft.world.phys.Vec3(8.5,145.01,12.5),arena.entry());
    }

    @Test void descriptorControlsStoreCollisionMetadataAndAllActorGoals() throws IOException {
        var c=read(COMMANDS);var open=ownerOpen(c);
        assertBefore(open,call(DESCRIPTOR,"requirePreparedWorld"),call("io/earthplatform/minecraft/enginebridge/RollingCubeActor","open"));
        assertBefore(open,call(DESCRIPTOR,"requireAllowedWindow"),call("io/earthplatform/minecraft/enginebridge/RollingCubeActor","open"));
        assertTrue(has(open,call(DESCRIPTOR,"saveDirectory")));
        assertTrue(has(open,n->n instanceof MethodInsnNode m&&m.owner.endsWith("/CoreCollisionBinding")&&m.name.equals("<init>")&&m.desc.endsWith("L"+DESCRIPTOR+";)V")));
        assertTrue(has(open,call(DESCRIPTOR,"naturalRelief")));
        assertBefore(method(c,"tick"),call(DESCRIPTOR,"requireAllowedTransition"),call("io/earthplatform/minecraft/enginebridge/RollingCubeActor","requestCenter"));
        assertBefore(method(c,"pumpRay"),call(DESCRIPTOR,"requireAllowedWindow"),call("io/earthplatform/minecraft/enginebridge/RollingCubeActor","requestCenter"));
        for(String factory:List.of("openArena","openIndexed","resumeEarth")){
            assertBefore(open,call(DESCRIPTOR,"requirePreparedWorld"),call("io/earthplatform/minecraft/enginebridge/RollingCubeActor",factory));
            assertBefore(open,call(DESCRIPTOR,"requireAllowedWindow"),call("io/earthplatform/minecraft/enginebridge/RollingCubeActor",factory));
        }
        assertBefore(method(c,"handleEvent"),call(DESCRIPTOR,"requireAllowedTransition"),n->n instanceof FieldInsnNode f&&f.name.equals("stage")&&f.getOpcode()==Opcodes.PUTFIELD);
        assertTrue(has(method(c,"manifestMatches"),call(DESCRIPTOR,"requireAllowedWindow")));
        assertTrue(c.methods.stream().anyMatch(m->has(m,call(DESCRIPTOR,"write"))));
        assertFalse(c.methods.stream().anyMatch(m->has(m,call("io/earthplatform/minecraft/coregate/CoreGateProfile","write"))));
        assertTrue(has(method(c,"enter"),call(DESCRIPTOR,"analytic")),"Analytic identity and existing stores must be preserved through their descriptor factory");
    }

    private static ClassNode read(String name) throws IOException {
        var node=new ClassNode(Opcodes.ASM9);
        try(var in=RollingEarthServerBootstrapTest.class.getClassLoader().getResourceAsStream(name+".class")){
            assertNotNull(in,"Missing controller bytecode: "+name);new ClassReader(in).accept(node,ClassReader.SKIP_DEBUG|ClassReader.SKIP_FRAMES);
        }return node;
    }
    private static MethodNode ownerOpen(ClassNode c){var matches=c.methods.stream().filter(m->m.name.equals("open")&&m.desc.endsWith("L"+COMMANDS+"$PendingDistance;)I")).toList();assertEquals(1,matches.size(),"Exactly one continuation-aware owner admission");return matches.getFirst();}
    private static MethodNode method(ClassNode c,String name){return c.methods.stream().filter(m->m.name.equals(name)).findFirst().orElseThrow();}
    private static List<AbstractInsnNode> instructions(MethodNode m){return Arrays.asList(m.instructions.toArray());}
    private static Predicate<AbstractInsnNode> call(String owner,String name){return n->n instanceof MethodInsnNode m&&m.owner.equals(owner)&&m.name.equals(name);}
    private static boolean has(MethodNode m,Predicate<AbstractInsnNode> p){return instructions(m).stream().anyMatch(p);}
    private static int index(MethodNode m,Predicate<AbstractInsnNode> p){return indexAfter(m,0,p);}
    private static int indexAfter(MethodNode m,int from,Predicate<AbstractInsnNode> p){var nodes=instructions(m);for(int i=from;i<nodes.size();i++)if(p.test(nodes.get(i)))return i;fail("Missing bytecode contract in "+m.name);return -1;}
    private static void assertBefore(MethodNode m,Predicate<AbstractInsnNode> first,Predicate<AbstractInsnNode> second){assertTrue(index(m,first)<index(m,second),"Wrong owner ordering in "+m.name);}
    private static Set<Object> constants(MethodNode m){var values=new HashSet<Object>();instructions(m).forEach(n->{if(n instanceof LdcInsnNode c)values.add(c.cst);});return values;}
    private static AbstractInsnNode nextOpcode(AbstractInsnNode node){do{node=node.getNext();assertNotNull(node);}while(node.getOpcode()<0);return node;}
    private static AbstractInsnNode previousOpcode(AbstractInsnNode node){do{node=node.getPrevious();assertNotNull(node);}while(node.getOpcode()<0);return node;}
    private static void assertRequiredBoolean(MethodNode method,Predicate<AbstractInsnNode> predicate,int target,boolean required){
        int condition=index(method,predicate);
        assertFalse(reachable(method,0,target,Set.of(condition)),"Required check cannot be bypassed in "+method.name);
        assertBooleanBranch(method,assertInstanceOf(JumpInsnNode.class,nextOpcode(method.instructions.get(condition))),target,required);
    }
    private static void assertBooleanBranch(MethodNode method,JumpInsnNode branch,int target,boolean required){
        int op=branch.getOpcode();assertTrue(op==Opcodes.IFEQ||op==Opcodes.IFNE,"Boolean must control the admission branch");
        int jump=method.instructions.indexOf(branch.label),fallthrough=method.instructions.indexOf(branch)+1;
        int whenTrue=op==Opcodes.IFNE?jump:fallthrough,whenFalse=op==Opcodes.IFEQ?jump:fallthrough;
        assertTrue(reachable(method,required?whenTrue:whenFalse,target,Set.of()),"Authorized branch must reach its target");
        assertFalse(reachable(method,required?whenFalse:whenTrue,target,Set.of()),"Refused branch cannot reach its target");
    }
    /** Normal CFG including all conditional alternatives; exception handlers are checked separately. */
    private static boolean reachable(MethodNode method,int start,int target,Set<Integer> blocked){
        var nodes=instructions(method);var visited=new HashSet<Integer>();var work=new ArrayDeque<Integer>();work.add(start);
        while(!work.isEmpty()){
            int i=work.removeFirst();if(i<0||i>=nodes.size()||blocked.contains(i)||!visited.add(i))continue;if(i==target)return true;
            var n=nodes.get(i);int op=n.getOpcode();
            if(op==Opcodes.ATHROW||op>=Opcodes.IRETURN&&op<=Opcodes.RETURN)continue;
            if(n instanceof JumpInsnNode j){work.add(method.instructions.indexOf(j.label));if(op!=Opcodes.GOTO)work.add(i+1);}
            else if(n instanceof TableSwitchInsnNode t){work.add(method.instructions.indexOf(t.dflt));for(var label:t.labels)work.add(method.instructions.indexOf(label));}
            else if(n instanceof LookupSwitchInsnNode l){work.add(method.instructions.indexOf(l.dflt));for(var label:l.labels)work.add(method.instructions.indexOf(label));}
            else work.add(i+1);
        }return false;
    }
}
