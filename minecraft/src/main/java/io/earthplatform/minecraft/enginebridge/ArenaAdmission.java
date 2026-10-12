package io.earthplatform.minecraft.enginebridge;

import java.util.Objects;

/** Pure common-side pre-save full union and native 52-byte vertex census. No IO or mutable actor access. */
@FunctionalInterface public interface ArenaAdmission {
    Receipt assess(ArenaCapture capture);
    record Receipt(boolean admitted, String reason, String definitionIdentity, String contentIdentity,
                   String sourceIdentity, String layoutIdentity) {
        public Receipt { Objects.requireNonNull(reason); Objects.requireNonNull(definitionIdentity); Objects.requireNonNull(contentIdentity); Objects.requireNonNull(sourceIdentity); Objects.requireNonNull(layoutIdentity); }
        public void verify(ArenaCapture capture,String source,String layout) {
            if(!definitionIdentity.equals(capture.definition().identity())||!contentIdentity.equals(capture.contentIdentity())||!sourceIdentity.equals(source)||!layoutIdentity.equals(layout))throw new IllegalArgumentException("Admission receipt does not bind this exact arena/source/layout capture");
        }
    }
}
