package io.earthplatform.minecraft.cubic.client;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonParser;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class FarTerrainNoticeTest {
    @Test void waitsForPlayerAndDeliversOnlyTheFirstFailureAcrossWorldChanges() {
        FarTerrainNotice notice = new FarTerrainNotice();
        assertNull(notice.take(true));
        notice.request(FarTerrainNotice.Reason.UNSUPPORTED_GL);
        assertNull(notice.take(false), "failure before joining must stay queued");
        notice.request(FarTerrainNotice.Reason.SHADER_BUILD);
        assertEquals(FarTerrainNotice.Reason.UNSUPPORTED_GL, notice.take(true));
        assertNull(notice.take(true));
        assertNull(notice.take(false));
        notice.request(FarTerrainNotice.Reason.SHADER_BUILD);
        assertNull(notice.take(true), "rejoining or rebuilding must not repeat the session notice");
    }

    @Test void shaderFailureCanBeTheFirstNotice() {
        FarTerrainNotice notice = new FarTerrainNotice();
        notice.request(FarTerrainNotice.Reason.SHADER_BUILD);
        assertEquals(FarTerrainNotice.Reason.SHADER_BUILD, notice.take(true));
        assertNull(notice.take(true));
    }

    @Test void everyReasonHasAnEnglishTranslationWithTroubleshooting() throws Exception {
        try (var stream = getClass().getResourceAsStream("/assets/earthplatform/lang/en_us.json")) {
            assertNotNull(stream);
            var translations = JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8)).getAsJsonObject();
            for (var reason : FarTerrainNotice.Reason.values()) {
                assertTrue(translations.has(reason.translationKey), reason.translationKey);
                String text = translations.get(reason.translationKey).getAsString();
                assertTrue(text.contains("GPU/driver"));
                assertTrue(text.contains("/earth bug"));
                assertTrue(text.contains("logs/latest.log"));
            }
            assertTrue(translations.get(FarTerrainNotice.Reason.UNSUPPORTED_GL.translationKey).getAsString().contains("OpenGL"));
            assertTrue(translations.get(FarTerrainNotice.Reason.SHADER_BUILD.translationKey).getAsString().contains("shader pack"));
        }
    }
}
