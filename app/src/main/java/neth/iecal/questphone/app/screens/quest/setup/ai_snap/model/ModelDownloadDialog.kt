package neth.iecal.questphone.app.screens.quest.setup.ai_snap.model

import android.content.Context
import android.util.Log
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.core.content.edit
import androidx.work.Data
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import kotlinx.coroutines.delay
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import neth.iecal.questphone.BuildConfig
import neth.iecal.questphone.backed.fetchUrlContent
import neth.iecal.questphone.core.workers.FileDownloadWorker
import java.io.File

@kotlinx.serialization.Serializable
data class DownloadableModel(
    val sizeMB: Int,
    val accuracy: String,
    val id: String,
    val url: String,
    val version: String,
    val isRecommended: Boolean = false,
    val isOnline: Boolean = false
)

const val SIGLIP2_HF_BASE =
    "https://huggingface.co/onnx-community/siglip2-base-patch16-224-ONNX/resolve/main"
const val SIGLIP2_TOKENIZER_URL = "$SIGLIP2_HF_BASE/tokenizer.model"
const val SIGLIP2_Q4_URL = "$SIGLIP2_HF_BASE/onnx/model_q4.onnx"
const val SIGLIP2_INT8_URL = "$SIGLIP2_HF_BASE/onnx/model_int8.onnx"
const val SIGLIP2_UINT8_URL = "$SIGLIP2_HF_BASE/onnx/model_uint8.onnx"
const val SIGLIP2_FP16_URL = "$SIGLIP2_HF_BASE/onnx/model_fp16.onnx"

val DEFAULT_SIGLIP2_MODELS = listOf(
    DownloadableModel(
        sizeMB = 0,
        accuracy = "Very High (Cloud / Gemini API)",
        id = "online",
        url = "",
        version = "2.0.0",
        isRecommended = !BuildConfig.IS_FDROID,
        isOnline = true
    ),
    DownloadableModel(
        sizeMB = 252,
        accuracy = "Good (Fast Q4 Quantized)",
        id = "small",
        url = SIGLIP2_Q4_URL,
        version = "2.0.0",
        isRecommended = BuildConfig.IS_FDROID,
        isOnline = false
    ),
    DownloadableModel(
        sizeMB = 380,
        accuracy = "High (Balanced INT8 Quantized)",
        id = "medium",
        url = SIGLIP2_INT8_URL,
        version = "2.0.0",
        isRecommended = false,
        isOnline = false
    ),
    DownloadableModel(
        sizeMB = 382,
        accuracy = "Very High (UINT8 Self-Contained)",
        id = "full",
        url = SIGLIP2_UINT8_URL,
        version = "2.0.0",
        isRecommended = false,
        isOnline = false
    )
)

/**
 * Ensures any remote model entry points to a self-contained single-file quantized
 * SigLIP 2 224x224 ONNX model and never uses the unquantized 1.5GB FP32 `model.onnx`
 * (which requires external `model.onnx_data` and causes OOM crashes on Android).
 */
fun sanitizeDownloadableModel(model: DownloadableModel): DownloadableModel {
    if (model.isOnline || model.id.equals("online", ignoreCase = true)) {
        return model.copy(isOnline = true)
    }
    val lowerId = model.id.lowercase()
    val lowerUrl = model.url.lowercase()

    val isUnquantizedFp32 = lowerUrl.endsWith("/model.onnx") ||
        lowerUrl.contains("model.onnx?") ||
        lowerId.contains("fp32") ||
        model.sizeMB > 1000
    val isNonSiglip2 = !lowerUrl.contains("siglip2-base-patch16-224-onnx")

    if (!isUnquantizedFp32 && !isNonSiglip2) {
        return model
    }

    return when {
        lowerId.contains("small") || lowerId.contains("q4") || lowerId.contains("tiny") -> model.copy(
            url = SIGLIP2_Q4_URL,
            sizeMB = if (model.sizeMB in 50..350) model.sizeMB else 252
        )
        lowerId.contains("medium") || lowerId.contains("int8") -> model.copy(
            url = SIGLIP2_INT8_URL,
            sizeMB = if (model.sizeMB in 200..500) model.sizeMB else 380
        )
        lowerId.contains("fp16") -> model.copy(
            url = SIGLIP2_FP16_URL,
            sizeMB = if (model.sizeMB in 500..900) model.sizeMB else 755
        )
        else -> model.copy(
            url = SIGLIP2_UINT8_URL,
            sizeMB = if (model.sizeMB in 250..600) model.sizeMB else 382
        )
    }
}

