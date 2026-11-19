package com.opentune.ui.player

import android.app.Application
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import com.opentune.data.lyrics.Lyrics
import com.opentune.data.model.Song
import com.opentune.data.settings.AppSettings
import com.opentune.data.settings.ThemeSettings
import com.opentune.ui.LyricsState
import com.opentune.ui.theme.OpenTuneTheme
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class TranslatedLyricsViewTest {
    @get:Rule val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()
    private val lyrics = Lyrics.Plain("Hello world", "Test")
    private val playingLyrics = mutableStateOf<Lyrics>(lyrics)
    private lateinit var context: Application

    @Before fun setup() {
        context = ApplicationProvider.getApplicationContext()
        AppSettings.init(context)
        GeminiLyricsStore(context).apply {
            deleteKey()
            clearCache()
            saveDisplayMode(TranslationMode.ORIGINAL)
            saveAutomatic(false)
            saveServerPause(null)
        }
    }

    private fun show() {
        compose.setContent {
            OpenTuneTheme(ThemeSettings()) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    LyricsView(LyricsState.Found(playingLyrics.value), position = { 0L }, onSeek = {})
                }
            }
        }
        compose.onNodeWithText("Traducción · Original").performClick()
    }

    @Test fun missingKeyDoesNotOfferNetworkTranslation() {
        show()
        compose.onNodeWithText("Traducir al español").assertIsNotEnabled()
        compose.onNodeWithText("Español").assertIsNotEnabled()
        compose.onNodeWithText("Original", substring = false).assertIsEnabled()
        compose.onNodeWithText("Cerrar").performClick()
        compose.onNodeWithText("Hello world").assertExists()
    }

    @Test fun cachedTranslationCanSwitchModesWithoutAKey() {
        val store = GeminiLyricsStore(context)
        val file = File(context.noBackupFilesDir,
            "gemini-lyrics/translations/${LyricsTranslation.legacyCacheKey(lyrics, store.model())}.json")
        file.writeText("""[{"id":0,"text":"Hola mundo"}]""")
        show()
        compose.waitUntil(5000) {
            compose.onAllNodesWithText("Español").fetchSemanticsNodes().any {
                !it.config.contains(androidx.compose.ui.semantics.SemanticsProperties.Disabled)
            }
        }
        compose.onNodeWithText("Español").performClick()
        compose.onNodeWithText("Cerrar").performClick()
        compose.onNodeWithText("Hola mundo").assertExists()
        compose.onNodeWithText("Traducción · Español").performClick()
        compose.onNodeWithText("Bilingüe").performClick()
        compose.onNodeWithText("Cerrar").performClick()
        compose.onNodeWithText("Hello world\nHola mundo").assertExists()
        compose.runOnIdle { playingLyrics.value = Lyrics.Plain("Different song", "Test") }
        compose.onNodeWithText("Different song").assertExists()
        compose.onNodeWithText("Traducción · Original").assertExists()
        compose.runOnIdle { playingLyrics.value = lyrics }
        compose.waitUntil(5000) {
            compose.onAllNodesWithText("Traducción · Bilingüe").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Hello world\nHola mundo").assertExists()
        org.junit.Assert.assertEquals(TranslationMode.BILINGUAL, GeminiLyricsStore(context).displayMode())
    }

    @Test fun oneOptInTranslatesTheNextSongInBilingualMode() {
        val store = GeminiLyricsStore(context)
        store.saveDisplayMode(TranslationMode.BILINGUAL)
        // Credential presence only. The fake transport never reads this file or calls Gemini.
        File(context.noBackupFilesDir, "gemini-lyrics/key.enc").writeBytes(byteArrayOf())
        val first = Song("first", "First", "Test", null)
        val next = Song("next", "Next", "Test", null)
        val nextLyrics = Lyrics.Plain("Second song", "Test")
        val song = mutableStateOf(first)
        val state = mutableStateOf(LyricsState.Found(lyrics, first.videoId))
        val requests = mutableListOf<Lyrics>()
        compose.setContent {
            OpenTuneTheme(ThemeSettings()) {
                LyricsView(state.value, position = { 0L }, onSeek = {}, shareSong = song.value,
                    translateLyrics = { source, _, refresh ->
                        check(!refresh)
                        requests += source
                        GeminiLyricsStore.Translation(listOf(if (source == lyrics) "Hola mundo" else "Segunda canción"), saved = true)
                    })
            }
        }
        compose.onNodeWithText("Traducción · Original").performClick()
        compose.runOnIdle { assertTrue(requests.isEmpty()) }
        compose.onNodeWithContentDescription("Traducción automática").assertIsOff().performClick()
        compose.onNodeWithText("Cerrar").performClick()
        compose.waitUntil(8000) {
            compose.onAllNodesWithText("Hello world\nHola mundo").fetchSemanticsNodes().isNotEmpty()
        }
        compose.runOnIdle {
            assertEquals(listOf<Lyrics>(lyrics), requests)
            // The player can announce the new song before it publishes the new lyrics.
            song.value = next
        }
        compose.onNodeWithText("Hello world\nHola mundo").assertDoesNotExist()
        compose.runOnIdle { state.value = LyricsState.Found(nextLyrics, next.videoId) }
        compose.waitUntil(8000) {
            compose.onAllNodesWithText("Second song\nSegunda canción").fetchSemanticsNodes().isNotEmpty()
        }
        compose.runOnIdle {
            assertEquals(listOf<Lyrics>(lyrics, nextLyrics), requests)
            assertTrue(GeminiLyricsStore(context).automatic())
            assertEquals(TranslationMode.BILINGUAL, GeminiLyricsStore(context).displayMode())
        }
    }

    @Test fun rejectedAccessShowsOriginalAcrossSongsUntilManualSuccess() {
        val store = GeminiLyricsStore(context)
        store.saveDisplayMode(TranslationMode.BILINGUAL)
        store.saveAutomatic(true)
        File(context.noBackupFilesDir, "gemini-lyrics/key.enc").writeBytes(byteArrayOf())
        val different = Lyrics.Plain("Different song", "Test")
        var requests = 0
        compose.setContent {
            OpenTuneTheme(ThemeSettings()) {
                LyricsView(LyricsState.Found(playingLyrics.value), position = { 0L }, onSeek = {},
                    translateLyrics = { _, _, refresh ->
                        requests++
                        if (requests == 1) {
                            check(!refresh)
                            val rejection = geminiHttpFailure(403, null, "", 0L)
                            store.saveServerPause(rejection.pause)
                            throw rejection
                        }
                        check(refresh)
                        store.saveServerPause(null)
                        GeminiLyricsStore.Translation(listOf("Otra canción"), saved = true)
                    })
            }
        }
        val errorLabel = "Traducción · Acceso rechazado"
        compose.waitUntil(8000) { compose.onAllNodesWithText(errorLabel).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Hello world").assertExists()
        compose.runOnIdle { playingLyrics.value = different }
        compose.onNodeWithText("Different song").assertExists()
        compose.onNodeWithText(errorLabel).performClick()
        compose.onNodeWithText("Traducción automática en pausa. Se muestra la letra original.").assertExists()
        compose.runOnIdle { assertEquals(1, requests) }
        compose.onNodeWithText("Reintentar con Gemini").performClick()
        compose.onNodeWithText("Cerrar").performClick()
        compose.waitUntil(5000) {
            compose.onAllNodesWithText("Different song\nOtra canción").fetchSemanticsNodes().isNotEmpty()
        }
        compose.runOnIdle {
            assertEquals(2, requests)
            org.junit.Assert.assertNull(GeminiLyricsStore(context).serverPause())
        }
    }
}
