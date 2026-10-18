package neth.iecal.questphone.ai

import android.graphics.Bitmap
import android.os.Build
import android.util.Log
import androidx.core.graphics.get
import androidx.core.graphics.scale
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.exp
import kotlin.math.sqrt

/**
 * SentencePiece tokenizer wrapper supporting both native JNI (`libsentencepiece_jni.so`)
 * and a pure-Kotlin `ModelProto` binary protobuf reader + Unigram Viterbi fallback
 * when native libraries are unavailable (e.g., local JVM tests or NDK-less containers).
 */
open class SentencePieceProcessor {

    external fun load(modelPath: String): Boolean
    external fun encodeAsIds(text: String): IntArray
    external fun unload()

    @Synchronized
    fun ensureLoaded(modelPath: String): Boolean {
        return Companion.ensureLoaded(modelPath, this)
    }

    fun tokenize(text: String): IntArray {
        val normalized = text.lowercase().trim()
        if (normalized.isEmpty()) return IntArray(0)
        return Companion.encodeNormalized(normalized, this)
    }

    companion object {
        private const val TAG = "SentencePieceProcessor"

        val instance: SentencePieceProcessor by lazy { SentencePieceProcessor() }

        private var nativeAvailable = false
        @Volatile
        private var loadedModelPath: String? = null
        @Volatile
        private var isModelLoaded = false

        private var kotlinTokenizer: KotlinSentencePieceModel? = null

        private fun safeLogW(msg: String, tr: Throwable? = null) {
            try {
                if (tr != null) Log.w(TAG, msg, tr) else Log.w(TAG, msg)
            } catch (_: Throwable) {
            }
        }

        private fun safeLogE(msg: String, tr: Throwable? = null) {
            try {
                if (tr != null) Log.e(TAG, msg, tr) else Log.e(TAG, msg)
            } catch (_: Throwable) {
            }
        }

        init {
            nativeAvailable = try {
                System.loadLibrary("sentencepiece_jni")
                true
            } catch (e: UnsatisfiedLinkError) {
                safeLogW("libsentencepiece_jni.so not loaded, using Kotlin ModelProto fallback: ${e.message}")
                false
            } catch (e: Throwable) {
                safeLogW("Native SentencePiece initialization fallback: ${e.message}")
                false
            }
        }

        @Synchronized
        fun ensureLoaded(modelPath: String, processor: SentencePieceProcessor = instance): Boolean {
            val file = File(modelPath)
            if (!file.exists() || !file.isFile || file.length() <= 0L) {
                safeLogE("Tokenizer model file missing or empty at: $modelPath")
                return false
            }

            val canonical = try {
                file.canonicalPath
            } catch (_: Exception) {
                file.absolutePath
            }

            if (isModelLoaded && loadedModelPath == canonical) {
                return true
            }

            if (nativeAvailable) {
                try {
                    if (processor.load(canonical)) {
                        loadedModelPath = canonical
                        isModelLoaded = true
                        return true
                    }
                } catch (e: UnsatisfiedLinkError) {
                    safeLogW("JNI symbol mismatch or unavailable, falling back to Kotlin ModelProto reader", e)
                    nativeAvailable = false
                } catch (e: Throwable) {
                    safeLogE("Native SentencePiece load failed, trying Kotlin ModelProto reader", e)
                }
            }

            return try {
                val model = KotlinSentencePieceModel.fromFile(file)
                if (model != null) {
                    kotlinTokenizer = model
                    loadedModelPath = canonical
                    isModelLoaded = true
                    true
                } else {
                    false
                }
            } catch (e: Exception) {
                safeLogE("Failed to load SentencePiece model at $canonical", e)
                false
            }
        }

        @Synchronized
        fun encodeNormalized(normalizedText: String, processor: SentencePieceProcessor = instance): IntArray {
            if (!isModelLoaded) {
                safeLogE("encodeNormalized called before ensureLoaded()")
                return IntArray(0)
            }
            if (nativeAvailable) {
                try {
                    return processor.encodeAsIds(normalizedText)
                } catch (e: Throwable) {
                    safeLogW("Native encodeAsIds failed, falling back to Kotlin ModelProto", e)
                }
            }
            return kotlinTokenizer?.encode(normalizedText) ?: IntArray(0)
        }

        @Synchronized
        fun resetCache() {
            loadedModelPath = null
            isModelLoaded = false
            kotlinTokenizer = null
            if (nativeAvailable) {
                try {
                    instance.unload()
                } catch (_: Throwable) {
                }
            }
        }
    }
}

