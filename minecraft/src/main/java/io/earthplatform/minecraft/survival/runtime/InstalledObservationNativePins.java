package io.earthplatform.minecraft.survival.runtime;
import java.util.Map;
/** Exact official installed class resources for READ-ONLY observation only. Development
 * hashes are deliberately not alternatives. No gameplay/callback/save admission uses this.
 * The frozen launcher separately hashes both installer-produced native JARs; the probe
 * also records runtime CodeSource/resource URLs and rechecks each actual class resource.
 * Field sets and selected read-only constructor/getter opcodes have an independent ABI
 * receipt. This is not whole-program equivalence or transformation authority.
 */
final class InstalledObservationNativePins {
    static final String PROFILE="official-installed-observation-21.1.256-v1";
    static final String MANIFEST_SHA256="5850224d17275533e10685c599e8258800bc113eabd8d34e5d166603e0017a51";
    static final String PATCHED_CLIENT_SHA256="aff67d0c3bbd015ecd881bb37416b740ada2e1e854433501ec5bb9381dbdd1c2";
    static final String NAMED_CLIENT_SHA256="a649bd3ce77f122d0f83ae1b37b4b303e662470f928b9bee5fbc76580805ffd4";
    private static final Map<String,String> PINS=Map.ofEntries(
        Map.entry("net.minecraft.client.server.IntegratedServer","26a16a6055bb148e1ffbe0d73bf74c38b710046a96f50600f3ae6b38a618bf5a"),
        Map.entry("net.minecraft.server.MinecraftServer","3f1b7a260a249ae836d9d71bf0a0fe7575b62aff48f2b8fac10dd06aaa9efd2f"),
        Map.entry("net.minecraft.server.network.ServerGamePacketListenerImpl","f8193992da15902eadb8a77681b1d9ee4ab3bed175ab7029d46061fa6486ba15"),
        Map.entry("net.minecraft.server.network.PlayerChunkSender","a45158b0d434f071f81a877d4d94a25bb4b1f47d4dca6e59505d0a764677c628"),
        Map.entry("net.minecraft.server.level.ServerPlayer","ec7c90a8b6a771cfd53b5038f377a72314b9efbf2301ed1ce540723b7d19c6d2"),
        Map.entry("net.minecraft.server.players.PlayerList","c7ed33824878b591e2c8440d392b1a2fbecb152e51c0873ae02bf148e43de430"),
        Map.entry("net.minecraft.client.server.IntegratedPlayerList","0aaf4d99d16b08f670b1a541207224a57ba65215b944c64818bf621332473641"));
    private InstalledObservationNativePins(){}
    static String expected(String name){String value=PINS.get(name);if(value==null)throw new IllegalArgumentException("Unlisted installed observation class");return value;}
}
