package io.earthplatform.geography.globe;

import org.junit.jupiter.api.Test;

/** Runs the {@link GlobeTest} harness (elevation, land cover, climate, rivers) as part of the Gradle tests. */
class GlobeSuiteTest {
    @Test
    void globeHarnessPasses() throws Exception {
        GlobeTest.main(new String[0]);
    }
}