/**
 * Pure-Kotlin reader and Unigram Viterbi tokenizer for SentencePiece `.model` (ModelProto) files.
 * Parses the exact vocabulary pieces, scores, types, and normalizer specs from `tokenizer.model`.
 */
internal class KotlinSentencePieceModel private constructor(
    private val trieNodes: ArrayList<TrieNode>,
    private val byteTokens: IntArray,
    private val unkId: Int,
    private val addDummyPrefix: Boolean,
    private val removeExtraWhitespaces: Boolean,
    private val escapeWhitespaces: Boolean
) {
    internal class TrieNode {
        val children = HashMap<Int, Int>(4)
        var tokenId: Int = -1
        var score: Float = -1e10f
    }

    fun encode(text: String): IntArray {
        if (text.isEmpty()) return IntArray(0)

        val spSpace = "\u2581"
        val sb = StringBuilder(text.length + 4)
        if (addDummyPrefix) {
            sb.append(spSpace)
        }
        var prevSpace = false
        for (i in text.indices) {
            val c = text[i]
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                if (removeExtraWhitespaces && prevSpace) continue
                prevSpace = true
                if (escapeWhitespaces) sb.append(spSpace) else sb.append(' ')
            } else {
                prevSpace = false
                sb.append(c)
            }
        }

        val normBytes = sb.toString().toByteArray(Charsets.UTF_8)
        val n = normBytes.size
        if (n == 0) return IntArray(0)

        val bestScore = DoubleArray(n + 1) { -1e18 }
        val bestPrev = IntArray(n + 1) { -1 }
        val bestToken = IntArray(n + 1) { unkId }
        bestScore[0] = 0.0

        for (i in 0 until n) {
            if (bestScore[i] <= -1e17) continue
            var nodeIdx = 0
            var matchedAny = false
            for (j in i until n) {
                val b = normBytes[j].toInt() and 0xFF
                val nextIdx = trieNodes[nodeIdx].children[b] ?: break
                nodeIdx = nextIdx
                val node = trieNodes[nodeIdx]
                if (node.tokenId >= 0) {
                    matchedAny = true
                    val candidateScore = bestScore[i] + node.score.toDouble()
                    if (candidateScore > bestScore[j + 1]) {
                        bestScore[j + 1] = candidateScore
                        bestPrev[j + 1] = i
                        bestToken[j + 1] = node.tokenId
                    }
                }
            }
            if (!matchedAny || bestPrev[i + 1] == -1) {
                val b = normBytes[i].toInt() and 0xFF
                val fallbackId = if (byteTokens[b] >= 0) byteTokens[b] else unkId
                val candidateScore = bestScore[i] - 20.0
                if (candidateScore > bestScore[i + 1]) {
                    bestScore[i + 1] = candidateScore
                    bestPrev[i + 1] = i
                    bestToken[i + 1] = fallbackId
                }
            }
        }

        val tokensReversed = ArrayList<Int>()
        var cur = n
        while (cur > 0) {
            tokensReversed.add(bestToken[cur])
            val prev = bestPrev[cur]
            if (prev < 0 || prev >= cur) break
            cur = prev
        }
        tokensReversed.reverse()
        return tokensReversed.toIntArray()
    }

    companion object {
        fun fromFile(file: File): KotlinSentencePieceModel? {
            val bytes = file.readBytes()
            if (bytes.isEmpty()) return null

            val trie = ArrayList<TrieNode>()
            trie.add(TrieNode())
            val byteTokens = IntArray(256) { -1 }
            var unkId = 3
            var addDummyPrefix = false
            var removeExtraWhitespaces = false
            var escapeWhitespaces = true

            var pos = 0
            val total = bytes.size
            var pieceIndex = 0

            while (pos < total) {
                val tagPair = readVarint(bytes, pos, total)
                val tag = tagPair.first
                pos = tagPair.second
                val fieldNum = (tag ushr 3).toInt()
                val wireType = (tag and 0x07L).toInt()

                if (fieldNum == 1 && wireType == 2) {
                    val lenPair = readVarint(bytes, pos, total)
                    val len = lenPair.first.toInt()
                    pos = lenPair.second
                    val end = (pos + len).coerceAtMost(total)
                    parsePiece(bytes, pos, end, pieceIndex, trie, byteTokens)?.let { detectedUnk ->
                        unkId = detectedUnk
                    }
                    pieceIndex++
                    pos = end
                } else if (fieldNum == 3 && wireType == 2) {
                    val lenPair = readVarint(bytes, pos, total)
                    val len = lenPair.first.toInt()
                    pos = lenPair.second
                    val end = (pos + len).coerceAtMost(total)
                    var nPos = pos
                    while (nPos < end) {
                        val nTagPair = readVarint(bytes, nPos, end)
                        val nTag = nTagPair.first
                        nPos = nTagPair.second
                        val nField = (nTag ushr 3).toInt()
                        val nWire = (nTag and 0x07L).toInt()
                        if (nField == 3 && nWire == 0) {
                            val v = readVarint(bytes, nPos, end)
                            addDummyPrefix = v.first != 0L
                            nPos = v.second
                        } else if (nField == 4 && nWire == 0) {
                            val v = readVarint(bytes, nPos, end)
                            removeExtraWhitespaces = v.first != 0L
                            nPos = v.second
                        } else if (nField == 5 && nWire == 0) {
                            val v = readVarint(bytes, nPos, end)
                            escapeWhitespaces = v.first != 0L
                            nPos = v.second
                        } else {
                            nPos = skipField(bytes, nPos, end, nWire)
                        }
                    }
                    pos = end
                } else {
                    pos = skipField(bytes, pos, total, wireType)
                }
            }

            if (pieceIndex == 0) return null
            return KotlinSentencePieceModel(
                trieNodes = trie,
                byteTokens = byteTokens,
                unkId = unkId,
                addDummyPrefix = addDummyPrefix,
                removeExtraWhitespaces = removeExtraWhitespaces,
                escapeWhitespaces = escapeWhitespaces
            )
        }

        private fun parsePiece(
            bytes: ByteArray,
            start: Int,
            end: Int,
            tokenId: Int,
            trie: ArrayList<TrieNode>,
            byteTokens: IntArray
        ): Int? {
            var pos = start
            var pieceBytes: ByteArray? = null
            var score = 0f
            var type = 1 // NORMAL

            while (pos < end) {
                val tagPair = readVarint(bytes, pos, end)
                val tag = tagPair.first
                pos = tagPair.second
                val fieldNum = (tag ushr 3).toInt()
                val wireType = (tag and 0x07L).toInt()

                if (fieldNum == 1 && wireType == 2) {
                    val lenPair = readVarint(bytes, pos, end)
                    val len = lenPair.first.toInt()
                    pos = lenPair.second
                    val pEnd = (pos + len).coerceAtMost(end)
                    pieceBytes = bytes.copyOfRange(pos, pEnd)
                    pos = pEnd
                } else if (fieldNum == 2 && wireType == 5) {
                    if (pos + 4 <= end) {
                        val bits = (bytes[pos].toInt() and 0xFF) or
                            ((bytes[pos + 1].toInt() and 0xFF) shl 8) or
                            ((bytes[pos + 2].toInt() and 0xFF) shl 16) or
                            ((bytes[pos + 3].toInt() and 0xFF) shl 24)
                        score = Float.fromBits(bits)
                    }
                    pos = (pos + 4).coerceAtMost(end)
                } else if (fieldNum == 3 && wireType == 0) {
                    val v = readVarint(bytes, pos, end)
                    type = v.first.toInt()
                    pos = v.second
                } else {
                    pos = skipField(bytes, pos, end, wireType)
                }
            }

            if (type == 2) {
                return tokenId // UNKNOWN token ID
            }
            if (type == 3 || type == 5) {
                return null // Skip CONTROL (<pad>, <eos>, <bos>) and UNUSED
            }
            val pBytes = pieceBytes ?: return null
            if (pBytes.isEmpty()) return null

            if (type == 6 && pBytes.size == 6) {
                val str = String(pBytes, Charsets.US_ASCII)
                if (str.startsWith("<0x") && str.endsWith(">")) {
                    val hexVal = str.substring(3, 5).toIntOrNull(16)
                    if (hexVal != null && hexVal in 0..255) {
                        byteTokens[hexVal] = tokenId
                        return null
                    }
                }
            }

            var nodeIdx = 0
            for (b in pBytes) {
                val unsignedB = b.toInt() and 0xFF
                val existing = trie[nodeIdx].children[unsignedB]
                nodeIdx = if (existing == null) {
                    val nextIdx = trie.size
                    trie.add(TrieNode())
                    trie[nodeIdx].children[unsignedB] = nextIdx
                    nextIdx
                } else {
                    existing
                }
            }
            trie[nodeIdx].tokenId = tokenId
            trie[nodeIdx].score = score
            return null
        }

        private fun readVarint(bytes: ByteArray, start: Int, limit: Int): Pair<Long, Int> {
            var result = 0L
            var shift = 0
            var pos = start
            while (pos < limit && shift < 64) {
                val b = bytes[pos++].toInt()
                result = result or ((b and 0x7F).toLong() shl shift)
                if ((b and 0x80) == 0) break
                shift += 7
            }
            return Pair(result, pos)
        }

        private fun skipField(bytes: ByteArray, start: Int, limit: Int, wireType: Int): Int {
            return when (wireType) {
                0 -> readVarint(bytes, start, limit).second
                1 -> (start + 8).coerceAtMost(limit)
                2 -> {
                    val lenPair = readVarint(bytes, start, limit)
                    (lenPair.second + lenPair.first.toInt()).coerceAtMost(limit)
                }
                5 -> (start + 4).coerceAtMost(limit)
                else -> limit
            }
        }
    }
}

