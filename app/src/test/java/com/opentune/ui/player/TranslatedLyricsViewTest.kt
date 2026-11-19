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
import com.opentune.data.settings.AppSettings
import com.opentune.data.settings.ThemeSettings
import com.opentune.ui.LyricsState
import com.opentune.ui.theme.OpenTuneTheme
import java.io.File
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
        GeminiLyricsStore(context).apply { deleteKey(); clearCache(); saveDisplayMode(TranslationMode.ORIGINAL) }
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
}
