package neth.iecal.questphone

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.util.Log
import androidx.core.content.edit
import androidx.core.graphics.scale
import androidx.lifecycle.application
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.jan.supabase.auth.auth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import neth.iecal.questphone.ai.OneShotTools
import neth.iecal.questphone.ai.SentencePieceProcessor
import neth.iecal.questphone.app.screens.quest.setup.ai_snap.model.SIGLIP2_TOKENIZER_URL
import neth.iecal.questphone.app.screens.quest.setup.ai_snap.model.ensureTokenizerQueued
import neth.iecal.questphone.app.screens.quest.view.ViewQuestVM
import neth.iecal.questphone.app.screens.quest.view.ai_snap.AI_SNAP_PIC
import neth.iecal.questphone.backed.repositories.QuestRepository
import neth.iecal.questphone.backed.repositories.StatsRepository
import neth.iecal.questphone.backed.repositories.UserRepository
import neth.iecal.questphone.core.Supabase
import neth.iecal.questphone.core.utils.LocalGeminiNanoValidator
import nethical.questphone.backend.GeminiValidator
import nethical.questphone.backend.TaskValidationClient
import nethical.questphone.data.EvaluationStep
import nethical.questphone.data.json
import nethical.questphone.data.quest.ai.snap.AiSnap
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.nio.LongBuffer
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import kotlin.math.max
import kotlin.random.Random

