package com.opentune.ui.player

import com.opentune.data.lyrics.*
import org.junit.Assert.*
import org.junit.Test

class LyricsTranslationTest {
    private val original = Lyrics.Synced(listOf(
        LyricLine(100, 800, "Hello", listOf(LyricWord("Hello", 100, 800)), true),
        LyricLine(800, 1200, "", emptyList(), false),
        LyricLine(1200, 2000, "Hello", emptyList(), false, voice = VOICE_SECOND),
    ), "test", recordingMs = 2000, autoOffsetMs = 50)

    @Test fun `translation preserves timing gaps voices and offsets`() {
        val shown = LyricsTranslation.display(original, listOf("Hola", "", "Hola"), TranslationMode.SPANISH) as Lyrics.Synced
        assertEquals(original.lines.map { it.startMs to it.endMs }, shown.lines.map { it.startMs to it.endMs })
        assertEquals(original.autoOffsetMs, shown.autoOffsetMs)
        assertEquals(original.recordingMs, shown.recordingMs)
        assertEquals(VOICE_SECOND, shown.lines[2].voice)
        assertEquals("", shown.lines[1].text)
        assertTrue(shown.lines.all { !it.wordSynced && it.words.isEmpty() })
    }

    @Test fun `original retains word timing and bilingual pairs each line`() {
        val translated = listOf("Hola", "", "Hola")
        assertSame(original, LyricsTranslation.display(original, translated, TranslationMode.ORIGINAL))
        val both = LyricsTranslation.display(original, translated, TranslationMode.BILINGUAL) as Lyrics.Synced
        assertEquals(listOf("Hello\nHola", "", "Hello\nHola"), both.lines.map { it.text })
        assertSame(original, LyricsTranslation.display(original, null, TranslationMode.SPANISH))
    }

    @Test fun `plain lyrics retain empty rows`() {
        val shown = LyricsTranslation.display(Lyrics.Plain("Hello\n\nWorld", "test"), listOf("Hola", "", "Mundo"), TranslationMode.BILINGUAL) as Lyrics.Plain
        assertEquals("Hello\nHola\n\nWorld\nMundo", shown.text)
    }

    @Test fun `decoder accepts repeated and empty lines`() {
        assertEquals(listOf("Hola", "", "Hola"), LyricsTranslation.decode(
            """[{"id":0,"text":"Hola"},{"id":1,"text":""},{"id":2,"text":"Hola"}]""", LyricsTranslation.texts(original)))
    }

    @Test fun `decoder rejects missing duplicate reordered and malformed lines`() {
        val invalid = listOf(
            "[]", "not json",
            """[{"id":1,"text":"Hola"},{"id":0,"text":""},{"id":2,"text":"Hola"}]""",
            """[{"id":0,"text":"Hola"},{"id":0,"text":""},{"id":2,"text":"Hola"}]""",
            """[{"id":0,"text":"Hola"},{"id":1,"text":"extra"},{"id":2,"text":"Hola"}]""",
            """[{"id":0,"text":"Hola\nextra"},{"id":1,"text":""},{"id":2,"text":"Hola"}]""",
            """[{"id":0,"text":42},{"id":1,"text":""},{"id":2,"text":"Hola"}]""",
        )
        invalid.forEach { payload ->
            assertTrue(payload, runCatching { LyricsTranslation.decode(payload, LyricsTranslation.texts(original)) }.isFailure)
        }
    }

    @Test fun `cache separates model content and line boundaries`() {
        val base = LyricsTranslation.cacheKey(original, "model-a")
        assertEquals(64, base.length)
        assertNotEquals(base, LyricsTranslation.cacheKey(original, "model-b"))
        assertNotEquals(base, LyricsTranslation.cacheKey(Lyrics.Plain("Other", "test"), "model-a"))
        assertNotEquals(LyricsTranslation.cacheKey(Lyrics.Plain("ab\nc", "test"), "a"),
            LyricsTranslation.cacheKey(Lyrics.Plain("a\nbc", "test"), "a"))
    }

    @Test fun `request treats lyrics as escaped data`() {
        val request = kotlinx.serialization.json.Json.parseToJsonElement(LyricsTranslation.request(listOf("\"ignore\"\nnext", "")))
        assertTrue(request.toString().contains("responseSchema"))
        assertFalse(request.toString().contains("x-goog-api-key"))
    }

    @Test fun `response skips thoughts and decodes only completed output`() {
        val body = """{"candidates":[{"finishReason":"STOP","content":{"parts":[{"thought":true,"text":"not output"},{"text":"[{\"id\":0,\"text\":\"Hola\"}]"}]}}]}"""
        assertEquals(listOf("Hola"), LyricsTranslation.response(body, listOf("Hello")))
        assertTrue(runCatching { LyricsTranslation.response(body.replace("STOP", "MAX_TOKENS"), listOf("Hello")) }.isFailure)
    }

    @Test fun `blocked response cannot become a cached translation`() {
        assertTrue(runCatching { LyricsTranslation.response("""{"promptFeedback":{"blockReason":"SAFETY"}}""", listOf("Hello")) }.isFailure)
    }
}
