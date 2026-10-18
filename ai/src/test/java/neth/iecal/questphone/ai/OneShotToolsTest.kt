package neth.iecal.questphone.ai

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

class OneShotToolsTest {

    @Test
    fun padTokenIds_shortSequence_placesEosOneAndPadsWithZeroUpTo64() {
        val rawTokens = intArrayOf(105, 2048, 3310)
        val padded = OneShotTools.padTokenIds(rawTokens)

        assertEquals(64, padded.size)
        assertEquals(105L, padded[0])
        assertEquals(2048L, padded[1])
        assertEquals(3310L, padded[2])
        // EOS token ID 1 placed immediately after truncated text tokens at index 3
        assertEquals(1L, padded[3])
        // Remaining slots filled with PAD token ID 0 (never 49407)
        for (i in 4 until 64) {
            assertEquals("Expected PAD=0L at index $i", 0L, padded[i])
        }
    }

    @Test
    fun padTokenIds_emptySequence_placesEosAtZeroAndPadsRestWithZero() {
        val padded = OneShotTools.padTokenIds(intArrayOf())

        assertEquals(64, padded.size)
        assertEquals(1L, padded[0])
        for (i in 1 until 64) {
            assertEquals(0L, padded[i])
        }
    }

    @Test
    fun padTokenIds_longSequence_truncatesTo63AndPlacesEosAtIndex63() {
        val rawTokens = IntArray(80) { idx -> idx + 10 }
        val padded = OneShotTools.padTokenIds(rawTokens)

        assertEquals(64, padded.size)
        for (i in 0 until 63) {
            assertEquals((i + 10).toLong(), padded[i])
        }
        // Index 63 (maxLength - 1) must be EOS = 1L
        assertEquals(1L, padded[63])
    }

    @Test
    fun formatSiglipPrompt_lowercasesAndFormatsCanonicalTemplate() {
        assertEquals(
            "this is a photo of a clean desk.",
            OneShotTools.formatSiglipPrompt("  A Clean Desk. ")
        )
        assertEquals(
            "this is a photo of green water bottle.",
            OneShotTools.formatSiglipPrompt("GREEN WATER BOTTLE")
        )
    }

    @Test
    fun cosineSimilarity_l2NormalizesVectorsBeforeDotProduct() {
        val a = floatArrayOf(3.0f, 4.0f)
        val b = floatArrayOf(6.0f, 8.0f)
        val sim = OneShotTools.cosineSimilarity(a, b)
        assertEquals(1.0f, sim, 1e-5f)

        val orthoA = floatArrayOf(5.0f, 0.0f)
        val orthoB = floatArrayOf(0.0f, 9.0f)
        assertEquals(0.0f, OneShotTools.cosineSimilarity(orthoA, orthoB), 1e-5f)
    }

    @Test
    fun sigmoid_computesExpectedProbabilities() {
        assertEquals(0.5f, OneShotTools.sigmoid(0.0f), 1e-5f)
        assertTrue(OneShotTools.sigmoid(5.0f) > 0.99f)
        assertTrue(OneShotTools.sigmoid(-5.0f) < 0.01f)
    }

    @Test
    fun tokenizeText_lowercasesTrimsCachesModelAndPadsTo64() {
        val tempModel = createSyntheticSentencePieceModelFile()
        try {
            SentencePieceProcessor.resetCache()
            val paddedUpper = OneShotTools.tokenizeText("  A Desk  ", tempModel.absolutePath)
            val paddedLower = OneShotTools.tokenizeText("a desk", tempModel.absolutePath)

            assertEquals(64, paddedUpper.size)
            assertArrayEquals(paddedLower, paddedUpper)
            // Verify non-empty tokens followed by EOS=1 and PAD=0
            val eosIndex = paddedLower.indexOf(1L)
            assertTrue("Expected EOS token (1L) in padded output", eosIndex > 0)
            for (i in (eosIndex + 1) until 64) {
                assertEquals(0L, paddedLower[i])
            }
        } finally {
            tempModel.delete()
        }
    }

    private fun createSyntheticSentencePieceModelFile(): File {
        val bos = ByteArrayOutputStream()

        fun writeVarint(v: Int) {
            var value = v
            while ((value and 0x7F.inv()) != 0) {
                bos.write((value and 0x7F) or 0x80)
                value = value ushr 7
            }
            bos.write(value and 0x7F)
        }

        fun writePiece(piece: String, score: Float, type: Int) {
            val pieceBytes = piece.toByteArray(Charsets.UTF_8)
            val sub = ByteArrayOutputStream()

            fun writeSubVarint(v: Int) {
                var value = v
                while ((value and 0x7F.inv()) != 0) {
                    sub.write((value and 0x7F) or 0x80)
                    value = value ushr 7
                }
                sub.write(value and 0x7F)
            }

            // field 1 (string piece): tag = 0x0A
            sub.write(0x0A)
            writeSubVarint(pieceBytes.size)
            sub.write(pieceBytes)

            // field 2 (float score): tag = 0x15
            sub.write(0x15)
            val floatBuf = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putFloat(score).array()
            sub.write(floatBuf)

            // field 3 (Type type): tag = 0x18
            sub.write(0x18)
            writeSubVarint(type)

            val subBytes = sub.toByteArray()
            // ModelProto field 1 (repeated SentencePiece pieces = 1): tag = 0x0A
            bos.write(0x0A)
            writeVarint(subBytes.size)
            bos.write(subBytes)
        }

        // 0: <pad> (CONTROL=3), 1: <eos> (CONTROL=3), 2: <bos> (CONTROL=3), 3: <unk> (UNKNOWN=2)
        writePiece("<pad>", 0.0f, 3)
        writePiece("<eos>", 0.0f, 3)
        writePiece("<bos>", 0.0f, 3)
        writePiece("<unk>", 0.0f, 2)
        writePiece("a", -1.0f, 1)
        writePiece("\u2581", -1.0f, 1)
        writePiece("\u2581desk", -0.5f, 1)
        writePiece("desk", -1.2f, 1)

        val file = File.createTempFile("test_tokenizer_", ".model")
        file.writeBytes(bos.toByteArray())
        return file
    }
}