@HiltViewModel
class AiSnapQuestViewVM @Inject constructor(
    questRepository: QuestRepository,
    userRepository: UserRepository,
    statsRepository: StatsRepository,
    application: android.app.Application,
) : ViewQuestVM(
    questRepository, userRepository, statsRepository, application,
) {
    companion object {
        private const val TAG = "AiSnapQuestViewVM"
        private const val TOKENIZER_FILE_NAME = "tokenizer.model"

        // SigLIP 2 calibrated thresholds:
        // Sigmoid over learned temperature+bias logits (`logits_per_image`):
        // Unrelated text prompts typically score < 0.005; genuine matches score >= 0.05..0.95.
        private const val SIGLIP_SIGMOID_THRESHOLD = 0.05f
        // Cosine similarity over L2-normalized embeddings (`image_embeds` dot `text_embeds`):
        private const val SIGLIP_COSINE_THRESHOLD = 0.16f
        // Required margin above negative baseline prompts:
        private const val NEGATIVE_MARGIN_LOGIT = 0.25f
        private const val NEGATIVE_MARGIN_COSINE = 0.015f

        private val NEGATIVE_BASELINE_PROMPTS = listOf(
            "this is a blurry, dark, or irrelevant photo.",
            "this is a photo of a blank wall, floor, or empty background."
        )
    }

    val isAiEvaluating = MutableStateFlow(false)
    val isCameraScreen = MutableStateFlow(false)
    var aiQuest = AiSnap()

    val currentStep = MutableStateFlow(EvaluationStep.INITIALIZING)
    val error = MutableStateFlow<String?>(null)
    val results = MutableStateFlow<TaskValidationClient.ValidationResult?>(null)
    val isModelDownloaded = MutableStateFlow(true)

    private var isModelLoaded = false
    private var modelId: String = "online"
    private var isOnlineInferencing = true

    private var ortEnv: OrtEnvironment? = null
    private var ortSession: OrtSession? = null
    private var loadedModelFilePath: String? = null
    private val sessionLock = Any()

    private val client = TaskValidationClient()
    private val localNano = LocalGeminiNanoValidator(application)

    private val httpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(60, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    init {
        viewModelScope.launch(Dispatchers.IO) {
            loadModel()
        }
    }

    fun setAiSnap() {
        if (commonQuestInfo.quest_json.isNotBlank()) {
            try {
                aiQuest = json.decodeFromString<AiSnap>(commonQuestInfo.quest_json)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to decode AiSnap quest_json", e)
            }
        }
    }

    fun onAiSnapQuestDone() {
        saveMarkedQuestToDb()
        isCameraScreen.value = false
    }

    /**
     * Resolves the local ONNX model file from SharedPreferences or scans `filesDir` for any
     * downloaded `.onnx` model, migrating legacy unquantized FP32 preferences automatically.
     */
    private fun resolveLocalOnnxModelFile(): File? {
        val sp = application.getSharedPreferences("models", Context.MODE_PRIVATE)
        var selected = sp.getString("selected_one_shot_model", null)

        // Migrate legacy unquantized FP32 model IDs
        if (selected == "model" || selected == "fp32" || selected == "full_fp32") {
            selected = "full"
            sp.edit(commit = true) {
                putString("selected_one_shot_model", selected)
            }
        }

        if (!selected.isNullOrBlank() && selected != "online") {
            val candidate = File(application.filesDir, "$selected.onnx")
            if (candidate.exists() && candidate.isFile && candidate.length() > 1024L) {
                modelId = selected
                return candidate
            }
        }

        // Scan filesDir for any valid downloaded .onnx model
        val preferredOrder = listOf("small", "medium", "full", "siglip2_small", "siglip2_medium", "siglip2_full")
        for (prefId in preferredOrder) {
            val file = File(application.filesDir, "$prefId.onnx")
            if (file.exists() && file.isFile && file.length() > 1024L) {
                modelId = prefId
                sp.edit(commit = true) {
                    putString("selected_one_shot_model", prefId)
                    putBoolean("is_downloaded_$prefId", true)
                }
                return file
            }
        }

        val anyOnnx = application.filesDir.listFiles()?.firstOrNull {
            it.isFile && it.extension.equals("onnx", ignoreCase = true) && it.length() > 1024L
        }
        if (anyOnnx != null) {
            val inferredId = anyOnnx.nameWithoutExtension
            modelId = inferredId
            sp.edit(commit = true) {
                putString("selected_one_shot_model", inferredId)
                putBoolean("is_downloaded_$inferredId", true)
            }
            return anyOnnx
        }

        return null
    }

    /**
     * Ensures `tokenizer.model` is present in `filesDir` and loaded into `SentencePieceProcessor`.
     * Downloads synchronously if missing so JNI never crashes on an absent file.
     */
    private fun ensureTokenizerReady(): File? {
        val tokenizerFile = File(application.filesDir, TOKENIZER_FILE_NAME)
        val sp = application.getSharedPreferences("models", Context.MODE_PRIVATE)

        if (!tokenizerFile.exists() || tokenizerFile.length() <= 1024L) {
            Log.i(TAG, "tokenizer.model missing or incomplete, downloading synchronously...")
            val tempFile = File(application.filesDir, "$TOKENIZER_FILE_NAME.sync.part")
            try {
                val request = Request.Builder().url(SIGLIP2_TOKENIZER_URL).build()
                httpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        throw IllegalStateException("HTTP ${response.code} downloading tokenizer.model")
                    }
                    val body = response.body ?: throw IllegalStateException("Empty body for tokenizer.model")
                    FileOutputStream(tempFile).use { out ->
                        body.byteStream().copyTo(out)
                    }
                }
                if (tempFile.exists() && tempFile.length() > 1024L) {
                    if (tokenizerFile.exists()) tokenizerFile.delete()
                    if (!tempFile.renameTo(tokenizerFile)) {
                        tempFile.copyTo(tokenizerFile, overwrite = true)
                        tempFile.delete()
                    }
                    sp.edit(commit = true) {
                        putBoolean("is_downloaded_tokenizer", true)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Synchronous tokenizer.model download failed: ${e.message}", e)
                tempFile.delete()
                ensureTokenizerQueued(application)
                return null
            }
        }

        return if (SentencePieceProcessor.ensureLoaded(tokenizerFile.absolutePath)) {
            tokenizerFile
        } else {
            Log.e(TAG, "SentencePieceProcessor failed to load tokenizer.model")
            null
        }
    }

    fun loadModel(): Boolean {
        val settingsSp = application.getSharedPreferences("private_settings", Context.MODE_PRIVATE)
        val defaultEngine = if (BuildConfig.IS_FDROID) "local" else "cloud"
        val engine = settingsSp.getString("validation_engine", defaultEngine) ?: defaultEngine

        if (engine != "local") {
            isOnlineInferencing = true
            isModelLoaded = true
            isModelDownloaded.value = true
            return true
        }

        isOnlineInferencing = false
        val modelFile = resolveLocalOnnxModelFile()
        if (modelFile == null) {
            isModelLoaded = false
            isModelDownloaded.value = localNano.isAvailable()
            return localNano.isAvailable()
        }

        return synchronized(sessionLock) {
            try {
                val canonicalPath = modelFile.canonicalPath
                if (ortSession != null && loadedModelFilePath == canonicalPath) {
                    isModelLoaded = true
                    isModelDownloaded.value = true
                    return@synchronized true
                }

                ortSession?.close()
                ortSession = null

                val env = ortEnv ?: OrtEnvironment.getEnvironment().also { ortEnv = it }
                val sessionOptions = OrtSession.SessionOptions().apply {
                    setIntraOpNumThreads(Runtime.getRuntime().availableProcessors().coerceIn(1, 4))
                    setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                }
                ortSession = env.createSession(canonicalPath, sessionOptions)
                loadedModelFilePath = canonicalPath
                isModelLoaded = true
                isModelDownloaded.value = true
                Log.i(TAG, "Loaded local SigLIP 2 ONNX session from $canonicalPath")
                true
            } catch (e: Throwable) {
                Log.e(TAG, "Failed to initialize ONNX session from ${modelFile.absolutePath}", e)
                isModelLoaded = false
                false
            }
        }
    }

    fun evaluateQuest(onEvaluationComplete: () -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            currentStep.value = EvaluationStep.INITIALIZING
            results.value = null
            error.value = null

            val photoFile = File(application.filesDir, AI_SNAP_PIC)
            if (!photoFile.exists() || photoFile.length() == 0L) {
                results.value = TaskValidationClient.ValidationResult(
                    isValid = false,
                    reason = "Captured image file not found. Please retake the photo."
                )
                currentStep.value = EvaluationStep.COMPLETED
                return@launch
            }

            val settingsSp = application.getSharedPreferences("private_settings", Context.MODE_PRIVATE)
            val modelsSp = application.getSharedPreferences("models", Context.MODE_PRIVATE)
            val selectedModel = modelsSp.getString("selected_one_shot_model", null)
            val defaultEngine = if (BuildConfig.IS_FDROID || (selectedModel != null && selectedModel != "online")) {
                "local"
            } else {
                "cloud"
            }
            val engine = settingsSp.getString("validation_engine", defaultEngine) ?: defaultEngine

            when (engine) {
                "local" -> runOfflineInference(photoFile, onEvaluationComplete)
                "gemini_api" -> {
                    val compressedFile = resizeAndCompressImage(photoFile, 1080, 50)
                    runGeminiApiInference(compressedFile, settingsSp.getString("gemini_api_key", null), onEvaluationComplete)
                }
                else -> {
                    val compressedFile = resizeAndCompressImage(photoFile, 1080, 50)
                    runCloudInference(compressedFile, onEvaluationComplete)
                }
            }
        }
    }

    private suspend fun runOfflineInference(
        photoFile: File,
        onEvaluationComplete: () -> Unit
    ) {
        currentStep.value = EvaluationStep.LOADING_MODEL

        val modelFile = resolveLocalOnnxModelFile()
        if (modelFile == null) {
            // If no SigLIP 2 ONNX model is downloaded yet, check if Gemini Nano is available
            if (localNano.isAvailable()) {
                currentStep.value = EvaluationStep.EVALUATING
                val fallbackFeatures = aiQuest.features.ifEmpty { listOf(aiQuest.taskDescription) }
                val nanoResult = localNano.validateTaskLocally(aiQuest.taskDescription, fallbackFeatures)
                results.value = nanoResult
                currentStep.value = EvaluationStep.COMPLETED
                if (nanoResult.isValid) {
                    onEvaluationComplete()
                }
                return
            }

            isModelDownloaded.value = false
            results.value = TaskValidationClient.ValidationResult(
                isValid = false,
                reason = "Local SigLIP 2 ONNX model is not downloaded yet. Tap 'Select Model' below to download the Small (Q4) or Medium (INT8) offline model."
            )
            currentStep.value = EvaluationStep.COMPLETED
            return
        }

        if (!loadModel() || ortSession == null) {
            results.value = TaskValidationClient.ValidationResult(
                isValid = false,
                reason = "Failed to load local ONNX model (${modelFile.name}). The model file may be corrupted; please re-download it via 'Select Model'."
            )
            currentStep.value = EvaluationStep.COMPLETED
            return
        }

        val tokenizerFile = ensureTokenizerReady()
        if (tokenizerFile == null) {
            results.value = TaskValidationClient.ValidationResult(
                isValid = false,
                reason = "SigLIP 2 tokenizer (tokenizer.model) has not finished downloading. Please connect to the internet briefly and retry."
            )
            currentStep.value = EvaluationStep.COMPLETED
            return
        }

        currentStep.value = EvaluationStep.PREPROCESSING

        val bitmap = decodeBitmapWithExif(photoFile)
        if (bitmap == null) {
            results.value = TaskValidationClient.ValidationResult(
                isValid = false,
                reason = "Could not decode captured photo. Please retake the snap."
            )
            currentStep.value = EvaluationStep.COMPLETED
            return
        }

        // Gather positive target features from quest configuration
        val rawPositiveFeatures = buildList {
            aiQuest.features.map { it.trim() }.filter { it.isNotEmpty() }.forEach { add(it) }
            if (isEmpty() && aiQuest.taskDescription.isNotBlank()) {
                add(aiQuest.taskDescription.trim())
            }
        }

        if (rawPositiveFeatures.isEmpty()) {
            results.value = TaskValidationClient.ValidationResult(
                isValid = false,
                reason = "Quest has no task description or features configured."
            )
            currentStep.value = EvaluationStep.COMPLETED
            return
        }

        val formattedPositivePrompts = rawPositiveFeatures.map { OneShotTools.formatSiglipPrompt(it) }
        val allPrompts = formattedPositivePrompts + NEGATIVE_BASELINE_PROMPTS
        val batchSize = allPrompts.size
        val seqLen = OneShotTools.MAX_SEQ_LEN

        val flatInputIds = LongArray(batchSize * seqLen)
        val flatAttentionMask = LongArray(batchSize * seqLen)

        try {
            for (b in 0 until batchSize) {
                val paddedIds = OneShotTools.tokenizeText(
                    text = allPrompts[b],
                    tokenizerModelPath = tokenizerFile.absolutePath,
                    maxLength = seqLen
                )
                val offset = b * seqLen
                for (i in 0 until seqLen) {
                    val tokenId = paddedIds[i]
                    flatInputIds[offset + i] = tokenId
                    flatAttentionMask[offset + i] = if (tokenId != OneShotTools.PAD_TOKEN_ID.toLong()) 1L else 0L
                }
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Tokenization failed", e)
            results.value = TaskValidationClient.ValidationResult(
                isValid = false,
                reason = "Local tokenization error: ${e.message}"
            )
            currentStep.value = EvaluationStep.COMPLETED
            return
        }

        val pixelBuffer = try {
            OneShotTools.preprocessBitmapToFloatBuffer(bitmap)
        } catch (e: Throwable) {
            Log.e(TAG, "Image preprocessing failed", e)
            results.value = TaskValidationClient.ValidationResult(
                isValid = false,
                reason = "Image preprocessing error: ${e.message}"
            )
            currentStep.value = EvaluationStep.COMPLETED
            return
        }

        currentStep.value = EvaluationStep.EVALUATING

        val env = ortEnv ?: OrtEnvironment.getEnvironment().also { ortEnv = it }
        val session = ortSession!!

        var inputIdsTensor: OnnxTensor? = null
        var pixelValuesTensor: OnnxTensor? = null
        var attentionMaskTensor: OnnxTensor? = null
        var ortResult: OrtSession.Result? = null

        try {
            inputIdsTensor = OnnxTensor.createTensor(
                env,
                LongBuffer.wrap(flatInputIds),
                longArrayOf(batchSize.toLong(), seqLen.toLong())
            )
            pixelValuesTensor = OnnxTensor.createTensor(
                env,
                pixelBuffer,
                longArrayOf(1L, 3L, OneShotTools.IMAGE_SIZE.toLong(), OneShotTools.IMAGE_SIZE.toLong())
            )

            val inputs = mutableMapOf<String, OnnxTensor>()
            val expectedInputs = session.inputNames

            val inputIdsName = expectedInputs.firstOrNull { it.contains("input_ids") } ?: "input_ids"
            val pixelValuesName = expectedInputs.firstOrNull { it.contains("pixel_values") } ?: "pixel_values"
            inputs[inputIdsName] = inputIdsTensor
            inputs[pixelValuesName] = pixelValuesTensor

            val attentionMaskName = expectedInputs.firstOrNull { it.contains("attention_mask") }
            if (attentionMaskName != null) {
                attentionMaskTensor = OnnxTensor.createTensor(
                    env,
                    LongBuffer.wrap(flatAttentionMask),
                    longArrayOf(batchSize.toLong(), seqLen.toLong())
                )
                inputs[attentionMaskName] = attentionMaskTensor
            }

            ortResult = session.run(inputs)

            val scoring = extractSiglipScores(ortResult, batchSize, formattedPositivePrompts.size)

            val matchedFeatures = mutableListOf<String>()
            val featureReport = mutableListOf<String>()

            for (i in rawPositiveFeatures.indices) {
                val featureName = rawPositiveFeatures[i]
                val prob = scoring.probabilities[i]
                val rawMetric = scoring.rawMetrics[i]
                val passed = scoring.passedFlags[i]
                val pct = (prob * 100f).toInt().coerceIn(0, 100)
                featureReport.add("$featureName ($pct%)")
                if (passed) {
                    matchedFeatures.add(featureName)
                }
                Log.i(
                    TAG,
                    "Feature '$featureName': prob=$prob, raw=$rawMetric, negMax=${scoring.maxNegativeMetric}, passed=$passed"
                )
            }

            val requiredMatches = max(1, (rawPositiveFeatures.size + 1) / 2)
            val siglipValid = matchedFeatures.size >= requiredMatches

            // Optional Gemini Nano reasoning enrichment when AICore is active on device;
            // NEVER fails or blocks completion when Gemini Nano is unavailable!
            if (localNano.isAvailable() && siglipValid) {
                val nanoValidation = localNano.validateTaskLocally(
                    taskDescription = aiQuest.taskDescription,
                    detectedFeatures = matchedFeatures
                )
                if (nanoValidation.isValid) {
                    results.value = nanoValidation
                    currentStep.value = EvaluationStep.COMPLETED
                    onEvaluationComplete()
                    return
                } else {
                    Log.i(TAG, "Gemini Nano returned non-valid or unavailable (${nanoValidation.reason}), using SigLIP 2 ONNX result")
                }
            }

            val reasonText = if (siglipValid) {
                "Verified locally with SigLIP 2 (${modelId}): detected ${matchedFeatures.joinToString(", ")}. Scores: ${featureReport.joinToString(", ")}."
            } else {
                "Could not confidently detect required features (${rawPositiveFeatures.joinToString(", ")}) in the photo. Scores: ${featureReport.joinToString(", ")}. Try taking a clearer, well-lit photo."
            }

            results.value = TaskValidationClient.ValidationResult(
                isValid = siglipValid,
                reason = reasonText
            )
            currentStep.value = EvaluationStep.COMPLETED
            if (siglipValid) {
                onEvaluationComplete()
            }
        } catch (e: Throwable) {
            Log.e(TAG, "ONNX inference execution failed", e)
            results.value = TaskValidationClient.ValidationResult(
                isValid = false,
                reason = "Local ONNX inference error: ${e.message ?: "Unexpected failure"}"
            )
            currentStep.value = EvaluationStep.COMPLETED
        } finally {
            try {
                ortResult?.close()
            } catch (_: Throwable) {
            }
            try {
                inputIdsTensor?.close()
            } catch (_: Throwable) {
            }
            try {
                pixelValuesTensor?.close()
            } catch (_: Throwable) {
            }
            try {
                attentionMaskTensor?.close()
            } catch (_: Throwable) {
            }
        }
    }

    private data class SiglipScoringResult(
        val probabilities: FloatArray,
        val rawMetrics: FloatArray,
        val passedFlags: BooleanArray,
        val maxNegativeMetric: Float
    )

    /**
     * Extracts and calibrates scores from SigLIP 2 ONNX outputs.
     * Supports both `logits_per_image` / `logits_per_text` (applying sigmoid `1 / (1 + exp(-logit))`)
     * and `image_embeds` + `text_embeds` (L2-normalizing both vectors and computing cosine similarity).
     */
    private fun extractSiglipScores(
        ortResult: OrtSession.Result,
        batchSize: Int,
        positiveCount: Int
    ): SiglipScoringResult {
        val outputMap = mutableMapOf<String, Any?>()
        for (entry in ortResult) {
            outputMap[entry.key] = entry.value?.value
        }

        // 1. Check for `logits_per_image` [1, batchSize] or `logits_per_text` [batchSize, 1]
        val logitsPerImage = extractFloatVector(outputMap["logits_per_image"], batchSize)
            ?: extractTransposedFloatVector(outputMap["logits_per_text"], batchSize)

        // 2. Also check for `image_embeds` [1, dim] and `text_embeds` [batchSize, dim]
        val imageEmbeds = extract2DFloatArray(outputMap["image_embeds"])?.firstOrNull()
        val textEmbeds = extract2DFloatArray(outputMap["text_embeds"])

        val cosineSims: FloatArray? = if (imageEmbeds != null && textEmbeds != null && textEmbeds.size >= batchSize) {
            FloatArray(batchSize) { i ->
                OneShotTools.cosineSimilarity(imageEmbeds, textEmbeds[i])
            }
        } else {
            null
        }

        val probabilities = FloatArray(positiveCount)
        val rawMetrics = FloatArray(positiveCount)
        val passedFlags = BooleanArray(positiveCount)

        if (logitsPerImage != null) {
            var maxNegLogit = Float.NEGATIVE_INFINITY
            for (i in positiveCount until batchSize) {
                if (logitsPerImage[i] > maxNegLogit) {
                    maxNegLogit = logitsPerImage[i]
                }
            }
            if (maxNegLogit == Float.NEGATIVE_INFINITY) maxNegLogit = -10.0f

            for (i in 0 until positiveCount) {
                val logit = logitsPerImage[i]
                val prob = OneShotTools.sigmoid(logit)
                val cosSim = cosineSims?.getOrNull(i)
                probabilities[i] = prob
                rawMetrics[i] = logit

                val passesLogit = prob >= SIGLIP_SIGMOID_THRESHOLD &&
                    (logit >= maxNegLogit + NEGATIVE_MARGIN_LOGIT || prob >= 0.20f)
                val passesCosine = cosSim != null && cosSim >= SIGLIP_COSINE_THRESHOLD
                passedFlags[i] = passesLogit || passesCosine
            }

            return SiglipScoringResult(
                probabilities = probabilities,
                rawMetrics = rawMetrics,
                passedFlags = passedFlags,
                maxNegativeMetric = maxNegLogit
            )
        }

        if (cosineSims != null) {
            var maxNegCos = Float.NEGATIVE_INFINITY
            for (i in positiveCount until batchSize) {
                if (cosineSims[i] > maxNegCos) {
                    maxNegCos = cosineSims[i]
                }
            }
            if (maxNegCos == Float.NEGATIVE_INFINITY) maxNegCos = 0.0f

            for (i in 0 until positiveCount) {
                val cos = cosineSims[i]
                // Calibrate cosine similarity to [0, 1] confidence using SigLIP temperature scale (~10.0)
                val calibratedLogit = (cos - 0.14f) * 15.0f
                val prob = OneShotTools.sigmoid(calibratedLogit)
                probabilities[i] = prob
                rawMetrics[i] = cos
                passedFlags[i] = cos >= SIGLIP_COSINE_THRESHOLD &&
                    (cos >= maxNegCos + NEGATIVE_MARGIN_COSINE || cos >= 0.22f)
            }

            return SiglipScoringResult(
                probabilities = probabilities,
                rawMetrics = rawMetrics,
                passedFlags = passedFlags,
                maxNegativeMetric = maxNegCos
            )
        }

        // Fallback if output tensor has custom name: inspect first available float output
        val firstOutput = outputMap.values.firstOrNull()
        val fallbackVec = extractFloatVector(firstOutput, batchSize)
            ?: FloatArray(batchSize) { 0.0f }

        var maxNeg = Float.NEGATIVE_INFINITY
        for (i in positiveCount until batchSize) {
            if (fallbackVec[i] > maxNeg) maxNeg = fallbackVec[i]
        }
        for (i in 0 until positiveCount) {
            val v = fallbackVec[i]
            val prob = OneShotTools.sigmoid(v)
            probabilities[i] = prob
            rawMetrics[i] = v
            passedFlags[i] = prob >= SIGLIP_SIGMOID_THRESHOLD && v > maxNeg
        }

        return SiglipScoringResult(
            probabilities = probabilities,
            rawMetrics = rawMetrics,
            passedFlags = passedFlags,
            maxNegativeMetric = maxNeg
        )
    }

    private fun extractFloatVector(raw: Any?, expectedSize: Int): FloatArray? {
        if (raw == null) return null
        return when (raw) {
            is FloatArray -> if (raw.size >= expectedSize) raw else null
            is Array<*> -> {
                val first = raw.firstOrNull()
                if (first is FloatArray && first.size >= expectedSize) {
                    first
                } else {
                    null
                }
            }
            else -> null
        }
    }

    private fun extractTransposedFloatVector(raw: Any?, expectedSize: Int): FloatArray? {
        if (raw is Array<*> && raw.size >= expectedSize) {
            val result = FloatArray(expectedSize)
            for (i in 0 until expectedSize) {
                val row = raw[i] as? FloatArray ?: return null
                if (row.isEmpty()) return null
                result[i] = row[0]
            }
            return result
        }
        return null
    }

    private fun extract2DFloatArray(raw: Any?): Array<FloatArray>? {
        if (raw is Array<*>) {
            val rows = mutableListOf<FloatArray>()
            for (elem in raw) {
                if (elem is FloatArray) {
                    rows.add(elem)
                }
            }
            if (rows.isNotEmpty()) {
                return rows.toTypedArray()
            }
        }
        return null
    }

    private fun decodeBitmapWithExif(file: File): Bitmap? {
        return try {
            val options = BitmapFactory.Options().apply {
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            val rawBitmap = BitmapFactory.decodeFile(file.absolutePath, options) ?: return null
            val exif = ExifInterface(file.absolutePath)
            val orientation = exif.getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL
            )
            val rotationDegrees = when (orientation) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
            if (rotationDegrees == 0f) {
                rawBitmap
            } else {
                val matrix = Matrix().apply { postRotate(rotationDegrees) }
                Bitmap.createBitmap(rawBitmap, 0, 0, rawBitmap.width, rawBitmap.height, matrix, true)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to decode bitmap from ${file.absolutePath}", e)
            null
        }
    }

    private suspend fun runGeminiApiInference(
        compressedFile: File,
        geminiKey: String?,
        onEvaluationComplete: () -> Unit
    ) {
        if (geminiKey.isNullOrBlank()) {
            results.value = TaskValidationClient.ValidationResult(
                isValid = false,
                reason = "Private Gemini API key is missing. Please configure it in your Profile settings or switch to Local On-Device AI."
            )
            currentStep.value = EvaluationStep.COMPLETED
            return
        }

        currentStep.value = EvaluationStep.LOADING_MODEL
        val geminiValidator = GeminiValidator()
        geminiValidator.validateTask(
            compressedFile,
            aiQuest.taskDescription,
            aiQuest.features.joinToString(","),
            geminiKey
        ) { result ->
            results.value = result.getOrNull() ?: TaskValidationClient.ValidationResult(
                isValid = false,
                reason = "Gemini validation failed: ${result.exceptionOrNull()?.message ?: "Unknown error"}"
            )
            currentStep.value = EvaluationStep.COMPLETED
            if (results.value?.isValid == true) {
                onEvaluationComplete()
            }
        }

        val allSteps = EvaluationStep.entries
        var currentStepInt = 0
        while (results.value == null) {
            delay(Random.nextInt(500, 1500).toLong())
            currentStep.value = EvaluationStep.valueOf(allSteps[currentStepInt].name)
            if (currentStepInt != EvaluationStep.EVALUATING.ordinal) currentStepInt++
        }
    }

    private suspend fun runCloudInference(
        compressedFile: File,
        onEvaluationComplete: () -> Unit
    ) {
        currentStep.value = EvaluationStep.LOADING_MODEL
        val token = if (userRepository.userInfo.isAnonymous) {
            ""
        } else {
            try {
                Supabase.supabase.auth.currentAccessTokenOrNull()?.toString() ?: ""
            } catch (_: Exception) {
                ""
            }
        }

        if (token.isEmpty()) {
            // If user is offline/anonymous and has a local ONNX model downloaded, automatically fall back to local ONNX!
            if (resolveLocalOnnxModelFile() != null) {
                Log.i(TAG, "Cloud auth unavailable, falling back to downloaded local SigLIP 2 ONNX model")
                runOfflineInference(File(application.filesDir, AI_SNAP_PIC), onEvaluationComplete)
                return
            }
            results.value = TaskValidationClient.ValidationResult(
                isValid = false,
                reason = "Authentication required for Cloud Server. Please log in, configure a Private Gemini API Key, or select Local On-Device AI."
            )
            currentStep.value = EvaluationStep.COMPLETED
            return
        }

        client.validateTask(
            compressedFile,
            aiQuest.taskDescription,
            aiQuest.features.joinToString(","),
            token
        ) { result ->
            results.value = result.getOrNull() ?: TaskValidationClient.ValidationResult(
                isValid = false,
                reason = "Online validation failed: ${result.exceptionOrNull()?.message ?: "Unknown error"}"
            )
            currentStep.value = EvaluationStep.COMPLETED
            if (results.value?.isValid == true) {
                onEvaluationComplete()
            }
        }

        val allSteps = EvaluationStep.entries
        var currentStepInt = 0
        while (results.value == null) {
            delay(Random.nextInt(500, 1500).toLong())
            currentStep.value = EvaluationStep.valueOf(allSteps[currentStepInt].name)
            if (currentStepInt != EvaluationStep.EVALUATING.ordinal) currentStepInt++
        }
    }

    fun resetResults() {
        isAiEvaluating.value = true
        results.value = null
        error.value = null
    }

    override fun onCleared() {
        super.onCleared()
        synchronized(sessionLock) {
            try {
                ortSession?.close()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to close ONNX session", e)
            } finally {
                ortSession = null
                loadedModelFilePath = null
                isModelLoaded = false
            }
        }
    }
}

fun resizeAndCompressImage(file: File, maxSize: Int = 1080, quality: Int = 70): File {
    val bitmap = BitmapFactory.decodeFile(file.absolutePath) ?: return file

    val ratio = bitmap.width.toFloat() / bitmap.height.toFloat().coerceAtLeast(1f)
    val width: Int
    val height: Int
    if (ratio > 1) {
        width = maxSize
        height = (maxSize / ratio).toInt().coerceAtLeast(1)
    } else {
        height = maxSize
        width = (maxSize * ratio).toInt().coerceAtLeast(1)
    }

    val scaledBitmap = bitmap.scale(width, height)

    val compressedFile = File(file.parent, "compressed_upload.jpg")
    FileOutputStream(compressedFile).use { out ->
        scaledBitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
        out.flush()
    }

    return compressedFile
}
