package io.earthplatform.minecraft.survival.render;

import io.earthplatform.minecraft.survival.world.SurvivalRefusal;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** Actual owner-minted content/plan/lease tests, without native model, GL, client or frame execution. */
class SurvivalSceneAuthorizationMailboxTest {
    @TempDir Path directory;
    private static void neutralAction(SurvivalAuthenticatedTestFixture f) {
        UUID player=new UUID(391,17);
        try(var action=f.world.begin(f.world.actionIdentity(player,f.world.nextActionSequence(player)),Map.of())) { action.commit(); }
    }
    @Test void oneSlotSafelyPublishesAnExactOwnerLeaseAndDrainsAllMailboxCharges() throws Exception {
        try(var f=new SurvivalAuthenticatedTestFixture(directory,false)) {
            long baseline=f.budget.evidence().bytes();
            var mailbox=new SurvivalSceneAuthorizationMailbox(f.plan,f.budget);
            assertTrue(mailbox.requested());assertNull(mailbox.take());
            mailbox.authorize(f.owner);assertFalse(mailbox.requested());
            assertThrows(IllegalStateException.class,()->mailbox.authorize(f.owner));
            var lease=mailbox.take();assertNotNull(lease);assertNull(mailbox.take());
            try(var frame=lease.tryLatchCurrent(f.plan)) { assertNotNull(frame);assertEquals(f.plan.pageCount(),frame.pageCount()); }
            lease.close();mailbox.close();assertTrue(mailbox.closeComplete());
            assertEquals(baseline,f.budget.evidence().bytes());
        }
    }
    @Test void staleBeforeCasIsNullWithoutLeakedChargeButHistoricalAcceptanceSurvivesLaterRevocation() throws Exception {
        try(var f=new SurvivalAuthenticatedTestFixture(directory,false)) {
            var historical=f.lease.tryLatchCurrent(f.plan);assertNotNull(historical);
            neutralAction(f);
            assertEquals(f.plan.pageCount(),historical.pageCount());
            long retained=f.budget.evidence().bytes();
            assertNull(f.lease.tryLatchCurrent(f.plan));assertEquals(retained,f.budget.evidence().bytes());
            assertThrows(SurvivalRefusal.class,()->f.lease.latch(f.plan));
            try(var fresh=f.owner.authorize(f.plan);var accepted=fresh.tryLatchCurrent(f.plan)) {
                assertNotNull(accepted);assertSame(f.plan.page(0),accepted.page(0));
            }
            historical.close();
        }
    }
    @Test void neutralEffectsReauthorizeSameGeometryThroughOneFreshSlot() throws Exception {
        try(var f=new SurvivalAuthenticatedTestFixture(directory,false);
            var mailbox=new SurvivalSceneAuthorizationMailbox(f.plan,f.budget)) {
            mailbox.authorize(f.owner);var first=mailbox.take();
            neutralAction(f);assertNull(first.tryLatchCurrent(f.plan));
            mailbox.request();mailbox.authorize(f.owner);var replacement=mailbox.take();
            assertNotSame(first,replacement);
            try(var accepted=replacement.tryLatchCurrent(f.plan)){assertNotNull(accepted);accepted.requireExactPlan(f.plan);}
            first.close();replacement.close();
        }
    }
    @Test void equalValuesForeignPlanRefusesBeforePendingAndDoesNotChangeTheSlot() throws Exception {
        try(var f=new SurvivalAuthenticatedTestFixture(directory,false);
            var mailbox=new SurvivalSceneAuthorizationMailbox(f.plan,f.budget);
            var equal=f.content.plan(f.override,new SurvivalDistancePagePlan.DistanceChoice(2,2,0),
                    new SurvivalDistancePagePlan.Limits(100000,1000,1000,10000,200000,200000,64,2<<20))) {
            assertEquals(f.plan.identity(),equal.identity());neutralAction(f);
            assertThrows(SurvivalRefusal.class,()->f.lease.tryLatchCurrent(equal));
            assertThrows(SurvivalRefusal.class,()->mailbox.requirePlan(equal));
            assertTrue(mailbox.requested());
        }
    }
    @Test void closedAndRetiredOwnersAreFatalRatherThanPendingContent() throws Exception {
        try(var f=new SurvivalAuthenticatedTestFixture(directory,false)) {
            var closed=f.owner.authorize(f.plan);closed.close();
            assertThrows(SurvivalRefusal.class,()->closed.tryLatchCurrent(f.plan));
            neutralAction(f);f.owner.requestInvalidation();
            assertThrows(SurvivalRefusal.class,()->f.lease.tryLatchCurrent(f.plan));
        }
    }
    @Test void completeMembershipChangePreventsExactPlanReauthorization() throws Exception {
        try(var f=new SurvivalAuthenticatedTestFixture(directory,false);
            var mailbox=new SurvivalSceneAuthorizationMailbox(f.plan,f.budget)) {
            f.world.admitGenerated(f.override,io.earthplatform.core.ImmutableCube.uniform(
                    f.palette.id(net.minecraft.world.level.block.Blocks.AIR.defaultBlockState())));
            assertThrows(SurvivalRefusal.class,()->mailbox.authorize(f.owner));
            assertNotNull(mailbox.refusal());assertNull(mailbox.take());assertFalse(mailbox.requested());
        }
    }
    @Test void equalSourceIdentityDoesNotReplaceTheChargedSourceOwner() throws Exception {
        try(var f=new SurvivalAuthenticatedTestFixture(directory,false)) {
            f.content.requireSource(f.source);
            var impostor=new io.earthplatform.minecraft.survival.source.MarinVisualCubeSource(
                    f.source.definition(),f.source.palette());
            assertEquals(f.source.identity(),impostor.identity());
            assertThrows(SurvivalRefusal.class,()->f.content.requireSource(impostor));
        }
    }
    @Test void publicationGenerationExhaustionIsFatalRatherThanPending() throws Exception {
        try(var f=new SurvivalAuthenticatedTestFixture(directory,false)) {
            var fence=f.world.renderPublicationFence();
            var field=fence.getClass().getDeclaredField("generation");field.setAccessible(true);
            var generation=(java.util.concurrent.atomic.AtomicLong)field.get(fence);
            generation.set(Long.MAX_VALUE);
            var stamp=fence.captureStamp();assertTrue(stamp.tryLatchBeforeRevocation());
            fence.revoke();
            assertThrows(SurvivalRefusal.class,stamp::tryLatchBeforeRevocation);
            assertThrows(SurvivalRefusal.class,()->f.lease.tryLatchCurrent(f.plan));
        }
    }
    @Test void sourceOwnerCannotBeInvokedFromTheRenderOrForeignThread() throws Exception {
        try(var f=new SurvivalAuthenticatedTestFixture(directory,false);
            var mailbox=new SurvivalSceneAuthorizationMailbox(f.plan,f.budget)) {
            var error=new AtomicReference<Throwable>();
            Thread thread=new Thread(()->{try{mailbox.authorize(f.owner);}catch(Throwable failure){error.set(failure);}});
            thread.start();thread.join();assertInstanceOf(SurvivalRefusal.class,error.get());
            assertNotNull(mailbox.refusal());assertNull(mailbox.take());
        }
    }
}
