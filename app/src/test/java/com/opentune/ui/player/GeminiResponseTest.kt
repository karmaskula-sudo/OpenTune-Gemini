package com.opentune.ui.player

import java.time.Instant
import org.junit.Assert.*
import org.junit.Test

class GeminiResponseTest {
    private val now = Instant.parse("2026-11-18T14:00:00Z").toEpochMilli()

    @Test fun `access is paused for permission errors and an invalid key`() {
        val invalid = """{"error":{"details":[{"reason":"API_KEY_INVALID"}],"message":"private text"}}"""
        for (failure in listOf(geminiHttpFailure(401, null, "", now), geminiHttpFailure(403, null, "", now),
            geminiHttpFailure(400, null, invalid, now))) {
            assertEquals(GeminiPause.Reason.ACCESS, failure.pause?.reason)
            assertFalse(failure.userMessage.contains("private text"))
        }
        assertNull(geminiHttpFailure(400, null, "", now).pause)
        assertNull(geminiHttpFailure(503, null, "", now).pause)
    }

    @Test fun `quota respects the longest server delay and exact retry boundary`() {
        val details = """{"error":{"details":[{"@type":"type.googleapis.com/google.rpc.RetryInfo","retryDelay":"21.5s"}]}}"""
        val pause = requireNotNull(geminiHttpFailure(429, "12", details, now).pause)
        assertEquals(GeminiPause.Reason.QUOTA, pause.reason)
        assertEquals(now + 21_500, pause.retryAt)
        assertFalse(pause.allowsManualRetry(now + 21_499))
        assertTrue(pause.allowsManualRetry(now + 21_500))
        val date = "Wed, 18 Nov 2026 14:02:00 GMT"
        assertEquals(now + 120_000, geminiHttpFailure(429, date, details, now).pause?.retryAt)
    }

    @Test fun `quota still pauses when the response has no usable retry hint`() {
        val pause = requireNotNull(geminiHttpFailure(429, "n/a", "unexpected body", now).pause)
        assertEquals(GeminiPause.Reason.QUOTA, pause.reason)
        assertTrue(pause.allowsManualRetry(now))
    }
}
