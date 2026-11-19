package com.opentune.ui.player

import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.*

internal data class GeminiPause(val reason: Reason, val retryAt: Long = 0L) {
    enum class Reason(val label: String, val explanation: String) {
        ACCESS("Acceso rechazado", "Gemini rechazó la clave o sus permisos. Revísalos y pulsa Reintentar con Gemini."),
        QUOTA("Límite de Gemini", "Gemini alcanzó el límite de cuota o solicitudes. Pulsa Reintentar con Gemini cuando vuelva a estar disponible."),
    }

    fun allowsManualRetry(now: Long) = now >= retryAt
}

/** Messages are local and fixed. Raw server errors can contain account information. */
internal class TranslationFailure(val userMessage: String, val pause: GeminiPause? = null) : Exception(userMessage)

internal fun geminiHttpFailure(code: Int, retryAfter: String?, body: String, now: Long): TranslationFailure {
    val details = runCatching {
        Json.parseToJsonElement(body).jsonObject["error"]?.jsonObject?.get("details")?.jsonArray
            ?.mapNotNull { it as? JsonObject }
    }.getOrNull().orEmpty()
    val keyRejected = details.any {
        (it["reason"] as? JsonPrimitive)?.content in setOf("API_KEY_INVALID", "API_KEY_EXPIRED", "API_KEY_SERVICE_BLOCKED")
    }
    val pause = when {
        code == 401 || code == 403 || (code == 400 && keyRejected) -> GeminiPause(GeminiPause.Reason.ACCESS)
        code == 429 -> {
            val headerDelay = retryAfter?.trim()?.let { value ->
                value.toLongOrNull()?.takeIf { it >= 0 }?.let(TimeUnit.SECONDS::toMillis)
                    ?: runCatching {
                        (ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() - now)
                            .coerceAtLeast(0L)
                    }.getOrNull()
            } ?: 0L
            val detailsDelay = details.mapNotNull { detail ->
                if ((detail["@type"] as? JsonPrimitive)?.content != "type.googleapis.com/google.rpc.RetryInfo") return@mapNotNull null
                val duration = (detail["retryDelay"] as? JsonPrimitive)?.content ?: return@mapNotNull null
                if (!duration.matches(Regex("[0-9]{1,10}(\\.[0-9]{1,9})?s"))) return@mapNotNull null
                kotlin.math.ceil(duration.dropLast(1).toDouble() * 1000).toLong()
            }.maxOrNull() ?: 0L
            val wait = maxOf(headerDelay, detailsDelay).coerceAtMost(Long.MAX_VALUE - now)
            GeminiPause(GeminiPause.Reason.QUOTA, now + wait)
        }
        else -> null
    }
    val message = pause?.reason?.explanation ?: when (code) {
        400 -> "Gemini rechazó la solicitud. Revisa el modelo y la configuración."
        404 -> "El modelo de Gemini no está disponible. Puedes cambiarlo en la configuración."
        else -> "Gemini no pudo completar la traducción (HTTP $code). Inténtalo de nuevo."
    }
    return TranslationFailure(message, pause)
}