fun ensureTokenizerQueued(context: Context) {
    val sp = context.getSharedPreferences("models", Context.MODE_PRIVATE)
    val tokenizerFile = File(context.filesDir, "tokenizer.model")
    val isTokenizerDownloaded = sp.getBoolean("is_downloaded_tokenizer", false) &&
        tokenizerFile.exists() && tokenizerFile.length() > 1024L

    if (!isTokenizerDownloaded) {
        val inputData = Data.Builder()
            .putString(FileDownloadWorker.KEY_URL, SIGLIP2_TOKENIZER_URL)
            .putString(FileDownloadWorker.KEY_FILE_NAME, "tokenizer.model")
            .putString(FileDownloadWorker.KEY_MODEL_ID, "tokenizer")
            .putString(FileDownloadWorker.KEY_MODEL_VERSION, "2.0.0")
            .putBoolean(FileDownloadWorker.KEY_IS_ONE_SHOT, false)
            .build()

        val workRequest = OneTimeWorkRequestBuilder<FileDownloadWorker>()
            .setInputData(inputData)
            .setExpedited(OutOfQuotaPolicy.DROP_WORK_REQUEST)
            .build()

        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
            "download_siglip2_tokenizer",
            androidx.work.ExistingWorkPolicy.KEEP,
            workRequest
        )
    }
}

