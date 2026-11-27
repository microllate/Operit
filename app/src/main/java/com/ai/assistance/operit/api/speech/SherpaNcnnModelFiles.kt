package com.ai.assistance.operit.api.speech

import android.content.Context
import com.ai.assistance.operit.util.OperitPaths
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal object SherpaNcnnModelFiles {
    private const val MODEL_DIR = "sherpa-ncnn-streaming-zipformer-bilingual-zh-en-2023-02-13"
    private const val MANIFEST = "stt-model-assets.properties"
    private val downloadMutex = Mutex()

    private data class ModelFile(
        val name: String,
        val url: String,
        val size: Long,
        val sha256: String
    )

    suspend fun prepare(context: Context): File = downloadMutex.withLock {
        val models = context.assets.open(MANIFEST).bufferedReader().useLines { lines ->
            lines.filter { it.startsWith("models/$MODEL_DIR/") }.map { line ->
                val fields = line.split('|')
                ModelFile(fields[0].substringAfterLast('/'), fields[1], fields[2].toLong(), fields[3])
            }.toList()
        }
        check(models.isNotEmpty()) { "Sherpa NCNN model manifest is empty" }

        val dir = File(OperitPaths.sherpaNcnnModelsDir(context), MODEL_DIR)
        if (!dir.isDirectory && !dir.mkdirs()) {
            throw IOException("Could not create the Sherpa NCNN model directory")
        }
        for (model in models) {
            coroutineContext.ensureActive()
            val file = File(dir, model.name)
            if (file.isFile && file.length() == model.size &&
                file.inputStream().use { checksum(it, model.size) } == model.sha256
            ) continue
            download(model, file)
        }
        dir
    }

    private suspend fun download(model: ModelFile, destination: File) {
        val temp = File.createTempFile("model-", ".download", destination.parentFile)
        try {
            val connection = URL(model.url).openConnection() as HttpURLConnection
            try {
                connection.connectTimeout = 30_000
                connection.readTimeout = 30_000
                val status = connection.responseCode
                if (status != HttpURLConnection.HTTP_OK) {
                    throw IOException("Sherpa NCNN model download failed: ${model.name} (HTTP $status)")
                }
                val digest = connection.inputStream.use { input ->
                    temp.outputStream().use { output ->
                        checksum(input, model.size, output)
                    }
                }
                if (digest != model.sha256) {
                    throw IOException("Sherpa NCNN model checksum mismatch: ${model.name}")
                }
            } finally {
                connection.disconnect()
            }

            coroutineContext.ensureActive()
            if (!temp.renameTo(destination)) {
                throw IOException("Could not save Sherpa NCNN model: ${model.name}")
            }
        } finally {
            temp.delete()
        }
    }

    private suspend fun checksum(
        input: InputStream,
        expectedSize: Long,
        output: java.io.OutputStream? = null
    ): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            coroutineContext.ensureActive()
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            if (total > expectedSize) throw IOException("Sherpa NCNN model exceeds its expected size")
            digest.update(buffer, 0, count)
            output?.write(buffer, 0, count)
        }
        if (total != expectedSize) throw IOException("Incomplete Sherpa NCNN model file")
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