/**
 * SigLIP 2 ONNX preprocessing, tokenization, padding, and similarity scoring utilities.
 */
object OneShotTools {

    /** SigLIP 2 Gemma tokenizer constants */
    const val PAD_TOKEN_ID: Int = 0
    const val EOS_TOKEN_ID: Int = 1
    const val BOS_TOKEN_ID: Int = 2
    const val UNK_TOKEN_ID: Int = 3
    const val MAX_SEQ_LEN: Int = 64
    const val IMAGE_SIZE: Int = 224

    /**
     * Formats a raw object/feature label into SigLIP 2's canonical zero-shot prompt template.
     * SigLIP 2 was trained strictly on lowercased text with the template "this is a photo of {label}."
     */
    fun formatSiglipPrompt(feature: String): String {
        val cleaned = feature.lowercase().trim().removeSuffix(".").trim()
        if (cleaned.isEmpty()) return "this is a photo."
        return if (cleaned.startsWith("this is a photo of ") || cleaned.startsWith("a photo of ")) {
            "$cleaned."
        } else {
            "this is a photo of $cleaned."
        }
    }

    /**
     * Pads and truncates raw token IDs to [maxLength] (default 64) for SigLIP 2's Gemma tokenizer.
     * - Places [eosTokenId] (`1`) immediately after the truncated text tokens at `minOf(tokenIds.size, maxLength - 1)`.
     * - Fills all remaining slots up to [maxLength] with [padTokenId] (`0`).
     */
    fun padTokenIds(
        tokenIds: IntArray,
        maxLength: Int = MAX_SEQ_LEN,
        padTokenId: Int = PAD_TOKEN_ID,
        eosTokenId: Int = EOS_TOKEN_ID
    ): LongArray {
        val padded = LongArray(maxLength) { padTokenId.toLong() }
        if (maxLength <= 0) return padded
        val copyLength = minOf(tokenIds.size, maxLength - 1)
        for (i in 0 until copyLength) {
            padded[i] = tokenIds[i].toLong()
        }
        padded[copyLength] = eosTokenId.toLong()
        return padded
    }

