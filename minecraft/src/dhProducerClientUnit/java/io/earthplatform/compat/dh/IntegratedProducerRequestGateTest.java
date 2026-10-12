package io.earthplatform.compat.dh;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class IntegratedProducerRequestGateTest {
    @Test void wrongServerCannotConsumeAndDuplicateTicksCannotRestart() {
        var gate=new IntegratedProducerRequestGate<Object,Object>();var server=new Object();var request=gate.publish(server,server,123);
        assertNull(gate.consume(new Object()));assertEquals(IntegratedProducerRequestGate.State.PENDING,gate.state());
        assertSame(request,gate.consume(server));assertNull(gate.consume(server));assertDoesNotThrow(()->gate.requireCurrent(request,server));
        assertThrows(IllegalStateException.class,()->gate.publish(server,server,124));
    }
    @Test void pendingLogoutAndRunningReplacementRevokeExactRequest() {
        for(boolean running:new boolean[]{false,true}) {
            var gate=new IntegratedProducerRequestGate<Object,Object>();var server=new Object();var request=gate.publish(server,server,123);
            if(running)gate.consume(server);
            assertTrue(gate.revoke());assertFalse(gate.revoke());assertNull(gate.consume(server));
            assertThrows(IllegalStateException.class,()->gate.requireCurrent(request,server));
            assertThrows(IllegalStateException.class,()->gate.complete(request,()->fail("No retired report publication")));
            assertEquals(IntegratedProducerRequestGate.State.FAIL,gate.state());
        }
    }
    @Test void equalValuedForeignTokenCannotProveCurrentIdentity() {
        var server=new Object();var a=new IntegratedProducerRequestGate<Object,Object>();var b=new IntegratedProducerRequestGate<Object,Object>();
        var own=a.publish(server,server,123);var foreign=b.publish(server,server,123);a.consume(server);b.consume(server);
        assertEquals(own.generation(),foreign.generation());assertThrows(IllegalStateException.class,()->a.requireCurrent(foreign,server));
        assertDoesNotThrow(()->a.requireCurrent(own,server));
    }
    @Test void reportFailureIsPermanentAndNeverRetried() {
        var gate=new IntegratedProducerRequestGate<Object,Object>();var server=new Object();var token=gate.publish(server,server,123);gate.consume(server);var calls=new AtomicInteger();
        var original=new IllegalStateException("CREATE_NEW write failed");
        assertSame(original,assertThrows(IllegalStateException.class,()->gate.complete(token,()->{calls.incrementAndGet();throw original;})));
        assertEquals(IntegratedProducerRequestGate.State.FAIL,gate.state());
        assertThrows(IllegalStateException.class,()->gate.complete(token,calls::incrementAndGet));assertEquals(1,calls.get());
    }
    @Test void reportPublicationRechecksRevocationAndOnlyCompletesOnce() {
        var gate=new IntegratedProducerRequestGate<Object,Object>();var server=new Object();var token=gate.publish(server,server,123);gate.consume(server);
        assertThrows(IllegalStateException.class,()->gate.complete(token,gate::revoke));assertEquals(IntegratedProducerRequestGate.State.FAIL,gate.state());
        var okay=new IntegratedProducerRequestGate<Object,Object>();var valid=okay.publish(server,server,123);okay.consume(server);var writes=new AtomicInteger();
        okay.complete(valid,writes::incrementAndGet);assertEquals(IntegratedProducerRequestGate.State.PASS,okay.state());assertFalse(okay.revoke());
        assertThrows(IllegalStateException.class,()->okay.complete(valid,writes::incrementAndGet));assertEquals(1,writes.get());
    }
    @Test void absentOrForeignInstallationWitnessCannotAuthorizeRequest() {
        var gate=new IntegratedProducerRequestGate<Object,Object>();var server=new Object();var witness=new Object();
        assertThrows(NullPointerException.class,()->gate.publish(server,null,123));assertEquals(IntegratedProducerRequestGate.State.NEW,gate.state());
        var token=gate.publish(server,witness,123);assertSame(witness,token.witness());gate.consume(server);
        assertThrows(IllegalStateException.class,()->gate.requireCurrent(token,new Object()));
        assertDoesNotThrow(()->gate.requireCurrent(token,witness));
    }
    @Test void serverBodyAndCurrentPredicateDoNotReadClientFieldsAndReportIsCreateNew() throws Exception {
        var root=Path.of(System.getProperty("producerSourceRoot"));
        String host=Files.readString(root.resolve("io/earthplatform/compat/dh/InstalledSurvivalDhProducerClient.java"));
        String body=Files.readString(root.resolve("io/earthplatform/compat/dh/InstalledSurvivalDhProducerClientBody.java"));
        String server=host.substring(host.indexOf("@SubscribeEvent public static void serverTick"),host.indexOf("/** Called under LOCK."));
        String predicate=host.substring(host.indexOf("private static void requireCurrent"),host.indexOf("@SubscribeEvent public static void serverTick"));
        for(String scope:new String[]{server,predicate,body})for(String forbidden:new String[]{"Minecraft.getInstance", "clientLevel", "clientConnection", "getSingleplayerServer", "getConnection()", "resolveInstalled()"})assertFalse(scope.contains(forbidden),forbidden);
        assertTrue(host.contains("StandardOpenOption.CREATE_NEW"));assertFalse(host.contains("TRUNCATE_EXISTING"));
        assertTrue(host.contains("server.getClass()!=IntegratedServer.class"));
        assertTrue(body.contains("grass.get().name()"));assertTrue(body.contains("debug.get().name()"));
        assertTrue(body.indexOf("Pool.SOURCE_ASSETS,64L<<20")<body.indexOf("SurvivalDhTestSupport.setup(source)"));
        assertTrue(body.contains("retainedSourceAssets="));assertFalse(body.contains("releaseAfterPhysicalDrain"));
        assertTrue(body.contains("new NeoForgeDhCatalogue(installed)"));
        assertFalse(body.contains("new NeoForgeDhCatalogue()"));
        assertTrue(host.contains("installed=InstalledIrisRuntime.resolveInstalled()"));
        assertFalse(body.contains("setWithoutFiringEvents"));assertFalse(body.contains("Bootstrap.bootStrap"));
    }
}
