package io.earthplatform.minecraft.survival.runtime;

import io.earthplatform.minecraft.survival.host.SurvivalInstalledModIdentity;
import io.earthplatform.minecraft.survival.world.SurvivalRefusal;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/** Audit-owned port. This stub intentionally cannot mint an installed admission capability.
 * Replace only with reviewed exact live inherited census and DH/native lifecycle state closure.
 * Artifact/version equality alone is never sufficient. No caller-supplied callbacks or booleans.
 */
final class InstalledSessionCallbackClosure {
    private InstalledSessionCallbackClosure() {}
    static Approval observe(ServerPlayer player, ServerLevel ordinary, ServerLevel carrier,
            SurvivalInstalledModIdentity.Capture artifacts) {
        throw new SurvivalRefusal("Installed callback/lifecycle observation is not yet admitted; ordinary play is unchanged");
    }
    static final class Approval {
        private Approval() {}
        void requireCurrent() { throw new SurvivalRefusal("No reviewed installed callback approval"); }
        void requireTransfer(ServerPlayer player, ServerLevel from, ServerLevel to) { requireCurrent(); }
        void requireOrdinarySave() {
            throw new SurvivalRefusal("Installed ordinary serialization/stat/advancement/world-data backing closure is not approved");
        }
        void requireTransportPreparation() { requireCurrent(); }
    }
}
