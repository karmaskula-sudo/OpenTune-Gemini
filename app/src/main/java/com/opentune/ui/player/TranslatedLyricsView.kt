package com.opentune.ui.player

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.opentune.data.lyrics.Lyrics
import com.opentune.data.model.Song
import com.opentune.ui.LyricsState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun LyricsView(
    state: LyricsState,
    position: () -> Long,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
    synced: Boolean = true,
    blur: Boolean = true,
    onSyncLine: ((Long) -> Unit)? = null,
    shareSong: Song? = null,
    playing: () -> Boolean = { true },
    seeks: () -> Long = { 0L },
    controlsVisible: Boolean = true,
    lyricsVisible: Boolean = true,
    translateLyrics: (suspend (Lyrics, String, Boolean) -> GeminiLyricsStore.Translation)? = null,
) {
    // Keyed composition owns both state and scope: late results cannot enter another song.
    val visibleState = if (state is LyricsState.Found && state.songId != null &&
        shareSong != null && state.songId != shareSong.videoId) LyricsState.Loading else state
    key(visibleState, shareSong?.videoId) {
        val context = LocalContext.current.applicationContext
        val lifecycleState by LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsState()
        val store = remember { GeminiLyricsStore(context) }
        val scope = rememberCoroutineScope()
        val lyrics = (visibleState as? LyricsState.Found)?.lyrics
        var model by remember { mutableStateOf(store.model()) }
        var translated by remember { mutableStateOf<List<String>?>(null) }
        var preferredMode by remember { mutableStateOf(store.displayMode()) }
        var automatic by remember { mutableStateOf(store.automatic()) }
        var busy by remember { mutableStateOf(false) }
        var cacheLoading by remember { mutableStateOf(true) }
        var message by remember { mutableStateOf<String?>(null) }
        var translationError by remember { mutableStateOf(false) }
        var dialog by remember { mutableStateOf(false) }
        var hasKey by remember { mutableStateOf(store.hasKey()) }
        fun selectMode(mode: TranslationMode) {
            store.saveDisplayMode(mode)
            preferredMode = mode
        }
        suspend fun requestTranslation(lyrics: Lyrics, refresh: Boolean) {
            if (busy) return
            busy = true
            translationError = false
            message = null
            try {
                val result = translateLyrics?.invoke(lyrics, model, refresh)
                    ?: store.translate(lyrics, model, refresh)
                translated = result.lines
                if (preferredMode == TranslationMode.ORIGINAL) selectMode(TranslationMode.SPANISH)
                message = if (result.saved) "Traducción guardada."
                    else "Traducción lista, pero no se pudo guardar en el dispositivo."
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                translationError = true
                message = (e as? TranslationFailure)?.userMessage ?: "No se pudo completar la traducción."
            } finally {
                busy = false
            }
        }
        // The player can keep a second lyrics pane mounted offscreen during transitions.
        LaunchedEffect(lyricsVisible) {
            if (lyricsVisible) {
                model = store.model()
                preferredMode = store.displayMode()
                automatic = store.automatic()
                hasKey = store.hasKey()
            }
        }
        LaunchedEffect(lyrics, model) {
            cacheLoading = true
            translated = null
            try {
                if (lyrics != null) translated = store.cached(lyrics, model)
            } finally { cacheLoading = false }
        }
        val mayTranslateAutomatically = automatic && hasKey && preferredMode != TranslationMode.ORIGINAL &&
            lyricsVisible && lifecycleState.isAtLeast(Lifecycle.State.RESUMED)
        // Wait briefly before sending anything so flipping through tracks skips transient lyrics.
        LaunchedEffect(model, cacheLoading, mayTranslateAutomatically) {
            if (lyrics != null && mayTranslateAutomatically && !cacheLoading && translated == null) {
                delay(750)
                if (translated == null) requestTranslation(lyrics, refresh = false)
            }
        }
        val mode = if (translated == null) TranslationMode.ORIGINAL else preferredMode
        val shown = remember(lyrics, translated, mode) {
            lyrics?.let { LyricsState.Found(LyricsTranslation.display(it, translated, mode)) } ?: visibleState
        }
        Column(modifier) {
            if (lyrics != null && controlsVisible) {
                TextButton(onClick = { dialog = true }) {
                    Text(when {
                        busy -> "Traduciendo…"
                        translationError -> "Traducción · Ver aviso"
                        else -> "Traducción · ${mode.label}"
                    })
                }
            }
            LyricsContent(shown, position, onSeek, Modifier.weight(1f), synced, blur,
                onSyncLine, shareSong, playing, seeks, controlsVisible)
        }
        if (dialog && lyrics != null) {
            // Never rememberSaveable, prefill, log, or put the secret in application settings.
            var enteredKey by remember { mutableStateOf("") }
            var enteredModel by remember { mutableStateOf(model) }
            var showSettings by remember { mutableStateOf(false) }
            AlertDialog(
                onDismissRequest = { dialog = false },
                properties = DialogProperties(securePolicy = SecureFlagPolicy.SecureOn),
                title = { Text("Traducción con Gemini") },
                text = {
                    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(when {
                            translated != null -> "Traducción disponible en este dispositivo."
                            hasKey -> "Tu clave ya está configurada para todas las canciones. Traducir envía la letra a Google y usa tu cuota."
                            else -> "Configura tu clave de Gemini una sola vez para traducir tus canciones."
                        })
                        TranslationMode.entries.forEach { option ->
                            FilterChip(selected = preferredMode == option,
                                enabled = !busy && (option == TranslationMode.ORIGINAL || translated != null || hasKey),
                                onClick = { selectMode(option) }, label = { Text(option.label) })
                        }
                        Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            Text("Traducción automática", Modifier.weight(1f))
                            Switch(
                                checked = automatic,
                                enabled = hasKey || automatic,
                                onCheckedChange = { enabled ->
                                    store.saveAutomatic(enabled)
                                    automatic = enabled
                                    if (enabled && preferredMode == TranslationMode.ORIGINAL) selectMode(TranslationMode.SPANISH)
                                },
                                modifier = Modifier.semantics { contentDescription = "Traducción automática" },
                            )
                        }
                        Text("Con Español o Bilingüe, traduce las canciones nuevas mientras ves Letras. Envía la letra a Google y usa tu cuota.",
                            style = MaterialTheme.typography.bodySmall)
                        TextButton(enabled = !busy, onClick = {
                            showSettings = !showSettings
                            if (!showSettings) { enteredKey = ""; enteredModel = model }
                        }) { Text(if (showSettings) "Ocultar configuración" else "Configurar Gemini") }
                        if (showSettings) {
                            OutlinedTextField(value = enteredKey, onValueChange = { enteredKey = it },
                                enabled = !busy, label = { Text(if (hasKey) "Reemplazar clave guardada" else "Clave API de Gemini") },
                                visualTransformation = PasswordVisualTransformation(),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false), singleLine = true)
                            OutlinedTextField(value = enteredModel, onValueChange = { enteredModel = it },
                                enabled = !busy, label = { Text("Modelo de Gemini") }, singleLine = true)
                            TextButton(enabled = !busy && !cacheLoading, onClick = {
                                busy = true
                                scope.launch {
                                    try {
                                        withContext(Dispatchers.IO) { store.save(enteredKey, enteredModel.trim()) }
                                        enteredKey = ""
                                        model = enteredModel.trim()
                                        enteredModel = model
                                        hasKey = store.hasKey()
                                        showSettings = false
                                        message = "Configuración guardada en este dispositivo."
                                    } catch (e: CancellationException) { throw e }
                                    catch (_: Exception) { message = "No se pudo guardar. Revisa el modelo y la clave." }
                                    finally { busy = false }
                                }
                            }) { Text("Guardar configuración") }
                            TextButton(enabled = !busy && hasKey, onClick = {
                                busy = true
                                scope.launch {
                                    try {
                                        withContext(Dispatchers.IO) { store.deleteKey() }
                                        hasKey = store.hasKey(); enteredKey = ""
                                        message = "Clave eliminada."
                                    } catch (e: CancellationException) { throw e }
                                    catch (_: Exception) { message = "No se pudo eliminar la clave." }
                                    finally { busy = false }
                                }
                            }) { Text("Eliminar clave") }
                            TextButton(enabled = !busy && !cacheLoading, onClick = {
                                busy = true
                                scope.launch {
                                    try {
                                        withContext(Dispatchers.IO) { store.clearCache() }
                                        translated = null
                                        message = "Caché de traducciones borrada."
                                    } catch (e: CancellationException) { throw e }
                                    catch (_: Exception) { message = "No se pudo borrar la caché." }
                                    finally { busy = false }
                                }
                            }) { Text("Borrar caché") }
                        }
                        if (busy) CircularProgressIndicator()
                        message?.let { Text(it) }
                        Text("Sincronización por línea. Las voces de fondo permanecen en su idioma original.", style = MaterialTheme.typography.bodySmall)
                    }
                },
                confirmButton = {
                    TextButton(enabled = !busy && !cacheLoading && hasKey && !showSettings,
                        onClick = {
                            val refresh = translated != null
                            scope.launch { requestTranslation(lyrics, refresh) }
                        }) { Text(if (busy) "Traduciendo…" else if (translated != null) "Volver a traducir" else "Traducir al español") }
                },
                dismissButton = { TextButton(onClick = { dialog = false }) { Text("Cerrar") } },
            )
        }
    }
}
