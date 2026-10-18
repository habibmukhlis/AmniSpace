package neth.iecal.questphone.core.workers

import android.R
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.edit
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import kotlinx.coroutines.delay
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.InputStream
import java.io.RandomAccessFile
import java.util.concurrent.TimeUnit

class FileDownloadWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        const val KEY_URL = "url"
        const val KEY_FILE_NAME = "fileName"
        const val KEY_MODEL_ID = "model_id"
        const val KEY_MODEL_VERSION = "version"
        const val KEY_IS_ONE_SHOT = "is_one_shot"
        private const val NOTIFICATION_CHANNEL_ID = "download_channel"
        private const val NOTIFICATION_CHANNEL_NAME = "Downloads"
        private const val DOWNLOAD_BUFFER_SIZE = 1024 * 256 // 256KB buffer for efficiency
        private const val MAX_RETRIES = 5
        private const val RETRY_DELAY_MS = 5000L
        private const val TAG = "FileDownloadWorker"
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(2, TimeUnit.MINUTES)
        .readTimeout(5, TimeUnit.MINUTES)
        .writeTimeout(5, TimeUnit.MINUTES)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    private val notificationManager =
        applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    private lateinit var notificationBuilder: NotificationCompat.Builder
    private var lastProgress = -1

    override suspend fun doWork(): Result {
        val url = inputData.getString(KEY_URL) ?: return Result.failure()
        val fileName = inputData.getString(KEY_FILE_NAME) ?: "downloaded_file.bin"
        val modelId = inputData.getString(KEY_MODEL_ID) ?: return Result.failure()
        val isOneShotModel = inputData.getBoolean(KEY_IS_ONE_SHOT, false)
        val modelVersion = inputData.getString(KEY_MODEL_VERSION)
            ?: inputData.getString("model_version")
            ?: "2.0.0"

        val notificationId = fileName.hashCode()
        val targetFile = File(applicationContext.filesDir, fileName)
        val tempFile = File(applicationContext.filesDir, "$fileName.part")

        return try {
            createNotificationChannel()
            try {
                setForeground(createForegroundInfo(fileName, notificationId))
            } catch (e: Exception) {
                Log.w(TAG, "Could not promote download worker to foreground service: ${e.message}")
            }

            downloadFile(url, tempFile, fileName, notificationId)

            if (!tempFile.exists() || tempFile.length() <= 0L) {
                throw IllegalStateException("Downloaded file is empty: $fileName")
            }

            if (targetFile.exists()) {
                targetFile.delete()
            }
            if (!tempFile.renameTo(targetFile)) {
                tempFile.copyTo(targetFile, overwrite = true)
                tempFile.delete()
            }

            updateSharedPrefsOnSuccess(modelId, modelVersion, isOneShotModel)
            showSuccessNotification(fileName, notificationId)
            Log.i(TAG, "Download successful for $fileName (${targetFile.length()} bytes).")
            Result.success()

        } catch (e: Exception) {
            Log.e(TAG, "Download failed permanently for $fileName.", e)
            updateSharedPrefsOnFailure(modelId)
            showErrorNotification(fileName, notificationId, e.message ?: "An unknown error occurred")
            tempFile.delete()
            Result.failure()
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        createNotificationChannel()
        val fileName = inputData.getString(KEY_FILE_NAME) ?: "downloading..."
        val notificationId = fileName.hashCode()
        return createForegroundInfo(fileName, notificationId)
    }

    private fun createForegroundInfo(fileName: String, notificationId: Int): ForegroundInfo {
        notificationBuilder = NotificationCompat.Builder(applicationContext, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.stat_sys_download)
            .setContentTitle("Starting Download")
            .setContentText(fileName)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setProgress(100, 0, true)

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(19, notificationBuilder.build(), FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(19, notificationBuilder.build())
        }
    }

    private suspend fun downloadFile(url: String, file: File, fileName: String, notificationId: Int) {
        for (attempt in 1..MAX_RETRIES) {
            try {
                val downloadedBytes = if (file.exists()) file.length() else 0L

                val request = Request.Builder().url(url)
                if (downloadedBytes > 0) {
                    request.addHeader("Range", "bytes=$downloadedBytes-")
                    Log.i(TAG, "Attempt $attempt: Resuming download for $fileName from byte $downloadedBytes")
                } else {
                    Log.i(TAG, "Attempt $attempt: Starting new download for $fileName")
                }

                client.newCall(request.build()).execute().use { response ->
                    when (response.code) {
                        416 -> {
                            Log.i(TAG, "File $fileName already downloaded (Server returned 416).")
                            return
                        }
                        200, 206 -> {
                            val body = response.body ?: throw IllegalStateException("Response body is null")

                            // If server ignored Range header and returned 200, restart from byte 0
                            val startOffset = if (response.code == 200) {
                                if (downloadedBytes > 0L && file.exists()) {
                                    file.delete()
                                }
                                0L
                            } else {
                                downloadedBytes
                            }

                            val totalFileSize = if (response.code == 200) {
                                body.contentLength()
                            } else {
                                parseTotalSizeFromContentRange(response.header("Content-Range"))
                            }

                            if (totalFileSize != -1L && startOffset >= totalFileSize) {
                                Log.i(TAG, "File $fileName already downloaded (size check).")
                                return
                            }

                            val finalSize = writeToFile(
                                body.byteStream(),
                                file,
                                startOffset,
                                totalFileSize,
                                fileName,
                                notificationId
                            )

                            if (totalFileSize != -1L && finalSize != totalFileSize) {
                                throw IllegalStateException("Download incomplete. Expected $totalFileSize, got $finalSize.")
                            }

                            Log.i(TAG, "Download stream finished successfully on attempt $attempt.")
                            return
                        }
                        else -> {
                            throw IllegalStateException("Server error: ${response.code} ${response.message}")
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Attempt $attempt to download $fileName failed.", e)
                if (attempt == MAX_RETRIES) {
                    throw e
                }
                delay(RETRY_DELAY_MS)
            }
        }
    }

    private fun writeToFile(
        input: InputStream,
        file: File,
        downloadedBytes: Long,
        totalFileSize: Long,
        fileName: String,
        notificationId: Int
    ): Long {
        var totalRead = downloadedBytes
        RandomAccessFile(file, "rw").use { raf ->
            raf.seek(downloadedBytes)
            val buffer = ByteArray(DOWNLOAD_BUFFER_SIZE)
            var bytesRead: Int
            while (input.read(buffer).also { bytesRead = it } != -1) {
                raf.write(buffer, 0, bytesRead)
                totalRead += bytesRead

                if (totalFileSize > 0) {
                    val progress = ((totalRead * 100) / totalFileSize).toInt().coerceIn(0, 100)
                    if (progress > lastProgress) {
                        updateProgressNotification(fileName, notificationId, progress)
                        lastProgress = progress
                    }
                }
            }
        }
        return totalRead
    }

    private fun parseTotalSizeFromContentRange(contentRange: String?): Long {
        return contentRange?.substringAfter('/')?.toLongOrNull() ?: -1L
    }

    private fun updateSharedPrefsOnSuccess(
        modelId: String,
        version: String,
        isOneShotModel: Boolean
    ) {
        applicationContext.getSharedPreferences("models", Context.MODE_PRIVATE).edit(commit = true) {
            if (isOneShotModel) {
                putString("selected_one_shot_model", modelId)
            }
            putBoolean("is_downloaded_$modelId", true)
            putString("version_$modelId", version)
            val currentDownloading = applicationContext
                .getSharedPreferences("models", Context.MODE_PRIVATE)
                .getString("downloading", null)
            if (currentDownloading == null || currentDownloading == modelId) {
                remove("downloading")
            }
        }
        if (isOneShotModel) {
            applicationContext.getSharedPreferences("private_settings", Context.MODE_PRIVATE).edit()
                .putString("validation_engine", "local")
                .apply()
        }
    }

    private fun updateSharedPrefsOnFailure(modelId: String) {
        val sp = applicationContext.getSharedPreferences("models", Context.MODE_PRIVATE)
        val currentDownloading = sp.getString("downloading", null)
        if (currentDownloading == null || currentDownloading == modelId) {
            sp.edit(commit = true) {
                remove("downloading")
            }
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            NOTIFICATION_CHANNEL_NAME,
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Shows file download progress."
        }
        notificationManager.createNotificationChannel(channel)
    }

    private fun updateProgressNotification(fileName: String, notificationId: Int, progress: Int) {
        if (!::notificationBuilder.isInitialized) {
            createForegroundInfo(fileName, notificationId)
        }
        notificationBuilder
            .setContentTitle("Downloading $fileName")
            .setContentText("$progress% complete")
            .setProgress(100, progress, false)
            .setSilent(true)
        notificationManager.notify(notificationId, notificationBuilder.build())
    }

    private fun showSuccessNotification(fileName: String, notificationId: Int) {
        notificationManager.cancel(notificationId)
        val successNotification = NotificationCompat.Builder(applicationContext, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.stat_sys_download_done)
            .setContentTitle("Download Complete")
            .setContentText("$fileName has been successfully downloaded.")
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .build()
        notificationManager.notify(notificationId + 1, successNotification)
    }

    private fun showErrorNotification(fileName: String, notificationId: Int, error: String) {
        val errorNotification = NotificationCompat.Builder(applicationContext, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.stat_notify_error)
            .setContentTitle("Download Failed")
            .setStyle(NotificationCompat.BigTextStyle().bigText("Could not download $fileName: $error"))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .build()
        notificationManager.notify(notificationId, errorNotification)
    }
}