@Composable
fun ModelDownloadDialog(
    allowSkipping: Boolean = true,
    modelDownloadDialogVisible: MutableState<Boolean>,
) {
    val context = LocalContext.current
    val sp = context.getSharedPreferences("models", Context.MODE_PRIVATE)
    var currentModel by remember { mutableStateOf<String?>(null) }
    var isModelDownloading by remember { mutableStateOf(sp.contains("downloading")) }
    var modelStates by remember {
        mutableStateOf(DEFAULT_SIGLIP2_MODELS.map { mutableStateOf(it) })
    }

    // Keep download state synchronized while dialog is visible
    LaunchedEffect(modelDownloadDialogVisible.value, isModelDownloading) {
        while (modelDownloadDialogVisible.value && isModelDownloading) {
            delay(1000L)
            val stillDownloading = sp.contains("downloading")
            if (!stillDownloading) {
                isModelDownloading = false
                currentModel = sp.getString("selected_one_shot_model", currentModel)
            }
        }
    }

    LaunchedEffect(Unit) {
        currentModel = sp.getString("selected_one_shot_model", null)

        // Migrate any legacy unquantized FP32 selection to a safe quantized variant
        if (currentModel != null && (currentModel == "model" || currentModel == "fp32" || currentModel == "full_fp32")) {
            val migrated = "full"
            sp.edit(commit = true) {
                putString("selected_one_shot_model", migrated)
            }
            currentModel = migrated
        }

        if (currentModel == null && !BuildConfig.IS_FDROID) {
            currentModel = "online"
            sp.edit(commit = true) {
                putString("selected_one_shot_model", "online")
            }
        }
        if (currentModel == null) {
            modelDownloadDialogVisible.value = true
        }

        val jsonStr = fetchUrlContent("https://raw.githubusercontent.com/QuestPhone/models/refs/heads/main/model_data.json")
        if (!jsonStr.isNullOrBlank()) {
            try {
                val parsed = Json.decodeFromString(
                    ListSerializer(DownloadableModel.serializer()),
                    jsonStr
                ).map { sanitizeDownloadableModel(it) }

                if (parsed.isNotEmpty()) {
                    modelStates = parsed.map { mutableStateOf(it) }
                }

                for (modelState in modelStates) {
                    val model = modelState.value
                    if (model.isOnline) continue
                    val modelFile = File(context.filesDir, "${model.id}.onnx")
                    val localVersion = sp.getString("version_${model.id}", null)
                    val isDownloaded = sp.getBoolean("is_downloaded_${model.id}", false) && modelFile.exists()
                    if (isDownloaded && localVersion != null && localVersion != model.version) {
                        context.deleteFile("${model.id}.onnx")
                        sp.edit {
                            remove("is_downloaded_${model.id}")
                            remove("version_${model.id}")
                        }
                        Toast.makeText(context, "Model ${model.id} updated. Re-downloading...", Toast.LENGTH_SHORT).show()

                        sp.edit(commit = true) { putString("downloading", model.id) }
                        isModelDownloading = true
                        ensureTokenizerQueued(context)

                        val inputData = Data.Builder()
                            .putString(FileDownloadWorker.KEY_URL, model.url)
                            .putString(FileDownloadWorker.KEY_FILE_NAME, "${model.id}.onnx")
                            .putString(FileDownloadWorker.KEY_MODEL_ID, model.id)
                            .putString(FileDownloadWorker.KEY_MODEL_VERSION, model.version)
                            .putBoolean(FileDownloadWorker.KEY_IS_ONE_SHOT, true)
                            .build()

                        val downloadWork = OneTimeWorkRequestBuilder<FileDownloadWorker>()
                            .setInputData(inputData)
                            .build()

                        WorkManager.getInstance(context).enqueue(downloadWork)
                    }
                }
            } catch (e: Exception) {
                Log.w("ModelDownloadDialog", "Using built-in SigLIP 2 model catalog: ${e.message}")
            }
        }
    }

    fun onDismiss() {
        if (currentModel == null && !allowSkipping && !isModelDownloading) {
            Toast.makeText(context, "Please select a model to perform this quest", Toast.LENGTH_SHORT).show()
        }
        modelDownloadDialogVisible.value = false
    }

    if (modelDownloadDialogVisible.value) {
        Dialog(onDismissRequest = { onDismiss() }) {
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                Column(modifier = Modifier.padding(24.dp)) {
                    Text(
                        "Select AI Verification Model",
                        style = MaterialTheme.typography.headlineSmall,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    Text(
                        "Choose an offline on-device SigLIP 2 vision model (quantized for fast mobile inference) or online verification.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 20.dp)
                    )
                    if (isModelDownloading) {
                        val downloadingId = sp.getString("downloading", "model") ?: "model"
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 12.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                "Downloading $downloadingId model in background...\nCheck notifications for live progress.",
                                style = MaterialTheme.typography.bodyMedium,
                                textAlign = TextAlign.Center
                            )
                        }
                    } else {
                        LazyColumn(
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                            modifier = Modifier.weight(1f, false)
                        ) {
                            items(modelStates) { modelState ->
                                val model = modelState.value
                                val modelFile = File(context.filesDir, "${model.id}.onnx")
                                val isDownloaded = model.isOnline ||
                                    (sp.getBoolean("is_downloaded_${model.id}", false) && modelFile.exists() && modelFile.length() > 1024L)
                                val localVersion = sp.getString("version_${model.id}", null)
                                val isSelected = currentModel == model.id
                                val needsUpdate = !model.isOnline && isDownloaded && localVersion != null && localVersion != model.version

                                Card(
                                    shape = RoundedCornerShape(12.dp),
                                    colors = CardDefaults.cardColors(
                                        containerColor = when {
                                            isSelected -> MaterialTheme.colorScheme.primaryContainer
                                            isDownloaded -> MaterialTheme.colorScheme.surfaceVariant
                                            else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
                                        }
                                    ),
                                    elevation = CardDefaults.cardElevation(defaultElevation = if (isSelected) 4.dp else 2.dp),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable(enabled = !isModelDownloading) {
                                            if ((isDownloaded && !needsUpdate) || model.isOnline) {
                                                sp.edit(commit = true) {
                                                    putString("selected_one_shot_model", model.id)
                                                }
                                                currentModel = model.id

                                                val privateSp = context.getSharedPreferences("private_settings", Context.MODE_PRIVATE)
                                                privateSp.edit().putString(
                                                    "validation_engine",
                                                    if (model.isOnline) "cloud" else "local"
                                                ).apply()
                                                if (!model.isOnline) {
                                                    ensureTokenizerQueued(context)
                                                }
                                                Toast.makeText(
                                                    context,
                                                    "AI engine switched to ${if (model.isOnline) "Cloud Server" else "Local AI (${model.id})"}",
                                                    Toast.LENGTH_SHORT
                                                ).show()
                                            } else {
                                                ensureTokenizerQueued(context)
                                                sp.edit(commit = true) {
                                                    putString("downloading", model.id)
                                                }

                                                isModelDownloading = true
                                                val inputData = Data.Builder()
                                                    .putString(FileDownloadWorker.KEY_URL, model.url)
                                                    .putString(FileDownloadWorker.KEY_FILE_NAME, "${model.id}.onnx")
                                                    .putString(FileDownloadWorker.KEY_MODEL_ID, model.id)
                                                    .putString(FileDownloadWorker.KEY_MODEL_VERSION, model.version)
                                                    .putBoolean(FileDownloadWorker.KEY_IS_ONE_SHOT, true)
                                                    .build()

                                                val workRequest = OneTimeWorkRequestBuilder<FileDownloadWorker>()
                                                    .setInputData(inputData)
                                                    .setExpedited(OutOfQuotaPolicy.DROP_WORK_REQUEST)
                                                    .build()

                                                WorkManager.getInstance(context).enqueue(workRequest)

                                                Toast.makeText(context, "Download Started for ${model.id}", Toast.LENGTH_SHORT).show()
                                            }
                                        }
                                ) {
                                    Column(modifier = Modifier.padding(16.dp)) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Column(modifier = Modifier.weight(1f)) {
                                                if (model.isRecommended) {
                                                    Text(
                                                        "Recommended",
                                                        style = MaterialTheme.typography.labelMedium,
                                                        color = MaterialTheme.colorScheme.primary
                                                    )
                                                }
                                                Text(
                                                    model.id,
                                                    style = MaterialTheme.typography.bodyLarge,
                                                    color = if (isSelected)
                                                        MaterialTheme.colorScheme.onPrimaryContainer
                                                    else MaterialTheme.colorScheme.onSurface
                                                )
                                                Text(
                                                    "Accuracy: ${model.accuracy}",
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = if (isSelected)
                                                        MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                                                    else MaterialTheme.colorScheme.onSurfaceVariant,
                                                    modifier = Modifier.padding(top = 2.dp)
                                                )
                                                Text(
                                                    text = if (model.isOnline) {
                                                        "Requires internet connection (Cloud Server or Gemini API Key)."
                                                    } else {
                                                        "100% offline on-device SigLIP 2 ONNX model (~${model.sizeMB} MB)."
                                                    },
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = if (isSelected)
                                                        MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.6f)
                                                    else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                                    modifier = Modifier.padding(top = 4.dp)
                                                )
                                                Text(
                                                    "Version: ${model.version}" + if (needsUpdate) " (Update Available)" else "",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.error.takeIf { needsUpdate }
                                                        ?: MaterialTheme.colorScheme.outline,
                                                    modifier = Modifier.padding(top = 2.dp)
                                                )
                                            }

                                            Column(horizontalAlignment = Alignment.End) {
                                                if (isDownloaded && !model.isOnline) {
                                                    TextButton(
                                                        onClick = {
                                                            context.deleteFile("${model.id}.onnx")
                                                            sp.edit(commit = true) {
                                                                remove("is_downloaded_${model.id}")
                                                                remove("version_${model.id}")
                                                            }
                                                            if (currentModel == model.id) {
                                                                currentModel = null
                                                            }
                                                            Toast.makeText(context, "Model deleted", Toast.LENGTH_SHORT).show()
                                                        },
                                                        modifier = Modifier.padding(top = 4.dp)
                                                    ) {
                                                        Text("Delete", style = MaterialTheme.typography.labelSmall)
                                                    }
                                                } else if (!model.isOnline) {
                                                    Text(
                                                        "${model.sizeMB} MB",
                                                        style = MaterialTheme.typography.bodySmall,
                                                        color = if (isSelected)
                                                            MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                                                        else MaterialTheme.colorScheme.onSurfaceVariant
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(20.dp))

                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = { onDismiss() }) {
                            Text("Close")
                        }
                    }
                }
            }
        }
    }
}
