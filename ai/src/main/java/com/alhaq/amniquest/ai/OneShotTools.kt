package com.alhaq.amniquest.ai

import android.graphics.Bitmap
import java.nio.FloatBuffer
import neth.iecal.questphone.ai.OneShotTools as CoreOneShotTools
import neth.iecal.questphone.ai.SentencePieceProcessor as CoreSentencePieceProcessor

/**
 * SentencePiece JNI wrapper for `com.alhaq.amniquest.ai.SentencePieceProcessor`.
 * Delegates to the shared thread-safe implementation and native JNI library.
 */
class SentencePieceProcessor : CoreSentencePieceProcessor() {
    companion object {
        @Synchronized
        fun ensureLoaded(modelPath: String): Boolean {
            return CoreSentencePieceProcessor.ensureLoaded(modelPath)
        }
    }
}

/**
 * SigLIP 2 ONNX preprocessing, tokenization, padding, and similarity scoring utilities
 * under `com.alhaq.amniquest.ai.OneShotTools`.
 */
object OneShotTools {
    const val PAD_TOKEN_ID: Int = CoreOneShotTools.PAD_TOKEN_ID
    const val EOS_TOKEN_ID: Int = CoreOneShotTools.EOS_TOKEN_ID
    const val BOS_TOKEN_ID: Int = CoreOneShotTools.BOS_TOKEN_ID
    const val UNK_TOKEN_ID: Int = CoreOneShotTools.UNK_TOKEN_ID
    const val MAX_SEQ_LEN: Int = CoreOneShotTools.MAX_SEQ_LEN
    const val IMAGE_SIZE: Int = CoreOneShotTools.IMAGE_SIZE

    fun formatSiglipPrompt(feature: String): String =
        CoreOneShotTools.formatSiglipPrompt(feature)

    fun padTokenIds(
        tokenIds: IntArray,
        maxLength: Int = MAX_SEQ_LEN,
        padTokenId: Int = PAD_TOKEN_ID,
        eosTokenId: Int = EOS_TOKEN_ID
    ): LongArray = CoreOneShotTools.padTokenIds(
        tokenIds = tokenIds,
        maxLength = maxLength,
        padTokenId = padTokenId,
        eosTokenId = eosTokenId
    )

    fun tokenizeText(
        text: String,
        tokenizerModelPath: String,
        maxLength: Int = MAX_SEQ_LEN
    ): LongArray = CoreOneShotTools.tokenizeText(
        text = text,
        tokenizerModelPath = tokenizerModelPath,
        maxLength = maxLength
    )

    fun preprocessBitmapToFloatBuffer(
        bitmap: Bitmap,
        width: Int = IMAGE_SIZE,
        height: Int = IMAGE_SIZE
    ): FloatBuffer = CoreOneShotTools.preprocessBitmapToFloatBuffer(
        bitmap = bitmap,
        width = width,
        height = height
    )

    fun l2Normalize(embedding: FloatArray): FloatArray =
        CoreOneShotTools.l2Normalize(embedding)

    fun cosineSimilarity(a: FloatArray, b: FloatArray): Float =
        CoreOneShotTools.cosineSimilarity(a, b)

    fun sigmoid(logit: Float): Float =
        CoreOneShotTools.sigmoid(logit)
}
