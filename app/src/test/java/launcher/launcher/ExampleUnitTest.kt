package launcher.launcher

import neth.iecal.questphone.ai.OneShotTools
import neth.iecal.questphone.app.screens.quest.setup.ai_snap.model.DownloadableModel
import neth.iecal.questphone.app.screens.quest.setup.ai_snap.model.SIGLIP2_Q4_URL
import neth.iecal.questphone.app.screens.quest.setup.ai_snap.model.SIGLIP2_UINT8_URL
import neth.iecal.questphone.app.screens.quest.setup.ai_snap.model.sanitizeDownloadableModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ExampleUnitTest {
    @Test
    fun padTokenIds_producesLength64WithEos1AndPad0() {
        val tokens = intArrayOf(42, 99, 128, 512)
        val padded = OneShotTools.padTokenIds(tokens)

        assertEquals(64, padded.size)
        assertEquals(42L, padded[0])
        assertEquals(99L, padded[1])
        assertEquals(128L, padded[2])
        assertEquals(512L, padded[3])
        assertEquals(1L, padded[4]) // EOS = 1
        for (i in 5 until 64) {
            assertEquals(0L, padded[i]) // PAD = 0
        }
    }

    @Test
    fun sanitizeDownloadableModel_replacesUnquantizedFp32WithQuantizedVariant() {
        val unquantized = DownloadableModel(
            sizeMB = 1500,
            accuracy = "High",
            id = "full",
            url = "https://huggingface.co/onnx-community/siglip2-base-patch16-224-ONNX/resolve/main/onnx/model.onnx",
            version = "1.0.0"
        )
        val sanitized = sanitizeDownloadableModel(unquantized)
        assertFalse(sanitized.url.endsWith("/model.onnx"))
        assertEquals(SIGLIP2_UINT8_URL, sanitized.url)

        val smallLegacy = DownloadableModel(
            sizeMB = 250,
            accuracy = "Good",
            id = "small",
            url = "https://example.com/old_siglip_small.onnx",
            version = "1.0.0"
        )
        assertEquals(SIGLIP2_Q4_URL, sanitizeDownloadableModel(smallLegacy).url)
    }
}