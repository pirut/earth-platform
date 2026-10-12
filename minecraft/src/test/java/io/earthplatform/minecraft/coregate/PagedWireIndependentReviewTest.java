package io.earthplatform.minecraft.coregate;

import io.earthplatform.core.CubePos;
import io.earthplatform.core.WorldIdentity;
import io.earthplatform.minecraft.enginebridge.NativeReplacementCapture;
import io.earthplatform.minecraft.enginebridge.ReplacementSetCertificate;
import io.earthplatform.minecraft.enginebridge.RollingCubeActor;
import io.netty.buffer.Unpooled;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Golden accepted011 bodies guard opt-in-only012 framing changes. */
class PagedWireIndependentReviewTest {
    private static final CubePos A = new CubePos(0, 8, 0);
    private static final WorldIdentity WORLD = new WorldIdentity(1, "fixture", "v1", "source", "palette");

    @Test void legacyAdjacentSetWireBodyRemainsByteExact() throws Exception {
        verifyLegacy(1, "6df85f9b231835b55152c9a62f5ebcbc920a8ecedf161b359f77dfed2529552b");
    }

    @Test void legacyDisjointSetWireBodyRemainsByteExact() throws Exception {
        verifyLegacy(4, "f12066f0a7a4223c7e4e8dfe8575717ef9e9c48a7c2b1a4955fddf4a417a4bbc");
    }

    private static void verifyLegacy(int offset, String expectedHash) throws Exception {
        var regions = new ArrayList<ReplacementSetCertificate.Region>();
        var captures = new ArrayList<NativeReplacementCapture>();
        long lease = 1;
        for (CubePos center : List.of(A, A.offset(offset, 0, 0))) {
            var capture = new NativeReplacementCapture(center, new byte[8000]);
            captures.add(capture);
            var dependencies = new ArrayList<RollingCubeActor.CubeVersion>();
            for (int x = center.x() - 1; x <= center.x() + 1; x++)
                for (int y = center.y() - 1; y <= center.y() + 1; y++)
                    for (int z = center.z() - 1; z <= center.z() + 1; z++)
                        dependencies.add(new RollingCubeActor.CubeVersion(new CubePos(x, y, z), lease++, 0));
            regions.add(new ReplacementSetCertificate.Region(center, dependencies, capture.contentIdentity()));
        }
        var certificate = new ReplacementSetCertificate(WORLD, 5, 1, "98e296d8-68d8-44cd-8fd4-d314bca95c72",
                "a".repeat(64), "provider", "b".repeat(64), "material", "detail", 101, regions);
        var bytes = new FriendlyByteBuf(Unpooled.buffer());
        try {
            RollingGateWire.writeReplacementSet(bytes, A, certificate, captures);
            assertEquals(17894, bytes.readableBytes());
            byte[] encoded = new byte[bytes.readableBytes()]; bytes.getBytes(bytes.readerIndex(), encoded);
            assertEquals(expectedHash, HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(encoded)));
            var decoded = RollingGateWire.readReplacementSet(bytes, WORLD, 5);
            assertEquals(certificate, decoded.certificate()); assertEquals(A, decoded.focus());
            assertFalse(bytes.isReadable()); decoded.certificate().verifyCaptures(decoded.captures());
        } finally { bytes.release(); }
    }
}
