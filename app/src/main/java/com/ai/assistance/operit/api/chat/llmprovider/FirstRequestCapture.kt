package com.ai.assistance.operit.api.chat.llmprovider

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.ai.assistance.operit.util.AppLogger
import java.io.File

/**
 * One-shot opt-in capture of the next OpenAI-compatible JSON request body.
 * The request body does not contain the HTTP Authorization header, but may
 * contain private conversation text and tool arguments.
 */
object FirstRequestCapture {
    private const val PREFS = "request_capture"
    private const val KEY_ARMED = "capture_next_request"
    private const val FILE_NAME = "post_summary_request.json"
    private val lock = Any()

    fun isArmed(context: Context): Boolean =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_ARMED, false)

    fun setArmed(context: Context, armed: Boolean) {
        synchronized(lock) {
            context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_ARMED, armed).commit()
        }
    }

    fun captureIfArmed(context: Context, json: String) {
        synchronized(lock) {
            if (!isArmed(context)) return
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val values = ContentValues().apply {
                        put(MediaStore.Downloads.DISPLAY_NAME, FILE_NAME)
                        put(MediaStore.Downloads.MIME_TYPE, "application/json")
                        put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Operit")
                    }
                    val resolver = context.contentResolver
                    val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                        ?: error("Unable to create Downloads entry")
                    try {
                        resolver.openOutputStream(uri)?.use { stream ->
                            stream.write(json.toByteArray(Charsets.UTF_8))
                        } ?: error("Unable to open Downloads entry")
                    } catch (e: Exception) {
                        resolver.delete(uri, null, null)
                        throw e
                    }
                } else {
                    val dir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
                        ?: error("External files directory unavailable")
                    dir.mkdirs()
                    File(dir, FILE_NAME).writeText(json, Charsets.UTF_8)
                }
                setArmed(context, false)
                AppLogger.d("FirstRequestCapture", "Captured first request to $FILE_NAME")
            } catch (e: Exception) {
                AppLogger.e("FirstRequestCapture", "Failed to save first request", e)
            }
        }
    }
}