    /**
     * Normalizes [text] with `.lowercase().trim()`, ensures the SentencePiece model at [tokenizerModelPath]
     * is loaded once (cached), and returns padded length-64 token IDs with EOS=1 and PAD=0.
     */
    fun tokenizeText(
        text: String,
        tokenizerModelPath: String,
        maxLength: Int = MAX_SEQ_LEN
    ): LongArray {
        val normalized = text.lowercase().trim()
        if (!SentencePieceProcessor.ensureLoaded(tokenizerModelPath)) {
            throw IllegalStateException("Failed to load SentencePiece tokenizer model from $tokenizerModelPath")
        }
        val rawIds = SentencePieceProcessor.encodeNormalized(normalized)
        return padTokenIds(rawIds, maxLength = maxLength, padTokenId = PAD_TOKEN_ID, eosTokenId = EOS_TOKEN_ID)
    }

    /**
     * Preprocesses an Android [Bitmap] into a direct [FloatBuffer] of shape `[1, 3, 224, 224]` (NCHW)
     * normalized to `[-1.0, 1.0]` using mean = `(0.5, 0.5, 0.5)` and std = `(0.5, 0.5, 0.5)`.
     * Guards against `Bitmap.Config.HARDWARE` to prevent `IllegalArgumentException` on `getPixel`.
     */
    fun preprocessBitmapToFloatBuffer(
        bitmap: Bitmap,
        width: Int = IMAGE_SIZE,
        height: Int = IMAGE_SIZE
    ): FloatBuffer {
        val softwareBitmap = ensureSoftwareBitmap(bitmap)
        val scaledBitmap = if (softwareBitmap.width == width && softwareBitmap.height == height) {
            softwareBitmap
        } else {
            softwareBitmap.scale(width, height)
        }
        val readableBitmap = ensureSoftwareBitmap(scaledBitmap)

        val floatBuffer = ByteBuffer
            .allocateDirect(3 * width * height * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()

        val channelSize = width * height
        val rChannel = FloatArray(channelSize)
        val gChannel = FloatArray(channelSize)
        val bChannel = FloatArray(channelSize)

        var idx = 0
        for (y in 0 until height) {
            for (x in 0 until width) {
                val pixel = readableBitmap[x, y]
                val r = ((pixel shr 16) and 0xFF) / 255.0f
                val g = ((pixel shr 8) and 0xFF) / 255.0f
                val b = (pixel and 0xFF) / 255.0f

                rChannel[idx] = (r - 0.5f) / 0.5f
                gChannel[idx] = (g - 0.5f) / 0.5f
                bChannel[idx] = (b - 0.5f) / 0.5f
                idx++
            }
        }

        floatBuffer.put(rChannel)
        floatBuffer.put(gChannel)
        floatBuffer.put(bChannel)
        floatBuffer.rewind()
        return floatBuffer
    }

    private fun ensureSoftwareBitmap(bitmap: Bitmap): Bitmap {
        val isHardware = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            bitmap.config == Bitmap.Config.HARDWARE
        return if (isHardware || bitmap.config == null) {
            bitmap.copy(Bitmap.Config.ARGB_8888, false)
                ?: throw IllegalStateException("Failed to copy HARDWARE bitmap to ARGB_8888")
        } else {
            bitmap
        }
    }

    /**
     * L2-normalizes an embedding vector to unit length.
     */
    fun l2Normalize(embedding: FloatArray): FloatArray {
        var sumSq = 0.0
        for (v in embedding) {
            sumSq += (v * v).toDouble()
        }
        val norm = sqrt(sumSq).coerceAtLeast(1e-12)
        return FloatArray(embedding.size) { i -> (embedding[i] / norm).toFloat() }
    }

    /**
     * Computes cosine similarity between two embedding vectors after L2-normalizing both.
     */
    fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
        require(a.size == b.size && a.isNotEmpty()) {
            "Embedding dimensions must match and be non-empty (got ${a.size} vs ${b.size})"
        }
        val normA = l2Normalize(a)
        val normB = l2Normalize(b)
        var dot = 0.0f
        for (i in normA.indices) {
            dot += normA[i] * normB[i]
        }
        return dot.coerceIn(-1.0f, 1.0f)
    }

    /**
     * Applies the standard sigmoid function `1 / (1 + exp(-x))` for SigLIP 2 logits.
     */
    fun sigmoid(logit: Float): Float {
        return (1.0 / (1.0 + exp(-logit.toDouble()))).toFloat()
    }
}
