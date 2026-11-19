package com.opentune.ui.player

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import com.opentune.data.lyrics.Lyrics
import java.io.File
import java.security.KeyStore
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

internal class GeminiLyricsStore(context: Context) {
    private val preferences = context.getSharedPreferences("gemini_lyrics_display", Context.MODE_PRIVATE)
    private val root = File(context.noBackupFilesDir, "gemini-lyrics").apply { mkdirs() }
    private val secret = File(root, "key.enc")
    private val modelFile = File(root, "model.txt")
    private val cache = File(root, "translations").apply { mkdirs() }
    fun hasKey() = secret.exists()
    fun model() = runCatching { modelFile.readText() }.getOrDefault(LyricsTranslation.DEFAULT_MODEL)
    fun displayMode(): TranslationMode = TranslationMode.entries.firstOrNull {
        it.name == preferences.getString("mode", null)
    } ?: TranslationMode.ORIGINAL

    fun saveDisplayMode(mode: TranslationMode) {
        preferences.edit().putString("mode", mode.name).apply()
    }

    fun automatic() = preferences.getBoolean("automatic", false)

    fun saveAutomatic(enabled: Boolean) {
        preferences.edit().putBoolean("automatic", enabled).apply()
    }

    fun serverPause(): GeminiPause? {
        val reason = preferences.getString("server_pause", null) ?: return null
        return GeminiPause.Reason.entries.firstOrNull { it.name == reason }?.let {
            GeminiPause(it, preferences.getLong("server_retry_at", 0L))
        }
    }

    fun saveServerPause(pause: GeminiPause?) {
        val editor = preferences.edit()
        if (pause == null) editor.remove("server_pause").remove("server_retry_at")
        else editor.putString("server_pause", pause.reason.name).putLong("server_retry_at", pause.retryAt)
        editor.apply()
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        return (store.getKey(ALIAS, null) as? SecretKey) ?: KeyGenerator.getInstance("AES", "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }

    fun save(apiKey: String, model: String) {
        require(model.matches(Regex("[a-zA-Z0-9._-]{1,100}")))
        if (apiKey.isNotBlank()) {
            require(apiKey.trim().all { it.code in 33..126 })
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key())
            atomicWrite(secret, cipher.iv + cipher.doFinal(apiKey.trim().toByteArray(Charsets.UTF_8)))
        }
        atomicWrite(modelFile, model.toByteArray(Charsets.UTF_8))
    }

    fun deleteKey() { AtomicFile(secret).delete() }
    fun clearCache() { cache.listFiles()?.forEach { it.delete() } }
    private fun readKey(): String {
        if (!hasKey()) throw TranslationFailure("Configura tu clave de Gemini para traducir.")
        try {
            val bytes = AtomicFile(secret).readFully()
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
            return String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8)
        } catch (_: Exception) {
            throw TranslationFailure("No se pudo abrir la clave guardada. Bórrala y configúrala de nuevo.")
        }
    }

    suspend fun cached(lyrics: Lyrics, model: String): List<String>? = withContext(Dispatchers.IO) {
        val lines = LyricsTranslation.texts(lyrics)
        listOf(LyricsTranslation.cacheKey(lyrics, model), LyricsTranslation.legacyCacheKey(lyrics, model))
            .firstNotNullOfOrNull { key ->
                runCatching {
                    val file = File(cache, "$key.json")
                    require(file.length() <= MAX_RESPONSE)
                    LyricsTranslation.decode(file.readText(), lines)
                }.getOrNull()
            }
    }

    data class Translation(val lines: List<String>, val saved: Boolean)

    suspend fun translate(lyrics: Lyrics, model: String, refresh: Boolean, manual: Boolean): Translation = requestLock.withLock {
        // Check inside the shared lock so a new song cannot race a quota or permission rejection.
        val pause = serverPause()
        if (pause != null && (!manual || !pause.allowsManualRetry(System.currentTimeMillis()))) {
            throw TranslationFailure(pause.reason.explanation, pause)
        }
        withContext(Dispatchers.IO) { translateOnIo(lyrics, model, refresh = refresh || pause != null) }
    }

    private suspend fun translateOnIo(lyrics: Lyrics, model: String, refresh: Boolean): Translation {
        if (!refresh) cached(lyrics, model)?.let { return Translation(it, saved = true) }
        val lines = LyricsTranslation.texts(lyrics)
        if (lines.size > 500 || lines.sumOf { it.length } > 30000) {
            throw TranslationFailure("Esta letra es demasiado larga para traducirla en una consulta.")
        }
        require(model.matches(Regex("[a-zA-Z0-9._-]{1,100}")))
        val request = Request.Builder()
            .url("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent")
            .header("x-goog-api-key", readKey())
            .post(LyricsTranslation.request(lines).toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()
        val call = client.newCall(request)
        // Cancels the socket when this lyrics view leaves composition or the song changes.
        val translated = kotlinx.coroutines.suspendCancellableCoroutine<List<String>> { continuation ->
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : okhttp3.Callback {
                override fun onFailure(call: okhttp3.Call, e: java.io.IOException) {
                    continuation.resumeWith(Result.failure(TranslationFailure("No se pudo conectar con Gemini. Inténtalo de nuevo.")))
                }
                override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                    val result = runCatching {
                        response.use {
                            if (!it.isSuccessful) {
                                val errorBody = if (it.code == 400 || it.code == 429) {
                                    runCatching { it.peekBody(16_384).string() }.getOrDefault("")
                                } else ""
                                val failure = geminiHttpFailure(it.code, it.header("Retry-After"), errorBody, System.currentTimeMillis())
                                if (!call.isCanceled()) failure.pause?.let(::saveServerPause)
                                throw failure
                            }
                            val source = it.body.source()
                            require(!source.request((MAX_RESPONSE + 1).toLong()))
                            val bytes = source.readByteArray()
                            LyricsTranslation.response(String(bytes, Charsets.UTF_8), lines)
                        }
                    }.recoverCatching { e ->
                        throw (e as? TranslationFailure ?: TranslationFailure("Gemini devolvió una traducción incompleta o inválida. No se guardó."))
                    }
                    continuation.resumeWith(result)
                }
            })
        }
        kotlin.coroutines.coroutineContext.ensureActive()
        // Only a complete response from Gemini lifts a pause. A cached result cannot lift it.
        if (serverPause() != null) saveServerPause(null)
        val payload = buildJsonArray {
            translated.forEachIndexed { i, text ->
                add(buildJsonObject { put("id", i); put("text", text) })
            }
        }.toString().toByteArray(Charsets.UTF_8)
        val saved = runCatching {
            require(payload.size <= MAX_RESPONSE)
            atomicWrite(File(cache, LyricsTranslation.cacheKey(lyrics, model) + ".json"), payload)
        }.isSuccess
        return Translation(translated, saved)
    }

    companion object {
        private const val ALIAS = "opentune.gemini.lyrics.v1"
        private const val MAX_RESPONSE = 1_000_000
        private val requestLock = Mutex()
        private val client = OkHttpClient.Builder().callTimeout(60, TimeUnit.SECONDS)
            .connectTimeout(15, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS)
            .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false).build()

        private fun atomicWrite(file: File, bytes: ByteArray) {
            val atomic = AtomicFile(file)
            val stream = atomic.startWrite()
            try { stream.write(bytes); atomic.finishWrite(stream) }
            catch (e: Exception) { atomic.failWrite(stream); throw e }
        }
    }
}
