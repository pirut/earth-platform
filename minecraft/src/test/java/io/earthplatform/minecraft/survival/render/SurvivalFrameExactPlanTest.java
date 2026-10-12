package io.earthplatform.minecraft.survival.render;

import io.earthplatform.minecraft.visual.NativeDistanceCpuBudget;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class SurvivalFrameExactPlanTest {
    @TempDir static Path source;
    @TempDir Path root;
    @BeforeAll static void setup()throws Exception{SurvivalDhTestSupport.setup(source);}
    @Test void equalDiagnosticPlanIdentitiesDoNotJoinIndependentOwners()throws Exception{
        try(var fixture=new SurvivalDhTestSupport(root.resolve("first"));
                var independent=new SurvivalDhTestSupport(root.resolve("independent"));
                var first=fixture.plan(2);var equal=independent.plan(2);
                var alias=first.retain();var coverage=SurvivalDhCoverage.capture(first,SurvivalDhTestSupport.masks(first),fixture.budget)){
            assertEquals(first.identity(),equal.identity());assertDoesNotThrow(()->first.requireSame(alias));
            assertDoesNotThrow(()->coverage.requireExactPlan(alias));
            assertThrows(RuntimeException.class,()->first.requireSame(equal));
            assertThrows(RuntimeException.class,()->coverage.requireExactPlan(equal));
            assertDoesNotThrow(()->first.requireBudget(fixture.budget));
            assertThrows(RuntimeException.class,()->first.requireBudget(new NativeDistanceCpuBudget(1<<20)));
            assertEquals(2,first.distance().selectedChunks());assertEquals(2,first.distance().effectiveChunks());
        }
    }
}
